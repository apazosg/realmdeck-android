#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

/*
 * Private, read-only kbase metadata probe.
 *
 * This translation unit deliberately contains only the first CSF version
 * handshake and the documented GET_GPUPROPS query.  In particular, the
 * handshake is not followed by context setup: a successful version query is the
 * complete context-free observation this probe is intended to make.
 */

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/ioctl.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define KBASE_METADATA_SCHEMA "wowforever.android-kbase-metadata/version1"
#define KBASE_METADATA_DEVICE "/dev/mali0"
#define KBASE_METADATA_UID ((uid_t)10330)
#define KBASE_METADATA_MAJOR 10U
#define KBASE_METADATA_MINOR 113U
#define KBASE_METADATA_CWD_PREFIX_A \
    "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-kernel/"
#define KBASE_METADATA_CWD_PREFIX_B \
    "/data/data/com.wowforever.arm64probe/files/forever/controls/panvk-kernel/"
#define KBASE_METADATA_RUN_PREFIX "PVK-"
#define KBASE_METADATA_RUN_HEX 32U
#define KBASE_METADATA_RUN_ID_LENGTH \
    (sizeof(KBASE_METADATA_RUN_PREFIX) - 1U + KBASE_METADATA_RUN_HEX)
#define KBASE_METADATA_CWD_MAX \
    (sizeof(KBASE_METADATA_CWD_PREFIX_A) + KBASE_METADATA_RUN_ID_LENGTH)
#define KBASE_METADATA_MAX_LINE 8192U
#define KBASE_METADATA_MAX_PROPERTIES 16384U
#define KBASE_METADATA_GUARD 16U
#define KBASE_METADATA_DEADLINE_NS UINT64_C(5000000000)

/* These are the two public Arm kbase UAPI structs used by this probe. */
struct kbase_ioctl_version_check {
    uint16_t major;
    uint16_t minor;
};

struct kbase_ioctl_get_gpuprops {
    uint64_t buffer;
    uint32_t size;
    uint32_t flags;
};

#define KBASE_IOCTL_TYPE 0x80U
#define KBASE_IOCTL_VERSION_CHECK_CSF \
    _IOWR(KBASE_IOCTL_TYPE, 52, struct kbase_ioctl_version_check)
#define KBASE_IOCTL_GET_GPUPROPS \
    _IOW(KBASE_IOCTL_TYPE, 3, struct kbase_ioctl_get_gpuprops)

_Static_assert(sizeof(struct kbase_ioctl_version_check) == 4U,
               "Arm CSF version ABI size");
_Static_assert(offsetof(struct kbase_ioctl_version_check, major) == 0U,
               "Arm CSF version major offset");
_Static_assert(offsetof(struct kbase_ioctl_version_check, minor) == 2U,
               "Arm CSF version minor offset");
_Static_assert(KBASE_IOCTL_VERSION_CHECK_CSF == 0xC0048034UL,
               "Arm CSF version ioctl encoding");
_Static_assert(sizeof(struct kbase_ioctl_get_gpuprops) == 16U,
               "kbase GPU properties ABI size");
_Static_assert(offsetof(struct kbase_ioctl_get_gpuprops, buffer) == 0U,
               "kbase GPU properties buffer offset");
_Static_assert(offsetof(struct kbase_ioctl_get_gpuprops, size) == 8U,
               "kbase GPU properties size offset");
_Static_assert(offsetof(struct kbase_ioctl_get_gpuprops, flags) == 12U,
               "kbase GPU properties flags offset");
_Static_assert(KBASE_IOCTL_GET_GPUPROPS == 0x40108003UL,
               "kbase GPU properties ioctl encoding");

enum kbase_selected_key {
    KBASE_KEY_TEXTURE_0 = 52U,
    KBASE_KEY_TEXTURE_1 = 53U,
    KBASE_KEY_TEXTURE_2 = 54U,
    KBASE_KEY_GPU_ID = 55U,
    KBASE_KEY_TEXTURE_3 = 81U,
    KBASE_SELECTED_COUNT = 5U
};

struct kbase_properties {
    uint32_t value[KBASE_SELECTED_COUNT];
    unsigned present[KBASE_SELECTED_COUNT];
};

#ifdef KBASE_METADATA_TESTING
struct kbase_metadata_test_hooks {
    int (*lstat_fn)(const char *, struct stat *);
    int (*open_fn)(const char *, int);
    int (*fstat_fn)(int, struct stat *);
    int (*ioctl_fn)(int, unsigned long, void *);
    int (*close_fn)(int);
    uid_t (*getuid_fn)(void);
    char *(*getcwd_fn)(char *, size_t);
};

static struct kbase_metadata_test_hooks kbase_metadata_test_hooks;

static int kbase_lstat(const char *path, struct stat *value) {
    return kbase_metadata_test_hooks.lstat_fn != NULL
               ? kbase_metadata_test_hooks.lstat_fn(path, value)
               : lstat(path, value);
}

static int kbase_open(const char *path, int flags) {
    return kbase_metadata_test_hooks.open_fn != NULL
               ? kbase_metadata_test_hooks.open_fn(path, flags)
               : open(path, flags);
}

static int kbase_fstat(int fd, struct stat *value) {
    return kbase_metadata_test_hooks.fstat_fn != NULL
               ? kbase_metadata_test_hooks.fstat_fn(fd, value)
               : fstat(fd, value);
}

static int kbase_ioctl_call(int fd, unsigned long request, void *argument) {
    return kbase_metadata_test_hooks.ioctl_fn != NULL
               ? kbase_metadata_test_hooks.ioctl_fn(fd, request, argument)
               : ioctl(fd, request, argument);
}

static int kbase_close(int fd) {
    return kbase_metadata_test_hooks.close_fn != NULL
               ? kbase_metadata_test_hooks.close_fn(fd)
               : close(fd);
}

static uid_t kbase_getuid(void) {
    return kbase_metadata_test_hooks.getuid_fn != NULL ? kbase_metadata_test_hooks.getuid_fn() : getuid();
}

static char *kbase_getcwd(char *path, size_t capacity) {
    return kbase_metadata_test_hooks.getcwd_fn != NULL ? kbase_metadata_test_hooks.getcwd_fn(path, capacity) : getcwd(path, capacity);
}
#else
static int kbase_lstat(const char *path, struct stat *value) {
    return lstat(path, value);
}

static int kbase_open(const char *path, int flags) {
    return open(path, flags);
}

static int kbase_fstat(int fd, struct stat *value) {
    return fstat(fd, value);
}

static int kbase_ioctl_call(int fd, unsigned long request, void *argument) {
    return ioctl(fd, request, argument);
}

static int kbase_close(int fd) {
    return close(fd);
}

static uid_t kbase_getuid(void) {
    return getuid();
}

static char *kbase_getcwd(char *path, size_t capacity) {
    return getcwd(path, capacity);
}
#endif

struct kbase_probe {
    int fd;
    int close_ret;
    int close_errno;
    int close_called;
    int identity_before;
    int identity_after;
    int deadline_valid;
    int clock_valid;
    int output_failure;
    int valid_observation;
    int native_success;
    const char *status;
    const char *error_stage;
    int error_errno_known;
    int error_errno;
    int error_ret_known;
    int error_ret;
    uint64_t started_ns;
    uint64_t finished_ns;
    int version_called;
    int version_ret;
    int version_errno;
    uint16_t version_major;
    uint16_t version_minor;
    int properties_probe_called;
    int properties_probe_ret;
    int properties_probe_errno;
    int properties_fill_called;
    int properties_fill_ret;
    int properties_fill_errno;
    size_t properties_allocated;
    size_t properties_returned;
    int properties_valid;
    struct kbase_properties properties;
};

static uint64_t kbase_clock_ns(int *ok) {
    struct timespec now;
    if (ok != NULL) *ok = 0;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0 || now.tv_sec < 0 || now.tv_nsec < 0)
        return 0U;
    if (ok != NULL) *ok = 1;
    return (uint64_t)now.tv_sec * UINT64_C(1000000000) + (uint64_t)now.tv_nsec;
}

static int kbase_deadline_ok(struct kbase_probe *probe) {
    int ok;
    uint64_t now;
    if (probe == NULL || !probe->clock_valid) return 0;
    now = kbase_clock_ns(&ok);
    if (!ok || now < probe->started_ns || now - probe->started_ns > KBASE_METADATA_DEADLINE_NS) {
        probe->deadline_valid = 0;
        probe->clock_valid = ok;
        return 0;
    }
    return probe->deadline_valid;
}

static void kbase_error_call(struct kbase_probe *probe, const char *stage,
                             int ret, int call_errno) {
    if (probe == NULL) return;
    probe->error_stage = stage;
    probe->error_ret_known = 1;
    probe->error_ret = ret;
    probe->error_errno_known = call_errno != 0;
    probe->error_errno = call_errno == 0 ? 0 : -call_errno;
}

static int kbase_valid_run_id(const char *run_id) {
    size_t index;
    if (run_id == NULL || strnlen(run_id, KBASE_METADATA_RUN_ID_LENGTH + 1U) != KBASE_METADATA_RUN_ID_LENGTH ||
        strncmp(run_id, KBASE_METADATA_RUN_PREFIX, sizeof(KBASE_METADATA_RUN_PREFIX) - 1U) != 0)
        return 0;
    for (index = sizeof(KBASE_METADATA_RUN_PREFIX) - 1U; index < sizeof(KBASE_METADATA_RUN_PREFIX) - 1U + KBASE_METADATA_RUN_HEX; ++index) {
        char value = run_id[index];
        if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) return 0;
    }
    return 1;
}

static int kbase_valid_cwd(const char *run_id, char *out, size_t capacity) {
    char expected_a[KBASE_METADATA_CWD_MAX];
    char expected_b[KBASE_METADATA_CWD_MAX];
    int written_a;
    int written_b;
    if (run_id == NULL || out == NULL || capacity == 0U || kbase_getcwd(out, capacity) == NULL) return 0;
    written_a = snprintf(expected_a, sizeof(expected_a), "%s%s", KBASE_METADATA_CWD_PREFIX_A, run_id);
    written_b = snprintf(expected_b, sizeof(expected_b), "%s%s", KBASE_METADATA_CWD_PREFIX_B, run_id);
    return written_a > 0 && (size_t)written_a < sizeof(expected_a) && written_b > 0 &&
           (size_t)written_b < sizeof(expected_b) &&
           (strcmp(out, expected_a) == 0 || strcmp(out, expected_b) == 0);
}

static int kbase_same_device(const struct stat *listed, const struct stat *opened) {
    return listed != NULL && opened != NULL && S_ISCHR(listed->st_mode) && S_ISCHR(opened->st_mode) &&
           major(listed->st_rdev) == KBASE_METADATA_MAJOR && minor(listed->st_rdev) == KBASE_METADATA_MINOR &&
           major(opened->st_rdev) == KBASE_METADATA_MAJOR && minor(opened->st_rdev) == KBASE_METADATA_MINOR &&
           listed->st_dev == opened->st_dev && listed->st_ino == opened->st_ino &&
           listed->st_rdev == opened->st_rdev;
}

static int kbase_selected_index(uint32_t key) {
    switch (key) {
    case KBASE_KEY_TEXTURE_0: return 0;
    case KBASE_KEY_TEXTURE_1: return 1;
    case KBASE_KEY_TEXTURE_2: return 2;
    case KBASE_KEY_GPU_ID: return 3;
    case KBASE_KEY_TEXTURE_3: return 4;
    default: return -1;
    }
}

/* Parse only the documented, tightly packed little-endian property stream. */
static int kbase_parse_properties(const uint8_t *data, size_t length,
                                  struct kbase_properties *properties) {
    size_t offset = 0U;
    unsigned records = 0U;
    if (data == NULL || properties == NULL || length == 0U) return 0;
    memset(properties, 0, sizeof(*properties));
    while (offset < length) {
        uint32_t header;
        uint32_t width;
        uint32_t key;
        int selected;
        if (length - offset < 4U) return 0;
        header = (uint32_t)data[offset] | ((uint32_t)data[offset + 1U] << 8) |
                 ((uint32_t)data[offset + 2U] << 16) | ((uint32_t)data[offset + 3U] << 24);
        offset += 4U;
        width = 1U << (header & 3U);
        key = header >> 2;
        if (length - offset < width) return 0;
        selected = kbase_selected_index(key);
        if (selected >= 0) {
            if (width != 4U || properties->present[selected]) return 0;
            properties->value[selected] = (uint32_t)data[offset] |
                                          ((uint32_t)data[offset + 1U] << 8) |
                                          ((uint32_t)data[offset + 2U] << 16) |
                                          ((uint32_t)data[offset + 3U] << 24);
            properties->present[selected] = 1U;
        }
        offset += width;
        ++records;
    }
    return records != 0U && offset == length;
}

static int kbase_append(char *out, size_t capacity, size_t *used,
                        const char *format, ...) {
    va_list arguments;
    int written;
    if (out == NULL || used == NULL || format == NULL || *used >= capacity) return 0;
    va_start(arguments, format);
    written = vsnprintf(out + *used, capacity - *used, format, arguments);
    va_end(arguments);
    if (written < 0 || (size_t)written >= capacity - *used) return 0;
    *used += (size_t)written;
    return 1;
}

static int kbase_json_string(char *out, size_t capacity, size_t *used,
                             const char *value, size_t maximum) {
    size_t index;
    if (!kbase_append(out, capacity, used, "\"")) return 0;
    if (value == NULL) return 0;
    for (index = 0U; index < maximum && value[index] != '\0'; ++index) {
        unsigned char character = (unsigned char)value[index];
        if (character == '"' || character == '\\') {
            if (!kbase_append(out, capacity, used, "\\%c", character)) return 0;
        } else if (character < 0x20U) {
            if (!kbase_append(out, capacity, used, "\\u%04x", character)) return 0;
        } else if (!kbase_append(out, capacity, used, "%c", character)) {
            return 0;
        }
    }
    return kbase_append(out, capacity, used, "\"");
}

static int kbase_append_nullable_int(char *out, size_t capacity, size_t *used,
                                     int known, int value) {
    return kbase_append(out, capacity, used, known ? "%d" : "null", value);
}

static int __attribute__((unused)) kbase_build_json(const struct kbase_probe *probe, const char *run_id,
                            pid_t pid, uid_t uid, pid_t pgid, pid_t sid,
                            const char *cwd, char *out, size_t capacity) {
    size_t used = 0U;
    int native_exit;
    unsigned index;
    if (probe == NULL || run_id == NULL || cwd == NULL || out == NULL || capacity == 0U) return 0;
    native_exit = probe->native_success && !probe->output_failure ? 0 : 1;
    if (!kbase_append(out, capacity, &used, "{\"schema\":") ||
        !kbase_json_string(out, capacity, &used, KBASE_METADATA_SCHEMA, 128U) ||
        !kbase_append(out, capacity, &used, ",\"runId\":") ||
        !kbase_json_string(out, capacity, &used, run_id, 64U) ||
        !kbase_append(out, capacity, &used, ",\"pid\":%ld,\"uid\":%lu,\"pgid\":%ld,\"sid\":%ld,\"cwd\":",
                      (long)pid, (unsigned long)uid, (long)pgid, (long)sid) ||
         !kbase_json_string(out, capacity, &used, cwd, KBASE_METADATA_CWD_MAX) ||
        !kbase_append(out, capacity, &used,
                      ",\"time\":{\"startedNs\":%" PRIu64 ",\"finishedNs\":%" PRIu64
                      ",\"durationNs\":%" PRIu64 "},\"status\":",
                      probe->started_ns, probe->finished_ns,
                      probe->finished_ns >= probe->started_ns ? probe->finished_ns - probe->started_ns : 0U) ||
        !kbase_json_string(out, capacity, &used, probe->status, 64U) ||
        !kbase_append(out, capacity, &used, ",\"error\":{\"stage\":") ||
        !kbase_json_string(out, capacity, &used, probe->error_stage, 64U) ||
        !kbase_append(out, capacity, &used, ",\"errno\":") ||
        !kbase_append_nullable_int(out, capacity, &used, probe->error_errno_known, probe->error_errno) ||
        !kbase_append(out, capacity, &used, ",\"ret\":") ||
        !kbase_append_nullable_int(out, capacity, &used, probe->error_ret_known, probe->error_ret) ||
        !kbase_append(out, capacity, &used, "},\"device\":{\"path\":") ||
        !kbase_json_string(out, capacity, &used, KBASE_METADATA_DEVICE, 32U) ||
        !kbase_append(out, capacity, &used, ",\"major\":%u,\"minor\":%u,\"identityBefore\":%s,\"identityAfter\":%s},\"version\":{\"called\":%s,\"ioctl\":\"0x%08lx\",\"ret\":",
                      KBASE_METADATA_MAJOR, KBASE_METADATA_MINOR,
                      probe->identity_before ? "true" : "false",
                      probe->identity_after ? "true" : "false",
                      probe->version_called ? "true" : "false",
                      (unsigned long)KBASE_IOCTL_VERSION_CHECK_CSF) ||
        !kbase_append_nullable_int(out, capacity, &used, probe->version_called, probe->version_ret) ||
        !kbase_append(out, capacity, &used, ",\"errno\":") ||
        !kbase_append_nullable_int(out, capacity, &used, probe->version_called, probe->version_errno) ||
        !kbase_append(out, capacity, &used, ",\"major\":%u,\"minor\":%u},\"properties\":{\"probeCalled\":%s,\"probeRet\":",
                      (unsigned)probe->version_major, (unsigned)probe->version_minor,
                      probe->properties_probe_called ? "true" : "false") ||
        !kbase_append_nullable_int(out, capacity, &used, probe->properties_probe_called, probe->properties_probe_ret) ||
        !kbase_append(out, capacity, &used, ",\"probeErrno\":") ||
        !kbase_append_nullable_int(out, capacity, &used, probe->properties_probe_called, probe->properties_probe_errno) ||
        !kbase_append(out, capacity, &used, ",\"fillCalled\":%s,\"fillRet\":",
                      probe->properties_fill_called ? "true" : "false") ||
        !kbase_append_nullable_int(out, capacity, &used, probe->properties_fill_called, probe->properties_fill_ret) ||
        !kbase_append(out, capacity, &used, ",\"fillErrno\":") ||
        !kbase_append_nullable_int(out, capacity, &used, probe->properties_fill_called, probe->properties_fill_errno) ||
        !kbase_append(out, capacity, &used, ",\"returnedBytes\":%zu,\"gpuIDKnown\":%s",
                      probe->properties_returned, probe->properties.present[3] ? "true" : "false"))
        return 0;
    if (probe->properties.present[3] &&
        !kbase_append(out, capacity, &used, ",\"gpuIDhex\":\"0x%08" PRIx32 "\"",
                      probe->properties.value[3])) return 0;
    if (!kbase_append(out, capacity, &used, ",\"textureflags\":{")) return 0;
    for (index = 0U; index < KBASE_SELECTED_COUNT; ++index) {
        const char *key = index == 0U ? "52" : index == 1U ? "53" : index == 2U ? "54" : index == 4U ? "81" : NULL;
        if (key != NULL && probe->properties.present[index] &&
            !kbase_append(out, capacity, &used, "%s\"%s\":\"0x%08" PRIx32 "\"",
                          used > 0U && out[used - 1U] != '{' ? "," : "", key,
                          probe->properties.value[index])) return 0;
    }
    return kbase_append(out, capacity, &used,
                        "},\"valid\":%s},\"flagsInited\":false,\"gpuWorkloadVerified\":false,\"driverExecution\":false,\"deviceVerified\":false,\"hardwareVerified\":false,\"cleanup\":{\"closed\":%s,\"closeRet\":%d,\"closeErrno\":%d},\"nativeExit\":%d}\n",
                        probe->properties_valid ? "true" : "false",
                        probe->close_called && probe->close_ret == 0 ? "true" : "false",
                        probe->close_called ? probe->close_ret : 0,
                        probe->close_called ? probe->close_errno : 0, native_exit);
}

static void kbase_init_probe(struct kbase_probe *probe) {
    memset(probe, 0, sizeof(*probe));
    probe->fd = -1;
    probe->status = "UNKNOWN";
    probe->error_stage = "none";
    probe->deadline_valid = 1;
}

static int kbase_run_probe(const char *run_id, struct kbase_probe *probe,
                           char *cwd, size_t cwd_capacity) {
    struct stat listed;
    struct stat opened;
    struct kbase_ioctl_version_check version = {0U, 0U};
    uint8_t *property_buffer = NULL;
    size_t allocation = 0U;
    int property_buffer_corrupt = 0;
    int call_errno;
    int ret;
    int identity_ok;
    int scope_ok;
    if (probe == NULL || cwd == NULL || !kbase_valid_run_id(run_id)) return 0;
    probe->started_ns = kbase_clock_ns(&probe->clock_valid);
    scope_ok = kbase_getuid() == KBASE_METADATA_UID && kbase_valid_cwd(run_id, cwd, cwd_capacity);
    if (!probe->clock_valid || probe->started_ns == 0U || !scope_ok || !kbase_deadline_ok(probe)) {
        probe->status = "UNKNOWN";
        probe->error_stage = "scope";
        goto finalize;
    }
    if (kbase_lstat(KBASE_METADATA_DEVICE, &listed) != 0) {
        call_errno = errno;
        kbase_error_call(probe, "lstat", -1, call_errno);
        probe->status = "OPEN_FAILED";
        goto finalize;
    }
    if (!kbase_deadline_ok(probe) || !S_ISCHR(listed.st_mode) ||
        major(listed.st_rdev) != KBASE_METADATA_MAJOR || minor(listed.st_rdev) != KBASE_METADATA_MINOR) {
        kbase_error_call(probe, "lstat_identity", 0, 0);
        probe->status = "UNKNOWN";
        goto finalize;
    }
    errno = 0;
    probe->fd = kbase_open(KBASE_METADATA_DEVICE, O_RDWR | O_CLOEXEC | O_NOFOLLOW);
    call_errno = errno;
    if (probe->fd < 0) {
        kbase_error_call(probe, "open", -1, call_errno);
        probe->status = "OPEN_FAILED";
        goto finalize;
    }
    if (!kbase_deadline_ok(probe)) {
        kbase_error_call(probe, "deadline_open", 0, 0);
        goto cleanup;
    }
    if (kbase_fstat(probe->fd, &opened) != 0) {
        call_errno = errno;
        kbase_error_call(probe, "fstat_before", -1, call_errno);
        goto cleanup;
    }
    probe->identity_before = kbase_same_device(&listed, &opened);
    if (!probe->identity_before) {
        kbase_error_call(probe, "identity_before", 0, 0);
        goto cleanup;
    }
    if (!kbase_deadline_ok(probe)) {
        kbase_error_call(probe, "deadline_before_version", 0, 0);
        goto cleanup;
    }
    errno = 0;
    ret = kbase_ioctl_call(probe->fd, KBASE_IOCTL_VERSION_CHECK_CSF, &version);
    call_errno = errno;
    probe->version_called = 1;
    probe->version_ret = ret;
    probe->version_errno = ret < 0 ? -call_errno : 0;
    probe->version_major = version.major;
    probe->version_minor = version.minor;
    if (!kbase_deadline_ok(probe)) {
        kbase_error_call(probe, "deadline_after_version", ret, call_errno);
        goto cleanup;
    }
    if (ret != 0) {
        kbase_error_call(probe, "version", ret, call_errno);
        probe->status = "VERSION_FAILED";
        goto cleanup;
    }
    if (version.major != 1U) {
        probe->status = "VERSION_OBSERVED";
        probe->valid_observation = 1;
        goto cleanup;
    }
    if (!kbase_deadline_ok(probe)) {
        kbase_error_call(probe, "deadline_before_properties", 0, 0);
        goto cleanup;
    }
    {
        struct kbase_ioctl_get_gpuprops request = {0U, 0U, 0U};
        errno = 0;
        ret = kbase_ioctl_call(probe->fd, KBASE_IOCTL_GET_GPUPROPS, &request);
        call_errno = errno;
        probe->properties_probe_called = 1;
        probe->properties_probe_ret = ret;
        probe->properties_probe_errno = ret < 0 ? -call_errno : 0;
        if (!kbase_deadline_ok(probe)) {
            kbase_error_call(probe, "deadline_after_properties_probe", ret, call_errno);
            goto cleanup;
        }
        if (ret < 0) {
            kbase_error_call(probe, "properties_probe", ret, call_errno);
            probe->status = "PROPERTIES_BLOCKED";
            probe->valid_observation = 1;
            goto cleanup;
        }
        if (ret <= 0 || ret > (int)KBASE_METADATA_MAX_PROPERTIES) {
            kbase_error_call(probe, "properties_size", ret, 0);
            goto cleanup;
        }
        allocation = (size_t)ret + KBASE_METADATA_GUARD;
        property_buffer = (uint8_t *)malloc(allocation);
        if (property_buffer == NULL) {
            kbase_error_call(probe, "properties_alloc", 0, ENOMEM);
            goto cleanup;
        }
        probe->properties_allocated = allocation;
        memset(property_buffer, 0, (size_t)ret);
        memset(property_buffer + ret, 0xA5, KBASE_METADATA_GUARD);
        request.buffer = (uint64_t)(uintptr_t)property_buffer;
        request.size = (uint32_t)ret;
        request.flags = 0U;
        if (!kbase_deadline_ok(probe)) {
            kbase_error_call(probe, "deadline_before_properties_fill", 0, 0);
            goto cleanup;
        }
        errno = 0;
        ret = kbase_ioctl_call(probe->fd, KBASE_IOCTL_GET_GPUPROPS, &request);
        call_errno = errno;
        probe->properties_fill_called = 1;
        probe->properties_fill_ret = ret;
        probe->properties_fill_errno = ret < 0 ? -call_errno : 0;
        if (memcmp(property_buffer + (size_t)probe->properties_probe_ret,
                   "\xA5\xA5\xA5\xA5\xA5\xA5\xA5\xA5\xA5\xA5\xA5\xA5\xA5\xA5\xA5\xA5",
                   KBASE_METADATA_GUARD) != 0) {
            property_buffer_corrupt = 1;
            kbase_error_call(probe, "properties_bounds", ret, call_errno);
            goto cleanup;
        }
        if (!kbase_deadline_ok(probe)) {
            kbase_error_call(probe, "deadline_after_properties_fill", ret, call_errno);
            goto cleanup;
        }
        if (ret < 0) {
            kbase_error_call(probe, "properties_fill", ret, call_errno);
            probe->status = "PROPERTIES_BLOCKED";
            probe->valid_observation = 1;
            goto cleanup;
        }
        if (ret <= 0 || (size_t)ret > (size_t)probe->properties_probe_ret) {
            kbase_error_call(probe, "properties_bounds", ret, 0);
            goto cleanup;
        }
        probe->properties_returned = (size_t)ret;
        if (!kbase_parse_properties(property_buffer, probe->properties_returned, &probe->properties)) {
            kbase_error_call(probe, "properties_malformed", ret, 0);
            goto cleanup;
        }
        probe->properties_valid = 1;
        probe->status = "PROPERTIES_OBSERVED";
        probe->valid_observation = 1;
    }

cleanup:
    if (!property_buffer_corrupt) free(property_buffer);
    if (probe->fd >= 0) {
        if (kbase_fstat(probe->fd, &opened) == 0) {
            identity_ok = kbase_same_device(&listed, &opened);
            probe->identity_after = identity_ok;
            if (!identity_ok) kbase_error_call(probe, "identity_after", 0, 0);
        } else {
            call_errno = errno;
            kbase_error_call(probe, "fstat_after", -1, call_errno);
        }
        errno = 0;
        probe->close_ret = kbase_close(probe->fd);
        probe->close_errno = errno;
        probe->close_called = 1;
        probe->fd = -1;
        if (probe->close_ret != 0) {
            probe->valid_observation = 0;
            probe->status = "UNKNOWN";
            kbase_error_call(probe, "close", probe->close_ret, probe->close_errno);
        }
    }
finalize:
    probe->finished_ns = kbase_clock_ns(&probe->clock_valid);
    {
        int fd_was_opened = probe->close_called || probe->fd >= 0;
    if (!probe->clock_valid || probe->finished_ns < probe->started_ns ||
        probe->finished_ns - probe->started_ns > KBASE_METADATA_DEADLINE_NS ||
        (fd_was_opened && (!probe->identity_before || !probe->identity_after))) {
        probe->deadline_valid = 0;
        probe->valid_observation = 0;
        probe->status = "UNKNOWN";
    }
    }
    if (!probe->valid_observation && strcmp(probe->status, "UNKNOWN") != 0 &&
        strcmp(probe->status, "OPEN_FAILED") != 0 && strcmp(probe->status, "VERSION_FAILED") != 0)
        probe->status = "UNKNOWN";
    probe->native_success = probe->valid_observation &&
                            (strcmp(probe->status, "VERSION_OBSERVED") == 0 ||
                             strcmp(probe->status, "PROPERTIES_OBSERVED") == 0);
    return probe->valid_observation;
}

#ifndef KBASE_METADATA_TESTING
int main(int argc, char **argv) {
    struct kbase_probe probe;
    char cwd[KBASE_METADATA_CWD_MAX] = {0};
    char output[KBASE_METADATA_MAX_LINE + 1U];
    pid_t pid;
    pid_t pgid;
    pid_t sid;
    uid_t uid;
    int output_ok;
    if (argc != 3 || argv == NULL || argv[1] == NULL || argv[2] == NULL ||
        strcmp(argv[1], "--run-id") != 0 || !kbase_valid_run_id(argv[2])) return 2;
    kbase_init_probe(&probe);
    pid = getpid();
    uid = getuid();
    pgid = getpgid(0);
    sid = getsid(0);
    (void)kbase_run_probe(argv[2], &probe, cwd, sizeof(cwd));
    output_ok = kbase_build_json(&probe, argv[2], pid, uid, pgid, sid,
                                 cwd[0] == '\0' ? "" : cwd,
                                 output, sizeof(output));
    if (!output_ok) {
        (void)snprintf(output, sizeof(output),
                       "{\"schema\":\"%s\",\"runId\":\"%s\",\"status\":\"UNKNOWN\",\"nativeExit\":1}\n",
                       KBASE_METADATA_SCHEMA, argv[2]);
    }
    if (fputs(output, stdout) == EOF || fflush(stdout) == EOF || ferror(stdout) != 0) return 1;
    return probe.native_success && output_ok ? 0 : 1;
}
#endif
