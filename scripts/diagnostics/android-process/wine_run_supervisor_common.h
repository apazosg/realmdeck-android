#ifndef WOWFOREVER_WINE_RUN_SUPERVISOR_COMMON_H
#define WOWFOREVER_WINE_RUN_SUPERVISOR_COMMON_H

#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifndef PR_SET_CHILD_SUBREAPER
#define PR_SET_CHILD_SUBREAPER 36
#endif
#ifndef PR_GET_CHILD_SUBREAPER
#define PR_GET_CHILD_SUBREAPER 37
#endif

#define WINE_SUPERVISOR_SCHEMA 2
#define WINE_SUPERVISOR_MAX_CHILDREN 64U
#define WINE_SUPERVISOR_MAX_LINE 8192U
#define WINE_SUPERVISOR_MAX_LOG_STREAM (64U * 1024U)
#define WINE_SUPERVISOR_MAX_OUTPUT (256U * 1024U)
#define WINE_SUPERVISOR_UNUSED __attribute__((unused))

#ifdef SUPERVISOR_HOST_TEST
#define WINE_SUPERVISOR_OPERATION_MS 4000ULL
#define WINE_SUPERVISOR_CLEANUP_MS 1500ULL
#define WINE_SUPERVISOR_WATCHDOG_MS 6000ULL
#define WINE_SUPERVISOR_HOST_LINKER "bin/linker64"
#else
#define WINE_SUPERVISOR_OPERATION_MS 45000ULL
#define WINE_SUPERVISOR_CLEANUP_MS 10000ULL
#define WINE_SUPERVISOR_WATCHDOG_MS 55000ULL
#endif

#ifndef WINE_SUPERVISOR_SERVER_SHA256
#define WINE_SUPERVISOR_SERVER_SHA256 \
    "804d676d11f228c565182d9b49317f42d0fca093b5c1c20a0960ba5753c199f2"
#endif
#ifndef WINE_SUPERVISOR_WINE_SHA256
#define WINE_SUPERVISOR_WINE_SHA256 \
    "389c7e15f699471042845a24fa47ce7d4e3276fd23181e0afb78b63b91cecc3f"
#endif
#ifndef WINE_SUPERVISOR_NTDLL_SHA256
#define WINE_SUPERVISOR_NTDLL_SHA256 \
    "4239686f121164bd3d96f8a44db81eb708ac9e30fe783c53d0291d93dbade42c"
#endif

struct wine_sha256 {
    uint32_t state[8];
    uint64_t bits;
    unsigned char block[64];
    size_t used;
};

static WINE_SUPERVISOR_UNUSED uint32_t wine_sha_rotr(uint32_t value, unsigned int amount) {
    return (value >> amount) | (value << (32U - amount));
}

static WINE_SUPERVISOR_UNUSED void wine_sha256_init(struct wine_sha256 *sha) {
    static const uint32_t initial[8] = {
        0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
        0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U,
    };
    memcpy(sha->state, initial, sizeof(initial));
    sha->bits = 0U;
    sha->used = 0U;
}

static WINE_SUPERVISOR_UNUSED void wine_sha256_block(struct wine_sha256 *sha,
                                                      const unsigned char *block) {
    static const uint32_t constants[64] = {
        0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U, 0x3956c25bU, 0x59f111f1U,
        0x923f82a4U, 0xab1c5ed5U, 0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
        0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U, 0xe49b69c1U, 0xefbe4786U,
        0x0fc19dc6U, 0x240ca1ccU, 0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
        0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U, 0xc6e00bf3U, 0xd5a79147U,
        0x06ca6351U, 0x14292967U, 0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
        0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U, 0xa2bfe8a1U, 0xa81a664bU,
        0xc24b8b70U, 0xc76c51a3U, 0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
        0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U, 0x391c0cb3U, 0x4ed8aa4aU,
        0x5b9cca4fU, 0x682e6ff3U, 0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
        0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U,
    };
    uint32_t words[64];
    uint32_t a, b, c, d, e, f, g, h;
    for (unsigned int index = 0U; index < 16U; index++) {
        size_t offset = index * 4U;
        words[index] = ((uint32_t)block[offset] << 24U) |
                       ((uint32_t)block[offset + 1U] << 16U) |
                       ((uint32_t)block[offset + 2U] << 8U) | (uint32_t)block[offset + 3U];
    }
    for (unsigned int index = 16U; index < 64U; index++) {
        uint32_t s0 = wine_sha_rotr(words[index - 15U], 7U) ^
                      wine_sha_rotr(words[index - 15U], 18U) ^ (words[index - 15U] >> 3U);
        uint32_t s1 = wine_sha_rotr(words[index - 2U], 17U) ^
                      wine_sha_rotr(words[index - 2U], 19U) ^ (words[index - 2U] >> 10U);
        words[index] = words[index - 16U] + s0 + words[index - 7U] + s1;
    }
    a = sha->state[0]; b = sha->state[1]; c = sha->state[2]; d = sha->state[3];
    e = sha->state[4]; f = sha->state[5]; g = sha->state[6]; h = sha->state[7];
    for (unsigned int index = 0U; index < 64U; index++) {
        uint32_t s1 = wine_sha_rotr(e, 6U) ^ wine_sha_rotr(e, 11U) ^ wine_sha_rotr(e, 25U);
        uint32_t choose = (e & f) ^ (~e & g);
        uint32_t temp1 = h + s1 + choose + constants[index] + words[index];
        uint32_t s0 = wine_sha_rotr(a, 2U) ^ wine_sha_rotr(a, 13U) ^ wine_sha_rotr(a, 22U);
        uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
        uint32_t temp2 = s0 + majority;
        h = g; g = f; f = e; e = d + temp1; d = c; c = b; b = a; a = temp1 + temp2;
    }
    sha->state[0] += a; sha->state[1] += b; sha->state[2] += c; sha->state[3] += d;
    sha->state[4] += e; sha->state[5] += f; sha->state[6] += g; sha->state[7] += h;
}

static WINE_SUPERVISOR_UNUSED void wine_sha256_update(struct wine_sha256 *sha,
                                                       const void *data, size_t length) {
    const unsigned char *bytes = (const unsigned char *)data;
    sha->bits += (uint64_t)length * 8U;
    while (length != 0U) {
        size_t copied = 64U - sha->used;
        if (copied > length) copied = length;
        memcpy(sha->block + sha->used, bytes, copied);
        sha->used += copied; bytes += copied; length -= copied;
        if (sha->used == 64U) {
            wine_sha256_block(sha, sha->block);
            sha->used = 0U;
        }
    }
}

static WINE_SUPERVISOR_UNUSED void wine_sha256_final(struct wine_sha256 *sha,
                                                      unsigned char digest[32]) {
    uint64_t bits = sha->bits;
    sha->block[sha->used++] = 0x80U;
    while (sha->used != 56U) {
        if (sha->used == 64U) {
            wine_sha256_block(sha, sha->block); sha->used = 0U;
        }
        sha->block[sha->used++] = 0U;
    }
    for (unsigned int index = 0U; index < 8U; index++) {
        sha->block[63U - index] = (unsigned char)(bits >> (index * 8U));
    }
    wine_sha256_block(sha, sha->block);
    for (unsigned int index = 0U; index < 8U; index++) {
        digest[index * 4U] = (unsigned char)(sha->state[index] >> 24U);
        digest[index * 4U + 1U] = (unsigned char)(sha->state[index] >> 16U);
        digest[index * 4U + 2U] = (unsigned char)(sha->state[index] >> 8U);
        digest[index * 4U + 3U] = (unsigned char)sha->state[index];
    }
}

struct wine_source_file {
    char path[PATH_MAX];
    int fd;
    dev_t device;
    ino_t inode;
    uint64_t size;
    uid_t uid;
    gid_t gid;
    mode_t mode;
    nlink_t nlink;
    int64_t mtime_sec;
    int64_t mtime_nsec;
    int64_t ctime_sec;
    int64_t ctime_nsec;
    char sha256[65];
};

struct wine_process_identity {
    pid_t pid;
    pid_t ppid;
    uid_t uid[4];
    uint64_t start_ticks;
};

static WINE_SUPERVISOR_UNUSED uint64_t wine_now_ms(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) return 0U;
    return (uint64_t)value.tv_sec * 1000U + (uint64_t)value.tv_nsec / 1000000U;
}

static WINE_SUPERVISOR_UNUSED int wine_path_join(char *out, size_t capacity,
                                                  const char *left, const char *right) {
    int length;
    if (left == NULL || right == NULL || right[0] == '/' || left[0] == '\0') return -1;
    length = snprintf(out, capacity, "%s/%s", left, right);
    return length > 0 && (size_t)length < capacity ? 0 : -1;
}

static WINE_SUPERVISOR_UNUSED int wine_path_is_canonical(const char *path, char *out,
                                                         size_t capacity) {
    char resolved[PATH_MAX];
    char prefix[PATH_MAX];
    struct stat value;
    size_t length;
    if (path == NULL || path[0] != '/' || realpath(path, resolved) == NULL ||
        strcmp(path, resolved) != 0 || strlen(resolved) + 1U > capacity ||
        stat(resolved, &value) != 0) return -1;
    length = strlen(resolved);
    if (length >= sizeof(prefix)) return -1;
    for (size_t index = 1U; index <= length; index++) {
        if (resolved[index] != '/' && resolved[index] != '\0') continue;
        memcpy(prefix, resolved, index); prefix[index] = '\0';
        if (lstat(prefix, &value) != 0 || S_ISLNK(value.st_mode)) return -1;
    }
    strcpy(out, resolved);
    return 0;
}

static WINE_SUPERVISOR_UNUSED int wine_path_below(const char *root, const char *path) {
    size_t length = strlen(root);
    return strncmp(root, path, length) == 0 && path[length] == '/';
}

static WINE_SUPERVISOR_UNUSED int wine_sha256_fd(int fd, char output[65]) {
    struct wine_sha256 sha;
    unsigned char digest[32];
    unsigned char buffer[16384];
    ssize_t count;
    if (lseek(fd, 0, SEEK_SET) < 0) return -1;
    wine_sha256_init(&sha);
    for (;;) {
        count = read(fd, buffer, sizeof(buffer));
        if (count < 0 && errno == EINTR) continue;
        if (count < 0) return -1;
        if (count == 0) break;
        wine_sha256_update(&sha, buffer, (size_t)count);
    }
    wine_sha256_final(&sha, digest);
    for (unsigned int index = 0U; index < 32U; index++) {
        static const char hex[] = "0123456789abcdef";
        output[index * 2U] = hex[digest[index] >> 4U];
        output[index * 2U + 1U] = hex[digest[index] & 15U];
    }
    output[64] = '\0';
    return 0;
}

static WINE_SUPERVISOR_UNUSED int wine_android_immutable_path(const char *path,
                                                              const struct stat *value) {
#ifdef __ANDROID__
    char parent[PATH_MAX];
    struct stat parent_stat;
    char *slash;
    if (path == NULL || value == NULL || (value->st_uid != 0U && value->st_uid != 1000U) ||
        (value->st_mode & 0022U) != 0U || access(path, W_OK) == 0 ||
        strlen(path) + 1U > sizeof(parent)) return -1;
    strcpy(parent, path); slash = strrchr(parent, '/');
    if (slash == NULL) return -1;
    if (slash == parent) slash[1] = '\0'; else *slash = '\0';
    if (stat(parent, &parent_stat) != 0 || !S_ISDIR(parent_stat.st_mode) ||
        (parent_stat.st_uid != 0U && parent_stat.st_uid != 1000U) ||
        (parent_stat.st_mode & 0022U) != 0U || access(parent, W_OK) == 0) return -1;
    return 0;
#else
    (void)path;
    (void)value;
    return 0;
#endif
}

static WINE_SUPERVISOR_UNUSED int wine_platform_linker_path(const char *path) {
    return path != NULL && (strcmp(path, "/system/bin/linker64") == 0 ||
                           strcmp(path, "/apex/com.android.runtime/bin/linker64") == 0);
}

static WINE_SUPERVISOR_UNUSED int wine_android_runtime_path(const char *path) {
    static const char *roots[] = {
        "/data/data/com.wowforever.arm64probe/files/imagefs_shared/proton/proton-11.0-90624-arm64ec",
        "/data/user/0/com.wowforever.arm64probe/files/imagefs_shared/proton/proton-11.0-90624-arm64ec"
    };
    if (path == NULL) return 0;
    for (size_t index = 0; index < sizeof(roots) / sizeof(roots[0]); index++) {
        if (strcmp(path, roots[index]) == 0 || wine_path_below(roots[index], path)) return 1;
    }
    return 0;
}

/* WCP cache authority is a pinned app-owned source, NOT nativeLibraryDir
 * immutability. Both authority classes retain fd/hash/dev/inode checks. */
static WINE_SUPERVISOR_UNUSED int wine_source_permissions(const char *path, const struct stat *value) {
#ifdef __ANDROID__
    if (wine_platform_linker_path(path)) return wine_android_immutable_path(path, value);
    if (!wine_android_runtime_path(path) || geteuid() < 10000U || value == NULL ||
        value->st_uid != geteuid() || value->st_gid != getegid() ||
        value->st_nlink != 1 || (value->st_mode & 0022U) != 0U) return -1;
    char parent[PATH_MAX];
    struct stat parent_stat;
    if (strlen(path) + 1U > sizeof(parent)) return -1;
    strcpy(parent, path);
    char *slash = strrchr(parent, '/');
    if (slash == NULL || slash == parent) return -1;
    *slash = '\0';
    if (stat(parent, &parent_stat) != 0 || !S_ISDIR(parent_stat.st_mode) ||
        parent_stat.st_uid != geteuid() || parent_stat.st_gid != getegid() ||
        /* Installed runtime directories are0771 with the app's private GID.
         * That group is the same authority, unlike a foreign/world writer. */
        (parent_stat.st_mode & 0002U) != 0U) return -1;
    return 0;
#else
    (void)path;
    (void)value;
    return 0;
#endif
}

static WINE_SUPERVISOR_UNUSED int wine_source_stat_matches(const struct stat *value,
                                                           const struct wine_source_file *source) {
    return value != NULL && source != NULL && value->st_size >= 0 &&
           (uint64_t)value->st_size == source->size && value->st_dev == source->device &&
           value->st_ino == source->inode && value->st_uid == source->uid &&
           value->st_gid == source->gid && value->st_mode == source->mode &&
           value->st_nlink == source->nlink && (int64_t)value->st_mtim.tv_sec == source->mtime_sec &&
           (int64_t)value->st_mtim.tv_nsec == source->mtime_nsec &&
           (int64_t)value->st_ctim.tv_sec == source->ctime_sec &&
           (int64_t)value->st_ctim.tv_nsec == source->ctime_nsec;
}

static WINE_SUPERVISOR_UNUSED int wine_open_source(const char *path, const char *expected_hash,
                                                    struct wine_source_file *out) {
    struct stat value, opened, after;
    char canonical[PATH_MAX], canonical_after[PATH_MAX];
    if (path == NULL || expected_hash == NULL || out == NULL || path[0] != '/' ||
        strlen(path) + 1U > sizeof(out->path) || wine_path_is_canonical(path, canonical, sizeof(canonical)) != 0 ||
        strcmp(path, canonical) != 0 || lstat(path, &value) != 0 || !S_ISREG(value.st_mode) ||
        wine_source_permissions(path, &value) != 0) return -1;
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0 || fstat(fd, &opened) != 0 || !S_ISREG(opened.st_mode) ||
        opened.st_size < 0 || opened.st_dev != value.st_dev || opened.st_ino != value.st_ino ||
        opened.st_uid != value.st_uid || opened.st_gid != value.st_gid ||
        opened.st_mode != value.st_mode || opened.st_nlink != value.st_nlink ||
        opened.st_mtim.tv_sec != value.st_mtim.tv_sec || opened.st_mtim.tv_nsec != value.st_mtim.tv_nsec ||
        opened.st_ctim.tv_sec != value.st_ctim.tv_sec || opened.st_ctim.tv_nsec != value.st_ctim.tv_nsec ||
        wine_source_permissions(path, &opened) != 0 || wine_sha256_fd(fd, out->sha256) != 0 ||
        strcmp(out->sha256, expected_hash) != 0 || fstat(fd, &after) != 0 ||
        after.st_size < 0 || after.st_dev != opened.st_dev || after.st_ino != opened.st_ino ||
        after.st_uid != opened.st_uid || after.st_gid != opened.st_gid ||
        after.st_mode != opened.st_mode || after.st_nlink != opened.st_nlink ||
        after.st_mtim.tv_sec != opened.st_mtim.tv_sec || after.st_mtim.tv_nsec != opened.st_mtim.tv_nsec ||
        after.st_ctim.tv_sec != opened.st_ctim.tv_sec || after.st_ctim.tv_nsec != opened.st_ctim.tv_nsec ||
        wine_path_is_canonical(path, canonical_after, sizeof(canonical_after)) != 0 ||
        strcmp(path, canonical_after) != 0 || lstat(path, &after) != 0 ||
        after.st_size < 0 || after.st_dev != opened.st_dev || after.st_ino != opened.st_ino ||
        after.st_uid != opened.st_uid || after.st_gid != opened.st_gid ||
        after.st_mode != opened.st_mode || after.st_nlink != opened.st_nlink ||
        after.st_mtim.tv_sec != opened.st_mtim.tv_sec || after.st_mtim.tv_nsec != opened.st_mtim.tv_nsec ||
        after.st_ctim.tv_sec != opened.st_ctim.tv_sec || after.st_ctim.tv_nsec != opened.st_ctim.tv_nsec) {
        if (fd >= 0) close(fd);
        return -1;
    }
    memset(out, 0, sizeof(*out));
    strcpy(out->path, canonical); out->fd = fd; out->device = opened.st_dev;
    out->inode = opened.st_ino; out->size = (uint64_t)opened.st_size;
    out->uid = opened.st_uid; out->gid = opened.st_gid; out->mode = opened.st_mode;
    out->nlink = opened.st_nlink; out->mtime_sec = (int64_t)opened.st_mtim.tv_sec;
    out->mtime_nsec = (int64_t)opened.st_mtim.tv_nsec; out->ctime_sec = (int64_t)opened.st_ctim.tv_sec;
    out->ctime_nsec = (int64_t)opened.st_ctim.tv_nsec;
    strcpy(out->sha256, expected_hash);
    return 0;
}

static WINE_SUPERVISOR_UNUSED int wine_source_stable(const struct wine_source_file *source) {
    struct stat path_before, fd_before, fd_after, path_after;
    char canonical[PATH_MAX], canonical_after[PATH_MAX];
    char digest[65];
    if (source == NULL || source->fd < 0 || wine_path_is_canonical(source->path, canonical, sizeof(canonical)) != 0 ||
        strcmp(source->path, canonical) != 0 || lstat(source->path, &path_before) != 0 ||
        !S_ISREG(path_before.st_mode) || !wine_source_stat_matches(&path_before, source) ||
        fstat(source->fd, &fd_before) != 0 || !S_ISREG(fd_before.st_mode) ||
        !wine_source_stat_matches(&fd_before, source) ||
        wine_source_permissions(source->path, &fd_before) != 0 ||
        wine_sha256_fd(source->fd, digest) != 0 || strcmp(digest, source->sha256) != 0 ||
        fstat(source->fd, &fd_after) != 0 || !wine_source_stat_matches(&fd_after, source) ||
        wine_path_is_canonical(source->path, canonical_after, sizeof(canonical_after)) != 0 ||
        strcmp(source->path, canonical_after) != 0 || lstat(source->path, &path_after) != 0 ||
        !S_ISREG(path_after.st_mode) || !wine_source_stat_matches(&path_after, source) ||
        path_after.st_dev != fd_after.st_dev || path_after.st_ino != fd_after.st_ino) return -1;
    return 0;
}

static WINE_SUPERVISOR_UNUSED int wine_decimal(const char *text, uint64_t *value) {
    char *end = NULL;
    unsigned long long number;
    if (text == NULL || *text == '\0') return -1;
    errno = 0; number = strtoull(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0') return -1;
    *value = (uint64_t)number; return 0;
}

static WINE_SUPERVISOR_UNUSED int wine_process_identity(pid_t pid,
                                                         struct wine_process_identity *out) {
    char path[64], data[4096], *close_paren, *cursor, *end;
    uint64_t parent = 0U, ticks = 0U;
    unsigned long uid[4] = {0U, 0U, 0U, 0U};
    int have_uid = 0;
    FILE *stream;
    if (pid <= 0 || snprintf(path, sizeof(path), "/proc/%ld/stat", (long)pid) <= 0) return -1;
    stream = fopen(path, "r"); if (stream == NULL) return -1;
    size_t length = fread(data, 1U, sizeof(data) - 1U, stream);
    int read_error = ferror(stream); fclose(stream);
    if (read_error != 0 || length == 0U) return -1;
    data[length] = '\0'; close_paren = strrchr(data, ')');
    if (close_paren == NULL || close_paren[1] != ' ') return -1;
    cursor = close_paren + 2U;
    for (int field = 3; field <= 22; field++) {
        while (*cursor == ' ') cursor++;
        end = cursor; while (*end != '\0' && *end != ' ') end++;
        if (end == cursor) return -1;
        if (field == 4 || field == 22) {
            char number[32]; size_t number_length = (size_t)(end - cursor);
            if (number_length >= sizeof(number)) return -1;
            memcpy(number, cursor, number_length); number[number_length] = '\0';
            if (wine_decimal(number, field == 4 ? &parent : &ticks) != 0) return -1;
        }
        cursor = end;
    }
    if (parent > (uint64_t)INT_MAX || ticks == 0U ||
        snprintf(path, sizeof(path), "/proc/%ld/status", (long)pid) <= 0) return -1;
    stream = fopen(path, "r"); if (stream == NULL) return -1;
    while (fgets(data, sizeof(data), stream) != NULL) {
        if (strncmp(data, "Uid:", 4U) == 0 &&
            sscanf(data + 4, "%lu %lu %lu %lu", &uid[0], &uid[1], &uid[2], &uid[3]) == 4) {
            have_uid = 1; break;
        }
    }
    read_error = ferror(stream); fclose(stream);
    if (read_error != 0 || !have_uid) return -1;
    for (size_t index = 0U; index < 4U; index++) if (uid[index] > UINT_MAX) return -1;
    out->pid = pid; out->ppid = (pid_t)parent; out->start_ticks = ticks;
    for (size_t index = 0U; index < 4U; index++) out->uid[index] = (uid_t)uid[index];
    return 0;
}

static WINE_SUPERVISOR_UNUSED int wine_identity_same(const struct wine_process_identity *a,
                                                      const struct wine_process_identity *b) {
    return a->pid == b->pid && a->start_ticks == b->start_ticks &&
           a->uid[0] == b->uid[0] && a->uid[1] == b->uid[1] &&
           a->uid[2] == b->uid[2] && a->uid[3] == b->uid[3];
}

static WINE_SUPERVISOR_UNUSED int wine_identity_owned(const struct wine_process_identity *value,
                                                       uid_t owner, pid_t parent) {
    return value != NULL && value->pid > 0 && value->ppid == parent && value->start_ticks != 0U &&
           value->uid[0] == owner && value->uid[1] == owner && value->uid[2] == owner && value->uid[3] == owner;
}

static WINE_SUPERVISOR_UNUSED int wine_read_children(pid_t parent, pid_t output[WINE_SUPERVISOR_MAX_CHILDREN],
                                                     size_t *count) {
    char path[96], data[4096], *cursor, *end;
    FILE *stream;
    if (snprintf(path, sizeof(path), "/proc/self/task/%ld/children", (long)parent) <= 0) return -1;
    stream = fopen(path, "r"); if (stream == NULL) return -1;
    size_t length = fread(data, 1U, sizeof(data) - 1U, stream);
    int read_error = ferror(stream); fclose(stream);
    if (read_error != 0 || length == sizeof(data) - 1U) return -1;
    data[length] = '\0'; cursor = data; *count = 0U;
    while (*cursor != '\0') {
        while (*cursor == ' ' || *cursor == '\n' || *cursor == '\t') cursor++;
        if (*cursor == '\0') break;
        end = cursor; while (*end >= '0' && *end <= '9') end++;
        if (end == cursor || *count == WINE_SUPERVISOR_MAX_CHILDREN) return -1;
        char number[32]; size_t number_length = (size_t)(end - cursor);
        if (number_length >= sizeof(number)) return -1;
        memcpy(number, cursor, number_length); number[number_length] = '\0';
        uint64_t parsed;
        if (wine_decimal(number, &parsed) != 0 || parsed > (uint64_t)INT_MAX) return -1;
        output[(*count)++] = (pid_t)parsed; cursor = end;
    }
    return 0;
}

static WINE_SUPERVISOR_UNUSED int wine_set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    return flags < 0 ? -1 : fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static WINE_SUPERVISOR_UNUSED int wine_poll_wait(int fd, short events, uint64_t deadline) {
    uint64_t now = wine_now_ms();
    int timeout = now >= deadline ? 0 : (int)(deadline - now);
    if (timeout > 100) timeout = 100;
    struct pollfd descriptor = {fd, events, 0};
    int result;
    do { result = poll(&descriptor, 1U, timeout); } while (result < 0 && errno == EINTR);
    return result;
}

static WINE_SUPERVISOR_UNUSED int wine_exit_code(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

static WINE_SUPERVISOR_UNUSED int wine_self_image_guard(uintptr_t address) {
    char line[4096], mapped[PATH_MAX], canonical[PATH_MAX];
    FILE *stream;
    unsigned long long start, end, offset, major_number, minor_number, inode_number;
    char permissions[5];
    int matches = 0;
    stream = fopen("/proc/self/maps", "r");
    if (stream == NULL) return -1;
    while (fgets(line, sizeof(line), stream) != NULL) {
        mapped[0] = '\0';
        int fields = sscanf(line, "%llx-%llx %4s %llx %llx:%llx %llu %4095[^\n]",
                            &start, &end, permissions, &offset, &major_number, &minor_number,
                            &inode_number, mapped);
        if (fields < 8 || address < (uintptr_t)start || address >= (uintptr_t)end) continue;
        if (permissions[0] != 'r' || permissions[2] != 'x' || permissions[1] == 'w' ||
            (permissions[3] != 'p' && permissions[3] != 's') || mapped[0] != '/' ||
            strstr(mapped, " (deleted)") != NULL || realpath(mapped, canonical) == NULL ||
            strcmp(mapped, canonical) != 0 ||
#ifdef SUPERVISOR_HOST_TEST
            strrchr(canonical, '/') == NULL || strcmp(strrchr(canonical, '/') + 1, "wine-supervisor-host") != 0 ||
#else
            strrchr(canonical, '/') == NULL || strcmp(strrchr(canonical, '/') + 1, "libforever_wine_supervisor.so") != 0 ||
#endif
            inode_number == 0U || major_number > UINT_MAX || minor_number > UINT_MAX) {
            fclose(stream); return -1;
        }
        struct stat value;
        if (lstat(canonical, &value) != 0 || !S_ISREG(value.st_mode) ||
            (unsigned long long)major(value.st_dev) != major_number ||
            (unsigned long long)minor(value.st_dev) != minor_number ||
            (unsigned long long)value.st_ino != inode_number || wine_android_immutable_path(canonical, &value) != 0) {
            fclose(stream); return -1;
        }
        matches++;
    }
    int read_error = ferror(stream);
    fclose(stream);
    return read_error == 0 && matches == 1 ? 0 : -1;
}

#endif
