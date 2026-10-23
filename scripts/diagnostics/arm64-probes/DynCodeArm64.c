#include "probe_common.h"

typedef int (*Arm64NoArgsFn)(void);

static const size_t arm64_function_size = 16;

/*
 * stp x29, x30, [sp, #-16]!
 * mov w0, #constant
 * ldp x29, x30, [sp], #16
 * ret
 *
 * These are generated AArch64 instructions, not bytes copied from a module.
 */
static void emit_arm64_function(void *destination, uint32_t constant)
{
    uint32_t instructions[4];

    instructions[0] = 0xA9BF7BFD;
    instructions[1] = 0x52800000 | (constant << 5);
    instructions[2] = 0xA8C17BFD;
    instructions[3] = 0xD65F03C0;
    memcpy(destination, instructions, sizeof(instructions));
}

static int flush_generated_code(ProbeLog *log, const char *label,
                                const void *address)
{
    BOOL flushed;

    SetLastError(ERROR_SUCCESS);
    flushed = FlushInstructionCache(GetCurrentProcess(), address,
                                    arm64_function_size);
    probe_log_line(log, "FLUSH label=%s result=%d lasterror=%lu", label,
                   flushed != FALSE, (unsigned long)GetLastError());
    return flushed ? 0 : 1;
}

static int query_protection(ProbeLog *log, const char *label,
                            const void *address, DWORD expected)
{
    MEMORY_BASIC_INFORMATION information;
    SIZE_T queried;
    DWORD actual;

    ZeroMemory(&information, sizeof(information));
    queried = VirtualQuery(address, &information, sizeof(information));
    actual = queried == 0 ? PAGE_NOACCESS : information.Protect & 0xff;
    probe_log_line(log, "QUERY label=%s address=%p result=%zu state=0x%lx "
                   "protect=0x%lx expected=0x%lx", label, address,
                   (size_t)queried, (unsigned long)information.State,
                   (unsigned long)actual, (unsigned long)expected);
    return queried != 0 && information.State == MEM_COMMIT && actual == expected;
}

static int run_private_allocation(ProbeLog *log)
{
    void *memory = NULL;
    DWORD old_protection = 0;
    int result = 0;

    probe_stage_begin(log, "private-rw-rx");
    memory = VirtualAlloc(NULL, 4096, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (memory == NULL) {
        result = 1;
    } else {
        emit_arm64_function(memory, 17);
        if (!VirtualProtect(memory, 4096, PAGE_EXECUTE_READ, &old_protection))
            result = 1;
        if (result == 0)
            result = flush_generated_code(log, "private", memory);
        if (result == 0) {
            int value = ((Arm64NoArgsFn)memory)();
            probe_log_line(log, "PRIVATE result=%d expected=17", value);
            if (value != 17)
                result = 1;
        }
    }
    if (memory != NULL && !VirtualFree(memory, 0, MEM_RELEASE))
        result = 1;
    probe_stage_end(log, "private-rw-rx", result);
    return result;
}

static int run_shared_alias(ProbeLog *log)
{
    HANDLE section = NULL;
    void *rw = NULL;
    void *rx = NULL;
    int result = 0;
    int value;

    probe_stage_begin(log, "shared-section");
    section = CreateFileMappingW(INVALID_HANDLE_VALUE, NULL,
                                 PAGE_EXECUTE_READWRITE, 0, 4096, NULL);
    if (section == NULL)
        result = 1;
    probe_log_line(log, "SECTION handle=%p result=%d", section, result == 0);
    probe_stage_end(log, "shared-section", result);

    probe_stage_begin(log, "shared-views");
    if (section != NULL) {
        rw = MapViewOfFile(section, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, 4096);
        rx = MapViewOfFile(section, FILE_MAP_READ | FILE_MAP_EXECUTE, 0, 0, 4096);
    }
    if (rw == NULL || rx == NULL || rw == rx)
        result = 1;
    probe_log_line(log, "VIEWS rw=%p rx=%p distinct=%d", rw, rx, rw != rx);
    if (!query_protection(log, "rw", rw, PAGE_READWRITE) ||
        !query_protection(log, "rx", rx, PAGE_EXECUTE_READ))
        result = 1;
    probe_stage_end(log, "shared-views", result);

    probe_stage_begin(log, "shared-first-call");
    if (result != 0) {
        probe_log_line(log, "CALL result=not-run prerequisite-failed");
        probe_stage_end(log, "shared-first-call", 1);
    } else {
        emit_arm64_function(rw, 17);
        if (flush_generated_code(log, "shared-first", rx) != 0) {
            result = 1;
        } else {
            value = ((Arm64NoArgsFn)rx)();
            probe_log_line(log, "CALL generation=first result=%d expected=17", value);
            if (value != 17)
                result = 1;
        }
        probe_stage_end(log, "shared-first-call", result);
    }

    probe_stage_begin(log, "shared-second-call");
    if (result != 0) {
        probe_log_line(log, "CALL result=not-run prerequisite-failed");
        probe_stage_end(log, "shared-second-call", 1);
    } else {
        emit_arm64_function(rw, 29);
        if (flush_generated_code(log, "shared-second", rx) != 0) {
            result = 1;
        } else {
            value = ((Arm64NoArgsFn)rx)();
            probe_log_line(log, "CALL generation=second result=%d expected=29", value);
            if (value != 29)
                result = 1;
        }
        probe_stage_end(log, "shared-second-call", result);
    }

    probe_stage_begin(log, "shared-cleanup");
    if (rw != NULL && !UnmapViewOfFile(rw))
        result = 1;
    if (rx != NULL && !UnmapViewOfFile(rx))
        result = 1;
    if (section != NULL && !CloseHandle(section))
        result = 1;
    probe_stage_end(log, "shared-cleanup", result);
    return result;
}

int main(int argc, char **argv)
{
    ProbeArgs args;
    ProbeLog log;
    SYSTEM_INFO system_info;
    int parse_result;
    int result = 0;

    parse_result = probe_parse_args(argc, argv, &args);
    if (parse_result != 0)
        return parse_result;
    if (!probe_open_log(&log, &args))
        return 2;

    probe_log_line(&log, "PROBE-BEGIN name=DynCodeArm64 pe_machine=0xAA64");
    GetNativeSystemInfo(&system_info);
    probe_log_line(&log, "ARCH processor_architecture=%u pointer_bits=%u",
                   (unsigned)system_info.wProcessorArchitecture,
                   (unsigned)(sizeof(void *) * 8));
    if (system_info.wProcessorArchitecture != PROCESSOR_ARCHITECTURE_ARM64 ||
        sizeof(void *) != 8)
        result = 1;
    if (result == 0 && run_private_allocation(&log) != 0)
        result = 1;
    if (run_shared_alias(&log) != 0)
        result = 1;
    probe_log_line(&log, "STAGE-NOT-INCLUDED name=syscall-copied "
                   "reason=no-arbitrary-or-offset-fragile-byte-copy");
    return probe_finish(&log, "DynCodeArm64", result);
}
