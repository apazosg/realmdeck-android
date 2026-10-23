#include "probe_common.h"

typedef BOOL(WINAPI *InitializeCriticalSectionExFn)(LPCRITICAL_SECTION,
                                                     DWORD,
                                                     DWORD);

static int load_api_set(ProbeLog *log, const WCHAR *name, DWORD flags,
                        const char *label, HMODULE *module)
{
    DWORD error;

    probe_stage_begin(log, label);
    SetLastError(ERROR_SUCCESS);
    *module = LoadLibraryExW(name, NULL, flags);
    error = GetLastError();
    probe_log_line(log, "LOAD path=%ls flags=0x%lx handle=%p lasterror=%lu",
                   name, (unsigned long)flags, *module,
                   (unsigned long)error);
    return probe_stage_end(log, label, *module == NULL ? 1 : 0);
}

static int resolve_export(ProbeLog *log, HMODULE module, const char *name,
                          const char *label, FARPROC *address)
{
    DWORD error;

    probe_stage_begin(log, label);
    SetLastError(ERROR_SUCCESS);
    *address = module == NULL ? NULL : GetProcAddress(module, name);
    error = GetLastError();
    probe_log_line(log, "EXPORT name=%s module=%p address=%p lasterror=%lu",
                   name, module, *address, (unsigned long)error);
    return probe_stage_end(log, label, *address == NULL ? 1 : 0);
}

int main(int argc, char **argv)
{
    static const WCHAR api_set_name[] = L"api-ms-win-core-synch-l1-2-0.dll";
    ProbeArgs args;
    ProbeLog log;
    HMODULE search_system32 = NULL;
    HMODULE default_flags = NULL;
    HMODULE kernel32 = NULL;
    FARPROC initialize_address = NULL;
    FARPROC load_library_address = NULL;
    InitializeCriticalSectionExFn initialize = NULL;
    CRITICAL_SECTION critical_section;
    int parse_result;
    int result = 0;
    int initialized = 0;

    parse_result = probe_parse_args(argc, argv, &args);
    if (parse_result != 0)
        return parse_result;
    if (!probe_open_log(&log, &args))
        return 2;

    probe_log_line(&log, "PROBE-BEGIN name=ApiArm64 pe_machine=0xAA64");
    if (!load_api_set(&log, api_set_name, LOAD_LIBRARY_SEARCH_SYSTEM32,
                      "load-library-search-system32", &search_system32))
        result = 1;
    if (!load_api_set(&log, api_set_name, 0, "load-library-default",
                      &default_flags))
        result = 1;

    probe_stage_begin(&log, "get-module-kernel32");
    SetLastError(ERROR_SUCCESS);
    kernel32 = GetModuleHandleW(L"kernel32.dll");
    probe_log_line(&log, "MODULE name=kernel32.dll handle=%p lasterror=%lu",
                   kernel32, (unsigned long)GetLastError());
    if (!probe_stage_end(&log, "get-module-kernel32", kernel32 == NULL ? 1 : 0))
        result = 1;

    if (!resolve_export(&log, kernel32, "InitializeCriticalSectionEx",
                        "get-proc-initialize-critical-section-ex",
                        &initialize_address))
        result = 1;
    if (!resolve_export(&log, kernel32, "LoadLibraryExW",
                        "get-proc-load-library-ex", &load_library_address))
        result = 1;

    probe_stage_begin(&log, "initialize-critical-section");
    ZeroMemory(&critical_section, sizeof(critical_section));
    initialize = (InitializeCriticalSectionExFn)initialize_address;
    if (initialize != NULL && initialize(&critical_section, 0, 0)) {
        initialized = 1;
        EnterCriticalSection(&critical_section);
        LeaveCriticalSection(&critical_section);
    }
    probe_log_line(&log, "CRITICAL-SECTION initialized=%d", initialized);
    if (!probe_stage_end(&log, "initialize-critical-section",
                         initialized ? 0 : 1))
        result = 1;
    if (initialized)
        DeleteCriticalSection(&critical_section);

    if (search_system32 != NULL) {
        int free_result;
        probe_stage_begin(&log, "free-library-search-system32");
        free_result = FreeLibrary(search_system32) ? 0 : 1;
        probe_log_line(&log, "FREE handle=%p result=%d", search_system32,
                       free_result == 0);
        if (!probe_stage_end(&log, "free-library-search-system32", free_result))
            result = 1;
    }
    if (default_flags != NULL) {
        int free_result;
        probe_stage_begin(&log, "free-library-default");
        free_result = FreeLibrary(default_flags) ? 0 : 1;
        probe_log_line(&log, "FREE handle=%p result=%d", default_flags,
                       free_result == 0);
        if (!probe_stage_end(&log, "free-library-default", free_result))
            result = 1;
    }

    (void)load_library_address;
    probe_log_line(&log, "API-COVERAGE InitializeCriticalSectionEx=checked "
                   "LoadLibraryExW=checked");
    return probe_finish(&log, "ApiArm64", result);
}
