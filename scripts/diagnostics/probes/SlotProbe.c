#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0600

#include <windows.h>
#include <tlhelp32.h>
#include <psapi.h>

#include <stdint.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <wchar.h>

#define SLOT_OFFSET ((uintptr_t)0x20B0738)
#define PROBE_TIMEOUT_MS 60000ULL
#define SAMPLE_SLEEP_MS 15
#define MODULE_RETRY_MS 100
#define FALLBACK_RETRY_MS 500
#define SYNTHETIC_READY_TIMEOUT_MS 2000ULL

#ifndef IMAGE_FILE_MACHINE_ARM64EC
#define IMAGE_FILE_MACHINE_ARM64EC 0xA641
#endif

typedef BOOL (WINAPI *IsWow64Process2Fn)(HANDLE, USHORT *, USHORT *);

static FILE *g_log;

static unsigned long long elapsed_ms(unsigned long long start)
{
    return (unsigned long long)GetTickCount64() - start;
}

static void log_line(const char *format, ...)
{
    va_list args;

    va_start(args, format);
    vfprintf(g_log, format, args);
    fputc('\n', g_log);
    va_end(args);
}

static void utf8_name(const WCHAR *name, char *buffer, int size)
{
    int length;

    length = WideCharToMultiByte(CP_UTF8, 0, name, -1, buffer, size, NULL,
                                 NULL);
    if (length == 0 && size > 0)
        snprintf(buffer, (size_t)size, "<wide-name-error-%lu>",
                 (unsigned long)GetLastError());
}

static const char *machine_name(USHORT machine)
{
    switch (machine) {
    case IMAGE_FILE_MACHINE_I386: return "x86";
    case IMAGE_FILE_MACHINE_AMD64: return "x64";
    case IMAGE_FILE_MACHINE_ARM64: return "arm64";
    case IMAGE_FILE_MACHINE_ARM64EC: return "arm64ec";
    case IMAGE_FILE_MACHINE_UNKNOWN: return "unknown";
    default: return "other";
    }
}

static void log_architecture(HANDLE process)
{
    SYSTEM_INFO info;
    HMODULE kernel32;
    FARPROC is_wow64_process2_proc;
    IsWow64Process2Fn is_wow64_process2;
    USHORT process_machine = IMAGE_FILE_MACHINE_UNKNOWN;
    USHORT native_machine = IMAGE_FILE_MACHINE_UNKNOWN;
    BOOL result;
    DWORD error;

    GetNativeSystemInfo(&info);
    log_line("ARCH native_processor=%u page_size=%lu", (unsigned)info.wProcessorArchitecture,
             (unsigned long)info.dwPageSize);

    kernel32 = GetModuleHandleW(L"kernel32.dll");
    is_wow64_process2_proc = kernel32 == NULL
        ? NULL : GetProcAddress(kernel32, "IsWow64Process2");
    is_wow64_process2 = NULL;
    if (is_wow64_process2_proc != NULL)
        memcpy(&is_wow64_process2, &is_wow64_process2_proc,
               sizeof(is_wow64_process2));
    if (is_wow64_process2 == NULL) {
        log_line("ARCH IsWow64Process2=unavailable error=%lu",
                 (unsigned long)GetLastError());
        return;
    }

    SetLastError(ERROR_SUCCESS);
    result = is_wow64_process2(process, &process_machine, &native_machine);
    error = GetLastError();
    log_line("ARCH IsWow64Process2 result=%d process_machine=0x%04x(%s) "
             "native_machine=0x%04x(%s) error=%lu",
             result, (unsigned)process_machine, machine_name(process_machine),
             (unsigned)native_machine, machine_name(native_machine),
             (unsigned long)error);
}

static int find_loader_enum(HANDLE process, uintptr_t *base, DWORD *error_out)
{
    HMODULE modules[512];
    DWORD bytes = 0;
    DWORD count;
    DWORD i;
    WCHAR name[MAX_PATH];

    SetLastError(ERROR_SUCCESS);
    if (!EnumProcessModulesEx(process, modules, sizeof(modules), &bytes,
                              LIST_MODULES_ALL)) {
        *error_out = GetLastError();
        return 0;
    }
    count = bytes / (DWORD)sizeof(HMODULE);
    if (count > ARRAYSIZE(modules))
        count = ARRAYSIZE(modules);

    for (i = 0; i < count; ++i) {
        DWORD length;

        SetLastError(ERROR_SUCCESS);
        length = GetModuleBaseNameW(process, modules[i], name, ARRAYSIZE(name));
        if (length == 0)
            continue;
        name[ARRAYSIZE(name) - 1] = L'\0';
        if (_wcsicmp(name, L"WowB_loader.dll") == 0) {
            *base = (uintptr_t)modules[i];
            *error_out = ERROR_SUCCESS;
            return 1;
        }
    }
    *error_out = ERROR_SUCCESS;
    return 0;
}

static int fallback_modules(HANDLE process, DWORD pid, uintptr_t *base,
                            unsigned long long start)
{
    HANDLE snapshot;
    MODULEENTRY32W entry;
    int found = 0;
    int first = 1;
    char name[512];

    snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE |
                                         TH32CS_SNAPMODULE32, pid);
    if (snapshot == INVALID_HANDLE_VALUE) {
        log_line("FALLBACK-SAMPLE t_ms=%llu error=%lu", elapsed_ms(start),
                 (unsigned long)GetLastError());
        return 0;
    }

    ZeroMemory(&entry, sizeof(entry));
    entry.dwSize = sizeof(entry);
    log_line("FALLBACK-SAMPLE t_ms=%llu modules=", elapsed_ms(start));
    if (Module32FirstW(snapshot, &entry)) {
        do {
            utf8_name(entry.szModule, name, sizeof(name));
            if (!first)
                fputs(",", g_log);
            fprintf(g_log, "%s@%p", name, (void *)entry.modBaseAddr);
            first = 0;
            if (_wcsicmp(entry.szModule, L"WowB_loader.dll") == 0) {
                *base = (uintptr_t)entry.modBaseAddr;
                found = 1;
            }
        } while (Module32NextW(snapshot, &entry));
    } else {
        log_line("FALLBACK-ENUM-ERROR t_ms=%llu error=%lu", elapsed_ms(start),
                 (unsigned long)GetLastError());
    }
    fputc('\n', g_log);
    CloseHandle(snapshot);
    (void)process;
    return found;
}

static void protection_name(DWORD protection, char *buffer, size_t size)
{
    const char *name = "unknown";

    switch (protection & 0xff) {
    case PAGE_NOACCESS: name = "NOACCESS"; break;
    case PAGE_READONLY: name = "R"; break;
    case PAGE_READWRITE: name = "RW"; break;
    case PAGE_WRITECOPY: name = "WC"; break;
    case PAGE_EXECUTE: name = "X"; break;
    case PAGE_EXECUTE_READ: name = "RX"; break;
    case PAGE_EXECUTE_READWRITE: name = "RWX"; break;
    case PAGE_EXECUTE_WRITECOPY: name = "XWC"; break;
    default: break;
    }
    snprintf(buffer, size, "%s%s%s", name,
             (protection & PAGE_GUARD) ? "+GUARD" : "",
             (protection & PAGE_NOCACHE) ? "+NOCACHE" : "");
}

static int query_remote(HANDLE process, const char *label, uintptr_t address,
                        MEMORY_BASIC_INFORMATION *info)
{
    SIZE_T result;
    DWORD error;
    char protect[64];

    SetLastError(ERROR_SUCCESS);
    result = VirtualQueryEx(process, (LPCVOID)address, info, sizeof(*info));
    error = GetLastError();
    if (result == 0) {
        log_line("VQ %s address=%p result=0 error=%lu", label,
                 (void *)address, (unsigned long)error);
        return 0;
    }
    protection_name(info->Protect, protect, sizeof(protect));
    log_line("VQ %s address=%p base=%p size=%zu state=0x%lx type=0x%lx "
             "protect=0x%lx(%s) allocation_protect=0x%lx",
             label, (void *)address, info->BaseAddress, (size_t)info->RegionSize,
             (unsigned long)info->State, (unsigned long)info->Type,
             (unsigned long)info->Protect, protect,
             (unsigned long)info->AllocationProtect);
    return 1;
}

static int readable_protection(DWORD protection)
{
    DWORD base = protection & 0xff;

    if ((protection & PAGE_GUARD) != 0)
        return 0;
    return base == PAGE_READONLY || base == PAGE_READWRITE ||
           base == PAGE_WRITECOPY || base == PAGE_EXECUTE_READ ||
           base == PAGE_EXECUTE_READWRITE || base == PAGE_EXECUTE_WRITECOPY;
}

static int log_destination_bytes(HANDLE process, uintptr_t address,
                                 const MEMORY_BASIC_INFORMATION *info)
{
    uintptr_t region_end;
    SIZE_T request;
    SIZE_T got = 0;
    unsigned char bytes[64];
    SIZE_T i;

    if (info->State != MEM_COMMIT || !readable_protection(info->Protect)) {
        log_line("DEST-BYTES address=%p status=not-readable", (void *)address);
        return 0;
    }
    region_end = (uintptr_t)info->BaseAddress + (uintptr_t)info->RegionSize;
    if (region_end <= address) {
        log_line("DEST-BYTES address=%p status=range-overflow", (void *)address);
        return 0;
    }
    request = (SIZE_T)(region_end - address);
    if (request > sizeof(bytes))
        request = sizeof(bytes);
    SetLastError(ERROR_SUCCESS);
    if (!ReadProcessMemory(process, (LPCVOID)address, bytes, request, &got)) {
        log_line("DEST-BYTES address=%p status=read-error error=%lu read=%zu",
                 (void *)address, (unsigned long)GetLastError(), (size_t)got);
        return 0;
    }
    if (got != request) {
        log_line("DEST-BYTES address=%p status=short-read expected=%zu read=%zu",
                 (void *)address, (size_t)request, (size_t)got);
        return 0;
    }
    if (fprintf(g_log, "DEST-BYTES address=%p read=%lu data=", (void *)address,
                (unsigned long)got) < 0)
        return 0;
    for (i = 0; i < got; ++i) {
        if (fprintf(g_log, "%02x", bytes[i]) < 0)
            return 0;
    }
    if (fputc('\n', g_log) == EOF || ferror(g_log))
        return 0;
    return 1;
}

static void log_slot_change(HANDLE process, uintptr_t slot_address,
                            uint64_t previous, int had_previous, uint64_t slot,
                            unsigned long long start)
{
    MEMORY_BASIC_INFORMATION slot_info;
    MEMORY_BASIC_INFORMATION destination_info;
    uintptr_t destination = (uintptr_t)slot;

    log_line("SLOT-CHANGE t_ms=%llu slot_address=%p previous=%s0x%016llx "
             "slot=0x%016llx destination=%p",
             elapsed_ms(start), (void *)slot_address,
             had_previous ? "" : "unseen/", (unsigned long long)previous,
             (unsigned long long)slot, (void *)destination);
    query_remote(process, "slot", slot_address, &slot_info);
    query_remote(process, "destination", destination, &destination_info);
    if (destination != 0)
        log_destination_bytes(process, destination, &destination_info);
}

static int WINAPI synthetic_marker(void)
{
    return 7;
}

/* Child mode for the host-only control. It writes only its own private pages. */
static int run_synthetic_target(void)
{
    uint64_t *slot;

    slot = (uint64_t *)VirtualAlloc(NULL, 4096, MEM_COMMIT | MEM_RESERVE,
                                    PAGE_READWRITE);
    if (slot == NULL)
        return 10;
    *slot = (uint64_t)(uintptr_t)&synthetic_marker;
    printf("SYNTHETIC-READY slot=%p destination=%p\n", (void *)slot,
           (void *)&synthetic_marker);
    fflush(stdout);
    Sleep(1000);
    VirtualFree(slot, 0, MEM_RELEASE);
    return 0;
}

static int run_synthetic_probe(void)
{
    SECURITY_ATTRIBUTES security;
    STARTUPINFOW startup;
    PROCESS_INFORMATION process_info;
    HANDLE read_pipe = NULL;
    HANDLE write_pipe = NULL;
    WCHAR module_path[MAX_PATH];
    WCHAR command_line[MAX_PATH + 64];
    char ready_line[256];
    DWORD got;
    SIZE_T bytes_read;
    uint64_t slot_value = 0;
    void *slot_pointer = NULL;
    void *destination_pointer = NULL;
    uintptr_t slot_address;
    uintptr_t destination;
    MEMORY_BASIC_INFORMATION info;
    DWORD exit_code = STILL_ACTIVE;
    int result = 0;
    size_t length = 0;
    unsigned long long ready_start;
    int destination_bytes_ok = 0;

    ZeroMemory(&security, sizeof(security));
    security.nLength = sizeof(security);
    security.bInheritHandle = TRUE;
    ZeroMemory(&startup, sizeof(startup));
    startup.cb = sizeof(startup);
    ZeroMemory(&process_info, sizeof(process_info));
    if (!CreatePipe(&read_pipe, &write_pipe, &security, 0)) {
        log_line("SYNTHETIC CREATE-PIPE-ERROR error=%lu",
                 (unsigned long)GetLastError());
        return 2;
    }
    SetHandleInformation(read_pipe, HANDLE_FLAG_INHERIT, 0);
    if (GetModuleFileNameW(NULL, module_path, ARRAYSIZE(module_path)) == 0) {
        log_line("SYNTHETIC MODULE-PATH-ERROR error=%lu",
                 (unsigned long)GetLastError());
        result = 2;
        goto cleanup;
    }
    _snwprintf(command_line, ARRAYSIZE(command_line) - 1,
               L"\"%ls\" --synthetic-target", module_path);
    command_line[ARRAYSIZE(command_line) - 1] = L'\0';
    startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdOutput = write_pipe;
    startup.hStdError = write_pipe;
    startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    log_line("SYNTHETIC CREATE target=self");
    if (!CreateProcessW(NULL, command_line, NULL, NULL, TRUE, CREATE_NO_WINDOW,
                        NULL, NULL, &startup, &process_info)) {
        log_line("SYNTHETIC CREATE-ERROR error=%lu",
                 (unsigned long)GetLastError());
        result = 3;
        goto cleanup;
    }
    CloseHandle(write_pipe);
    write_pipe = NULL;

    ready_start = (unsigned long long)GetTickCount64();
    while (length + 1 < sizeof(ready_line)) {
        DWORD available = 0;

        if (!PeekNamedPipe(read_pipe, NULL, 0, NULL, &available, NULL)) {
            log_line("SYNTHETIC READY-PIPE-ERROR error=%lu",
                     (unsigned long)GetLastError());
            result = 4;
            goto child_cleanup;
        }
        if (available != 0) {
            if (!ReadFile(read_pipe, ready_line + length, 1, &got, NULL) ||
                got != 1) {
                log_line("SYNTHETIC READY-READ-ERROR error=%lu",
                         (unsigned long)GetLastError());
                result = 4;
                goto child_cleanup;
            }
            if (ready_line[length++] == '\n')
                break;
        } else if ((unsigned long long)GetTickCount64() - ready_start >=
                   SYNTHETIC_READY_TIMEOUT_MS) {
            log_line("SYNTHETIC READY-TIMEOUT timeout_ms=%llu action=not-killed",
                     (unsigned long long)SYNTHETIC_READY_TIMEOUT_MS);
            result = 4;
            goto child_cleanup;
        } else {
            Sleep(10);
        }
    }
    ready_line[length] = '\0';
    if (sscanf(ready_line, "SYNTHETIC-READY slot=%p destination=%p",
               &slot_pointer, &destination_pointer) != 2) {
        log_line("SYNTHETIC READY-ERROR line=%s", ready_line);
        result = 4;
        goto child_cleanup;
    }
    slot_address = (uintptr_t)slot_pointer;
    destination = (uintptr_t)destination_pointer;
    log_line("SYNTHETIC READY slot=%p destination=%p", slot_pointer,
             destination_pointer);
    SetLastError(ERROR_SUCCESS);
    bytes_read = 0;
    if (!ReadProcessMemory(process_info.hProcess, (LPCVOID)slot_address,
                           &slot_value, sizeof(slot_value), &bytes_read) ||
        bytes_read != sizeof(slot_value)) {
        log_line("SYNTHETIC SLOT-READ-ERROR error=%lu read=%zu",
                 (unsigned long)GetLastError(), (size_t)bytes_read);
        result = 5;
        goto child_cleanup;
    }
    log_line("SYNTHETIC SLOT-READ slot=%p value=0x%016llx destination=%p",
             (void *)slot_address, (unsigned long long)slot_value,
             (void *)destination);
    if (slot_value != (uint64_t)destination) {
        log_line("SYNTHETIC FAIL slot-value-mismatch");
        result = 6;
        goto child_cleanup;
    }
    if (!query_remote(process_info.hProcess, "synthetic-slot", slot_address,
                      &info) ||
        !query_remote(process_info.hProcess, "synthetic-destination", destination,
                      &info)) {
        result = 7;
        goto child_cleanup;
    }
    destination_bytes_ok = log_destination_bytes(process_info.hProcess, destination,
                                                  &info);
    if (!destination_bytes_ok) {
        log_line("SYNTHETIC FAIL destination-bytes");
        result = 8;
        goto child_cleanup;
    }

child_cleanup:
    {
        DWORD wait_result = WaitForSingleObject(process_info.hProcess, 2000);
        if (wait_result == WAIT_TIMEOUT) {
        log_line("SYNTHETIC TIMEOUT child_still_running=1 action=not-killed");
        if (result == 0) result = 8;
        } else if (wait_result == WAIT_FAILED) {
            log_line("SYNTHETIC WAIT-ERROR error=%lu",
                     (unsigned long)GetLastError());
            if (result == 0) result = 9;
        }
    }
    if (GetExitCodeProcess(process_info.hProcess, &exit_code)) {
        log_line("SYNTHETIC EXIT pid=%lu code=%lu",
                 (unsigned long)process_info.dwProcessId,
                 (unsigned long)exit_code);
        if (exit_code != 0 && result == 0)
            result = 10;
    } else if (result == 0) {
        log_line("SYNTHETIC EXIT-ERROR error=%lu", (unsigned long)GetLastError());
        result = 11;
    }
    if (result == 0)
        log_line("SYNTHETIC PASS read-only observation");
    CloseHandle(process_info.hThread);
    CloseHandle(process_info.hProcess);

cleanup:
    if (write_pipe) CloseHandle(write_pipe);
    if (read_pipe) CloseHandle(read_pipe);
    log_line("PROBE-END result=%d", result);
    return result;
}

static int run_probe(void)
{
    STARTUPINFOW startup;
    PROCESS_INFORMATION process_info;
    WCHAR command_line[] = L"\"C:\\wow\\client\\WowB.exe\"";
    const WCHAR cwd[] = L"C:\\wow\\client";
    unsigned long long start;
    unsigned long long next_module = 0;
    unsigned long long next_fallback = 0;
    uintptr_t loader_base = 0;
    uintptr_t last_loader_base = 0;
    uint64_t last_slot = 0;
    int had_slot = 0;
    int api_failed = 0;
    DWORD last_api_error = ERROR_SUCCESS;
    DWORD exit_code = STILL_ACTIVE;
    int result = 0;

    ZeroMemory(&startup, sizeof(startup));
    startup.cb = sizeof(startup);
    ZeroMemory(&process_info, sizeof(process_info));
    log_line("PROBE-BEGIN SlotProbe read-only x86_64");
    log_line("TARGET exe=C:/wow/client/WowB.exe cwd=C:/wow/client "
             "loader=WowB_loader.dll offset=0x%llx timeout_ms=%llu",
             (unsigned long long)SLOT_OFFSET,
             (unsigned long long)PROBE_TIMEOUT_MS);

    SetLastError(ERROR_SUCCESS);
    if (!CreateProcessW(NULL, command_line, NULL, NULL, FALSE, CREATE_SUSPENDED,
                        NULL, cwd, &startup, &process_info)) {
        log_line("CREATE-ERROR error=%lu", (unsigned long)GetLastError());
        log_line("PROBE-END result=2");
        return 2;
    }
    log_line("CREATE pid=%lu tid=%lu t_ms=0", (unsigned long)process_info.dwProcessId,
             (unsigned long)process_info.dwThreadId);
    log_architecture(process_info.hProcess);

    SetLastError(ERROR_SUCCESS);
    if (ResumeThread(process_info.hThread) == (DWORD)-1) {
        log_line("RESUME-ERROR error=%lu", (unsigned long)GetLastError());
        result = 3;
        goto cleanup;
    }
    start = (unsigned long long)GetTickCount64();
    log_line("RESUME t_ms=0");

    while (elapsed_ms(start) <= PROBE_TIMEOUT_MS) {
        unsigned long long now = elapsed_ms(start);
        DWORD api_error = ERROR_SUCCESS;
        DWORD wait_result;

        wait_result = WaitForSingleObject(process_info.hProcess, 0);
        if (wait_result == WAIT_OBJECT_0)
            break;
        if (wait_result == WAIT_FAILED) {
            log_line("WAIT-ERROR t_ms=%llu error=%lu", now,
                     (unsigned long)GetLastError());
            result = 4;
            break;
        }

        if (now >= next_module) {
            uintptr_t found_base = 0;
            int found = find_loader_enum(process_info.hProcess, &found_base,
                                         &api_error);
            if (api_error != ERROR_SUCCESS) {
                if (!api_failed || api_error != last_api_error)
                    log_line("MODULES-API-ERROR t_ms=%llu api=EnumProcessModulesEx "
                             "error=%lu", now, (unsigned long)api_error);
                api_failed = 1;
                last_api_error = api_error;
            } else {
                if (api_failed)
                    log_line("MODULES-API-RECOVERED t_ms=%llu", now);
                api_failed = 0;
                last_api_error = ERROR_SUCCESS;
            }
            if (api_error == ERROR_SUCCESS)
                loader_base = found ? found_base : 0;
            next_module = now + MODULE_RETRY_MS;
        }
        if (api_failed && now >= next_fallback) {
            uintptr_t found_base = 0;
            if (fallback_modules(process_info.hProcess, process_info.dwProcessId,
                                 &found_base, start))
                loader_base = found_base;
            else
                loader_base = 0;
            next_fallback = now + FALLBACK_RETRY_MS;
        }

        if (loader_base != 0 && loader_base != last_loader_base) {
            log_line("LOADER base=%p t_ms=%llu slot_address=%p",
                     (void *)loader_base, now,
                     (void *)(loader_base + SLOT_OFFSET));
            last_loader_base = loader_base;
        }
        if (loader_base != 0) {
            uintptr_t slot_address = loader_base + SLOT_OFFSET;
            uint64_t slot = 0;
            SIZE_T bytes_read = 0;

            SetLastError(ERROR_SUCCESS);
            if (ReadProcessMemory(process_info.hProcess,
                                  (LPCVOID)slot_address, &slot, sizeof(slot),
                                  &bytes_read) && bytes_read == sizeof(slot)) {
                if (!had_slot || slot != last_slot) {
                    log_slot_change(process_info.hProcess, slot_address,
                                    last_slot, had_slot, slot, start);
                    last_slot = slot;
                    had_slot = 1;
                }
            }
        }
        Sleep(SAMPLE_SLEEP_MS);
    }

    if (WaitForSingleObject(process_info.hProcess, 0) == WAIT_TIMEOUT) {
        log_line("TIMEOUT t_ms=%llu child_still_running=1 action=not-killed",
                 elapsed_ms(start));
        result = 5;
    }

cleanup:
    if (GetExitCodeProcess(process_info.hProcess, &exit_code))
        log_line("EXIT pid=%lu code=%lu", (unsigned long)process_info.dwProcessId,
                 (unsigned long)exit_code);
    else
        log_line("EXIT pid=%lu code=unavailable error=%lu",
                 (unsigned long)process_info.dwProcessId,
                 (unsigned long)GetLastError());
    CloseHandle(process_info.hThread);
    CloseHandle(process_info.hProcess);
    log_line("PROBE-END result=%d", result);
    return result;
}

int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR command_line,
                   int show_command)
{
    int result;

    (void)instance;
    (void)previous;
    (void)show_command;

    if (command_line != NULL && strstr(command_line, "--synthetic-target") != NULL)
        return run_synthetic_target();

    g_log = fopen("C:/wow/diag/slot-probe.txt", "w");
    if (g_log == NULL)
        return 2;
    setvbuf(g_log, NULL, _IONBF, 0);
    if (command_line != NULL && strstr(command_line, "--synthetic") != NULL) {
        log_line("PROBE-BEGIN SlotProbe read-only x86_64 mode=synthetic");
        log_architecture(GetCurrentProcess());
        result = run_synthetic_probe();
    } else {
        result = run_probe();
    }
    fclose(g_log);
    return result;
}
