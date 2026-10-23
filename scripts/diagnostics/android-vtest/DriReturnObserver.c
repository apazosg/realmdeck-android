#define _GNU_SOURCE

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define OBSERVER_SCHEMA "wowforever.android-dri-return-observer/version1"
#define EXPECTED_UID 10330U
#define MAX_RUN_ID 38U
#define MAX_PATH_STRING 4096U
#define MAX_RECORD 2048U
#define MAX_OUTPUT_BYTES 4096U
#define MAX_RECORDS 4U
#define MAX_ATTRIBUTE_PAIRS 16U
#define MAX_ATTRIBUTE_VALUES (MAX_ATTRIBUTE_PAIRS * 2U)
#define PROVIDER_RELATIVE "guest/lib/libgallium-26.2.4.so"
#define OBSERVER_LIBRARY "libdri_return_observer.so"

#ifndef RTLD_NOLOAD
#define RTLD_NOLOAD 4
#endif

typedef void *(*DriCreateContextAttribsFn)(void *, int, const void *, void *, unsigned,
                                           const uint32_t *, unsigned *error, void *data, bool thread_safe);

void *driCreateContextAttribs(void *, int, const void *, void *, unsigned,
                              const uint32_t *, unsigned *, void *, bool);

typedef struct AttributeCapture {
    uint32_t values[MAX_ATTRIBUTE_VALUES];
    unsigned pairs;
    int observed;
    int oversized;
} AttributeCapture;

#ifndef DRI_RETURN_OBSERVER_HOST_TEST
static int path_join(char *out, size_t capacity, const char *base, const char *relative);
static int canonical_path(const char *path, char *out, size_t capacity);
static int validate_cmdline(const char *expected_executable, const char *run_id);
#endif

static _Atomic unsigned call_sequence;
static _Atomic unsigned record_count;
static _Atomic unsigned output_bytes;
static __thread int recursion_guard;

static size_t bounded_length(const char *value, size_t limit, int *terminated) {
    size_t index;
    if (terminated != NULL) *terminated = 0;
    if (value == NULL) return 0U;
    for (index = 0U; index < limit; ++index) {
        if (value[index] == '\0') {
            if (terminated != NULL) *terminated = 1;
            return index;
        }
    }
    return limit;
}

static int copy_bounded(char *out, size_t capacity, const char *value, size_t limit) {
    int terminated = 0;
    size_t length;
    if (out == NULL || capacity == 0U || value == NULL) return 0;
    length = bounded_length(value, limit, &terminated);
    if (!terminated || length + 1U > capacity) return 0;
    memcpy(out, value, length);
    out[length] = '\0';
    return 1;
}

#ifndef DRI_RETURN_OBSERVER_HOST_TEST
static int valid_run_id(const char *run_id) {
    size_t index;
    if (run_id == NULL || bounded_length(run_id, MAX_RUN_ID + 1U, NULL) != MAX_RUN_ID ||
        memcmp(run_id, "VTEST-", 6U) != 0) return 0;
    for (index = 6U; index < MAX_RUN_ID; ++index) {
        unsigned char value = (unsigned char)run_id[index];
        if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) return 0;
    }
    return 1;
}

static int scoped_run(char *run_id, size_t capacity) {
    static const char *const prefixes[] = {
        "/data/user/0/com.wowforever.arm64probe/files/forever/controls/vtest-loader/",
        "/data/data/com.wowforever.arm64probe/files/forever/controls/vtest-loader/"
    };
    char canonical[MAX_PATH_STRING];
    char expected_observer[MAX_PATH_STRING];
    char expected_executable[MAX_PATH_STRING];
    char canonical_executable[MAX_PATH_STRING];
    const char *preload;
    size_t index;
    if (getuid() != (uid_t)EXPECTED_UID || realpath(".", canonical) == NULL ||
        !path_join(expected_observer, sizeof(expected_observer), canonical, "guest/observer/libdri_return_observer.so") ||
        !path_join(expected_executable, sizeof(expected_executable), canonical, "guest/bin/GuestEglDriObservation") ||
        !canonical_path(expected_executable, canonical_executable, sizeof(canonical_executable)) ||
        strcmp(canonical_executable, expected_executable) != 0) return 0;
    preload = getenv("LD_PRELOAD");
    if (preload == NULL || strcmp(preload, expected_observer) != 0) return 0;
    for (index = 0U; index < sizeof(prefixes) / sizeof(prefixes[0]); ++index) {
        size_t prefix_length = strlen(prefixes[index]);
        if (strncmp(canonical, prefixes[index], prefix_length) == 0 &&
            valid_run_id(canonical + prefix_length) && strchr(canonical + prefix_length, '/') == NULL &&
            copy_bounded(run_id, capacity, canonical + prefix_length, MAX_RUN_ID + 1U))
            return validate_cmdline(expected_executable, run_id);
    }
    return 0;
}

static int path_join(char *out, size_t capacity, const char *base, const char *relative) {
    int written;
    if (out == NULL || base == NULL || relative == NULL) return 0;
    written = snprintf(out, capacity, "%s/%s", base, relative);
    return written > 0 && (size_t)written < capacity;
}

static int cmdline_text_equal(const unsigned char *value, size_t length, const char *expected) {
    size_t expected_length;
    if (value == NULL || expected == NULL) return 0;
    expected_length = strlen(expected);
    return length == expected_length && memcmp(value, expected, length) == 0;
}

static int validate_cmdline(const char *expected_executable, const char *run_id) {
    unsigned char data[4096U];
    const unsigned char *arguments[4];
    size_t lengths[4];
    size_t used = 0U;
    size_t start = 0U;
    size_t argument_count = 0U;
    size_t index;
    int fd;
    if (expected_executable == NULL || run_id == NULL) return 0;
    fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return 0;
    while (used < sizeof(data)) {
        ssize_t count = read(fd, data + used, sizeof(data) - used);
        if (count > 0) used += (size_t)count;
        else if (count == 0) break;
        else if (errno != EINTR) {
            (void)close(fd);
            return 0;
        }
    }
    if (close(fd) != 0 || used == 0U || used == sizeof(data) || data[used - 1U] != '\0') return 0;
    for (index = 0U; index < used; ++index) {
        if (data[index] == '\0') {
            if (index == start || argument_count >= sizeof(arguments) / sizeof(arguments[0])) return 0;
            arguments[argument_count] = data + start;
            lengths[argument_count] = index - start;
            ++argument_count;
            start = index + 1U;
        }
    }
    if (start != used) return 0;
    if (argument_count == 4U && cmdline_text_equal(arguments[0], lengths[0], "/system/bin/linker64")) {
        start = 1U;
    } else if (argument_count != 3U) {
        return 0;
    }
    return argument_count == start + 3U &&
           cmdline_text_equal(arguments[start], lengths[start], expected_executable) &&
           cmdline_text_equal(arguments[start + 1U], lengths[start + 1U], "--run-id") &&
           cmdline_text_equal(arguments[start + 2U], lengths[start + 2U], run_id);
}

static int stat_identity(const char *path, struct stat *snapshot) {
    struct stat listed;
    struct stat opened;
    int fd;
    int stat_result;
    int close_result;
    if (path == NULL || snapshot == NULL || lstat(path, &listed) != 0 || !S_ISREG(listed.st_mode) ||
        listed.st_uid != getuid()) return 0;
    fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return 0;
    stat_result = fstat(fd, &opened);
    close_result = close(fd);
    if (stat_result != 0 || close_result != 0 || !S_ISREG(opened.st_mode) ||
        opened.st_uid != getuid() || opened.st_dev != listed.st_dev || opened.st_ino != listed.st_ino ||
        opened.st_size != listed.st_size || (opened.st_mode & 07777U) != (listed.st_mode & 07777U)) return 0;
    *snapshot = opened;
    return 1;
}

static int same_identity(const struct stat *before, const struct stat *after) {
    return before != NULL && after != NULL && before->st_dev == after->st_dev &&
           before->st_ino == after->st_ino && before->st_size == after->st_size &&
           (before->st_mode & 07777U) == (after->st_mode & 07777U);
}

static int canonical_path(const char *path, char *out, size_t capacity) {
    char *resolved;
    int result;
    if (path == NULL || out == NULL || capacity == 0U) return 0;
    resolved = realpath(path, NULL);
    if (resolved == NULL) return 0;
    result = copy_bounded(out, capacity, resolved, capacity - 1U);
    free(resolved);
    return result;
}

static int address_owner(void *address, char *owner, size_t capacity) {
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (address == NULL || dladdr(address, &info) == 0 || info.dli_fname == NULL) return 0;
    return canonical_path(info.dli_fname, owner, capacity);
}
#endif

static int resolve_provider(DriCreateContextAttribsFn *result, void **retained_handle) {
#ifdef DRI_RETURN_OBSERVER_HOST_TEST
    extern int dri_observer_test_scope;
    extern DriCreateContextAttribsFn dri_observer_test_provider;
    if (result == NULL || retained_handle == NULL || !dri_observer_test_scope || dri_observer_test_provider == NULL) return 0;
    *retained_handle = NULL;
    *result = dri_observer_test_provider;
    return 1;
#else
    char cwd[MAX_PATH_STRING];
    char run_id[MAX_RUN_ID + 1U];
    char provider_path[MAX_PATH_STRING];
    char provider_canonical[MAX_PATH_STRING];
    char owner[MAX_PATH_STRING];
    char self[MAX_PATH_STRING];
    struct stat before;
    struct stat after;
    void *handle;
    void *symbol;
    const char *error;
    if (result == NULL || retained_handle == NULL || !scoped_run(run_id, sizeof(run_id)) || realpath(".", cwd) == NULL) return 0;
    if (!path_join(provider_path, sizeof(provider_path), cwd, PROVIDER_RELATIVE) ||
        !stat_identity(provider_path, &before) || !canonical_path(provider_path, provider_canonical, sizeof(provider_canonical)) ||
        strcmp(provider_path, provider_canonical) != 0) return 0;
    handle = dlopen(provider_path, RTLD_NOLOAD | RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) return 0;
    (void)dlerror();
    symbol = dlsym(handle, "driCreateContextAttribs");
    error = dlerror();
    if (symbol == NULL || error != NULL || !address_owner(symbol, owner, sizeof(owner)) ||
        strcmp(owner, provider_canonical) != 0 || !address_owner((void *)&driCreateContextAttribs, self, sizeof(self)) ||
        strcmp(owner, self) == 0 || !stat_identity(provider_path, &after) || !same_identity(&before, &after)) {
        (void)dlclose(handle);
        return 0;
    }
    memcpy(result, &symbol, sizeof(symbol));
    *retained_handle = handle;
    return *result != NULL;
#endif
}

static DriCreateContextAttribsFn verified_provider(void **retained_handle) {
    DriCreateContextAttribsFn result = NULL;
    /* dlopen/dlsym are thread-safe; no shared provider state or spin lock. */
    if (!resolve_provider(&result, retained_handle)) _exit(125);
    return result;
}

static void capture_attributes(AttributeCapture *capture, unsigned count, const uint32_t *attributes) {
    unsigned index;
    memset(capture, 0, sizeof(*capture));
    if (count == 0U) {
        capture->observed = 1;
        return;
    }
    /* num_attribs is a count of PAIRS (the provider iterates 2 * count). */
    if (attributes == NULL || count > MAX_ATTRIBUTE_PAIRS) {
        capture->oversized = count > MAX_ATTRIBUTE_PAIRS;
        return;
    }
    capture->pairs = count;
    capture->observed = 1;
    for (index = 0U; index < count * 2U; ++index) capture->values[index] = attributes[index];
}

static int append_text(char *out, size_t capacity, size_t *used, const char *format, ...) {
    va_list arguments;
    int written;
    if (out == NULL || used == NULL || *used >= capacity || format == NULL) return 0;
    va_start(arguments, format);
    written = vsnprintf(out + *used, capacity - *used, format, arguments);
    va_end(arguments);
    if (written < 0 || (size_t)written >= capacity - *used) return 0;
    *used += (size_t)written;
    return 1;
}

static int write_record(const char *run_id, unsigned sequence, int api, unsigned count, bool thread_safe,
                        const AttributeCapture *attributes, bool provider_verified, bool forwarded,
                        void *result, unsigned *error, int incoming_errno, int returned_errno) {
    char line[MAX_RECORD];
    size_t used = 0U;
    unsigned pair;
    int error_present = error != NULL;
    unsigned error_value = error_present ? *error : 0U;
    unsigned slot = atomic_fetch_add_explicit(&record_count, 1U, memory_order_relaxed);
    if (slot >= MAX_RECORDS || run_id == NULL || attributes == NULL) return 1;
    if (!append_text(line, sizeof(line), &used,
                     "WFDRI_RETURN {\"schema\":\"%s\",\"runId\":\"%s\",\"pid\":%ld,\"uid\":%lu,"
                     "\"callSequence\":%u,\"api\":%d,\"numAttribs\":%u,\"threadSafe\":%s,"
                     "\"attributesObserved\":%s,\"attributesOversize\":%s,\"attributePairs\":[",
                     OBSERVER_SCHEMA, run_id, (long)getpid(), (unsigned long)getuid(), sequence, api, count,
                     thread_safe ? "true" : "false", attributes->observed ? "true" : "false",
                     attributes->oversized ? "true" : "false")) return 0;
    for (pair = 0U; pair < attributes->pairs; ++pair) {
        if (!append_text(line, sizeof(line), &used, "%s[%u,%u]", pair == 0U ? "" : ",",
                         attributes->values[pair * 2U], attributes->values[pair * 2U + 1U])) return 0;
    }
    if (!append_text(line, sizeof(line), &used,
                     "],\"providerVerified\":%s,\"forwarded\":%s,\"resultPresent\":%s,"
                      "\"errorPresent\":%s,\"error\":%s",
                     provider_verified ? "true" : "false", forwarded ? "true" : "false",
                     result != NULL ? "true" : "false", error_present ? "true" : "false",
                     error_present ? "" : "null")) return 0;
    if (error_present && !append_text(line, sizeof(line), &used, "%u", error_value)) return 0;
    if (!append_text(line, sizeof(line), &used,
                     ",\"incomingErrno\":%d,\"returnedErrno\":%d}\n", incoming_errno, returned_errno)) return 0;
    if (used >= MAX_OUTPUT_BYTES || atomic_fetch_add_explicit(&output_bytes, (unsigned)used, memory_order_relaxed) + used > MAX_OUTPUT_BYTES ||
        write(STDERR_FILENO, line, used) != (ssize_t)used) return 0;
    return 1;
}

#ifdef DRI_RETURN_OBSERVER_HOST_TEST
int dri_observer_test_scope = 1;
DriCreateContextAttribsFn dri_observer_test_provider;
void dri_observer_test_set_provider(DriCreateContextAttribsFn provider) { dri_observer_test_provider = provider; }
void dri_observer_test_set_scope(int valid) { dri_observer_test_scope = valid; }
void dri_observer_test_reset(void) {
    atomic_store_explicit(&call_sequence, 0U, memory_order_relaxed);
    atomic_store_explicit(&record_count, 0U, memory_order_relaxed);
    atomic_store_explicit(&output_bytes, 0U, memory_order_relaxed);
    dri_observer_test_scope = 1;
}
#endif

static int current_run_id(char *run_id, size_t capacity) {
#ifdef DRI_RETURN_OBSERVER_HOST_TEST
    return copy_bounded(run_id, capacity, "VTEST-0123456789abcdef0123456789abcdef", MAX_RUN_ID + 1U);
#else
    return scoped_run(run_id, capacity);
#endif
}

__attribute__((visibility("default")))
void *driCreateContextAttribs(void *screen, int api, const void *config, void *shared,
                              unsigned num_attribs, const uint32_t *attribs, unsigned *error,
                              void *data, bool thread_safe) {
    AttributeCapture captured;
    DriCreateContextAttribsFn provider;
    void *retained_handle = NULL;
    char run_id[MAX_RUN_ID + 1U];
    unsigned sequence;
    int incoming_errno;
    int returned_errno;
    void *result;
    incoming_errno = errno;
    if (recursion_guard || !current_run_id(run_id, sizeof(run_id))) _exit(125);
    recursion_guard = 1;
    capture_attributes(&captured, num_attribs, attribs);
    provider = verified_provider(&retained_handle);
    errno = incoming_errno;
    result = provider(screen, api, config, shared, num_attribs, attribs, error, data, thread_safe);
    returned_errno = errno;
    sequence = atomic_fetch_add_explicit(&call_sequence, 1U, memory_order_relaxed) + 1U;
    (void)write_record(run_id, sequence, api, num_attribs, thread_safe, &captured, true, true,
                        result, error, incoming_errno, returned_errno);
    /* Keep the mapped provider alive through the original call and observation. */
    if (retained_handle != NULL) (void)dlclose(retained_handle);
    errno = returned_errno;
    recursion_guard = 0;
    return result;
}
