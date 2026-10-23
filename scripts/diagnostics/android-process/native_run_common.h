#ifndef WOWFOREVER_NATIVE_RUN_COMMON_H
#define WOWFOREVER_NATIVE_RUN_COMMON_H

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

#define NATIVE_RUN_SCHEMA 1
#define NATIVE_RUN_OPERATION_MS 15000ULL
#define NATIVE_RUN_CLEANUP_MS 5000ULL
#define NATIVE_RUN_FIXTURE_TTL_SECONDS 35U
#define NATIVE_RUN_MAX_LINE 8192U
#define NATIVE_RUN_MAX_STREAM 65536U
#define NATIVE_RUN_MAX_MAP_LINE 4096U
#define NATIVE_RUN_MAX_MAP_BYTES (4U * 1024U * 1024U)
#define NATIVE_RUN_UNUSED __attribute__((unused))

#define NATIVE_RUN_SUPERVISOR_NAME "libforever_run_supervisor.so"
#define NATIVE_RUN_FIXTURE_NAME "libforever_run_fixture.so"

struct native_run_identity {
    int32_t pid;
    int32_t ppid;
    uint32_t uid[4];
    uint64_t start_ticks;
};

static NATIVE_RUN_UNUSED uint64_t native_run_now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0U;
    }
    return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)ts.tv_nsec / 1000000ULL;
}

static NATIVE_RUN_UNUSED uint64_t native_run_min_deadline(uint64_t requested, uint64_t ceiling) {
    return ceiling != 0U && requested > ceiling ? ceiling : requested;
}

static NATIVE_RUN_UNUSED int native_run_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    return flags < 0 ? -1 : fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static NATIVE_RUN_UNUSED int native_run_decimal(const char *text, uint64_t *value) {
    char *end = NULL;
    unsigned long long parsed;
    if (text == NULL || *text == '\0') {
        return -1;
    }
    errno = 0;
    parsed = strtoull(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0') {
        return -1;
    }
    *value = (uint64_t)parsed;
    return 0;
}

static NATIVE_RUN_UNUSED int native_run_process_identity(pid_t pid, struct native_run_identity *out) {
    char path[64];
    char data[4096];
    char *close_paren;
    char *cursor;
    char *end;
    uint64_t ppid_value = 0U;
    uint64_t start_ticks = 0U;
    unsigned long uid_values[4] = {0U, 0U, 0U, 0U};
    int have_uids = 0;
    FILE *stream;

    if (pid <= 0 || snprintf(path, sizeof(path), "/proc/%ld/stat", (long)pid) < 0) {
        return -1;
    }
    stream = fopen(path, "r");
    if (stream == NULL) {
        return -1;
    }
    size_t length = fread(data, 1U, sizeof(data) - 1U, stream);
    int read_error = ferror(stream);
    (void)fclose(stream);
    if (read_error != 0 || length == 0U) {
        return -1;
    }
    data[length] = '\0';
    close_paren = strrchr(data, ')');
    if (close_paren == NULL || close_paren[1] != ' ') {
        return -1;
    }
    cursor = close_paren + 2;
    for (int field = 3; field <= 22; field++) {
        while (*cursor == ' ') {
            cursor++;
        }
        end = cursor;
        while (*end != '\0' && *end != ' ') {
            end++;
        }
        if (end == cursor) {
            return -1;
        }
        if (field == 4 || field == 22) {
            char number[32];
            size_t number_length = (size_t)(end - cursor);
            if (number_length >= sizeof(number)) {
                return -1;
            }
            memcpy(number, cursor, number_length);
            number[number_length] = '\0';
            if (native_run_decimal(number, field == 4 ? &ppid_value : &start_ticks) != 0) {
                return -1;
            }
        }
        cursor = end;
    }
    if (ppid_value > INT32_MAX || start_ticks == 0U) {
        return -1;
    }

    if (snprintf(path, sizeof(path), "/proc/%ld/status", (long)pid) < 0) {
        return -1;
    }
    stream = fopen(path, "r");
    if (stream == NULL) {
        return -1;
    }
    while (fgets(data, sizeof(data), stream) != NULL) {
        if (strncmp(data, "Uid:", 4U) == 0 &&
            sscanf(data + 4, "%lu %lu %lu %lu", &uid_values[0], &uid_values[1],
                   &uid_values[2], &uid_values[3]) == 4) {
            have_uids = 1;
            break;
        }
    }
    read_error = ferror(stream);
    (void)fclose(stream);
    if (read_error != 0 || !have_uids) {
        return -1;
    }
    for (size_t index = 0U; index < 4U; index++) {
        if (uid_values[index] > UINT32_MAX) {
            return -1;
        }
        out->uid[index] = (uint32_t)uid_values[index];
    }
    out->pid = (int32_t)pid;
    out->ppid = (int32_t)ppid_value;
    out->start_ticks = start_ticks;
    return 0;
}

static NATIVE_RUN_UNUSED int native_run_identity_same_process(const struct native_run_identity *a,
                                            const struct native_run_identity *b) {
    return a->pid == b->pid && a->start_ticks == b->start_ticks &&
           a->uid[0] == b->uid[0] && a->uid[1] == b->uid[1] &&
           a->uid[2] == b->uid[2] && a->uid[3] == b->uid[3];
}

static NATIVE_RUN_UNUSED int native_run_identity_equal(const struct native_run_identity *a,
                                     const struct native_run_identity *b) {
    return native_run_identity_same_process(a, b) && a->ppid == b->ppid;
}

static NATIVE_RUN_UNUSED int native_run_identity_owned(const struct native_run_identity *identity, uid_t uid) {
    return identity->pid > 0 && identity->ppid > 0 && identity->start_ticks > 0U &&
           identity->uid[0] == (uint32_t)uid && identity->uid[1] == (uint32_t)uid &&
           identity->uid[2] == (uint32_t)uid && identity->uid[3] == (uint32_t)uid;
}

static NATIVE_RUN_UNUSED int native_run_pipe(int pipefd[2]) {
#ifdef O_CLOEXEC
    if (pipe2(pipefd, O_CLOEXEC) == 0) {
        return 0;
    }
#endif
    return pipe(pipefd);
}

static NATIVE_RUN_UNUSED int native_run_write_full(int fd, const void *buffer, size_t length, uint64_t deadline,
                                 uint64_t ceiling, size_t *written) {
    const unsigned char *bytes = (const unsigned char *)buffer;
    while (length != 0U) {
        uint64_t now = native_run_now_ms();
        uint64_t bounded = native_run_min_deadline(deadline, ceiling);
        int timeout = now >= bounded ? 0 : (int)(bounded - now);
        struct pollfd pfd = {fd, POLLOUT | POLLERR | POLLHUP, 0};
        int ready = poll(&pfd, 1U, timeout);
        if (ready <= 0) {
            errno = ready == 0 ? ETIMEDOUT : errno;
            return -1;
        }
        ssize_t count = write(fd, bytes, length);
        if (count < 0) {
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) {
                continue;
            }
            return -1;
        }
        if (count == 0) {
            errno = EPIPE;
            return -1;
        }
        bytes += (size_t)count;
        length -= (size_t)count;
        if (written != NULL) {
            *written += (size_t)count;
            if (*written > NATIVE_RUN_MAX_STREAM) {
                errno = EOVERFLOW;
                return -1;
            }
        }
    }
    return 0;
}

static NATIVE_RUN_UNUSED int native_run_read_full(int fd, void *buffer, size_t length, uint64_t deadline,
                                uint64_t ceiling) {
    unsigned char *bytes = (unsigned char *)buffer;
    while (length != 0U) {
        uint64_t now = native_run_now_ms();
        uint64_t bounded = native_run_min_deadline(deadline, ceiling);
        int timeout = now >= bounded ? 0 : (int)(bounded - now);
        struct pollfd pfd = {fd, POLLIN | POLLERR | POLLHUP, 0};
        int ready = poll(&pfd, 1U, timeout);
        if (ready <= 0) {
            errno = ready == 0 ? ETIMEDOUT : errno;
            return -1;
        }
        ssize_t count = read(fd, bytes, length);
        if (count < 0) {
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) {
                continue;
            }
            return -1;
        }
        if (count == 0) {
            errno = EPIPE;
            return -1;
        }
        bytes += (size_t)count;
        length -= (size_t)count;
    }
    return 0;
}

static NATIVE_RUN_UNUSED int native_run_wait_exact(pid_t pid, int *status, uint64_t deadline, int *reaped) {
    *reaped = 0;
    *status = -1;
    for (;;) {
        pid_t result = waitpid(pid, status, WNOHANG);
        if (result == pid) {
            *reaped = 1;
            return 0;
        }
        if (result < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        if (native_run_now_ms() >= deadline) {
            errno = ETIMEDOUT;
            return -1;
        }
        (void)poll(NULL, 0U, 10);
    }
}

static NATIVE_RUN_UNUSED int native_run_exit_code(int status) {
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return -1;
}

static NATIVE_RUN_UNUSED int native_run_valid_hex(const char *value, size_t length) {
    if (value == NULL || strlen(value) != length) {
        return 0;
    }
    for (size_t index = 0U; index < length; index++) {
        char c = value[index];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            return 0;
        }
    }
    return 1;
}

static NATIVE_RUN_UNUSED int native_run_valid_id(const char *run_id, const char *prefix) {
    size_t prefix_length = strlen(prefix);
    return run_id != NULL && strlen(run_id) == prefix_length + 32U &&
           strncmp(run_id, prefix, prefix_length) == 0 &&
           native_run_valid_hex(run_id + prefix_length, 32U);
}

struct native_run_loaded_image {
    char path[PATH_MAX];
    dev_t device;
    ino_t inode;
};

static NATIVE_RUN_UNUSED int native_run_path_basename(const char *path, const char *name) {
    const char *slash = strrchr(path, '/');
    return slash != NULL && strcmp(slash + 1, name) == 0;
}

#ifdef __ANDROID__
static NATIVE_RUN_UNUSED int native_run_android_system_owner(uid_t owner) {
    return owner == (uid_t)0 || owner == (uid_t)1000;
}

static NATIVE_RUN_UNUSED int native_run_android_immutable_path(const char *path,
                                                                const struct stat *file_stat) {
    char parent[PATH_MAX];
    struct stat parent_stat;
    char *slash;
    if (path == NULL || file_stat == NULL || !native_run_android_system_owner(file_stat->st_uid) ||
        (file_stat->st_mode & 0022U) != 0U || access(path, W_OK) == 0 ||
        strlen(path) + 1U > sizeof(parent)) {
        return -1;
    }
    (void)strcpy(parent, path);
    slash = strrchr(parent, '/');
    if (slash == NULL) {
        return -1;
    }
    if (slash == parent) {
        slash[1] = '\0';
    } else {
        *slash = '\0';
    }
    if (stat(parent, &parent_stat) != 0 || !S_ISDIR(parent_stat.st_mode) ||
        !native_run_android_system_owner(parent_stat.st_uid) ||
        (parent_stat.st_mode & 0022U) != 0U || access(parent, W_OK) == 0) {
        return -1;
    }
    return 0;
}
#endif

static NATIVE_RUN_UNUSED int native_run_regular_canonical(const char *path, char *canonical,
                                                           size_t capacity, struct stat *out) {
    struct stat link_stat;
    struct stat file_stat;
    char resolved[PATH_MAX];
    if (path == NULL || canonical == NULL || capacity == 0U || lstat(path, &link_stat) != 0 ||
        !S_ISREG(link_stat.st_mode) || realpath(path, resolved) == NULL ||
        strlen(resolved) + 1U > capacity || stat(resolved, &file_stat) != 0 ||
        !S_ISREG(file_stat.st_mode)) {
        return -1;
    }
    if (link_stat.st_dev != file_stat.st_dev || link_stat.st_ino != file_stat.st_ino) {
        return -1;
    }
#ifdef __ANDROID__
    if (native_run_android_immutable_path(resolved, &file_stat) != 0) {
        return -1;
    }
#endif
    (void)strcpy(canonical, resolved);
    if (out != NULL) {
        *out = file_stat;
    }
    return 0;
}

struct native_run_fixture_guard {
    char path[PATH_MAX];
    dev_t device;
    ino_t inode;
};

static NATIVE_RUN_UNUSED int native_run_fixture_guard_current(
    const struct native_run_fixture_guard *guard) {
    char current[PATH_MAX];
    struct stat current_stat;
    return guard != NULL && native_run_regular_canonical(guard->path, current, sizeof(current),
                                                         &current_stat) == 0 &&
           strcmp(current, guard->path) == 0 && current_stat.st_dev == guard->device &&
           current_stat.st_ino == guard->inode ? 0 : -1;
}

static NATIVE_RUN_UNUSED int native_run_loaded_image_stream(
    FILE *stream, uintptr_t code_address, struct native_run_loaded_image *out) {
    char line[NATIVE_RUN_MAX_MAP_LINE];
    char mapped_path[PATH_MAX];
    char canonical[PATH_MAX];
    size_t total = 0U;
    unsigned int matches = 0U;
    if (stream == NULL || out == NULL || code_address == 0U) {
        return -1;
    }
    for (;;) {
        size_t length;
        unsigned long long start;
        unsigned long long end;
        unsigned long long offset;
        unsigned long long major_number;
        unsigned long long minor_number;
        unsigned long long inode_number;
        char permissions[5];
        int fields = 0;
        if (fgets(line, sizeof(line), stream) == NULL) {
            break;
        }
        length = strlen(line);
        if (length == 0U || line[length - 1U] != '\n' || length > NATIVE_RUN_MAX_MAP_LINE - 1U ||
            total > NATIVE_RUN_MAX_MAP_BYTES - length) {
            return -1;
        }
        total += length;
        mapped_path[0] = '\0';
        fields = sscanf(line, "%llx-%llx %4s %llx %llx:%llx %llu %4095[^\n]",
                        &start, &end, permissions, &offset, &major_number, &minor_number,
                        &inode_number, mapped_path);
        if (fields < 7 || start >= end || (code_address < (uintptr_t)start) ||
            code_address >= (uintptr_t)end) {
            continue;
        }
        if (fields != 8 || permissions[0] != 'r' || permissions[2] != 'x' ||
            permissions[1] == 'w' || (permissions[3] != 'p' && permissions[3] != 's') ||
            mapped_path[0] != '/' ||
            strstr(mapped_path, " (deleted)") != NULL || inode_number == 0U ||
            major_number > UINT_MAX || minor_number > UINT_MAX || matches != 0U ||
            realpath(mapped_path, canonical) == NULL || strcmp(mapped_path, canonical) != 0 ||
            !native_run_path_basename(canonical, NATIVE_RUN_SUPERVISOR_NAME)) {
            return -1;
        }
        struct stat mapped_stat;
        if (native_run_regular_canonical(canonical, canonical, sizeof(canonical), &mapped_stat) != 0 ||
            (unsigned long long)major(mapped_stat.st_dev) != major_number ||
            (unsigned long long)minor(mapped_stat.st_dev) != minor_number ||
            (unsigned long long)mapped_stat.st_ino != inode_number) {
            return -1;
        }
        if (strlen(canonical) + 1U > sizeof(out->path)) {
            return -1;
        }
        (void)strcpy(out->path, canonical);
        out->device = mapped_stat.st_dev;
        out->inode = mapped_stat.st_ino;
        matches++;
    }
    int read_error = ferror(stream);
    return read_error == 0 && matches == 1U ? 0 : -1;
}

static NATIVE_RUN_UNUSED int native_run_loaded_image(uintptr_t code_address,
                                                      struct native_run_loaded_image *out) {
    FILE *stream = fopen("/proc/self/maps", "r");
    if (stream == NULL) {
        return -1;
    }
    int result = native_run_loaded_image_stream(stream, code_address, out);
    (void)fclose(stream);
    return result;
}

static NATIVE_RUN_UNUSED int native_run_same_parent_fixture(
    const char *fixture, struct native_run_fixture_guard *guard) {
    char fixture_real[PATH_MAX];
    char supervisor_parent[PATH_MAX];
    char fixture_parent[PATH_MAX];
    struct stat fixture_stat;
    struct native_run_loaded_image supervisor;
    uintptr_t code_address = (uintptr_t)(void *)&native_run_same_parent_fixture;
    if (guard == NULL || native_run_loaded_image(code_address, &supervisor) != 0 ||
        native_run_regular_canonical(fixture, fixture_real, sizeof(fixture_real), &fixture_stat) != 0 ||
        !native_run_path_basename(fixture_real, NATIVE_RUN_FIXTURE_NAME) ||
        fixture_stat.st_dev == 0 || fixture_stat.st_ino == 0 ||
        supervisor.device == 0 || supervisor.inode == 0) {
        return -1;
    }
    if (strlen(supervisor.path) + 1U > sizeof(supervisor_parent) ||
        strlen(fixture_real) + 1U > sizeof(fixture_parent)) {
        return -1;
    }
    (void)strcpy(supervisor_parent, supervisor.path);
    (void)strcpy(fixture_parent, fixture_real);
    char *supervisor_slash = strrchr(supervisor_parent, '/');
    char *fixture_slash = strrchr(fixture_parent, '/');
    if (supervisor_slash == NULL || fixture_slash == NULL) {
        return -1;
    }
    *supervisor_slash = '\0';
    *fixture_slash = '\0';
    if (strcmp(fixture_parent, supervisor_parent) != 0 || strlen(fixture_real) + 1U > sizeof(guard->path)) {
        return -1;
    }
    (void)strcpy(guard->path, fixture_real);
    guard->device = fixture_stat.st_dev;
    guard->inode = fixture_stat.st_ino;
    return 0;
}

#endif
