/*
 * Tiny, graphics-free vtest handshake client.
 *
 * This is deliberately a protocol qualification probe, not a renderer.  It
 * has no dynamic-library or EGL dependencies and never interprets the
 * capability bytes supplied by the server.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define VTEST_UID ((uid_t)10330)
#define SOCKET_NAME "vtest.sock"
#define SERVER_IDENTITY_NAME "server.identity"
#define SOCKET_PATH_LIMIT 107U
#define MAX_CAP_BYTES (256U * 1024U)
#define MAX_IDENTITY_BYTES 128U
#define MAX_PROC_STAT_BYTES (16U * 1024U)
#define STAGE_SECONDS 5U
#define TOTAL_SECONDS 15U
#define MAX_RUN_ID 100U
#if defined(__GNUC__)
#define VTEST_UNUSED __attribute__((unused))
#else
#define VTEST_UNUSED
#endif

struct caps_summary {
    uint32_t bytes;
    uint32_t nonzero_bytes;
    uint64_t fnv1a;
};

struct server_identity {
    pid_t pid;
    uint64_t start_ticks;
};

struct run_state {
    const char *run_id;
    pid_t native_pid;
    uid_t uid;
    pid_t peer_pid;
    uid_t peer_uid;
    struct server_identity expected_server;
    int server_identity_verified;
    uint64_t started_ms;
    const char *stages[16];
    size_t stage_count;
};

static uint64_t monotonic_ms(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        return 0U;
    }
    return ((uint64_t)now.tv_sec * 1000U) + ((uint64_t)now.tv_nsec / 1000000U);
}

static void add_stage(struct run_state *state, const char *stage) {
    if (state->stage_count < (sizeof(state->stages) / sizeof(state->stages[0]))) {
        state->stages[state->stage_count++] = stage;
    }
}

static VTEST_UNUSED int valid_run_id(const char *run_id) {
    size_t length;
    size_t index;

    if (run_id == NULL) {
        return 0;
    }
    length = strnlen(run_id, MAX_RUN_ID + 1U);
    if (length == 0U || length > MAX_RUN_ID ||
        !((run_id[0] >= 'A' && run_id[0] <= 'Z') ||
          (run_id[0] >= 'a' && run_id[0] <= 'z') ||
          (run_id[0] >= '0' && run_id[0] <= '9'))) {
        return 0;
    }
    for (index = 1U; index < length; ++index) {
        const char value = run_id[index];
        if (!((value >= 'A' && value <= 'Z') ||
              (value >= 'a' && value <= 'z') ||
              (value >= '0' && value <= '9') || value == '_' || value == '-')) {
            return 0;
        }
    }
    return 1;
}

static int parse_decimal_token(const uint8_t *data, size_t length, uint64_t maximum,
                               uint64_t *value) {
    size_t index;
    uint64_t parsed = 0U;
    if (length == 0U) {
        return 0;
    }
    for (index = 0U; index < length; ++index) {
        const uint8_t digit = data[index];
        if (digit < (uint8_t)'0' || digit > (uint8_t)'9' ||
            parsed > (maximum - (uint64_t)(digit - (uint8_t)'0')) / 10U) {
            return 0;
        }
        parsed = (parsed * 10U) + (uint64_t)(digit - (uint8_t)'0');
    }
    *value = parsed;
    return 1;
}

static int parse_server_identity_bytes(const uint8_t *data, size_t length,
                                       struct server_identity *identity) {
    size_t separator;
    size_t newline;
    uint64_t pid_value;
    uint64_t ticks_value;

    if (data == NULL || identity == NULL || length == 0U || length > MAX_IDENTITY_BYTES) {
        return 0;
    }
    separator = 0U;
    while (separator < length && data[separator] != (uint8_t)' ') {
        ++separator;
    }
    if (separator == 0U || separator >= length ||
        !parse_decimal_token(data, separator, (uint64_t)INT_MAX, &pid_value) ||
        pid_value == 0U) {
        return 0;
    }
    newline = separator + 1U;
    while (newline < length && data[newline] != (uint8_t)'\n') {
        ++newline;
    }
    if (newline <= separator + 1U || newline + 1U != length ||
        !parse_decimal_token(data + separator + 1U, newline - separator - 1U,
                             UINT64_MAX, &ticks_value) || ticks_value == 0U) {
        return 0;
    }
    identity->pid = (pid_t)pid_value;
    identity->start_ticks = ticks_value;
    return 1;
}

static int parse_proc_stat_bytes(const uint8_t *data, size_t length, pid_t expected_pid,
                                 uint64_t *start_ticks) {
    size_t first_space = 0U;
    size_t closing_paren = length;
    size_t cursor;
    uint64_t observed_pid;
    uint64_t parsed_ticks = 0U;
    size_t field;

    if (data == NULL || start_ticks == NULL || length == 0U ||
        length > MAX_PROC_STAT_BYTES || expected_pid <= 0) {
        return 0;
    }
    while (first_space < length && data[first_space] != (uint8_t)' ') {
        ++first_space;
    }
    if (first_space == 0U || first_space >= length ||
        !parse_decimal_token(data, first_space, (uint64_t)INT_MAX, &observed_pid) ||
        observed_pid != (uint64_t)expected_pid) {
        return 0;
    }
    cursor = length;
    while (cursor > first_space + 1U) {
        --cursor;
        if (data[cursor] == (uint8_t)')' && cursor + 1U < length &&
            data[cursor + 1U] == (uint8_t)' ') {
            closing_paren = cursor;
            break;
        }
    }
    if (closing_paren == length || closing_paren <= first_space + 2U ||
        data[first_space + 1U] != (uint8_t)'(') {
        return 0;
    }
    cursor = closing_paren + 2U;
    for (field = 0U; field <= 19U; ++field) {
        size_t token_start;
        size_t token_end;
        while (cursor < length &&
               (data[cursor] == (uint8_t)' ' || data[cursor] == (uint8_t)'\t' ||
                data[cursor] == (uint8_t)'\n' || data[cursor] == (uint8_t)'\r')) {
            ++cursor;
        }
        token_start = cursor;
        while (cursor < length && data[cursor] != (uint8_t)' ' &&
               data[cursor] != (uint8_t)'\t' && data[cursor] != (uint8_t)'\n' &&
               data[cursor] != (uint8_t)'\r') {
            ++cursor;
        }
        token_end = cursor;
        if (token_start == token_end) {
            return 0;
        }
        if (field == 19U &&
            !parse_decimal_token(data + token_start, token_end - token_start,
                                 UINT64_MAX, &parsed_ticks)) {
            return 0;
        }
    }
    if (parsed_ticks == 0U) {
        return 0;
    }
    *start_ticks = parsed_ticks;
    return 1;
}

static int read_proc_start_ticks(const struct server_identity *identity,
                                 uint64_t *start_ticks) {
    char path[64];
    uint8_t data[MAX_PROC_STAT_BYTES];
    int written;
    int fd = -1;
    int result = 0;
    size_t used = 0U;

    written = snprintf(path, sizeof(path), "/proc/%ld/stat", (long)identity->pid);
    if (written < 0 || (size_t)written >= sizeof(path)) {
        return 0;
    }
    fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        return 0;
    }
    while (used < sizeof(data)) {
        const ssize_t count = read(fd, data + used, sizeof(data) - used);
        if (count > 0) {
            used += (size_t)count;
        } else if (count == 0) {
            break;
        } else if (errno != EINTR) {
            goto done;
        }
    }
    if (used == sizeof(data) || !parse_proc_stat_bytes(data, used, identity->pid, start_ticks)) {
        goto done;
    }
    result = 1;
done:
    if (close(fd) != 0) {
        result = 0;
    }
    return result;
}

static int load_server_identity(struct server_identity *identity) {
    struct stat listed;
    struct stat opened;
    uint8_t data[MAX_IDENTITY_BYTES];
    size_t expected_size;
    size_t used = 0U;
    int fd = -1;
    int result = 0;

    if (lstat(SERVER_IDENTITY_NAME, &listed) != 0 || !S_ISREG(listed.st_mode) ||
        listed.st_uid != getuid() || (listed.st_mode & 07777U) != 0600 ||
        listed.st_size <= 0 || (uintmax_t)listed.st_size > MAX_IDENTITY_BYTES) {
        return 0;
    }
    expected_size = (size_t)listed.st_size;
    fd = open(SERVER_IDENTITY_NAME, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0 || fstat(fd, &opened) != 0 || !S_ISREG(opened.st_mode) ||
        opened.st_uid != getuid() || (opened.st_mode & 07777U) != 0600 ||
        opened.st_dev != listed.st_dev || opened.st_ino != listed.st_ino ||
        opened.st_size != listed.st_size) {
        goto done;
    }
    while (used < expected_size) {
        const ssize_t count = read(fd, data + used, expected_size - used);
        if (count > 0) {
            used += (size_t)count;
        } else if (count == 0) {
            goto done;
        } else if (errno != EINTR) {
            goto done;
        }
    }
    {
        uint8_t extra;
        ssize_t count;
        do {
            count = read(fd, &extra, sizeof(extra));
        } while (count < 0 && errno == EINTR);
        if (count != 0) {
            goto done;
        }
    }
    if (fstat(fd, &opened) != 0 || opened.st_dev != listed.st_dev ||
        opened.st_ino != listed.st_ino || opened.st_size != listed.st_size ||
        !parse_server_identity_bytes(data, used, identity)) {
        goto done;
    }
    result = 1;
done:
    if (fd >= 0 && close(fd) != 0) {
        result = 0;
    }
    return result;
}

static int allowed_cwd(const char *run_id) {
    char cwd[512];
    char expected_user[512];
    char expected_data[512];
    int written_user;
    int written_data;

    if (getcwd(cwd, sizeof(cwd)) == NULL) {
        return 0;
    }
    written_user = snprintf(expected_user, sizeof(expected_user),
                            "/data/user/0/com.wowforever.arm64probe/files/forever/controls/vtest-loader/%s",
                            run_id);
    written_data = snprintf(expected_data, sizeof(expected_data),
                            "/data/data/com.wowforever.arm64probe/files/forever/controls/vtest-loader/%s",
                            run_id);
    if (written_user < 0 || written_data < 0 ||
        (size_t)written_user >= sizeof(expected_user) ||
        (size_t)written_data >= sizeof(expected_data)) {
        return 0;
    }
    /* getcwd is already canonical on Android for the normal /data/data alias. */
    return strcmp(cwd, expected_user) == 0 || strcmp(cwd, expected_data) == 0;
}

static int socket_path_is_owned_socket(uid_t uid) {
    struct stat info;
    if (lstat(SOCKET_NAME, &info) != 0) {
        return 0;
    }
    return S_ISSOCK(info.st_mode) && info.st_uid == uid;
}

static int wait_for_socket(uid_t uid, uint64_t total_deadline_ms) {
    const uint64_t stage_deadline = monotonic_ms() + (STAGE_SECONDS * 1000U);
    uint64_t deadline = stage_deadline < total_deadline_ms ? stage_deadline : total_deadline_ms;

    for (;;) {
        const uint64_t now = monotonic_ms();
        struct timespec pause_for;
        if (socket_path_is_owned_socket(uid)) {
            return 1;
        }
        if (now >= deadline) {
            return 0;
        }
        pause_for.tv_sec = 0;
        pause_for.tv_nsec = 20000000L;
        (void)nanosleep(&pause_for, NULL);
    }
}

static int nonblocking_socket(int fd) {
    const int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) {
        return 0;
    }
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK) == 0;
}

static int wait_fd(int fd, short events, uint64_t total_deadline_ms) {
    const uint64_t stage_deadline = monotonic_ms() + (STAGE_SECONDS * 1000U);
    const uint64_t deadline = stage_deadline < total_deadline_ms ? stage_deadline : total_deadline_ms;

    for (;;) {
        struct pollfd descriptor;
        const uint64_t now = monotonic_ms();
        uint64_t remaining;
        int timeout;
        int result;

        if (now >= deadline) {
            return 0;
        }
        remaining = deadline - now;
        timeout = remaining > 5000U ? 5000 : (int)remaining;
        descriptor.fd = fd;
        descriptor.events = events;
        descriptor.revents = 0;
        result = poll(&descriptor, 1U, timeout);
        if (result > 0) {
            if ((descriptor.revents & (events | POLLERR | POLLHUP | POLLNVAL)) != 0) {
                return (descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) == 0;
            }
        } else if (result == 0) {
            return 0;
        } else if (errno != EINTR) {
            return 0;
        }
    }
}

static int connect_socket(int fd, uint64_t total_deadline_ms) {
    struct sockaddr_un address;
    int connect_result;

    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    if (strlen(SOCKET_NAME) + 1U > sizeof(address.sun_path) ||
        strlen(SOCKET_NAME) + 1U > SOCKET_PATH_LIMIT) {
        return 0;
    }
    (void)strncpy(address.sun_path, SOCKET_NAME, sizeof(address.sun_path) - 1U);
    connect_result = connect(fd, (const struct sockaddr *)&address, sizeof(address));
    if (connect_result == 0) {
        return 1;
    }
    if (errno != EINPROGRESS || !wait_fd(fd, POLLOUT, total_deadline_ms)) {
        return 0;
    }
    {
        int socket_error = 0;
        socklen_t error_length = sizeof(socket_error);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &socket_error, &error_length) != 0 ||
            socket_error != 0) {
            return 0;
        }
    }
    return 1;
}

static int send_all(int fd, const uint8_t *data, size_t length, uint64_t total_deadline_ms) {
    size_t sent = 0U;
    while (sent < length) {
        ssize_t result;
        if (!wait_fd(fd, POLLOUT, total_deadline_ms)) {
            return 0;
        }
        result = send(fd, data + sent, length - sent, MSG_NOSIGNAL);
        if (result > 0) {
            sent += (size_t)result;
        } else if (result < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) {
            continue;
        } else {
            return 0;
        }
    }
    return 1;
}

static int receive_exact(int fd, uint8_t *data, size_t length, uint64_t total_deadline_ms) {
    size_t received = 0U;
    while (received < length) {
        ssize_t result;
        if (!wait_fd(fd, POLLIN, total_deadline_ms)) {
            return 0;
        }
        result = recv(fd, data + received, length - received, 0);
        if (result > 0) {
            received += (size_t)result;
        } else if (result < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) {
            continue;
        } else {
            return 0;
        }
    }
    return 1;
}

static int verify_server_peer(int fd, struct run_state *state) {
    struct ucred credentials;
    socklen_t credentials_length = sizeof(credentials);
    uint64_t observed_ticks;

    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &credentials, &credentials_length) != 0 ||
        credentials.pid <= 0 || credentials.uid != VTEST_UID ||
        credentials.pid != state->expected_server.pid ||
        !read_proc_start_ticks(&state->expected_server, &observed_ticks) ||
        observed_ticks != state->expected_server.start_ticks) {
        return 0;
    }
    state->peer_pid = credentials.pid;
    state->peer_uid = credentials.uid;
    return 1;
}

static uint32_t read_le32(const uint8_t *data) {
    return ((uint32_t)data[0]) |
           ((uint32_t)data[1] << 8U) |
           ((uint32_t)data[2] << 16U) |
           ((uint32_t)data[3] << 24U);
}

static void write_le32(uint8_t *data, uint32_t value) {
    data[0] = (uint8_t)(value & 0xffU);
    data[1] = (uint8_t)((value >> 8U) & 0xffU);
    data[2] = (uint8_t)((value >> 16U) & 0xffU);
    data[3] = (uint8_t)((value >> 24U) & 0xffU);
}

#ifdef VTEST_TESTING
static void summarize_caps(const uint8_t *data, size_t length, struct caps_summary *summary) {
    size_t index;
    summary->bytes = (uint32_t)length;
    summary->nonzero_bytes = 0U;
    summary->fnv1a = UINT64_C(14695981039346656037);
    for (index = 0U; index < length; ++index) {
        if (data[index] != 0U) {
            ++summary->nonzero_bytes;
        }
        summary->fnv1a ^= (uint64_t)data[index];
        summary->fnv1a *= UINT64_C(1099511628211);
    }
}
#endif

static int validate_response_header(const uint8_t *header, uint32_t *cap_bytes) {
    const uint32_t encoded_length = read_le32(header);
    const uint32_t command = read_le32(header + 4U);
    if (encoded_length == 0U || command != 1U ||
        encoded_length > (MAX_CAP_BYTES + 1U)) {
        return 0;
    }
    *cap_bytes = encoded_length - 1U;
    return *cap_bytes != 0U;
}

#ifdef VTEST_TESTING
size_t vtest_encode_requests(uint8_t *output, size_t capacity) {
    static const uint8_t name[] = {'v', 't', 'e', 's', 't', '\0'};
    if (capacity < 22U) {
        return 0U;
    }
    write_le32(output, 6U);
    write_le32(output + 4U, 8U);
    memcpy(output + 8U, name, sizeof(name));
    write_le32(output + 14U, 0U);
    write_le32(output + 18U, 1U);
    return 22U;
}

int vtest_parse_response(const uint8_t *response, size_t length, struct caps_summary *summary) {
    uint32_t cap_bytes;
    if (response == NULL || summary == NULL || length < 8U ||
        !validate_response_header(response, &cap_bytes) ||
        length != (size_t)cap_bytes + 8U) {
        return 0;
    }
    summarize_caps(response + 8U, cap_bytes, summary);
    return 1;
}

int vtest_valid_run_id(const char *run_id) {
    return valid_run_id(run_id);
}

int vtest_parse_server_identity(const uint8_t *data, size_t length,
                                struct server_identity *identity) {
    return parse_server_identity_bytes(data, length, identity);
}

int vtest_parse_proc_stat(const uint8_t *data, size_t length, pid_t expected_pid,
                          uint64_t *start_ticks) {
    return parse_proc_stat_bytes(data, length, expected_pid, start_ticks);
}
#endif

static int receive_caps(int fd, uint64_t total_deadline_ms, struct caps_summary *summary) {
    uint8_t header[8];
    uint8_t chunk[4096];
    uint32_t cap_bytes;
    uint32_t remaining;
    uint32_t offset = 0U;

    if (!receive_exact(fd, header, sizeof(header), total_deadline_ms) ||
        !validate_response_header(header, &cap_bytes)) {
        return 0;
    }
    summary->bytes = cap_bytes;
    summary->nonzero_bytes = 0U;
    summary->fnv1a = UINT64_C(14695981039346656037);
    remaining = cap_bytes;
    while (remaining > 0U) {
        const size_t wanted = remaining > sizeof(chunk) ? sizeof(chunk) : (size_t)remaining;
        size_t index;
        if (!receive_exact(fd, chunk, wanted, total_deadline_ms)) {
            return 0;
        }
        for (index = 0U; index < wanted; ++index) {
            if (chunk[index] != 0U) {
                ++summary->nonzero_bytes;
            }
            summary->fnv1a ^= (uint64_t)chunk[index];
            summary->fnv1a *= UINT64_C(1099511628211);
        }
        offset += (uint32_t)wanted;
        remaining -= (uint32_t)wanted;
    }
    return offset == cap_bytes;
}

static int receive_expected_eof(int fd, uint64_t total_deadline_ms) {
    const uint64_t stage_deadline = monotonic_ms() + (STAGE_SECONDS * 1000U);
    const uint64_t deadline = stage_deadline < total_deadline_ms ? stage_deadline : total_deadline_ms;

    for (;;) {
        struct pollfd descriptor;
        const uint64_t now = monotonic_ms();
        uint64_t remaining;
        int timeout;
        int ready;
        uint8_t extra;
        ssize_t received;

        if (now >= deadline) {
            return 0;
        }
        remaining = deadline - now;
        timeout = remaining > 5000U ? 5000 : (int)remaining;
        descriptor.fd = fd;
        descriptor.events = POLLIN | POLLHUP;
        descriptor.revents = 0;
        ready = poll(&descriptor, 1U, timeout);
        if (ready < 0) {
            if (errno == EINTR) {
                continue;
            }
            return 0;
        }
        if (ready == 0) {
            return 0;
        }
        if ((descriptor.revents & (POLLIN | POLLHUP | POLLERR | POLLNVAL)) == 0) {
            continue;
        }
        if ((descriptor.revents & (POLLERR | POLLNVAL)) != 0) {
            return 0;
        }
        received = recv(fd, &extra, sizeof(extra), 0);
        if (received == 0) {
            return 1;
        }
        if (received > 0) {
            return 0;
        }
        if (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) {
            return 0;
        }
    }
}

static void emit_event(const struct run_state *state, const char *status,
                       const struct caps_summary *summary) {
    const uint64_t finished_ms = monotonic_ms();
    const uint64_t duration = finished_ms >= state->started_ms ?
                              finished_ms - state->started_ms : 0U;
    size_t index;
    const uint32_t cap_bytes = summary != NULL ? summary->bytes : 0U;
    const uint32_t nonzero = summary != NULL ? summary->nonzero_bytes : 0U;
    const uint64_t hash = summary != NULL ? summary->fnv1a : 0U;

    (void)printf("{\"runId\":\"%s\",\"status\":\"%s\",\"nativePid\":%ld,\"uid\":%lu,\"peerPid\":%ld,\"peerUid\":%lu,\"expectedServerPid\":%ld,\"expectedServerStartTicks\":%" PRIu64 ",\"serverIdentityVerified\":%s,\"stages\":[",
                 state->run_id, status, (long)state->native_pid,
                 (unsigned long)state->uid, (long)state->peer_pid,
                 (unsigned long)state->peer_uid, (long)state->expected_server.pid,
                 state->expected_server.start_ticks,
                 state->server_identity_verified ? "true" : "false");
    for (index = 0U; index < state->stage_count; ++index) {
        (void)printf("%s\"%s\"", index == 0U ? "" : ",", state->stages[index]);
    }
    (void)printf("],\"capBytes\":%" PRIu32 ",\"capNonzeroBytes\":%" PRIu32
                 ",\"capsFNVhash\":\"%016" PRIx64 "\",\"durationMs\":%" PRIu64
                 ",\"egl\":false,\"gpu\":false,\"triangle\":false,\"device\":false,\"hardware\":false,\"present\":false,\"wowPass\":false,\"render\":false}\n",
                 cap_bytes, nonzero, hash, duration);
    (void)fflush(stdout);
}

static VTEST_UNUSED int run_handshake(struct run_state *state) {
    int fd = -1;
    int success = 0;
    int cleanup_ok = 1;
    const uint64_t total_deadline = state->started_ms + (TOTAL_SECONDS * 1000U);
    struct caps_summary summary;
    uint8_t request_one[14];
    uint8_t request_two[8];
    static const uint8_t name[] = {'v', 't', 'e', 's', 't', '\0'};

    if (getuid() != VTEST_UID) {
        add_stage(state, "uid_rejected");
        goto finish;
    }
    add_stage(state, "uid_verified");
    if (!allowed_cwd(state->run_id)) {
        add_stage(state, "cwd_rejected");
        goto finish;
    }
    add_stage(state, "cwd_verified");
    if (!load_server_identity(&state->expected_server)) {
        add_stage(state, "server_identity_rejected");
        goto finish;
    }
    add_stage(state, "server_identity_loaded");
    if (!wait_for_socket(VTEST_UID, total_deadline)) {
        add_stage(state, "socket_timeout");
        goto finish;
    }
    add_stage(state, "socket_verified");

    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0 || !nonblocking_socket(fd)) {
        add_stage(state, "socket_open_failed");
        goto finish;
    }
    if (!connect_socket(fd, total_deadline)) {
        add_stage(state, "connect_failed");
        goto finish;
    }
    add_stage(state, "connected");
    if (!verify_server_peer(fd, state)) {
        add_stage(state, "peer_rejected");
        goto finish;
    }
    add_stage(state, "peer_verified");
    state->server_identity_verified = 1;
    add_stage(state, "server_identity_verified");

    write_le32(request_one, 6U);
    write_le32(request_one + 4U, 8U);
    memcpy(request_one + 8U, name, sizeof(name));
    write_le32(request_two, 0U);
    write_le32(request_two + 4U, 1U);
    if (!send_all(fd, request_one, sizeof(request_one), total_deadline) ||
        !send_all(fd, request_two, sizeof(request_two), total_deadline)) {
        add_stage(state, "request_failed");
        goto finish;
    }
    add_stage(state, "request_sent");
    if (!receive_caps(fd, total_deadline, &summary)) {
        add_stage(state, "response_rejected");
        goto finish;
    }
    if (!verify_server_peer(fd, state)) {
        add_stage(state, "server_identity_rejected");
        goto finish;
    }
    add_stage(state, "server_identity_revalidated");
    if (shutdown(fd, SHUT_WR) != 0) {
        add_stage(state, "shutdown_failed");
        goto finish;
    }
    if (!receive_expected_eof(fd, total_deadline)) {
        add_stage(state, "response_eof_rejected");
        goto finish;
    }
    add_stage(state, "response_eofverified");
    success = 1;

finish:
    if (fd >= 0 && close(fd) != 0) {
        cleanup_ok = 0;
    }
    if (success && cleanup_ok) {
        add_stage(state, "capabilities_received");
    }
    add_stage(state, "cleanup");
    if (!cleanup_ok) {
        success = 0;
    }
    if (success) {
        emit_event(state, "CAPABILITIES_RECEIVED", &summary);
    } else {
        emit_event(state, "UNKNOWN", NULL);
    }
    return success;
}

#ifndef VTEST_TESTING
int main(int argc, char **argv) {
    struct run_state state;
    const char *run_id;

    if (argc != 3 || strcmp(argv[1], "--run-id") != 0 || !valid_run_id(argv[2])) {
        return 2;
    }
    run_id = argv[2];
    memset(&state, 0, sizeof(state));
    state.run_id = run_id;
    state.native_pid = getpid();
    state.uid = getuid();
    state.started_ms = monotonic_ms();
    if (state.started_ms == 0U) {
        add_stage(&state, "clock_unavailable");
        emit_event(&state, "UNKNOWN", NULL);
        return 1;
    }
    return run_handshake(&state) ? 0 : 1;
}
#endif
