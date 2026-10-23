#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

typedef BOOL (WINAPI *InitializeCriticalSectionExFn)(LPCRITICAL_SECTION,
                                                      DWORD,
                                                      DWORD);

static FILE *g_log;

static const char *command_line_log_path(const char *command_line)
{
    static char path[1024];
    const char *prefix = "--log ";
    const char *start = strstr(command_line != NULL ? command_line : "", prefix);
    size_t length;

    if (start == NULL) return NULL;
    start += strlen(prefix);
    if (*start == '"') {
        ++start;
        length = strcspn(start, "\"");
    } else {
        length = strcspn(start, " \t");
    }
    if (length == 0 || length >= sizeof(path)) return NULL;
    memcpy(path, start, length);
    path[length] = '\0';
    return path;
}

static void log_line(const char *format, ...)
{
    va_list args;

    va_start(args, format);
    vfprintf(g_log, format, args);
    fputc('\n', g_log);
    va_end(args);
}

static void protection_name(DWORD protection, char *buffer, size_t size)
{
    const char *name = "unknown";
    DWORD base = protection & 0xff;

    switch (base) {
    case PAGE_NOACCESS:          name = "NOACCESS"; break;
    case PAGE_READONLY:          name = "R"; break;
    case PAGE_READWRITE:         name = "RW"; break;
    case PAGE_WRITECOPY:         name = "WC"; break;
    case PAGE_EXECUTE:           name = "X"; break;
    case PAGE_EXECUTE_READ:      name = "RX"; break;
    case PAGE_EXECUTE_READWRITE: name = "RWX"; break;
    case PAGE_EXECUTE_WRITECOPY: name = "XWC"; break;
    default: break;
    }

    snprintf(buffer, size, "%s%s%s%s", name,
             (protection & PAGE_GUARD) ? "+GUARD" : "",
             (protection & PAGE_NOCACHE) ? "+NOCACHE" : "",
             (protection & PAGE_WRITECOMBINE) ? "+WRITECOMBINE" : "");
}

static void describe_address(const char *label, const void *address)
{
    MEMORY_BASIC_INFORMATION mbi;
    SIZE_T queried;
    DWORD error;
    char protect[64];
    WCHAR module_path[MAX_PATH];
    DWORD module_length;

    log_line("BEFORE VirtualQuery %s address=%p", label, address);
    SetLastError(ERROR_SUCCESS);
    queried = VirtualQuery(address, &mbi, sizeof(mbi));
    error = GetLastError();
    if (queried == 0) {
        log_line("AFTER VirtualQuery %s result=0 error=%lu", label,
                 (unsigned long)error);
        return;
    }

    protection_name(mbi.Protect, protect, sizeof(protect));
    log_line("AFTER VirtualQuery %s result=%zu base=%p allocation_base=%p "
             "region_size=%zu state=0x%lx type=0x%lx protect=0x%lx(%s) "
             "allocation_protect=0x%lx error=%lu",
             label, (size_t)queried, mbi.BaseAddress, mbi.AllocationBase,
             (size_t)mbi.RegionSize, (unsigned long)mbi.State,
             (unsigned long)mbi.Type, (unsigned long)mbi.Protect, protect,
             (unsigned long)mbi.AllocationProtect, (unsigned long)error);

    log_line("BEFORE GetModuleFileNameW %s allocation_base=%p",
             label, mbi.AllocationBase);
    SetLastError(ERROR_SUCCESS);
    module_length = GetModuleFileNameW((HMODULE)mbi.AllocationBase,
                                       module_path,
                                       ARRAYSIZE(module_path));
    error = GetLastError();
    if (module_length == 0) {
        log_line("AFTER GetModuleFileNameW %s length=0 error=%lu", label,
                 (unsigned long)error);
    } else {
        module_path[module_length < ARRAYSIZE(module_path)
                        ? module_length : ARRAYSIZE(module_path) - 1] = L'\0';
        log_line("AFTER GetModuleFileNameW %s length=%lu path=%ls error=%lu",
                 label, (unsigned long)module_length, module_path,
                 (unsigned long)error);
    }
}

static HMODULE probe_load(const WCHAR *name, DWORD flags, const char *label)
{
    HMODULE module;
    DWORD error;

    log_line("BEFORE LoadLibraryExW %s name=%ls flags=0x%lx", label, name,
             (unsigned long)flags);
    SetLastError(ERROR_SUCCESS);
    module = LoadLibraryExW(name, NULL, flags);
    error = GetLastError();
    log_line("AFTER LoadLibraryExW %s handle=%p error=%lu", label, module,
             (unsigned long)error);
    if (module != NULL)
        describe_address(label, module);
    return module;
}

static FARPROC probe_proc(HMODULE module, const char *name, const char *label)
{
    FARPROC proc;
    DWORD error;

    log_line("BEFORE GetProcAddress %s module=%p name=%s", label, module,
             name);
    SetLastError(ERROR_SUCCESS);
    proc = GetProcAddress(module, name);
    error = GetLastError();
    log_line("AFTER GetProcAddress %s address=%p error=%lu", label, proc,
             (unsigned long)error);
    if (proc != NULL)
        describe_address(label, (const void *)proc);
    return proc;
}

static BOOL probe_critical_section(InitializeCriticalSectionExFn init)
{
    CRITICAL_SECTION critical_section;
    BOOL result;
    DWORD error;

    ZeroMemory(&critical_section, sizeof(critical_section));
    log_line("BEFORE InitializeCriticalSectionEx spin_count=0 flags=0x0");
    SetLastError(ERROR_SUCCESS);
    result = init(&critical_section, 0, 0);
    error = GetLastError();
    log_line("AFTER InitializeCriticalSectionEx result=%d error=%lu", result,
             (unsigned long)error);
    if (!result) {
        log_line("PROBE-ERROR InitializeCriticalSectionEx failed");
        return FALSE;
    }

    log_line("BEFORE DeleteCriticalSection");
    DeleteCriticalSection(&critical_section);
    log_line("AFTER DeleteCriticalSection");
    return TRUE;
}

static int run_probe(void)
{
    const WCHAR api_set_name[] = L"api-ms-win-core-synch-l1-2-0";
    HMODULE kernel32;
    HMODULE system32_module;
    HMODULE default_module;
    FARPROC initialize_proc;
    FARPROC load_library_proc;
    InitializeCriticalSectionExFn initialize;
    int result = 0;

    log_line("PROBE-BEGIN ApiProbe x86_64 WinAPI control");
    log_line("API-SET-NAME=%ls", api_set_name);

    system32_module = probe_load(api_set_name, 0x800,
                                 "LOAD_LIBRARY_SEARCH_SYSTEM32");
    default_module = probe_load(api_set_name, 0, "DEFAULT_FLAGS");

    if (system32_module == NULL) {
        log_line("PROBE-ERROR LoadLibraryExW SEARCH_SYSTEM32 failed");
        result = 1;
    }
    if (default_module == NULL) {
        log_line("PROBE-ERROR LoadLibraryExW DEFAULT_FLAGS failed");
        result = 1;
    }

    log_line("BEFORE GetModuleHandleW kernel32.dll");
    SetLastError(ERROR_SUCCESS);
    kernel32 = GetModuleHandleW(L"kernel32.dll");
    log_line("AFTER GetModuleHandleW kernel32.dll handle=%p error=%lu",
             kernel32, (unsigned long)GetLastError());
    if (kernel32 == NULL) {
        log_line("PROBE-ERROR kernel32 module unavailable");
        result = 1;
        goto cleanup;
    }
    describe_address("kernel32-module", kernel32);

    initialize_proc = probe_proc(kernel32, "InitializeCriticalSectionEx",
                                 "kernel32!InitializeCriticalSectionEx");
    load_library_proc = probe_proc(kernel32, "LoadLibraryExW",
                                   "kernel32!LoadLibraryExW");

    initialize = (InitializeCriticalSectionExFn)initialize_proc;
    if (initialize != NULL) {
        if (!probe_critical_section(initialize)) result = 1;
    } else {
        log_line("SKIP InitializeCriticalSectionEx unresolved");
        result = 1;
    }
    if (load_library_proc == NULL) {
        log_line("PROBE-WARN LoadLibraryExW export unresolved");
        result = 1;
    }

cleanup:
    if (system32_module != NULL && system32_module != default_module) {
        log_line("BEFORE FreeLibrary SEARCH_SYSTEM32 handle=%p",
                 system32_module);
        SetLastError(ERROR_SUCCESS);
        if (!FreeLibrary(system32_module))
            result = 1;
        log_line("AFTER FreeLibrary SEARCH_SYSTEM32 error=%lu",
                 (unsigned long)GetLastError());
    }
    if (default_module != NULL) {
        log_line("BEFORE FreeLibrary DEFAULT_FLAGS handle=%p", default_module);
        SetLastError(ERROR_SUCCESS);
        if (!FreeLibrary(default_module))
            result = 1;
        log_line("AFTER FreeLibrary DEFAULT_FLAGS error=%lu",
                 (unsigned long)GetLastError());
    }
    log_line("PROBE-END result=%d", result);
    return result;
}

int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR command_line,
                   int show_command)
{
    (void)instance;
    (void)previous;
    (void)show_command;

    {
        const char *log_path = command_line_log_path(command_line);
        g_log = fopen(log_path != NULL ? log_path : "C:/wow/diag/api-probe.txt", "w");
    }
    if (g_log == NULL)
        return 2;
    setvbuf(g_log, NULL, _IONBF, 0);

    {
        int result = run_probe();
        fclose(g_log);
        return result;
    }
}
