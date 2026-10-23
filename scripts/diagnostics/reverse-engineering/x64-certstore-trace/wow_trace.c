/*
 * Bounded Windows debug-API cert-store tracer.
 *
 * The normal build launches only the fixed child selected by the mode flag:
 *   --real         Z:\lab\game\WowB.exe -d3d11 -config BetaTrace.wtf
 *   --passive-real Z:\lab\game\WowB.exe -d3d11 -config BetaTrace.wtf
 *   --synthetic    Z:\lab\synthetic_certstore_fixture.exe
 *
 * It never attaches to a PID.  It scans only the child process handle returned
 * by CreateProcessW, and only committed private PAGE_READWRITE regions.  The
 * synthetic build of this same source is the mechanical test target; it uses
 * a VirtualAlloc'ed 12x40 table and the public contract values below.
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <dbghelp.h>
#include <psapi.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef TRACE_SYNTHETIC_FIXTURE

#define ROWS 12u
#define ROW_WIDTH 40u
#define TABLE_BYTES (ROWS * ROW_WIDTH)
#define SCAN_CHUNK (64u * 1024u)
#define SCAN_OVERLAP (TABLE_BYTES - 1u)
#define MAX_SCAN_BYTES (2ull * 1024ull * 1024ull * 1024ull)
#define SCAN_BUDGET_MS 5000ull
#define POLL_INTERVAL_MS 1000ull
/* Leave the wrapper's 300-second client deadline enough time to drain the
 * owned child and emit the final protocol record. */
#define TRACE_LIMIT_MS 285000ull
#define DETAIL_LIMIT_MS 30000ull
#define EVENT_LIMIT 64u
#define STACK_LIMIT 16u
#define CANDIDATE_LIMIT 8u
#define MODULE_LIMIT 128u
#define EXCEPTION_SAMPLE_LIMIT 16u
#define EXCEPTION_CODE_LIMIT 8u
#define EXCEPTION_DISPATCH_LIMIT 4096u

/* Public contract data only; these are not credentials. */
static const unsigned char known_keys[ROWS][32] = {
    {0x9b,0x06,0x71,0xc8,0x15,0xdf,0xf5,0x13,0xbf,0xd4,0xa2,0xb2,0x6a,0xe1,0xf8,0x4e,0xc9,0x10,0x68,0x41,0xb2,0xfb,0x62,0x0d,0xb6,0x5f,0x6a,0xde,0x7c,0x21,0xad,0x06},
    {0x11,0x24,0x51,0xb9,0x84,0x3c,0x27,0x99,0xe4,0x75,0x0a,0xa3,0xd9,0xb9,0xaf,0xab,0xf5,0x36,0xa6,0x45,0xb8,0x63,0xc4,0xad,0xb2,0x07,0x8b,0x93,0x2b,0x35,0x4e,0x04},
    {0xa6,0xb8,0x58,0x48,0x57,0x48,0xbf,0x38,0xbe,0x19,0x35,0x17,0xab,0x90,0xf8,0xde,0x16,0x9e,0xff,0x09,0x89,0xea,0x93,0x60,0xdb,0x34,0x6a,0x37,0x8b,0x0f,0xfe,0x15},
    {0x50,0xff,0xde,0xb8,0x07,0xf8,0xff,0x73,0xa2,0x99,0xa1,0x60,0x00,0xaa,0x65,0x75,0xc5,0x94,0x5b,0xe8,0x75,0xad,0xfc,0x87,0x95,0x37,0x4e,0xd3,0x41,0x52,0x49,0xac},
    {0x9f,0x84,0x2d,0x07,0x87,0x55,0x64,0x7c,0x00,0x8e,0x5f,0xe3,0xe1,0x2b,0x83,0x9a,0x98,0x1d,0xe0,0x2a,0x1f,0x52,0x0c,0xb2,0x54,0x5c,0xc0,0x4c,0xd2,0x32,0x3e,0x0e},
    {0xb8,0xcc,0x75,0x86,0x4d,0x8e,0xf4,0x61,0xd8,0xdd,0x6a,0xa7,0x42,0x5e,0x2c,0x63,0xc2,0x4d,0x39,0x82,0x06,0x6b,0x8d,0x77,0x3a,0x15,0x49,0xbf,0xe2,0x4e,0x05,0xee},
    {0x76,0xbb,0x7b,0xbd,0xd9,0xf3,0x4e,0x12,0x4a,0x45,0x73,0xc3,0xaa,0x22,0x7e,0x3c,0x87,0xcc,0x60,0x35,0x06,0x69,0x7d,0x05,0x4f,0x6f,0xde,0xc3,0x42,0xe5,0x6e,0xbf},
    {0x1f,0xd6,0xdd,0x8f,0xa0,0xec,0x30,0xd3,0x9e,0x3f,0x72,0xe7,0x55,0xb8,0xa0,0x45,0xbd,0xe0,0xf7,0x04,0x49,0xdc,0x71,0x00,0x8b,0x76,0x7c,0x2e,0xaa,0x89,0xfb,0x9f},
    {0x15,0xd6,0x18,0xbd,0x7d,0xb5,0x77,0xbd,0x9a,0x8d,0x45,0x76,0x9c,0x59,0xe4,0xfc,0x63,0x16,0x33,0xbf,0x44,0x73,0x98,0xa4,0xb4,0x89,0xb4,0xc2,0x6f,0xbc,0x03,0xad},
    {0xb3,0x4e,0xc5,0x92,0xd2,0xac,0x49,0x93,0xf5,0xff,0xc8,0x5b,0x15,0xc3,0xda,0x90,0x78,0x69,0x40,0x51,0xcb,0x22,0x43,0x45,0x59,0x2a,0xf7,0x13,0x6d,0x79,0x6c,0x99},
    {0x9e,0x91,0x58,0x6d,0xd4,0xb1,0x15,0xab,0x05,0x68,0xb2,0x19,0x59,0xe1,0x81,0xf6,0x54,0x59,0x10,0xbc,0x5e,0x2f,0x30,0xb8,0x97,0x5c,0xa0,0x9d,0x7b,0xc3,0xef,0xce},
    {0x76,0x9c,0x82,0x4a,0x1d,0xad,0xd1,0x9a,0xee,0xfa,0x42,0x04,0x14,0xd1,0x73,0x2d,0xaf,0x44,0x53,0x7f,0x98,0xaa,0x22,0x9f,0xc5,0x47,0x7b,0xca,0x55,0xf9,0xd5,0x9a}
};
static const unsigned char new_group8_key[32] = {
    0x02,0x59,0x6f,0x0d,0x0c,0x06,0x1a,0x8b,0x30,0x74,0x59,0x88,0xfd,0x72,0xc5,0x9e,
    0x29,0xec,0x36,0x7f,0xb0,0xf3,0x41,0xf2,0x8e,0x0f,0x08,0xd0,0x37,0xba,0xfc,0x69
};

typedef struct {
    uintptr_t address;
    MEMORY_BASIC_INFORMATION region;
} Candidate;

typedef struct {
    unsigned long long bytes_read;
    unsigned long long read_errors;
    unsigned long long regions_seen;
    unsigned long long valid_blocks;
    unsigned long long query_errors;
    unsigned long long address_overflows;
    unsigned int candidate_count;
    Candidate candidates[CANDIDATE_LIMIT];
    int complete;
    int budget_exceeded;
} ScanResult;

typedef struct {
    unsigned int ids;
    unsigned int keys;
    unsigned int flags;
    unsigned int padding;
} ValidationCounts;

typedef struct {
    HANDLE process;
    HANDLE primary_thread;
    DWORD pid;
    ULONGLONG creation_time;
    uintptr_t image_base;
    DWORD image_size;
    const char *module_name;
    int synthetic;
    int passive;
} TraceState;

typedef struct {
    DWORD tid;
    int present;
} ThreadRecord;

typedef struct {
    uintptr_t base;
    DWORD size;
    char label[64];
    int symbol_verified;
} ModuleRecord;

typedef struct {
    DWORD code;
    unsigned int count;
} ExceptionCodeCount;

typedef struct {
    DWORD code;
    uintptr_t instruction;
    DWORD tid;
    DWORD first_chance;
    uintptr_t module_rva;
    char module[64];
    char classification[32];
    int module_verified;
} ExceptionSample;

static ThreadRecord threads[128];
static unsigned int thread_count;
static ModuleRecord modules[MODULE_LIMIT];
static unsigned int module_count;
static TraceState state;
static uintptr_t watched_address;
static MEMORY_BASIC_INFORMATION watched_region;
static int watch_armed;
static ULONGLONG watch_started;
static unsigned int event_count;
static unsigned int unclassified_count;
static unsigned int watch_trap_count;
static unsigned int unexpected_exception_count;
static unsigned int continue_failures;
static unsigned int arm_errors;
static int first_absence_seen;
static int first_valid_seen;
static ULONGLONG first_absence_tick;
static ULONGLONG first_valid_tick;
static int any_scan_incomplete;
static int deadline_expired;
static int target_exit_seen;
static DWORD target_exit_code;
static int target_image_verified;
static int debugger_error;
static int symbols_initialized;
static int synthetic_write_seen;
static int synthetic_read_after_write;
static int event_cap_exceeded;
static unsigned int exception_dispatch_count;
static unsigned int exception_samples_dropped;
static unsigned int exception_sample_count;
static unsigned int exception_code_count;
static ExceptionCodeCount exception_codes[EXCEPTION_CODE_LIMIT];
static ExceptionSample exception_samples[EXCEPTION_SAMPLE_LIMIT];
static int exception_dispatch_cap_exceeded;
static int creation_time_valid;
static int passive_identity_verified;
static unsigned int passive_complete_scans;
static unsigned int passive_candidate_count;
static unsigned long long passive_valid_blocks;
static int passive_timed_out;

static const ModuleRecord *module_for_instruction(uintptr_t instruction);

static int exception_dispatch_limit_reached(unsigned int dispatched) {
    return dispatched >= EXCEPTION_DISPATCH_LIMIT;
}

static DWORD child_creation_flags(int debug_child) {
    DWORD flags = CREATE_UNICODE_ENVIRONMENT | EXTENDED_STARTUPINFO_PRESENT;
    if (debug_child) flags |= DEBUG_ONLY_THIS_PROCESS;
    return flags;
}

static unsigned long long tick_ns(void) {
    return (unsigned long long)GetTickCount64() * 1000000ull;
}

static void emit_symbol_load(const char *module, uintptr_t base, DWORD size, int loaded, DWORD64 returned_base) {
    printf("{\"type\":\"symbol_load\",\"module\":\"%s\",\"base\":\"0x%llx\",\"size\":%lu,\"loaded\":%s,\"returned_base\":\"0x%llx\"}\n",
           module, (unsigned long long)base, (unsigned long)size, loaded ? "true" : "false", (unsigned long long)returned_base);
    fflush(stdout);
}

static void json_bool(int value) { fputs(value ? "true" : "false", stdout); }

static const char *protect_name(DWORD protect) {
    switch (protect & 0xffu) {
        case PAGE_NOACCESS: return "PAGE_NOACCESS";
        case PAGE_READONLY: return "PAGE_READONLY";
        case PAGE_READWRITE: return "PAGE_READWRITE";
        case PAGE_WRITECOPY: return "PAGE_WRITECOPY";
        case PAGE_EXECUTE: return "PAGE_EXECUTE";
        case PAGE_EXECUTE_READ: return "PAGE_EXECUTE_READ";
        case PAGE_EXECUTE_READWRITE: return "PAGE_EXECUTE_READWRITE";
        case PAGE_EXECUTE_WRITECOPY: return "PAGE_EXECUTE_WRITECOPY";
        default: return "UNKNOWN";
    }
}

static const char *state_name(DWORD state_value) {
    if (state_value == MEM_COMMIT) return "MEM_COMMIT";
    if (state_value == MEM_RESERVE) return "MEM_RESERVE";
    if (state_value == MEM_FREE) return "MEM_FREE";
    return "UNKNOWN";
}

static const char *type_name(DWORD type) {
    if (type == MEM_PRIVATE) return "MEM_PRIVATE";
    if (type == MEM_MAPPED) return "MEM_MAPPED";
    if (type == MEM_IMAGE) return "MEM_IMAGE";
    return "UNKNOWN";
}

static void emit_region(const MEMORY_BASIC_INFORMATION *region) {
    unsigned long long base = (unsigned long long)(uintptr_t)region->BaseAddress;
    unsigned long long allocation = (unsigned long long)(uintptr_t)region->AllocationBase;
    printf("{\"base\":\"0x%llx\",\"size\":%llu,\"protection\":\"%s\",\"protection_value\":%lu,\"state\":\"%s\",\"type\":\"%s\",\"backing\":\"%s\",\"private\":%s,\"file_mapped\":%s,\"allocation_base\":\"0x%llx\"}",
           base, (unsigned long long)region->RegionSize, protect_name(region->Protect),
           (unsigned long)region->Protect, state_name(region->State), type_name(region->Type),
           region->Type == MEM_PRIVATE ? "anonymous" : "mapped", region->Type == MEM_PRIVATE ? "true" : "false",
           region->Type == MEM_IMAGE || region->Type == MEM_MAPPED ? "true" : "false", allocation);
}

static void emit_scan(const ScanResult *result, ULONGLONG tick) {
    printf("{\"type\":\"scan\",\"timestamp_ns\":%llu,\"complete\":", (unsigned long long)tick * 1000000ull);
    json_bool(result->complete);
    printf(",\"budget_exceeded\":"); json_bool(result->budget_exceeded);
    printf(",\"bytes_read\":%llu,\"read_errors\":%llu,\"query_errors\":%llu,\"address_overflows\":%llu,\"regions_seen\":%llu,\"valid_blocks\":%llu,\"unique\":%s,\"regions\":[", result->bytes_read, result->read_errors, result->query_errors, result->address_overflows, result->regions_seen, result->valid_blocks, result->valid_blocks == 1 ? "true" : "false");
    for (unsigned int i = 0; i < result->candidate_count; ++i) {
        if (i) putchar(',');
        printf("{\"address\":\"0x%llx\",\"region\":", (unsigned long long)result->candidates[i].address);
        emit_region(&result->candidates[i].region);
        putchar('}');
    }
    puts("]}");
    fflush(stdout);
}

static int read_process_exact(uintptr_t address, void *buffer, SIZE_T size) {
    SIZE_T got = 0;
    return ReadProcessMemory(state.process, (LPCVOID)address, buffer, size, &got) && got == size;
}

static void count_validation(const unsigned char *table, ValidationCounts *counts) {
    memset(counts, 0, sizeof(*counts));
    for (unsigned int index = 0; index < ROWS; ++index) {
        const unsigned char *row = table + index * ROW_WIDTH;
        uint32_t id;
        memcpy(&id, row, sizeof(id));
        if (id == index + 1u) counts->ids++;
        if (memcmp(row + 4, known_keys[index], 32) == 0 || (index == 7u && memcmp(row + 4, new_group8_key, 32) == 0)) counts->keys++;
        if (row[36] == (index == 8u ? 0u : 1u)) counts->flags++;
        if (memcmp(row + 37, "\x7f\x00\x00", 3) == 0 || memcmp(row + 37, "\x00\x00\x00", 3) == 0) counts->padding++;
    }
}

static int row_matches(const unsigned char *row, unsigned int index) {
    uint32_t id;
    memcpy(&id, row, sizeof(id));
    if (id != index + 1u || row[36] != (index == 8u ? 0u : 1u)) return 0;
    if (memcmp(row + 4, known_keys[index], 32) != 0 && !(index == 7u && memcmp(row + 4, new_group8_key, 32) == 0)) return 0;
    return memcmp(row + 37, "\x7f\x00\x00", 3) == 0 || memcmp(row + 37, "\x00\x00\x00", 3) == 0;
}

static int valid_store(const unsigned char *table) {
    uint32_t first_id;
    memcpy(&first_id, table, sizeof(first_id));
    if (first_id != 1u || memcmp(table + 4, known_keys[0], 32) != 0) return 0;
    for (unsigned int index = 0; index < ROWS; ++index)
        if (!row_matches(table + index * ROW_WIDTH, index)) return 0;
    return 1;
}

static void add_candidate(ScanResult *result, uintptr_t address, const MEMORY_BASIC_INFORMATION *region) {
    for (unsigned int i = 0; i < result->candidate_count; ++i)
        if (result->candidates[i].address == address) return;
    result->valid_blocks++;
    if (result->candidate_count < CANDIDATE_LIMIT) {
        result->candidates[result->candidate_count].address = address;
        result->candidates[result->candidate_count].region = *region;
        result->candidate_count++;
    }
}

static SIZE_T first_aligned_candidate(uintptr_t chunk_base) {
    return (SIZE_T)((4u - (chunk_base & 3u)) & 3u);
}

static ScanResult scan_private_regions(void) {
    ScanResult result;
    memset(&result, 0, sizeof(result));
    ULONGLONG started = GetTickCount64();
    SYSTEM_INFO system_info;
    GetSystemInfo(&system_info);
    uintptr_t maximum_address = (uintptr_t)system_info.lpMaximumApplicationAddress;
    uintptr_t cursor = 0;
    unsigned char *buffer = (unsigned char *)malloc(SCAN_CHUNK + SCAN_OVERLAP);
    if (!buffer) { result.complete = 0; result.read_errors = 1; return result; }
    for (;;) {
        if (cursor > maximum_address) break;
        MEMORY_BASIC_INFORMATION region;
        SetLastError(ERROR_SUCCESS);
        SIZE_T queried = VirtualQueryEx(state.process, (LPCVOID)cursor, &region, sizeof(region));
        if (queried != sizeof(region)) {
            DWORD error = GetLastError();
            if (!(error == ERROR_INVALID_PARAMETER && cursor > maximum_address)) result.query_errors++;
            break;
        }
        uintptr_t base = (uintptr_t)region.BaseAddress;
        uintptr_t end = base + (uintptr_t)region.RegionSize;
        if (end <= base) { result.address_overflows++; break; }
        if (region.State == MEM_COMMIT && region.Type == MEM_PRIVATE &&
            (region.Protect & 0xffu) == PAGE_READWRITE && !(region.Protect & PAGE_GUARD)) {
            result.regions_seen++;
            SIZE_T offset = 0;
            while (offset < region.RegionSize) {
                ULONGLONG now = GetTickCount64();
                if (result.bytes_read >= MAX_SCAN_BYTES || now - started >= SCAN_BUDGET_MS) {
                    result.budget_exceeded = 1;
                    goto finished;
                }
                SIZE_T begin = offset > 0 ? offset - SCAN_OVERLAP : 0;
                SIZE_T available = region.RegionSize - begin;
                SIZE_T amount = available > SCAN_CHUNK ? SCAN_CHUNK : available;
                if (result.bytes_read + amount > MAX_SCAN_BYTES) {
                    result.budget_exceeded = 1;
                    goto finished;
                }
                if (!read_process_exact(base + begin, buffer, amount)) {
                    result.read_errors++;
                } else {
                    result.bytes_read += amount;
                    /* Examine overlap too: a 480-byte block can begin before
                     * the preceding chunk's end but finish in this chunk.
                     * add_candidate() deduplicates successful observations. */
                    SIZE_T first = first_aligned_candidate(base + begin);
                    if (amount >= TABLE_BYTES) {
                        for (SIZE_T local = first; local + TABLE_BYTES <= amount; local += 4) {
                            uintptr_t address = base + begin + local;
                            if (valid_store(buffer + local)) {
                                add_candidate(&result, address, &region);
                            }
                        }
                    }
                }
                if (available <= amount) break;
                offset = begin + amount;
            }
        }
        if (end == (uintptr_t)-1) break;
        cursor = end;
    }
finished:
    free(buffer);
    result.complete = !result.budget_exceeded && result.read_errors == 0 && result.query_errors == 0 && result.address_overflows == 0;
    return result;
}

static int child_is_live(void) {
    DWORD code = 0;
    return GetExitCodeProcess(state.process, &code) && code == STILL_ACTIVE;
}

static int child_creation_time(ULONGLONG *value) {
    FILETIME creation, exit_time, kernel, user;
    if (!GetProcessTimes(state.process, &creation, &exit_time, &kernel, &user)) return 0;
    ULARGE_INTEGER integer;
    integer.LowPart = creation.dwLowDateTime;
    integer.HighPart = creation.dwHighDateTime;
    *value = integer.QuadPart;
    return 1;
}

static int owned_child_identity(void) {
    ULONGLONG current = 0;
    return state.process != NULL && GetProcessId(state.process) == state.pid &&
           creation_time_valid && child_creation_time(&current) && current == state.creation_time;
}

static int terminate_owned_child(UINT exit_code) {
    if (!owned_child_identity()) return 0;
    if (!child_is_live()) return 1;
    return TerminateProcess(state.process, exit_code) != 0;
}

static int watched_region_current(MEMORY_BASIC_INFORMATION *current) {
    /* Query at the same region origin as discovery. VirtualQueryEx may return
     * a page-rounded suffix when queried at an interior watched address. */
    SIZE_T queried = VirtualQueryEx(state.process, watched_region.BaseAddress, current, sizeof(*current));
    if (queried != sizeof(*current)) return 0;
    if (current->State != MEM_COMMIT || current->Type != MEM_PRIVATE ||
        (current->Protect & 0xffu) != PAGE_READWRITE || (current->Protect & PAGE_GUARD) ||
        watched_address < (uintptr_t)current->BaseAddress ||
        TABLE_BYTES > (uintptr_t)current->RegionSize - (watched_address - (uintptr_t)current->BaseAddress)) return 0;
    return current->BaseAddress == watched_region.BaseAddress && current->RegionSize == watched_region.RegionSize &&
           current->AllocationBase == watched_region.AllocationBase && current->State == watched_region.State &&
           current->Type == watched_region.Type && current->Protect == watched_region.Protect;
}

static int read_watched_stable(ValidationCounts *counts, MEMORY_BASIC_INFORMATION *current) {
    unsigned char first[TABLE_BYTES];
    unsigned char second[TABLE_BYTES];
    if (!watched_region_current(current) || !read_process_exact(watched_address, first, sizeof(first))) return 0;
    if (!watched_region_current(current) || !read_process_exact(watched_address, second, sizeof(second))) return 0;
    if (memcmp(first, second, TABLE_BYTES) != 0 || !watched_region_current(current)) return 0;
    count_validation(second, counts);
    return 1;
}

static void remember_thread(DWORD tid) {
    for (unsigned int i = 0; i < thread_count; ++i) if (threads[i].tid == tid) { threads[i].present = 1; return; }
    if (thread_count < sizeof(threads) / sizeof(threads[0])) {
        threads[thread_count].tid = tid;
        threads[thread_count].present = 1;
        thread_count++;
    } else arm_errors++;
}

static void forget_thread(DWORD tid) {
    for (unsigned int i = 0; i < thread_count; ++i)
        if (threads[i].tid == tid) { threads[i].present = 0; return; }
}

static unsigned long long register_value(const CONTEXT *context, unsigned int register_number) {
    switch (register_number & 7u) {
        case 0: return context->Rax; case 1: return context->Rcx; case 2: return context->Rdx; case 3: return context->Rbx;
        case 4: return context->Rsp; case 5: return context->Rbp; case 6: return context->Rsi; default: return context->Rdi;
    }
}

static int decode_access(const CONTEXT *context, const unsigned char *code, uintptr_t code_base, uintptr_t *instruction, uintptr_t *target, unsigned int *width, const char **access, unsigned char *instruction_bytes, unsigned int *instruction_length) {
    uintptr_t rip = (uintptr_t)context->Rip;
    for (unsigned int offset = 0; offset + 2 <= 16; ++offset) {
        const unsigned char *p = code + offset;
        uintptr_t ip = code_base + offset;
        if (p[0] == 0x48 && (p[1] == 0x89 || p[1] == 0x8b) && p[2] == 0x05 && offset + 7 <= 16 && ip + 7 == rip) {
            int32_t displacement;
            memcpy(&displacement, p + 3, sizeof(displacement));
            uintptr_t destination = ip + 7 + displacement;
            if (destination != watched_address) continue;
            *instruction = ip; *target = destination; *width = 8; *access = p[1] == 0x89 ? "write" : "read"; *instruction_length = 7; memcpy(instruction_bytes, p, 7); return 1;
        }
        if (p[0] == 0x48 && (p[1] == 0x89 || p[1] == 0x8b) && (p[2] & 0xc7u) == 0x01u && offset + 3 <= 16 && ip + 3 == rip) {
            uintptr_t destination = (uintptr_t)register_value(context, p[2] & 7u);
            if (destination != watched_address) continue;
            *instruction = ip; *target = destination; *width = 8; *access = p[1] == 0x89 ? "write" : "read"; *instruction_length = 3; memcpy(instruction_bytes, p, 3); return 1;
        }
    }
    return 0;
}

static unsigned int collect_stack(HANDLE thread, CONTEXT *context, uintptr_t *frames) {
    if (!symbols_initialized) return 0;
    CONTEXT stack_context = *context;
    STACKFRAME64 frame;
    memset(&frame, 0, sizeof(frame));
    frame.AddrPC.Offset = context->Rip; frame.AddrPC.Mode = AddrModeFlat;
    frame.AddrFrame.Offset = context->Rbp; frame.AddrFrame.Mode = AddrModeFlat;
    frame.AddrStack.Offset = context->Rsp; frame.AddrStack.Mode = AddrModeFlat;
    unsigned int count = 0;
    for (unsigned int i = 0; i < STACK_LIMIT; ++i) {
        if (!StackWalk64(IMAGE_FILE_MACHINE_AMD64, state.process, thread, &frame, &stack_context, NULL, SymFunctionTableAccess64, SymGetModuleBase64, NULL) || frame.AddrPC.Offset == 0) break;
        frames[count++] = (uintptr_t)frame.AddrPC.Offset;
    }
    return count;
}

static void emit_event(const char *access, uintptr_t instruction, uintptr_t target, unsigned int width, const unsigned char *bytes, unsigned int length, DWORD tid, HANDLE thread, CONTEXT *context, const MEMORY_BASIC_INFORMATION *region, const ValidationCounts *counts) {
    const ModuleRecord *module = module_for_instruction(instruction);
    printf("{\"type\":\"event\",\"timestamp_ns\":%llu,\"thread_id\":%lu,\"access\":\"%s\",\"phase\":\"%s\",\"timing\":\"POST_DISCOVERY\",\"instruction_ip\":\"0x%llx\",\"post_trap_ip\":\"0x%llx\",\"module\":", tick_ns(), (unsigned long)tid, access, strcmp(access, "write") == 0 ? "MUTATION" : "USE", (unsigned long long)instruction, (unsigned long long)context->Rip);
    if (module != NULL && module->symbol_verified) printf("\"%s\"", module->label); else fputs("null", stdout);
    fputs(",\"module_rva\":", stdout);
    if (module != NULL && module->symbol_verified) printf("\"0x%llx\"", (unsigned long long)(instruction - module->base)); else fputs("null", stdout);
    printf(",\"width\":%u,\"watch_width\":8,\"target\":\"0x%llx\",\"instruction_bytes\":\"", width, (unsigned long long)target);
    for (unsigned int i = 0; i < length; ++i) printf("%02x", bytes[i]);
    uintptr_t frames[STACK_LIMIT];
    unsigned int stack_count = collect_stack(thread, context, frames);
    printf("\",\"stack_status\":\"%s\",\"stack_count\":%u,\"stack\":[", stack_count ? "captured" : "unknown-unwind", stack_count);
    for (unsigned int i = 0; i < stack_count; ++i) {
        if (i) putchar(',');
        printf("\"0x%llx\"", (unsigned long long)frames[i]);
    }
    putchar(']');
    printf(",\"validation_counts_after_access\":{\"ids\":%u,\"keys\":%u,\"flags\":%u,\"padding\":%u},\"region\":", counts->ids, counts->keys, counts->flags, counts->padding);
    if (region) emit_region(region); else fputs("null", stdout);
    puts("}");
    fflush(stdout);
}

static int arm_handle(HANDLE thread) {
    CONTEXT context;
    memset(&context, 0, sizeof(context));
    context.ContextFlags = CONTEXT_DEBUG_REGISTERS;
    if (!GetThreadContext(thread, &context)) return 0;
    context.Dr0 = (DWORD64)watched_address;
    context.Dr6 = 0;
    context.Dr7 = (context.Dr7 & ~0x000f000fULL) | 0x000b0001ULL;
    return SetThreadContext(thread, &context) != 0;
}

static HANDLE open_owned_thread(DWORD tid) {
    HANDLE thread = OpenThread(THREAD_GET_CONTEXT | THREAD_SET_CONTEXT | THREAD_QUERY_INFORMATION, FALSE, tid);
    if (!thread) return NULL;
    DWORD owner = GetProcessIdOfThread(thread);
    DWORD exit_code = 0;
    if (owner != state.pid || !GetExitCodeThread(thread, &exit_code) || exit_code != STILL_ACTIVE) {
        CloseHandle(thread);
        return NULL;
    }
    return thread;
}

static void arm_all_threads(void) {
    for (unsigned int i = 0; i < thread_count; ++i) {
        if (!threads[i].present) continue;
        HANDLE thread = open_owned_thread(threads[i].tid);
        if (!thread || !arm_handle(thread)) arm_errors++;
        if (thread) CloseHandle(thread);
    }
    watch_armed = arm_errors == 0;
    watch_started = GetTickCount64();
}

static void disarm_all_threads(void) {
    for (unsigned int i = 0; i < thread_count; ++i) {
        if (!threads[i].present) continue;
        HANDLE thread = open_owned_thread(threads[i].tid);
        if (thread) {
            CONTEXT context; memset(&context, 0, sizeof(context)); context.ContextFlags = CONTEXT_DEBUG_REGISTERS;
            if (GetThreadContext(thread, &context)) { context.Dr6 = 0; context.Dr7 &= ~0x000b0001ULL; SetThreadContext(thread, &context); }
            CloseHandle(thread);
        }
    }
    watch_armed = 0;
}

static const char *exception_classification(DWORD code) {
    if (code == 0x406d1388u) return "thread_name_notify";
    if (code == EXCEPTION_BREAKPOINT) return "breakpoint";
    return "unexpected";
}

static void remember_exception(const DEBUG_EVENT *event, DWORD code, uintptr_t ip) {
    const ModuleRecord *module = module_for_instruction(ip);
    unexpected_exception_count++;
    for (unsigned int index = 0; index < exception_code_count; ++index) {
        if (exception_codes[index].code == code) {
            exception_codes[index].count++;
            goto sample;
        }
    }
    if (exception_code_count < EXCEPTION_CODE_LIMIT) {
        exception_codes[exception_code_count].code = code;
        exception_codes[exception_code_count].count = 1;
        exception_code_count++;
    }
sample:
    if (exception_sample_count >= EXCEPTION_SAMPLE_LIMIT) {
        exception_samples_dropped++;
        return;
    }
    ExceptionSample *sample_record = &exception_samples[exception_sample_count++];
    memset(sample_record, 0, sizeof(*sample_record));
    sample_record->code = code;
    sample_record->instruction = ip;
    sample_record->tid = event->dwThreadId;
    sample_record->first_chance = event->u.Exception.dwFirstChance;
    strncpy(sample_record->classification, exception_classification(code), sizeof(sample_record->classification) - 1);
    if (module != NULL && module->symbol_verified && ip >= module->base && ip - module->base < module->size) {
        sample_record->module_verified = 1;
        sample_record->module_rva = ip - module->base;
        strncpy(sample_record->module, module->label, sizeof(sample_record->module) - 1);
    }
}

static void emit_exception_summary(void) {
    printf(",\"exception_dispatches\":%u,\"exception_dispatch_limit\":%u,\"exception_dispatch_cap_exceeded\":",
           exception_dispatch_count, EXCEPTION_DISPATCH_LIMIT);
    json_bool(exception_dispatch_cap_exceeded);
    printf(",\"exception_samples_dropped\":%u,\"exception_code_counts\":[", exception_samples_dropped);
    for (unsigned int index = 0; index < exception_code_count; ++index) {
        if (index) putchar(',');
        printf("{\"code\":\"0x%lx\",\"count\":%u}",
               (unsigned long)exception_codes[index].code, exception_codes[index].count);
    }
    fputs("],\"exception_samples\":[", stdout);
    for (unsigned int index = 0; index < exception_sample_count; ++index) {
        const ExceptionSample *sample = &exception_samples[index];
        if (index) putchar(',');
        printf("{\"code\":\"0x%lx\",\"instruction_ip\":\"0x%llx\",\"thread_id\":%lu,\"first_chance\":%s,\"classification\":\"%s\",\"continue\":\"DBG_EXCEPTION_NOT_HANDLED\",\"module\":",
               (unsigned long)sample->code, (unsigned long long)sample->instruction,
               (unsigned long)sample->tid, sample->first_chance ? "true" : "false",
               sample->classification);
        if (sample->module_verified) printf("\"%s\",\"module_rva\":\"0x%llx\"",
                                            sample->module, (unsigned long long)sample->module_rva);
        else fputs("null,\"module_rva\":null", stdout);
        putchar('}');
    }
    putchar(']');
}

static void emit_unknown_access(DWORD tid, uintptr_t ip, const MEMORY_BASIC_INFORMATION *region) {
    printf("{\"type\":\"event\",\"classification\":\"UNKNOWN\",\"timing\":\"POST_DISCOVERY\",\"thread_id\":%lu,\"post_trap_ip\":\"0x%llx\",\"module\":null,\"module_rva\":null,\"region\":", (unsigned long)tid, (unsigned long long)ip);
    if (region) emit_region(region); else fputs("null", stdout);
    puts("}");
    fflush(stdout);
}

static int handle_watch_exception(const DEBUG_EVENT *event) {
    if (watch_trap_count >= EVENT_LIMIT) {
        event_cap_exceeded = 1;
        debugger_error = 1;
        disarm_all_threads();
        return 1;
    }
    watch_trap_count++;
    if (GetTickCount64() - watch_started >= DETAIL_LIMIT_MS) { disarm_all_threads(); return 1; }
    HANDLE thread = open_owned_thread(event->dwThreadId);
    if (!thread) { unclassified_count++; debugger_error = 1; return 0; }
    CONTEXT context; memset(&context, 0, sizeof(context)); context.ContextFlags = CONTEXT_FULL | CONTEXT_DEBUG_REGISTERS;
    unsigned char code[16]; memset(code, 0, sizeof(code));
    int context_ok = GetThreadContext(thread, &context);
    if (context_ok && !(context.Dr6 & 1u)) {
        remember_exception(event, EXCEPTION_SINGLE_STEP, (uintptr_t)context.Rip);
        debugger_error = 1;
        CloseHandle(thread);
        disarm_all_threads();
        return 0;
    }
    int decoded = context_ok && context.Rip >= 16 && read_process_exact((uintptr_t)context.Rip - 16, code, sizeof(code));
    uintptr_t instruction = 0, target = 0; unsigned int width = 0, length = 0; const char *access = NULL; unsigned char instruction_bytes[16];
    if (decoded) decoded = decode_access(&context, code, (uintptr_t)context.Rip - 16, &instruction, &target, &width, &access, instruction_bytes, &length);
    if (!decoded) {
        unclassified_count++;
        MEMORY_BASIC_INFORMATION unknown_region;
        SIZE_T unknown_query = VirtualQueryEx(state.process, (LPCVOID)watched_address, &unknown_region, sizeof(unknown_region));
        emit_unknown_access(event->dwThreadId, (uintptr_t)context.Rip, unknown_query == sizeof(unknown_region) ? &unknown_region : NULL);
        context.Dr6 = 0;
        if (!SetThreadContext(thread, &context)) debugger_error = 1;
        CloseHandle(thread);
        return 1;
    }
    MEMORY_BASIC_INFORMATION region; memset(&region, 0, sizeof(region));
    SIZE_T queried = VirtualQueryEx(state.process, (LPCVOID)watched_address, &region, sizeof(region));
    ValidationCounts counts;
    if (!read_watched_stable(&counts, &region)) {
        debugger_error = 1;
        unclassified_count++;
        emit_unknown_access(event->dwThreadId, (uintptr_t)context.Rip, queried == sizeof(region) ? &region : NULL);
        context.Dr6 = 0;
        if (!SetThreadContext(thread, &context)) debugger_error = 1;
        CloseHandle(thread);
        disarm_all_threads();
        return 1;
    }
    emit_event(access, instruction, target, width, instruction_bytes, length, event->dwThreadId, thread, &context, &region, &counts);
    event_count++;
    int finish_synthetic_window = 0;
    if (state.synthetic && strcmp(access, "write") == 0) synthetic_write_seen = 1;
    if (state.synthetic && strcmp(access, "read") == 0 && synthetic_write_seen) {
        synthetic_read_after_write = 1;
        finish_synthetic_window = 1;
    }
    context.Dr6 = 0;
    if (!SetThreadContext(thread, &context)) debugger_error = 1;
    CloseHandle(thread);
    if (finish_synthetic_window) disarm_all_threads();
    return 1;
}

static DWORD image_size_from_file(const wchar_t *path) {
    HANDLE file = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) return 0;
    unsigned char header[4096]; DWORD got = 0; DWORD size = 0;
    if (ReadFile(file, header, sizeof(header), &got, NULL) && got >= 0x100 && header[0] == 'M' && header[1] == 'Z') {
        DWORD pe_offset = *(DWORD *)(header + 0x3c);
        if (pe_offset + 0x98 < got && memcmp(header + pe_offset, "PE\0\0", 4) == 0 &&
            *(WORD *)(header + pe_offset + 4) == 0x8664 &&
            *(WORD *)(header + pe_offset + 24) == 0x20b)
            size = *(DWORD *)(header + pe_offset + 0x50);
    }
    CloseHandle(file); return size;
}

static const wchar_t *expected_child_path(int synthetic) {
    return synthetic ? L"Z:\\lab\\synthetic_certstore_fixture.exe" : L"Z:\\lab\\game\\WowB.exe";
}

static DWORD module_size_from_process(uintptr_t base) {
    MODULEINFO info;
    memset(&info, 0, sizeof(info));
    if (GetModuleInformation(state.process, (HMODULE)base, &info, sizeof(info))) return info.SizeOfImage;
    unsigned char header[4096];
    if (read_process_exact(base, header, sizeof(header)) && header[0] == 'M' && header[1] == 'Z') {
        DWORD pe_offset;
        memcpy(&pe_offset, header + 0x3c, sizeof(pe_offset));
        if (pe_offset + 0x54 < sizeof(header) && memcmp(header + pe_offset, "PE\0\0", 4) == 0) {
            DWORD image_size;
            memcpy(&image_size, header + pe_offset + 0x50, sizeof(image_size));
            if (image_size != 0) return image_size;
        }
    }
    return 0;
}

static DWORD64 load_debug_symbols(HANDLE file, const wchar_t *image_path, const char *label, uintptr_t base, DWORD size) {
    DWORD64 returned_base = 0;
    if (symbols_initialized && image_path != NULL)
        returned_base = SymLoadModuleExW(state.process, file, image_path, NULL, (DWORD64)base, size, NULL, 0);
    int already_loaded = symbols_initialized && SymGetModuleBase64(state.process, (DWORD64)base) == (DWORD64)base;
    emit_symbol_load(label, base, size, returned_base != 0 || already_loaded, returned_base);
    return returned_base != 0 || already_loaded ? (returned_base != 0 ? returned_base : (DWORD64)base) : 0;
}

static void safe_module_basename(const wchar_t *path, char *label, size_t capacity) {
    const wchar_t *start = path;
    for (const wchar_t *cursor = path; *cursor != L'\0'; ++cursor)
        if (*cursor == L'\\' || *cursor == L'/') start = cursor + 1;
    size_t index = 0;
    while (start[index] != L'\0' && index + 1 < capacity) {
        wchar_t value = start[index];
        label[index] = ((value >= L'a' && value <= L'z') || (value >= L'A' && value <= L'Z') ||
                       (value >= L'0' && value <= L'9') || value == L'.' || value == L'_' || value == L'-')
                          ? (char)value : '?';
        index++;
    }
    label[index] = '\0';
    if (index == 0) strncpy(label, "loaded-dll", capacity);
}

static int remember_module(uintptr_t base, DWORD size, const char *label) {
    if (base == 0 || size == 0 || base > UINTPTR_MAX - (uintptr_t)size || label == NULL || label[0] == '\0') return 0;
    for (unsigned int index = 0; index < module_count; ++index) {
        if (modules[index].base == base) {
            modules[index].size = size;
            strncpy(modules[index].label, label, sizeof(modules[index].label) - 1);
            modules[index].label[sizeof(modules[index].label) - 1] = '\0';
            modules[index].symbol_verified = 1;
            return 1;
        }
    }
    if (module_count >= MODULE_LIMIT) return 0;
    modules[module_count].base = base;
    modules[module_count].size = size;
    strncpy(modules[module_count].label, label, sizeof(modules[module_count].label) - 1);
    modules[module_count].label[sizeof(modules[module_count].label) - 1] = '\0';
    modules[module_count].symbol_verified = 1;
    module_count++;
    return 1;
}

static void forget_module(uintptr_t base) {
    for (unsigned int index = 0; index < module_count; ++index) {
        if (modules[index].base == base) {
            modules[index] = modules[module_count - 1];
            module_count--;
            return;
        }
    }
}

static const ModuleRecord *module_for_instruction(uintptr_t instruction) {
    const ModuleRecord *match = NULL;
    for (unsigned int index = 0; index < module_count; ++index) {
        uintptr_t end = modules[index].base + (uintptr_t)modules[index].size;
        if (end > modules[index].base && instruction >= modules[index].base && instruction < end &&
            (match == NULL || modules[index].size < match->size)) match = &modules[index];
    }
    return match;
}

static DWORD64 load_dll_debug_symbols(const LOAD_DLL_DEBUG_INFO *dll) {
    wchar_t image_path[1024];
    DWORD path_length = 0;
    const wchar_t *path = NULL;
    char label[64] = "loaded-dll";
    if (dll->hFile != NULL && dll->hFile != INVALID_HANDLE_VALUE) {
        path_length = GetFinalPathNameByHandleW(dll->hFile, image_path, sizeof(image_path) / sizeof(image_path[0]), FILE_NAME_NORMALIZED);
        if (path_length > 0 && path_length < sizeof(image_path) / sizeof(image_path[0])) {
            path = image_path;
            safe_module_basename(image_path, label, sizeof(label));
        }
    }
    DWORD size = module_size_from_process((uintptr_t)dll->lpBaseOfDll);
    DWORD64 loaded_base = load_debug_symbols(dll->hFile, path, label, (uintptr_t)dll->lpBaseOfDll, size);
    if (loaded_base == 0 || loaded_base != (DWORD64)(uintptr_t)dll->lpBaseOfDll || !remember_module((uintptr_t)dll->lpBaseOfDll, size, label)) return 0;
    return loaded_base;
}

static int launch_child(int synthetic, int debug_child) {
    wchar_t application[128]; wchar_t command_line[512];
    const wchar_t *path = synthetic ? L"Z:\\lab\\synthetic_certstore_fixture.exe" : L"Z:\\lab\\game\\WowB.exe";
    if (synthetic) wcscpy(application, path); else wcscpy(application, path);
    if (synthetic) _snwprintf(command_line, sizeof(command_line) / sizeof(command_line[0]), L"\"%s\"", path);
    else _snwprintf(command_line, sizeof(command_line) / sizeof(command_line[0]), L"\"%s\" -d3d11 -config BetaTrace.wtf", path);
    SECURITY_ATTRIBUTES nul_attributes;
    memset(&nul_attributes, 0, sizeof(nul_attributes));
    nul_attributes.nLength = sizeof(nul_attributes);
    nul_attributes.bInheritHandle = TRUE;
    HANDLE nul = CreateFileW(L"NUL", GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, &nul_attributes, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (nul == INVALID_HANDLE_VALUE) { fprintf(stderr, "NUL handle failed: %lu\n", (unsigned long)GetLastError()); return 0; }
    SIZE_T attribute_size = 0;
    InitializeProcThreadAttributeList(NULL, 1, 0, &attribute_size);
    if (attribute_size == 0) {
        fprintf(stderr, "handle-list attribute sizing failed: %lu\n", (unsigned long)GetLastError());
        CloseHandle(nul);
        return 0;
    }
    LPPROC_THREAD_ATTRIBUTE_LIST attributes = (LPPROC_THREAD_ATTRIBUTE_LIST)HeapAlloc(GetProcessHeap(), 0, attribute_size);
    if (attributes == NULL || !InitializeProcThreadAttributeList(attributes, 1, 0, &attribute_size)) {
        fprintf(stderr, "handle-list attribute initialization failed: %lu\n", (unsigned long)GetLastError());
        if (attributes) HeapFree(GetProcessHeap(), 0, attributes);
        CloseHandle(nul);
        return 0;
    }
    HANDLE inherited_handles[1] = {nul};
    if (!UpdateProcThreadAttribute(attributes, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
                                   inherited_handles, sizeof(inherited_handles), NULL, NULL)) {
        fprintf(stderr, "handle-list attribute update failed: %lu\n", (unsigned long)GetLastError());
        DeleteProcThreadAttributeList(attributes);
        HeapFree(GetProcessHeap(), 0, attributes);
        CloseHandle(nul);
        return 0;
    }
    STARTUPINFOEXW startup; PROCESS_INFORMATION process_info; memset(&startup, 0, sizeof(startup)); memset(&process_info, 0, sizeof(process_info));
    startup.StartupInfo.cb = sizeof(startup); startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdOutput = nul; startup.StartupInfo.hStdError = nul; startup.StartupInfo.hStdInput = nul;
    startup.lpAttributeList = attributes;
    DWORD flags = child_creation_flags(debug_child);
    const wchar_t *working_directory = synthetic ? L"Z:\\lab" : L"Z:\\lab\\game";
    BOOL created = CreateProcessW(application, command_line, NULL, NULL, TRUE, flags, NULL, working_directory, &startup.StartupInfo, &process_info);
    DWORD create_error = created ? ERROR_SUCCESS : GetLastError();
    DeleteProcThreadAttributeList(attributes);
    HeapFree(GetProcessHeap(), 0, attributes);
    CloseHandle(nul);
    if (!created) { fprintf(stderr, "fixed child launch failed: %lu\n", (unsigned long)create_error); return 0; }
    state.process = process_info.hProcess; state.primary_thread = process_info.hThread; state.pid = process_info.dwProcessId; state.synthetic = synthetic; state.passive = !debug_child;
    state.module_name = synthetic ? "synthetic_certstore_fixture.exe" : "WowB.exe";
    creation_time_valid = child_creation_time(&state.creation_time);
    if (!creation_time_valid) debugger_error = 1;
    return 1;
}

static void emit_launch(void) {
    printf("{\"type\":\"launch\",\"pid\":%lu,\"creation_time_100ns\":%llu,\"owned_process_handle\":true,\"fixed_child\":true,\"target\":\"%s\",\"arguments\":\"%s\"",
           (unsigned long)state.pid, (unsigned long long)state.creation_time, state.module_name,
           state.synthetic ? "" : "-d3d11 -config BetaTrace.wtf");
    if (state.passive) fputs(",\"mode\":\"passive-real\"", stdout);
    puts("}");
    fflush(stdout);
}

static void emit_module_records(void) {
    fputs(",\"module_records\":[", stdout);
    for (unsigned int index = 0; index < module_count; ++index) {
        if (index) putchar(',');
        printf("{\"base\":\"0x%llx\",\"size\":%lu,\"module\":\"%s\",\"symbol_verified\":true}",
               (unsigned long long)modules[index].base, (unsigned long)modules[index].size, modules[index].label);
    }
    putchar(']');
}

static void emit_final(void) {
    int unique = first_valid_seen && !any_scan_incomplete;
    int owned_process_handle = state.process != NULL && GetProcessId(state.process) == state.pid;
    int start_time_double_checked = owned_child_identity();
    if (!owned_process_handle || !start_time_double_checked) debugger_error = 1;
    printf("{\"type\":\"final\",\"identity\":{\"pid\":%lu,\"creation_time_100ns\":%llu,\"owned_process_handle\":", (unsigned long)state.pid, (unsigned long long)state.creation_time); json_bool(owned_process_handle);
    printf(",\"start_time_double_checked\":"); json_bool(start_time_double_checked);
    printf("},\"target_exit_seen\":"); json_bool(target_exit_seen);
    fputs(",\"target_exit_code\":", stdout);
    if (target_exit_seen) printf("%lu", (unsigned long)target_exit_code); else fputs("null", stdout);
    printf(",\"executable_pe_x64_verified\":"); json_bool(target_image_verified);
    printf(",\"complete_unique_store\":"); json_bool(unique);
    printf(",\"first_complete_absence_ns\":"); if (first_absence_seen) printf("%llu", (unsigned long long)first_absence_tick * 1000000ull); else fputs("null", stdout);
    printf(",\"first_valid_ns\":"); if (first_valid_seen) printf("%llu", (unsigned long long)first_valid_tick * 1000000ull); else fputs("null", stdout);
    printf(",\"first_valid_interval_ns\":"); if (first_absence_seen && first_valid_seen) printf("%llu", (unsigned long long)(first_valid_tick - first_absence_tick) * 1000000ull); else fputs("null", stdout);
    printf(",\"watch_armed\":"); json_bool(watch_armed);
    printf(",\"events\":%u,\"watch_traps\":%u,\"unclassified_events\":%u,\"unexpected_exceptions\":%u,\"continue_failures\":%u,\"arm_errors\":%u,\"debugger_error\":", event_count, watch_trap_count, unclassified_count, unexpected_exception_count, continue_failures, arm_errors); json_bool(debugger_error);
    printf(",\"deadline_expired\":"); json_bool(deadline_expired);
    printf(",\"event_cap_exceeded\":"); json_bool(event_cap_exceeded);
    printf(",\"no_store_observed\":"); json_bool(!first_valid_seen && first_absence_seen && !any_scan_incomplete);
    printf(",\"synthetic_window_complete\":"); json_bool(!state.synthetic || synthetic_read_after_write);
    printf(",\"initializer_construction_proven\":false,\"authentication_claim\":false,\"real_wow_launched\":"); json_bool(!state.synthetic);
    emit_exception_summary();
    emit_module_records();
    puts("}"); fflush(stdout);
}

static int verify_fixed_image_path(void) {
    wchar_t module_path[512];
    DWORD path_length = sizeof(module_path) / sizeof(module_path[0]);
    return QueryFullProcessImageNameW(state.process, 0, module_path, &path_length) &&
           path_length > 0 && lstrcmpiW(module_path, expected_child_path(0)) == 0 && state.image_size != 0;
}

static void passive_record_process_exit(void) {
    DWORD code = 0;
    if (!GetExitCodeProcess(state.process, &code)) {
        debugger_error = 1;
        return;
    }
    if (code != STILL_ACTIVE) {
        target_exit_seen = 1;
        target_exit_code = code;
    }
}

static void passive_scan_once(void) {
    ULONGLONG identity_before = 0;
    ULONGLONG identity_after = 0;
    if (!owned_child_identity() || !verify_fixed_image_path()) {
        target_image_verified = 0;
        passive_identity_verified = 0;
        debugger_error = 1;
        return;
    }
    if (!child_is_live()) {
        passive_record_process_exit();
        return;
    }
    if (!child_creation_time(&identity_before) || identity_before != state.creation_time) {
        passive_identity_verified = 0;
        debugger_error = 1;
        return;
    }
    ScanResult result = scan_private_regions();
    emit_scan(&result, GetTickCount64());
    passive_candidate_count = result.candidate_count;
    passive_valid_blocks = result.valid_blocks;
    if (result.complete) passive_complete_scans++;
    if (!result.complete) any_scan_incomplete = 1;
    if (result.complete && result.candidate_count == 0 && !first_absence_seen) {
        first_absence_seen = 1;
        first_absence_tick = GetTickCount64();
    }
    if (result.complete && result.valid_blocks == 1 && result.candidate_count == 1 && !first_valid_seen) {
        watched_address = result.candidates[0].address;
        watched_region = result.candidates[0].region;
        ValidationCounts initial_counts;
        MEMORY_BASIC_INFORMATION stable_region;
        if (read_watched_stable(&initial_counts, &stable_region) &&
            initial_counts.ids == ROWS && initial_counts.keys == ROWS &&
            initial_counts.flags == ROWS && initial_counts.padding == ROWS) {
            first_valid_seen = 1;
            first_valid_tick = GetTickCount64();
            watched_region = stable_region;
        } else {
            any_scan_incomplete = 1;
            debugger_error = 1;
            watched_address = 0;
        }
    }
    int image_after = verify_fixed_image_path();
    if (!child_creation_time(&identity_after) || identity_after != identity_before || !image_after) {
        if (!image_after) target_image_verified = 0;
        passive_identity_verified = 0;
        debugger_error = 1;
    }
    if (!child_is_live()) passive_record_process_exit();
}

static void emit_passive_final(void) {
    int real_alive = child_is_live();
    int identity_after = owned_child_identity();
    int image_after = verify_fixed_image_path();
    if (!identity_after || !image_after) {
        if (!image_after) target_image_verified = 0;
        passive_identity_verified = 0;
        debugger_error = 1;
    }
    if (!real_alive && !target_exit_seen) passive_record_process_exit();
    printf("{\"type\":\"final\",\"mode\":\"passive-real\",\"identity\":{\"pid\":%lu,\"creation_time_100ns\":%llu,\"owned_process_handle\":",
           (unsigned long)state.pid, (unsigned long long)state.creation_time);
    json_bool(state.process != NULL && GetProcessId(state.process) == state.pid);
    printf(",\"start_time_double_checked\":"); json_bool(identity_after);
    printf("},\"real_alive\":"); json_bool(real_alive);
    printf(",\"target_exit_seen\":"); json_bool(target_exit_seen);
    fputs(",\"target_exit_code\":", stdout);
    if (target_exit_seen) printf("%lu", (unsigned long)target_exit_code); else fputs("null", stdout);
    printf(",\"executable_pe_x64_verified\":"); json_bool(target_image_verified);
    fputs(",\"watch_armed\":false,\"events\":0,\"watch_traps\":0,\"unclassified_events\":0", stdout);
    printf(",\"store_observed\":"); json_bool(first_valid_seen);
    printf(",\"region\":");
    if (first_valid_seen) emit_region(&watched_region); else fputs("null", stdout);
    printf(",\"counts\":{\"complete_scans\":%u,\"last_candidate_count\":%u,\"last_valid_blocks\":%llu,\"stable_reads\":%u,\"watch_traps\":%u,\"events\":%u}",
           passive_complete_scans, passive_candidate_count, passive_valid_blocks,
           first_valid_seen ? 2u : 0u, watch_trap_count, event_count);
    printf(",\"proof\":{\"identity_before_after\":"); json_bool(passive_identity_verified);
    printf(",\"complete_unique_snapshot\":"); json_bool(first_valid_seen && !any_scan_incomplete);
    printf(",\"two_stable_reads\":"); json_bool(first_valid_seen);
    fputs(",\"hardware_traps\":false", stdout);
    printf(",\"construction_inferred\":false,\"crypto_writes\":false}");
    printf(",\"first_complete_absence_ns\":"); if (first_absence_seen) printf("%llu", (unsigned long long)first_absence_tick * 1000000ull); else fputs("null", stdout);
    printf(",\"first_valid_ns\":"); if (first_valid_seen) printf("%llu", (unsigned long long)first_valid_tick * 1000000ull); else fputs("null", stdout);
    printf(",\"first_valid_interval_ns\":"); if (first_absence_seen && first_valid_seen) printf("%llu", (unsigned long long)(first_valid_tick - first_absence_tick) * 1000000ull); else fputs("null", stdout);
    printf(",\"debugger_error\":"); json_bool(debugger_error);
    printf(",\"deadline_expired\":"); json_bool(passive_timed_out);
    printf(",\"initializer_construction_proven\":false,\"authentication_claim\":false,\"crypto_writes\":false}");
    puts(""); fflush(stdout);
}

static int run_passive_trace(void) {
    if (!launch_child(0, 0)) return 2;
    emit_launch();
    state.image_size = image_size_from_file(expected_child_path(0));
    target_image_verified = verify_fixed_image_path();
    passive_identity_verified = creation_time_valid && target_image_verified;
    if (!passive_identity_verified) debugger_error = 1;
    ULONGLONG started = GetTickCount64();
    ULONGLONG next_scan = started;
    int active = 1;
    while (active) {
        ULONGLONG now = GetTickCount64();
        if (now - started >= 300000ull) {
            passive_timed_out = 1;
            if (!terminate_owned_child(124)) debugger_error = 1;
            WaitForSingleObject(state.process, 30000);
            passive_record_process_exit();
            break;
        }
        if (now >= next_scan && child_is_live()) {
            passive_scan_once();
            next_scan = GetTickCount64() + POLL_INTERVAL_MS;
        } else if (!child_is_live()) {
            passive_record_process_exit();
            active = 0;
            continue;
        } else {
            Sleep(100);
        }
        if (!child_is_live()) {
            passive_record_process_exit();
            active = 0;
        }
    }
    emit_passive_final();
    if (state.primary_thread) CloseHandle(state.primary_thread);
    if (state.process) CloseHandle(state.process);
    return 0;
}

static int run_trace(int synthetic) {
    if (!launch_child(synthetic, 1)) return 2;
    emit_launch();
    wchar_t module_path[512]; DWORD path_length = sizeof(module_path) / sizeof(module_path[0]);
    state.image_size = image_size_from_file(expected_child_path(synthetic));
    target_image_verified = QueryFullProcessImageNameW(state.process, 0, module_path, &path_length) &&
                            path_length > 0 && lstrcmpiW(module_path, expected_child_path(synthetic)) == 0 && state.image_size != 0;
    if (!target_image_verified) debugger_error = 1;
    SymSetOptions(SYMOPT_DEFERRED_LOADS);
    symbols_initialized = SymInitializeW(state.process, NULL, FALSE) != FALSE;
    ULONGLONG started = GetTickCount64(); ULONGLONG next_scan = started; int debug_active = 1; int first_breakpoint = 1;
    while (debug_active) {
        if (GetTickCount64() - started >= TRACE_LIMIT_MS) {
            deadline_expired = 1;
            if (!terminate_owned_child(124)) debugger_error = 1;
            WaitForSingleObject(state.process, 30000);
            break;
        }
        DEBUG_EVENT event; memset(&event, 0, sizeof(event));
        if (WaitForDebugEvent(&event, 100)) {
            DWORD continue_status = DBG_CONTINUE;
            int stop_after_event = 0;
            if (event.dwDebugEventCode == CREATE_PROCESS_DEBUG_EVENT) {
                state.image_base = (uintptr_t)event.u.CreateProcessInfo.lpBaseOfImage;
                remember_thread(event.dwThreadId);
                if (!load_debug_symbols(event.u.CreateProcessInfo.hFile, expected_child_path(state.synthetic), state.module_name, state.image_base, state.image_size) ||
                    !remember_module(state.image_base, state.image_size, state.module_name)) debugger_error = 1;
                if (event.u.CreateProcessInfo.hFile) CloseHandle(event.u.CreateProcessInfo.hFile);
            } else if (event.dwDebugEventCode == CREATE_THREAD_DEBUG_EVENT) {
                remember_thread(event.dwThreadId);
                if (watch_armed) {
                    if (!arm_handle(event.u.CreateThread.hThread)) arm_errors++;
                }
                if (event.u.CreateThread.hThread) CloseHandle(event.u.CreateThread.hThread);
            } else if (event.dwDebugEventCode == EXIT_THREAD_DEBUG_EVENT) {
                forget_thread(event.dwThreadId);
            } else if (event.dwDebugEventCode == EXCEPTION_DEBUG_EVENT) {
                DWORD code = event.u.Exception.ExceptionRecord.ExceptionCode;
                uintptr_t exception_ip = (uintptr_t)event.u.Exception.ExceptionRecord.ExceptionAddress;
                if (exception_dispatch_limit_reached(exception_dispatch_count)) {
                    exception_dispatch_cap_exceeded = 1;
                    debugger_error = 1;
                    disarm_all_threads();
                    continue_status = DBG_EXCEPTION_NOT_HANDLED;
                    stop_after_event = 1;
                } else if (code == EXCEPTION_BREAKPOINT && first_breakpoint && event.u.Exception.dwFirstChance) {
                    exception_dispatch_count++;
                    first_breakpoint = 0;
                    continue_status = DBG_CONTINUE;
                } else if (code == EXCEPTION_SINGLE_STEP && watch_armed) {
                    exception_dispatch_count++;
                    continue_status = handle_watch_exception(&event) ? DBG_CONTINUE : DBG_EXCEPTION_NOT_HANDLED;
                } else {
                    exception_dispatch_count++;
                    remember_exception(&event, code, exception_ip);
                    debugger_error = 1;
                    continue_status = DBG_EXCEPTION_NOT_HANDLED;
                }
                if (exception_dispatch_count >= EXCEPTION_DISPATCH_LIMIT) {
                    exception_dispatch_cap_exceeded = 1;
                    debugger_error = 1;
                    disarm_all_threads();
                    stop_after_event = 1;
                }
            } else if (event.dwDebugEventCode == EXIT_PROCESS_DEBUG_EVENT) {
                target_exit_seen = 1; target_exit_code = event.u.ExitProcess.dwExitCode; debug_active = 0;
            } else if (event.dwDebugEventCode == LOAD_DLL_DEBUG_EVENT) {
                if (!load_dll_debug_symbols(&event.u.LoadDll)) debugger_error = 1;
                if (event.u.LoadDll.hFile) CloseHandle(event.u.LoadDll.hFile);
            } else if (event.dwDebugEventCode == UNLOAD_DLL_DEBUG_EVENT) {
                forget_module((uintptr_t)event.u.UnloadDll.lpBaseOfDll);
            }
            if (!ContinueDebugEvent(event.dwProcessId, event.dwThreadId, continue_status)) {
                continue_failures++;
                debugger_error = 1;
                if (!terminate_owned_child(125)) debugger_error = 1;
                WaitForSingleObject(state.process, 30000);
                debug_active = 0;
            } else if (stop_after_event) {
                if (!terminate_owned_child(126)) debugger_error = 1;
                WaitForSingleObject(state.process, 30000);
                debug_active = 0;
            }
        }
        ULONGLONG now = GetTickCount64();
        /* Do not let a memory scan run immediately after a loader/debug event:
         * the target is then in an initial event burst and an absence sample
         * would describe that scheduling point, not construction. */
        if (!event.dwDebugEventCode && now >= next_scan && !target_exit_seen) {
            ULONGLONG identity_before = 0, identity_after = 0;
            if (!child_creation_time(&identity_before) || identity_before != state.creation_time) { any_scan_incomplete = 1; debugger_error = 1; }
            else {
                ScanResult result = scan_private_regions();
                emit_scan(&result, GetTickCount64());
                if (!result.complete) any_scan_incomplete = 1;
                if (result.complete && result.candidate_count == 0 && !first_absence_seen) { first_absence_seen = 1; first_absence_tick = GetTickCount64(); }
                if (result.complete && result.valid_blocks == 1 && result.candidate_count == 1 && !first_valid_seen) {
                    watched_address = result.candidates[0].address;
                    watched_region = result.candidates[0].region;
                    ValidationCounts initial_counts;
                    MEMORY_BASIC_INFORMATION stable_region;
                    if (read_watched_stable(&initial_counts, &stable_region) &&
                        initial_counts.ids == ROWS && initial_counts.keys == ROWS &&
                        initial_counts.flags == ROWS && initial_counts.padding == ROWS) {
                        first_valid_seen = 1;
                        first_valid_tick = GetTickCount64();
                        watched_region = stable_region;
                        arm_all_threads();
                    } else {
                        any_scan_incomplete = 1;
                        debugger_error = 1;
                        watched_address = 0;
                    }
                }
                if (!child_creation_time(&identity_after) || identity_after != identity_before || !child_is_live()) { any_scan_incomplete = 1; debugger_error = 1; }
            }
            next_scan = GetTickCount64() + POLL_INTERVAL_MS;
        }
        if (watch_armed && GetTickCount64() - watch_started >= DETAIL_LIMIT_MS) disarm_all_threads();
    }
    if (watch_armed) disarm_all_threads();
    emit_final();
    if (symbols_initialized) SymCleanup(state.process);
    if (state.primary_thread) CloseHandle(state.primary_thread);
    if (state.process) CloseHandle(state.process);
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 2 || (strcmp(argv[1], "--synthetic") != 0 && strcmp(argv[1], "--real") != 0 &&
                      strcmp(argv[1], "--passive-real") != 0)) {
        fputs("usage: wow_trace.exe --synthetic|--real|--passive-real\n", stderr); return 64;
    }
    if (strcmp(argv[1], "--passive-real") == 0) return run_passive_trace();
    return run_trace(strcmp(argv[1], "--synthetic") == 0);
}

#else

/* Fixed child used by the synthetic Wine11 mechanics run. */
static const unsigned char known_keys[12][32] = {
    {0x9b,0x06,0x71,0xc8,0x15,0xdf,0xf5,0x13,0xbf,0xd4,0xa2,0xb2,0x6a,0xe1,0xf8,0x4e,0xc9,0x10,0x68,0x41,0xb2,0xfb,0x62,0x0d,0xb6,0x5f,0x6a,0xde,0x7c,0x21,0xad,0x06},
     {0x11,0x24,0x51,0xb9,0x84,0x3c,0x27,0x99,0xe4,0x75,0x0a,0xa3,0xd9,0xb9,0xaf,0xab,0xf5,0x36,0xa6,0x45,0xb8,0x63,0xc4,0xad,0xb2,0x07,0x8b,0x93,0x2b,0x35,0x4e,0x04},
    {0xa6,0xb8,0x58,0x48,0x57,0x48,0xbf,0x38,0xbe,0x19,0x35,0x17,0xab,0x90,0xf8,0xde,0x16,0x9e,0xff,0x09,0x89,0xea,0x93,0x60,0xdb,0x34,0x6a,0x37,0x8b,0x0f,0xfe,0x15},
    {0x50,0xff,0xde,0xb8,0x07,0xf8,0xff,0x73,0xa2,0x99,0xa1,0x60,0x00,0xaa,0x65,0x75,0xc5,0x94,0x5b,0xe8,0x75,0xad,0xfc,0x87,0x95,0x37,0x4e,0xd3,0x41,0x52,0x49,0xac},
    {0x9f,0x84,0x2d,0x07,0x87,0x55,0x64,0x7c,0x00,0x8e,0x5f,0xe3,0xe1,0x2b,0x83,0x9a,0x98,0x1d,0xe0,0x2a,0x1f,0x52,0x0c,0xb2,0x54,0x5c,0xc0,0x4c,0xd2,0x32,0x3e,0x0e},
    {0xb8,0xcc,0x75,0x86,0x4d,0x8e,0xf4,0x61,0xd8,0xdd,0x6a,0xa7,0x42,0x5e,0x2c,0x63,0xc2,0x4d,0x39,0x82,0x06,0x6b,0x8d,0x77,0x3a,0x15,0x49,0xbf,0xe2,0x4e,0x05,0xee},
    {0x76,0xbb,0x7b,0xbd,0xd9,0xf3,0x4e,0x12,0x4a,0x45,0x73,0xc3,0xaa,0x22,0x7e,0x3c,0x87,0xcc,0x60,0x35,0x06,0x69,0x7d,0x05,0x4f,0x6f,0xde,0xc3,0x42,0xe5,0x6e,0xbf},
    {0x1f,0xd6,0xdd,0x8f,0xa0,0xec,0x30,0xd3,0x9e,0x3f,0x72,0xe7,0x55,0xb8,0xa0,0x45,0xbd,0xe0,0xf7,0x04,0x49,0xdc,0x71,0x00,0x8b,0x76,0x7c,0x2e,0xaa,0x89,0xfb,0x9f},
    {0x15,0xd6,0x18,0xbd,0x7d,0xb5,0x77,0xbd,0x9a,0x8d,0x45,0x76,0x9c,0x59,0xe4,0xfc,0x63,0x16,0x33,0xbf,0x44,0x73,0x98,0xa4,0xb4,0x89,0xb4,0xc2,0x6f,0xbc,0x03,0xad},
    {0xb3,0x4e,0xc5,0x92,0xd2,0xac,0x49,0x93,0xf5,0xff,0xc8,0x5b,0x15,0xc3,0xda,0x90,0x78,0x69,0x40,0x51,0xcb,0x22,0x43,0x45,0x59,0x2a,0xf7,0x13,0x6d,0x79,0x6c,0x99},
    {0x9e,0x91,0x58,0x6d,0xd4,0xb1,0x15,0xab,0x05,0x68,0xb2,0x19,0x59,0xe1,0x81,0xf6,0x54,0x59,0x10,0xbc,0x5e,0x2f,0x30,0xb8,0x97,0x5c,0xa0,0x9d,0x7b,0xc3,0xef,0xce},
    {0x76,0x9c,0x82,0x4a,0x1d,0xad,0xd1,0x9a,0xee,0xfa,0x42,0x04,0x14,0xd1,0x73,0x2d,0xaf,0x44,0x53,0x7f,0x98,0xaa,0x22,0x9f,0xc5,0x47,0x7b,0xca,0x55,0xf9,0xd5,0x9a}
};
static volatile unsigned char *synthetic_table;
static unsigned char *synthetic_allocation;
static const unsigned char padding[3] = {0x7f, 0, 0};

__declspec(noinline) static void initialize_table(void) {
    for (unsigned int row = 0; row < 12; ++row) {
        uint32_t id = row + 1;
        memcpy((void *)synthetic_table + row * 40, &id, 4);
        memcpy((void *)synthetic_table + row * 40 + 4, known_keys[row], 32);
        synthetic_table[row * 40 + 36] = row == 8 ? 0 : 1;
        memcpy((void *)synthetic_table + row * 40 + 37, padding, 3);
    }
}

__declspec(noinline) static void late_write(void) {
    __asm__ __volatile__("movq synthetic_table(%%rip), %%rcx\n\t"
                         "movq (%%rcx), %%rax\n\t"
                         "movq %%rax, (%%rcx)\n\t" ::: "rax", "rcx", "memory");
}

__declspec(noinline) static unsigned long long late_read(void) {
    unsigned long long value;
    __asm__ __volatile__("movq synthetic_table(%%rip), %%rcx\n\t"
                         "movq (%%rcx), %%rax" : "=a"(value) :: "rcx", "memory");
    return value;
}

static int validate(void) {
    for (unsigned int row = 0; row < 12; ++row) {
        uint32_t id;
        memcpy(&id, (const void *)synthetic_table + row * 40, 4);
        if (id != row + 1 || memcmp((const void *)synthetic_table + row * 40 + 4, known_keys[row], 32) != 0) return 0;
        if (synthetic_table[row * 40 + 36] != (row == 8 ? 0 : 1) || memcmp((const void *)synthetic_table + row * 40 + 37, padding, 3) != 0) return 0;
    }
    return 1;
}

int main(void) {
    synthetic_allocation = (unsigned char *)VirtualAlloc(NULL, 128 * 1024, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!synthetic_allocation) return 11;
    /* 64 KiB - 200 deliberately crosses the tracer's 64 KiB read boundary. */
    synthetic_table = (volatile unsigned char *)(synthetic_allocation + 65536u - 200u);
    initialize_table();
    Sleep(2500);
    late_write();
    Sleep(100);
    (void)late_read();
    return validate() ? 0 : 17;
}

#endif
