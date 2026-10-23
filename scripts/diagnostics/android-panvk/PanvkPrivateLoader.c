#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

/*
 * PanVK private loader-only control.  This file intentionally contains no
 * Vulkan headers and never calls an address returned by dlsym().
 */

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

extern char **environ;

#define SCHEMA "wowforever.android-panvk-private-loader"
#define EXPECTED_UID 10330U
#define RUN_PREFIX "PVK-"
#define RUN_LENGTH 36U
#define PATH_LIMIT 4096U
#define ERROR_LIMIT 512U
#define OUTPUT_LIMIT (16U * 1024U)
#define ICD_NAME "libvulkan_panfrost.so"
#define ICD_SIZE UINT64_C(31142504)
#define ICD_SHA256 "5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9"
#define MAYBE_UNUSED __attribute__((unused))

static const char *const export_names[3] = {
    "vk_icdGetInstanceProcAddr",
    "vk_icdGetPhysicalDeviceProcAddr",
    "vk_icdNegotiateLoaderICDInterfaceVersion"
};

typedef struct Sha256 {
    uint32_t h[8];
    uint64_t bits;
    unsigned char block[64];
    size_t used;
} Sha256;

typedef struct Identity {
    int valid;
    uint64_t device;
    uint64_t inode;
    uint64_t owner_uid;
    uint64_t mode;
    uint64_t size;
} Identity;

typedef struct ExportObservation {
    int found;
    int owner_exact;
    char owner[PATH_LIMIT];
    char error[ERROR_LIMIT + 1U];
} ExportObservation;

typedef struct ProbeResult {
    char run_id[RUN_LENGTH + 1U];
    int pid;
    int uid;
    int pgid;
    int sid;
    uint64_t monotonic_start;
    uint64_t monotonic_end;
    char cwd[PATH_LIMIT];
    int scope_valid;
    int binding_valid;
    char icd_path[PATH_LIMIT];
    uint64_t icd_bytes;
    char icd_sha256[65];
    char before_sha256[65];
    char after_sha256[65];
    Identity before;
    Identity after;
    int before_hash_valid;
    int after_hash_valid;
    int identity_stable;
    int load_attempted;
    int load_loaded;
    char load_error[ERROR_LIMIT + 1U];
    ExportObservation exports[3];
    int dlclose_attempted;
    int dlclose_ok;
    int cleanup_complete;
    int dlclose_errno;
    char dlclose_error[ERROR_LIMIT + 1U];
    int icd_code_loaded;
    int constructors_may_have_executed;
    int clock_start_valid;
    int clock_valid;
    const char *status;
    const char *failure_stage;
    char failure_detail[ERROR_LIMIT + 1U];
    int native_exit;
} ProbeResult;

typedef struct Json {
    char *data;
    size_t capacity;
    size_t used;
    int valid;
} Json;

static uint32_t sha_rotr(uint32_t value, unsigned count) {
    return (value >> count) | (value << (32U - count));
}

static const uint32_t sha_k[64] = {
    0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
    0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
    0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
    0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
    0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
    0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
    0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
    0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
    0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
    0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
    0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
    0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
    0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
    0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
    0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
    0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U
};

static void sha_init(Sha256 *state) {
    static const uint32_t initial[8] = {
        0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
        0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U
    };
    memcpy(state->h, initial, sizeof(initial));
    state->bits = 0U;
    state->used = 0U;
}

static void sha_block(Sha256 *state, const unsigned char *input) {
    uint32_t words[64];
    uint32_t a, b, c, d, e, f, g, h;
    unsigned index;

    for (index = 0U; index < 16U; ++index) {
        words[index] = ((uint32_t)input[index * 4U] << 24U) |
                       ((uint32_t)input[index * 4U + 1U] << 16U) |
                       ((uint32_t)input[index * 4U + 2U] << 8U) |
                       (uint32_t)input[index * 4U + 3U];
    }
    for (index = 16U; index < 64U; ++index) {
        uint32_t s0 = sha_rotr(words[index - 15U], 7U) ^
                      sha_rotr(words[index - 15U], 18U) ^
                      (words[index - 15U] >> 3U);
        uint32_t s1 = sha_rotr(words[index - 2U], 17U) ^
                      sha_rotr(words[index - 2U], 19U) ^
                      (words[index - 2U] >> 10U);
        words[index] = words[index - 16U] + s0 + words[index - 7U] + s1;
    }
    a = state->h[0]; b = state->h[1]; c = state->h[2]; d = state->h[3];
    e = state->h[4]; f = state->h[5]; g = state->h[6]; h = state->h[7];
    for (index = 0U; index < 64U; ++index) {
        uint32_t s1 = sha_rotr(e, 6U) ^ sha_rotr(e, 11U) ^ sha_rotr(e, 25U);
        uint32_t choice = (e & f) ^ ((~e) & g);
        uint32_t s0 = sha_rotr(a, 2U) ^ sha_rotr(a, 13U) ^ sha_rotr(a, 22U);
        uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
        uint32_t first = h + s1 + choice + sha_k[index] + words[index];
        uint32_t second = s0 + majority;
        h = g; g = f; f = e; e = d + first;
        d = c; c = b; b = a; a = first + second;
    }
    state->h[0] += a; state->h[1] += b; state->h[2] += c; state->h[3] += d;
    state->h[4] += e; state->h[5] += f; state->h[6] += g; state->h[7] += h;
}

static void sha_update(Sha256 *state, const unsigned char *input, size_t length) {
    state->bits += (uint64_t)length * 8U;
    while (length != 0U) {
        size_t take = 64U - state->used;
        if (take > length) take = length;
        memcpy(state->block + state->used, input, take);
        state->used += take;
        input += take;
        length -= take;
        if (state->used == 64U) {
            sha_block(state, state->block);
            state->used = 0U;
        }
    }
}

static void sha_final(Sha256 *state, unsigned char digest[32]) {
    uint64_t bits = state->bits;
    size_t index;
    state->block[state->used++] = 0x80U;
    while (state->used != 56U) {
        if (state->used == 64U) {
            sha_block(state, state->block);
            state->used = 0U;
        }
        state->block[state->used++] = 0U;
    }
    for (index = 0U; index < 8U; ++index) {
        state->block[56U + index] = (unsigned char)(bits >> (56U - 8U * index));
    }
    sha_block(state, state->block);
    for (index = 0U; index < 8U; ++index) {
        digest[index * 4U] = (unsigned char)(state->h[index] >> 24U);
        digest[index * 4U + 1U] = (unsigned char)(state->h[index] >> 16U);
        digest[index * 4U + 2U] = (unsigned char)(state->h[index] >> 8U);
        digest[index * 4U + 3U] = (unsigned char)state->h[index];
    }
}

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

static void copy_error(char out[ERROR_LIMIT + 1U], const char *value) {
    size_t length = bounded_length(value, ERROR_LIMIT, NULL);
    if (value == NULL) {
        out[0] = '\0';
        return;
    }
    memcpy(out, value, length);
    out[length] = '\0';
}

static MAYBE_UNUSED int path_join(char *out, size_t capacity, const char *base, const char *name) {
    int written;
    if (out == NULL || base == NULL || name == NULL) return 0;
    written = snprintf(out, capacity, "%s/%s", base, name);
    return written > 0 && (size_t)written < capacity;
}

static MAYBE_UNUSED int canonical_path(const char *path, char *out, size_t capacity) {
    char *resolved;
    int result;
    if (path == NULL || out == NULL || capacity == 0U) return 0;
    resolved = realpath(path, NULL);
    if (resolved == NULL) return 0;
    result = copy_bounded(out, capacity, resolved, capacity - 1U);
    free(resolved);
    return result;
}

static int valid_run_id(const char *run_id) {
    size_t index;
    if (run_id == NULL || bounded_length(run_id, RUN_LENGTH + 1U, NULL) != RUN_LENGTH ||
        memcmp(run_id, RUN_PREFIX, sizeof(RUN_PREFIX) - 1U) != 0) return 0;
    for (index = sizeof(RUN_PREFIX) - 1U; index < RUN_LENGTH; ++index) {
        unsigned char value = (unsigned char)run_id[index];
        if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) return 0;
    }
    return 1;
}

static int scope_path_matches(const char *cwd, const char *run_id) {
    static const char *const prefixes[] = {
        "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-loader/",
        "/data/data/com.wowforever.arm64probe/files/forever/controls/panvk-loader/"
    };
    size_t index;
    if (!valid_run_id(run_id) || cwd == NULL) return 0;
    for (index = 0U; index < sizeof(prefixes) / sizeof(prefixes[0]); ++index) {
        size_t prefix_length = strlen(prefixes[index]);
        if (strncmp(cwd, prefixes[index], prefix_length) == 0 &&
            strcmp(cwd + prefix_length, run_id) == 0) return 1;
    }
    return 0;
}

static MAYBE_UNUSED int environment_matches(const char *cwd) {
    char tmpdir[PATH_LIMIT + 16U];
    static const char *const fixed[] = {
        "PATH=/system/bin", "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        "LD_LIBRARY_PATH=/system/lib64"
    };
    size_t count = 0U;
    size_t index;
    int written;
    if (cwd == NULL) return 0;
    written = snprintf(tmpdir, sizeof(tmpdir), "TMPDIR=%s", cwd);
    if (written <= 0 || (size_t)written >= sizeof(tmpdir)) return 0;
    while (environ != NULL && environ[count] != NULL) {
        ++count;
        if (count > sizeof(fixed) / sizeof(fixed[0]) + 1U) return 0;
    }
    if (count != sizeof(fixed) / sizeof(fixed[0]) + 1U) return 0;
    for (index = 0U; index < sizeof(fixed) / sizeof(fixed[0]); ++index) {
        int found = 0;
        size_t env_index;
        for (env_index = 0U; env_index < count; ++env_index) {
            if (strcmp(environ[env_index], fixed[index]) == 0) found = 1;
        }
        if (!found) return 0;
    }
    for (index = 0U; index < count; ++index) {
        if (strcmp(environ[index], tmpdir) == 0) return 1;
    }
    return 0;
}

static MAYBE_UNUSED int scope_valid(char *cwd, const char *run_id) {
    if (cwd == NULL || run_id == NULL || getuid() != (uid_t)EXPECTED_UID ||
        realpath(".", cwd) == NULL || !scope_path_matches(cwd, run_id)) return 0;
    return environment_matches(cwd);
}

static MAYBE_UNUSED void identity_from_stat(Identity *identity, const struct stat *value) {
    if (identity == NULL) return;
    memset(identity, 0, sizeof(*identity));
    if (value == NULL || !S_ISREG(value->st_mode) ||
        value->st_uid != (uid_t)EXPECTED_UID || value->st_size < 0) return;
    identity->valid = 1;
    identity->device = (uint64_t)value->st_dev;
    identity->inode = (uint64_t)value->st_ino;
    identity->owner_uid = (uint64_t)value->st_uid;
    identity->mode = (uint64_t)(value->st_mode & 07777U);
    identity->size = (uint64_t)value->st_size;
}

static MAYBE_UNUSED int identity_same(const Identity *left, const Identity *right) {
    return left != NULL && right != NULL && left->valid && right->valid &&
           left->device == right->device && left->inode == right->inode &&
           left->owner_uid == right->owner_uid && left->mode == right->mode &&
           left->size == right->size;
}

static MAYBE_UNUSED int hash_file(const char *path, Identity *identity, char digest_hex[65]) {
    static const char digits[] = "0123456789abcdef";
    unsigned char buffer[65536U];
    unsigned char digest[32];
    struct stat listed;
    struct stat opened;
    Sha256 state;
    uint64_t total = 0U;
    ssize_t got;
    int fd = -1;
    int result = 0;
    size_t index;

    if (identity == NULL || digest_hex == NULL || path == NULL) return 0;
    memset(identity, 0, sizeof(*identity));
    digest_hex[0] = '\0';
    if (lstat(path, &listed) != 0) return 0;
    fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0 || fstat(fd, &opened) != 0) goto done;
    if (!S_ISREG(listed.st_mode) || listed.st_uid != (uid_t)EXPECTED_UID ||
        !S_ISREG(opened.st_mode) || opened.st_uid != (uid_t)EXPECTED_UID ||
        listed.st_dev != opened.st_dev || listed.st_ino != opened.st_ino ||
        listed.st_size != opened.st_size ||
        (listed.st_mode & 07777U) != (opened.st_mode & 07777U) ||
        listed.st_size < 0 || (uint64_t)listed.st_size != ICD_SIZE) goto done;
    sha_init(&state);
    for (;;) {
        got = read(fd, buffer, sizeof(buffer));
        if (got == 0) break;
        if (got < 0) {
            if (errno == EINTR) continue;
            goto done;
        }
        if ((uint64_t)got > ICD_SIZE || total > ICD_SIZE - (uint64_t)got) goto done;
        sha_update(&state, buffer, (size_t)got);
        total += (uint64_t)got;
    }
    if (fstat(fd, &opened) != 0 || total != ICD_SIZE ||
        opened.st_dev != listed.st_dev || opened.st_ino != listed.st_ino ||
        opened.st_size != listed.st_size || opened.st_uid != listed.st_uid ||
        (opened.st_mode & 07777U) != (listed.st_mode & 07777U)) goto done;
    sha_final(&state, digest);
    for (index = 0U; index < sizeof(digest); ++index) {
        digest_hex[index * 2U] = digits[digest[index] >> 4U];
        digest_hex[index * 2U + 1U] = digits[digest[index] & 15U];
    }
    digest_hex[64] = '\0';
    identity_from_stat(identity, &opened);
    result = identity->valid && strcmp(digest_hex, ICD_SHA256) == 0;
done:
    if (fd >= 0 && close(fd) != 0) result = 0;
    return result;
}

static int json_append(Json *json, const char *format, ...) {
    va_list args;
    int written;
    if (json == NULL || !json->valid || format == NULL || json->used >= json->capacity) {
        if (json != NULL) json->valid = 0;
        return 0;
    }
    va_start(args, format);
    written = vsnprintf(json->data + json->used, json->capacity - json->used, format, args);
    va_end(args);
    if (written < 0 || (size_t)written >= json->capacity - json->used) {
        json->valid = 0;
        return 0;
    }
    json->used += (size_t)written;
    return 1;
}

static int json_string(Json *json, const char *value, size_t limit) {
    static const char hex[] = "0123456789abcdef";
    size_t index;
    if (!json_append(json, "\"")) return 0;
    if (value != NULL) {
        for (index = 0U; index < limit && value[index] != '\0'; ++index) {
            unsigned char byte = (unsigned char)value[index];
            if (byte == '"' || byte == '\\') {
                if (!json_append(json, "\\%c", byte)) return 0;
            } else if (byte == '\b') {
                if (!json_append(json, "\\b")) return 0;
            } else if (byte == '\f') {
                if (!json_append(json, "\\f")) return 0;
            } else if (byte == '\n') {
                if (!json_append(json, "\\n")) return 0;
            } else if (byte == '\r') {
                if (!json_append(json, "\\r")) return 0;
            } else if (byte == '\t') {
                if (!json_append(json, "\\t")) return 0;
            } else if (byte < 0x20U || byte >= 0x80U) {
                if (!json_append(json, "\\u00%c%c", hex[byte >> 4U], hex[byte & 15U])) return 0;
            } else if (!json_append(json, "%c", byte)) {
                return 0;
            }
        }
    }
    return json_append(json, "\"");
}

static int json_bool(Json *json, int value) {
    return json_append(json, value ? "true" : "false");
}

static int json_identity(Json *json, const Identity *identity) {
    if (!json_append(json, "{\"regular\":")) return 0;
    if (!json_bool(json, identity != NULL && identity->valid) ||
        !json_append(json, ",\"ownerUid\":%" PRIu64, identity != NULL ? identity->owner_uid : 0U)) return 0;
    return json_append(json, ",\"device\":%" PRIu64 ",\"inode\":%" PRIu64
                       ",\"mode\":%" PRIu64 ",\"size\":%" PRIu64 "}",
                       identity != NULL ? identity->device : 0U,
                       identity != NULL ? identity->inode : 0U,
                       identity != NULL ? identity->mode : 0U,
                       identity != NULL ? identity->size : 0U);
}

static int json_export(Json *json, const ExportObservation *observation, const char *name) {
    return json_append(json, "{\"name\":") && json_string(json, name, PATH_LIMIT) &&
           json_append(json, ",\"found\":") && json_bool(json, observation->found) &&
           json_append(json, ",\"owner\":") && json_string(json, observation->owner, PATH_LIMIT) &&
           json_append(json, ",\"ownerExact\":") && json_bool(json, observation->owner_exact) &&
           json_append(json, ",\"error\":") && json_string(json, observation->error, ERROR_LIMIT) &&
           json_append(json, "}");
}

static int encode_result(const ProbeResult *result, char *output, size_t capacity) {
    Json json = {output, capacity, 0U, 1};
    size_t index;
    if (result == NULL || output == NULL || capacity == 0U) return 0;
    if (!json_append(&json, "{\"schema\":") || !json_string(&json, SCHEMA, sizeof(SCHEMA)) ||
        !json_append(&json, ",\"version\":1,\"runId\":") ||
        !json_string(&json, result->run_id, RUN_LENGTH) ||
        !json_append(&json, ",\"pid\":%d,\"uid\":%d,\"pgid\":%d,\"sid\":%d",
                     result->pid, result->uid, result->pgid, result->sid) ||
        !json_append(&json, ",\"monotonicStartNs\":%" PRIu64 ",\"monotonicEndNs\":%" PRIu64,
                     result->monotonic_start, result->monotonic_end) ||
        !json_append(&json, ",\"cwd\":") || !json_string(&json, result->cwd, PATH_LIMIT) ||
        !json_append(&json, ",\"scopeValid\":") || !json_bool(&json, result->scope_valid) ||
        !json_append(&json, ",\"icdPath\":") || !json_string(&json, result->icd_path, PATH_LIMIT) ||
        !json_append(&json, ",\"icdBytes\":%" PRIu64 ",\"icdSha256\":", result->icd_bytes) ||
        !json_string(&json, result->icd_sha256, 64U) ||
        !json_append(&json, ",\"icdIdentityBefore\":") || json_identity(&json, &result->before) == 0 ||
        !json_append(&json, ",\"icdIdentityAfter\":") || json_identity(&json, &result->after) == 0 ||
        !json_append(&json, ",\"icdIdentityStable\":") || !json_bool(&json, result->identity_stable) ||
        !json_append(&json, ",\"load\":{\"attempted\":") || !json_bool(&json, result->load_attempted) ||
        !json_append(&json, ",\"loaded\":") || !json_bool(&json, result->load_loaded) ||
        !json_append(&json, ",\"dlerror\":") || !json_string(&json, result->load_error, ERROR_LIMIT) ||
        !json_append(&json, "},\"exports\":[")) return 0;
    for (index = 0U; index < 3U; ++index) {
        if (index != 0U && !json_append(&json, ",")) return 0;
        if (!json_export(&json, &result->exports[index], export_names[index])) return 0;
    }
    if (!json_append(&json, "],\"cleanup\":{\"dlcloseAttempted\":") ||
        !json_bool(&json, result->dlclose_attempted) || json_append(&json, ",\"dlcloseOk\":") == 0 ||
        !json_bool(&json, result->dlclose_ok) || json_append(&json, ",\"complete\":") == 0 ||
        !json_bool(&json, result->cleanup_complete) ||
        !json_append(&json, ",\"errno\":%d,\"dlerror\":", result->dlclose_errno) ||
        !json_string(&json, result->dlclose_error, ERROR_LIMIT) ||
        !json_append(&json, "},\"icdCodeLoaded\":") || json_bool(&json, result->icd_code_loaded) == 0 ||
        !json_append(&json, ",\"constructorsMayHaveExecuted\":") ||
        json_bool(&json, result->constructors_may_have_executed) == 0 ||
        !json_append(&json, ",\"vulkanFunctionsInvoked\":false,\"apiComplete\":false,\"vulkan\":false,\"gpu\":false,\"triangle\":false,\"presentation\":false,\"d3d\":false,\"wow\":false,\"status\":") ||
        !json_string(&json, result->status, 64U) ||
        !json_append(&json, ",\"failureStage\":") || !json_string(&json, result->failure_stage, 64U) ||
        !json_append(&json, ",\"failureDetail\":") || !json_string(&json, result->failure_detail, ERROR_LIMIT) ||
        !json_append(&json, ",\"nativeExit\":%d}", result->native_exit)) return 0;
    return json.valid && json.used < capacity;
}

static void make_unknown_fallback(ProbeResult *fallback, const ProbeResult *source) {
    memset(fallback, 0, sizeof(*fallback));
    if (source == NULL) return;
    (void)copy_bounded(fallback->run_id, sizeof(fallback->run_id), source->run_id, RUN_LENGTH + 1U);
    fallback->pid = source->pid;
    fallback->uid = source->uid;
    fallback->pgid = source->pgid;
    fallback->sid = source->sid;
    fallback->monotonic_start = source->monotonic_start;
    fallback->monotonic_end = source->monotonic_end;
    /* Scope and export owners are absent in this bounded fallback, not valid
     * observations. Preserve only truthful load/constructor/cleanup state. */
    fallback->scope_valid = 0;
    fallback->load_attempted = source->load_attempted;
    fallback->load_loaded = source->load_loaded;
    fallback->icd_code_loaded = source->load_loaded;
    fallback->constructors_may_have_executed = source->load_attempted;
    fallback->dlclose_attempted = source->dlclose_attempted;
    fallback->dlclose_ok = source->dlclose_ok;
    fallback->cleanup_complete = source->cleanup_complete;
    fallback->dlclose_errno = source->dlclose_errno;
    fallback->status = "UNKNOWN";
    fallback->failure_stage = "output";
    fallback->failure_detail[0] = '\0';
    fallback->native_exit = 1;
}

static int encode_unknown_fallback(const ProbeResult *source, char *output, size_t capacity) {
    ProbeResult fallback;
    make_unknown_fallback(&fallback, source);
    return encode_result(&fallback, output, capacity);
}

static MAYBE_UNUSED uint64_t monotonic_now(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0 || value.tv_sec <= 0 || value.tv_nsec < 0) return 0U;
    return (uint64_t)value.tv_sec * UINT64_C(1000000000) + (uint64_t)value.tv_nsec;
}

static MAYBE_UNUSED void set_failure(ProbeResult *result, const char *stage, const char *detail) {
    if (result->failure_stage == NULL || strcmp(result->failure_stage, "none") == 0) {
        result->failure_stage = stage;
        copy_error(result->failure_detail, detail);
    }
}

static int failure_is(const ProbeResult *result, const char *stage) {
    return result != NULL && result->failure_stage != NULL && stage != NULL &&
           strcmp(result->failure_stage, stage) == 0;
}

static int pinned_identity_valid(const ProbeResult *result) {
    return result != NULL && result->binding_valid && result->icd_bytes == ICD_SIZE &&
           result->before_hash_valid && result->after_hash_valid && result->before.valid &&
           result->after.valid && result->before.size == ICD_SIZE &&
           result->after.size == ICD_SIZE &&
           strcmp(result->before_sha256, ICD_SHA256) == 0 &&
           strcmp(result->after_sha256, ICD_SHA256) == 0;
}

static void classify(ProbeResult *result) {
    size_t index;
    int all_exports = 1;
    for (index = 0U; index < 3U; ++index) {
        if (!result->exports[index].found || !result->exports[index].owner_exact) all_exports = 0;
    }
    result->icd_code_loaded = result->load_loaded;
    result->constructors_may_have_executed = result->load_attempted;
    if (result->scope_valid && result->binding_valid && pinned_identity_valid(result) &&
        result->identity_stable && result->clock_valid && result->load_attempted && !result->load_loaded &&
        result->load_error[0] != '\0' && failure_is(result, "loader") &&
        !result->dlclose_attempted && !result->dlclose_ok && result->cleanup_complete) {
        result->status = "LOADER_FAILED";
        result->native_exit = 1;
        return;
    }
    if (result->scope_valid && result->binding_valid && pinned_identity_valid(result) &&
        result->clock_valid && failure_is(result, "none") && result->load_attempted &&
        result->load_loaded && all_exports && result->load_error[0] == '\0' &&
        result->identity_stable && result->dlclose_attempted && result->dlclose_ok &&
        result->cleanup_complete) {
        result->status = "PRIVATE_ICD_LOADER_OBSERVED";
        result->native_exit = 0;
        return;
    }
    result->status = "UNKNOWN";
    result->native_exit = 1;
}

static MAYBE_UNUSED int address_owner(void *address, char *owner, size_t capacity) {
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (address == NULL || owner == NULL || dladdr(address, &info) == 0 || info.dli_fname == NULL ||
        !canonical_path(info.dli_fname, owner, capacity)) return 0;
    return 1;
}

static MAYBE_UNUSED void observe_exports(ProbeResult *result, void *handle, const char *icd_canonical) {
    size_t index;
    for (index = 0U; index < 3U; ++index) {
        void *symbol;
        const char *error;
        (void)dlerror();
        symbol = dlsym(handle, export_names[index]);
        error = dlerror();
        if (symbol != NULL && error == NULL) {
            result->exports[index].found = 1;
            if (!address_owner(symbol, result->exports[index].owner,
                               sizeof(result->exports[index].owner))) {
                copy_error(result->exports[index].error, "dladdr failed");
            } else {
                result->exports[index].owner_exact = strcmp(result->exports[index].owner,
                                                            icd_canonical) == 0;
                if (!result->exports[index].owner_exact) {
                    copy_error(result->exports[index].error, "owner mismatch");
                }
            }
        } else if (error != NULL) {
            copy_error(result->exports[index].error, error);
        } else {
            copy_error(result->exports[index].error, "symbol not found");
        }
    }
}

#ifdef PANVK_PRIVATE_LOADER_HOST_TEST

int panvk_test_sha256_hex(const unsigned char *data, size_t length, char *out, size_t capacity) {
    static const char digits[] = "0123456789abcdef";
    unsigned char digest[32];
    Sha256 state;
    size_t index;
    if (data == NULL || out == NULL || capacity < 65U) return 0;
    sha_init(&state);
    sha_update(&state, data, length);
    sha_final(&state, digest);
    for (index = 0U; index < sizeof(digest); ++index) {
        out[index * 2U] = digits[digest[index] >> 4U];
        out[index * 2U + 1U] = digits[digest[index] & 15U];
    }
    out[64] = '\0';
    return 1;
}

int panvk_test_scope_path(const char *cwd, const char *run_id) {
    return scope_path_matches(cwd, run_id);
}

int panvk_test_owner_exact(const char *owner, const char *expected) {
    return owner != NULL && expected != NULL && strcmp(owner, expected) == 0;
}

static void panvk_test_fixture_base(ProbeResult *result) {
    size_t index;
    memset(result, 0, sizeof(*result));
    (void)snprintf(result->run_id, sizeof(result->run_id), "PVK-0123456789abcdef0123456789abcdef");
    result->pid = 7; result->uid = EXPECTED_UID; result->pgid = 7; result->sid = 7;
    result->monotonic_start = 1U; result->monotonic_end = 2U;
    result->clock_start_valid = 1; result->clock_valid = 1;
    (void)snprintf(result->cwd, sizeof(result->cwd), "/data/data/com.wowforever.arm64probe/files/forever/controls/panvk-loader/%s", result->run_id);
    if (!path_join(result->icd_path, sizeof(result->icd_path), result->cwd, ICD_NAME)) return;
    result->scope_valid = 1; result->binding_valid = 1; result->icd_bytes = ICD_SIZE;
    (void)snprintf(result->icd_sha256, sizeof(result->icd_sha256), "%s", ICD_SHA256);
    (void)snprintf(result->before_sha256, sizeof(result->before_sha256), "%s", ICD_SHA256);
    (void)snprintf(result->after_sha256, sizeof(result->after_sha256), "%s", ICD_SHA256);
    result->before.valid = 1; result->after.valid = 1;
    result->before.owner_uid = EXPECTED_UID; result->after.owner_uid = EXPECTED_UID;
    result->before.size = ICD_SIZE; result->after.size = ICD_SIZE;
    result->before_hash_valid = 1; result->after_hash_valid = 1; result->identity_stable = 1;
    result->load_attempted = 1; result->load_loaded = 1;
    result->dlclose_attempted = 1; result->dlclose_ok = 1; result->cleanup_complete = 1;
    result->failure_stage = "none";
    for (index = 0U; index < 3U; ++index) {
        result->exports[index].found = 1;
        result->exports[index].owner_exact = 1;
        (void)snprintf(result->exports[index].owner, sizeof(result->exports[index].owner), "%s", result->icd_path);
    }
}

int panvk_test_encode_fixture(int loaded, int owners, int stable, int cleanup,
                              const char *load_error, char *output, size_t capacity) {
    ProbeResult result;
    typedef void (*PrivateLoaderFakeFn)(void);
    PrivateLoaderFakeFn fake_exports[3] = {
        (PrivateLoaderFakeFn)(uintptr_t)1U,
        (PrivateLoaderFakeFn)(uintptr_t)2U,
        (PrivateLoaderFakeFn)(uintptr_t)3U
    };
    size_t index;
    (void)fake_exports;
    panvk_test_fixture_base(&result);
    result.load_loaded = loaded;
    result.identity_stable = stable;
    result.before_hash_valid = stable;
    result.after_hash_valid = stable;
    copy_error(result.load_error, load_error);
    result.dlclose_attempted = loaded;
    result.dlclose_ok = loaded && cleanup;
    result.cleanup_complete = loaded ? cleanup : (load_error != NULL && cleanup);
    if (load_error != NULL) set_failure(&result, "loader", load_error);
    if (!stable) set_failure(&result, "identity", "fixture identity/hash is unstable");
    for (index = 0U; index < 3U; ++index) {
        result.exports[index].found = loaded;
        result.exports[index].owner_exact = loaded && owners;
        (void)snprintf(result.exports[index].owner, sizeof(result.exports[index].owner), "%s", owners ? result.icd_path : "");
        if (loaded && !owners) set_failure(&result, "owner", "fixture owner mismatch");
        /* These are deliberately opaque fake export observations; no pointer is called. */
    }
    classify(&result);
    return encode_result(&result, output, capacity);
}

int panvk_test_encode_adversarial(const char *case_name, char *output, size_t capacity) {
    ProbeResult result;
    if (case_name == NULL || output == NULL) return 0;
    panvk_test_fixture_base(&result);
    if (strcmp(case_name, "invalid-start") == 0) {
        result.monotonic_start = 0U; result.clock_start_valid = 0; result.clock_valid = 0;
        result.load_attempted = 0; result.load_loaded = 0; result.cleanup_complete = 1;
        set_failure(&result, "clock", "invalid start");
    } else if (strcmp(case_name, "invalid-end") == 0) {
        result.monotonic_end = 0U; result.clock_valid = 0;
        set_failure(&result, "clock", "invalid end");
    } else if (strcmp(case_name, "reversed-time") == 0) {
        result.monotonic_start = 2U; result.monotonic_end = 1U; result.clock_valid = 0;
        set_failure(&result, "clock", "end precedes start");
    } else if (strcmp(case_name, "contradictory-cleanup") == 0) {
        result.dlclose_attempted = 0; result.dlclose_ok = 1; result.cleanup_complete = 1;
    } else if (strcmp(case_name, "stable-false") == 0) {
        result.identity_stable = 0; set_failure(&result, "identity", "unstable identity");
    } else if (strcmp(case_name, "wrong-hash") == 0) {
        result.before_hash_valid = 0; set_failure(&result, "hash", "wrong pinned hash");
    } else if (strcmp(case_name, "missing-identity") == 0) {
        result.before.valid = 0; set_failure(&result, "identity", "missing identity");
    } else if (strcmp(case_name, "loader-failure-complete") == 0) {
        result.load_loaded = 0;
        result.load_error[0] = '\0';
        copy_error(result.load_error, "real dlopen failure");
        result.dlclose_attempted = 0; result.dlclose_ok = 0; result.cleanup_complete = 1;
        set_failure(&result, "loader", result.load_error);
    } else if (strcmp(case_name, "loader-failure-unstable") == 0) {
        result.load_loaded = 0;
        result.identity_stable = 0;
        result.load_error[0] = '\0';
        copy_error(result.load_error, "real dlopen failure");
        result.dlclose_attempted = 0; result.dlclose_ok = 0; result.cleanup_complete = 1;
        set_failure(&result, "loader", result.load_error);
    } else {
        return 0;
    }
    classify(&result);
    return encode_result(&result, output, capacity);
}

int panvk_test_encode_oversized_fallback(char *output, size_t capacity) {
    ProbeResult result;
    size_t index;
    panvk_test_fixture_base(&result);
    memset(result.cwd, 'x', sizeof(result.cwd) - 1U);
    result.cwd[sizeof(result.cwd) - 1U] = '\0';
    memset(result.icd_path, 'y', sizeof(result.icd_path) - 1U);
    result.icd_path[sizeof(result.icd_path) - 1U] = '\0';
    memset(result.failure_detail, 'z', sizeof(result.failure_detail) - 1U);
    result.failure_detail[sizeof(result.failure_detail) - 1U] = '\0';
    for (index = 0U; index < 3U; ++index) {
        memset(result.exports[index].owner, 'o', sizeof(result.exports[index].owner) - 1U);
        result.exports[index].owner[sizeof(result.exports[index].owner) - 1U] = '\0';
        memset(result.exports[index].error, 'e', sizeof(result.exports[index].error) - 1U);
        result.exports[index].error[sizeof(result.exports[index].error) - 1U] = '\0';
    }
    classify(&result);
    if (encode_result(&result, output, capacity)) return 0;
    return encode_unknown_fallback(&result, output, capacity);
}

#else

static int emit_invalid_invocation(void) {
    ProbeResult result;
    char output[OUTPUT_LIMIT];
    size_t output_length;
    memset(&result, 0, sizeof(result));
    result.pid = (int)getpid();
    result.uid = (int)getuid();
    result.pgid = (int)getpgid(0);
    result.sid = (int)getsid(0);
    result.monotonic_start = monotonic_now();
    result.monotonic_end = monotonic_now();
    result.scope_valid = 0;
    result.status = "UNKNOWN";
    result.failure_stage = "arguments";
    copy_error(result.failure_detail, "expected --run-id PVK-<32 lowercase hexadecimal characters>");
    result.native_exit = 1;
    if (!encode_result(&result, output, sizeof(output))) return 1;
    output_length = strlen(output);
    if (output_length >= OUTPUT_LIMIT || fwrite(output, 1U, output_length, stdout) != output_length ||
        fputc('\n', stdout) == EOF || fflush(stdout) != 0) return 1;
    return 1;
}

static int native_main(const char *run_id) {
    ProbeResult result;
    char canonical_icd[PATH_LIMIT];
    void *handle = NULL;
    uint64_t end_time;
    int close_result;
    const char *close_error;
    char output[OUTPUT_LIMIT];
    size_t output_length;
    int exit_code;

    memset(&result, 0, sizeof(result));
    result.pid = (int)getpid();
    result.uid = (int)getuid();
    result.pgid = (int)getpgid(0);
    result.sid = (int)getsid(0);
    result.failure_stage = "none";
    (void)copy_bounded(result.run_id, sizeof(result.run_id), run_id, RUN_LENGTH + 1U);
    result.monotonic_start = monotonic_now();
    result.clock_start_valid = result.monotonic_start > 0U;
    if (!result.clock_start_valid) set_failure(&result, "clock", "monotonic start unavailable");

    result.scope_valid = scope_valid(result.cwd, run_id);
    if (!result.scope_valid) set_failure(&result, "scope", "UID, CWD, run ID, or environment mismatch");
    if (result.scope_valid && !result.clock_start_valid) {
        set_failure(&result, "clock", "initial monotonic clock invalid; load not attempted");
    }
    if (result.scope_valid && result.clock_start_valid &&
        !path_join(result.icd_path, sizeof(result.icd_path), result.cwd, ICD_NAME)) {
        set_failure(&result, "path", "ICD path exceeds bound");
        result.scope_valid = 0;
    }
    if (result.scope_valid && result.clock_start_valid) {
        struct stat listed;
        if (lstat(result.icd_path, &listed) == 0 && listed.st_size >= 0) result.icd_bytes = (uint64_t)listed.st_size;
        if (!canonical_path(result.icd_path, canonical_icd, sizeof(canonical_icd)) ||
            strcmp(canonical_icd, result.icd_path) != 0) {
            set_failure(&result, "identity", "ICD is not the exact canonical regular file");
        } else {
            result.binding_valid = 1;
            result.before_hash_valid = hash_file(result.icd_path, &result.before, result.before_sha256);
            if (result.before_hash_valid) {
                (void)copy_bounded(result.icd_sha256, sizeof(result.icd_sha256),
                                   result.before_sha256, sizeof(result.before_sha256));
            }
        }
        if (!result.binding_valid || !result.before_hash_valid) {
            set_failure(&result, "hash", "ICD identity, size, owner, or SHA-256 mismatch");
        } else {
            (void)dlerror();
            result.load_attempted = 1;
            result.constructors_may_have_executed = 1;
            handle = dlopen(result.icd_path, RTLD_NOW | RTLD_LOCAL);
            if (handle == NULL) {
                result.load_error[0] = '\0';
                copy_error(result.load_error, dlerror());
                if (result.load_error[0] == '\0') {
                    set_failure(&result, "loader", "dlopen failed without dlerror");
                } else {
                    set_failure(&result, "loader", result.load_error);
                }
            } else {
                result.load_loaded = 1;
                observe_exports(&result, handle, canonical_icd);
                {
                    size_t export_index;
                    for (export_index = 0U; export_index < 3U; ++export_index) {
                        if (!result.exports[export_index].found) {
                            set_failure(&result, "export", "required ICD export missing");
                        } else if (!result.exports[export_index].owner_exact) {
                            set_failure(&result, "owner", "required ICD export owner mismatch");
                        }
                    }
                }
            }
            result.after_hash_valid = hash_file(result.icd_path, &result.after, result.after_sha256);
            if (result.after_hash_valid) {
                (void)copy_bounded(result.icd_sha256, sizeof(result.icd_sha256),
                                   result.after_sha256, sizeof(result.after_sha256));
            }
            result.identity_stable = identity_same(&result.before, &result.after);
            if (!result.after_hash_valid) set_failure(&result, "hash", "ICD changed or failed post-load verification");
            if (!result.identity_stable) set_failure(&result, "identity", "ICD identity changed during observation");
            if (handle == NULL) {
                /* No handle was returned, so there is nothing to close. */
                result.dlclose_attempted = 0;
                result.dlclose_ok = 0;
                result.cleanup_complete = 1;
            } else {
                result.dlclose_attempted = 1;
                errno = 0;
                (void)dlerror();
                close_result = dlclose(handle);
                result.dlclose_errno = errno;
                close_error = dlerror();
                copy_error(result.dlclose_error, close_error);
                result.dlclose_ok = close_result == 0 && close_error == NULL;
                result.cleanup_complete = result.dlclose_ok;
                if (!result.cleanup_complete) set_failure(&result, "cleanup", "dlclose failed");
            }
        }
    }
    end_time = monotonic_now();
    result.monotonic_end = end_time;
    result.clock_valid = result.clock_start_valid && end_time > 0U && end_time >= result.monotonic_start;
    if (!result.clock_valid) {
        set_failure(&result, "clock", "monotonic end unavailable or not increasing");
    }
    classify(&result);
    exit_code = result.native_exit;
    if (!encode_result(&result, output, sizeof(output))) {
        if (!encode_unknown_fallback(&result, output, sizeof(output))) return 1;
        exit_code = 1;
    }
    output_length = strlen(output);
    if (output_length >= OUTPUT_LIMIT || fwrite(output, 1U, output_length, stdout) != output_length ||
        fputc('\n', stdout) == EOF || fflush(stdout) != 0) return 1;
    return exit_code;
}

int main(int argc, char **argv) {
    if (argc != 3 || argv == NULL || argv[1] == NULL || argv[2] == NULL ||
        strcmp(argv[1], "--run-id") != 0 || !valid_run_id(argv[2])) return emit_invalid_invocation();
    return native_main(argv[2]);
}

#endif
