#include "wine_run_supervisor_common.h"

#include <signal.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/un.h>

extern char **environ;

enum ws_phase { WS_PHASE_NONE, WS_PHASE_SERVER, WS_PHASE_CLIENT };
enum ws_command_kind { WS_START, WS_STOP, WS_CLOSE };
enum ws_input_result { WS_INPUT_OK, WS_INPUT_EOF, WS_INPUT_INVALID };
enum ws_wait_result { WS_WAIT_FAILED = -1, WS_WAIT_COMPLETE = 0, WS_WAIT_CANCELLED = 1 };

struct ws_command {
    enum ws_command_kind kind;
    enum ws_phase phase;
    char run_id[64];
    char nonce[64];
    uint64_t sequence;
};

struct ws_child {
    int used;
    int expected;
    int reaped;
    int exit_emitted;
    enum ws_phase phase;
    char role[16];
    struct wine_process_identity identity;
    int status;
};

struct ws_source_set {
    struct wine_source_file linker;
    struct wine_source_file server;
    struct wine_source_file wine;
    struct wine_source_file ntdll;
};

struct ws_log_channel {
    int read_fd;
    int file_fd;
    size_t bytes;
    int eof;
    int fsynced;
    struct stat file_stat;
    char path[PATH_MAX];
};

struct ws_context {
    char run_id[64];
    char nonce[64];
    char runtime_root[PATH_MAX];
    char prefix[PATH_MAX];
    char wineserver_dir[PATH_MAX];
    char lock_path[PATH_MAX];
    char socket_path[PATH_MAX];
    char windows_dll_path[PATH_MAX];
    char home_path[PATH_MAX];
    char tmp_path[PATH_MAX];
    char output_dir_path[PATH_MAX];
    int prefix_fd;
    int home_fd;
    int tmp_fd;
    int output_dir_fd;
    struct stat output_dir_stat;
    struct stat home_stat;
    struct stat tmp_stat;
    struct stat prefix_stat;
    struct stat ready_prefix_stat;
    struct stat socket_stat;
    struct stat lock_stat;
    struct stat ready_socket_stat;
    struct stat ready_lock_stat;
    struct ws_log_channel logs[WS_PHASE_CLIENT + 1][2];
    size_t log_bytes;
    int log_overflow;
    int log_failure;
    struct ws_source_set sources;
    struct wine_process_identity self;
    uid_t owner;
    struct ws_child children[WINE_SUPERVISOR_MAX_CHILDREN];
    size_t child_count;
    ssize_t server_index;
    ssize_t client_index;
    enum ws_phase phase;
    uint64_t operation_deadline;
    uint64_t watchdog_deadline;
    uint64_t event_sequence;
    uint64_t input_sequence;
    size_t output_bytes;
    int server_ready;
    int client_pass;
    int client_finished;
    int failed;
    int output_failed;
    int error_emitted;
    int cleanup_ok;
    int echild;
    int set_result;
    int get_result;
    int subreaper_value;
    int unexpected_server_exit;
    int server_termination_intended;
};

static struct ws_context *current_context;
static int ws_same_stat_identity(const struct stat *left, const struct stat *right);
static int ws_drain_logs(void);
static int ws_logs_eof(void);
static int ws_logs_durable(void);

static int ws_output_failure(void) {
    current_context->output_failed = 1;
    current_context->failed = 1;
    return -1;
}

static int ws_valid_hex(const char *value, size_t length) {
    if (value == NULL || strlen(value) != length) return 0;
    for (size_t index = 0U; index < length; index++) {
        char c = value[index];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return 0;
    }
    return 1;
}

static int ws_install_signal_policy(void) {
    struct sigaction child_action;
    struct sigaction pipe_action;
    if (sigaction(SIGCHLD, NULL, &child_action) != 0 || child_action.sa_handler != SIG_DFL ||
        (child_action.sa_flags & SA_NOCLDWAIT) != 0) return -1;
    memset(&pipe_action, 0, sizeof(pipe_action));
    pipe_action.sa_handler = SIG_IGN;
    sigemptyset(&pipe_action.sa_mask);
    return sigaction(SIGPIPE, &pipe_action, NULL);
}

static int ws_valid_run_id(const char *value) {
    return value != NULL && strlen(value) == 35U && strncmp(value, "WS-", 3U) == 0 &&
           ws_valid_hex(value + 3U, 32U);
}

static const char *ws_phase_name(enum ws_phase phase) {
    if (phase == WS_PHASE_SERVER) return "server";
    if (phase == WS_PHASE_CLIENT) return "client";
    return "none";
}

static int ws_phase_parse(const char *value, enum ws_phase *phase) {
    if (strcmp(value, "none") == 0) *phase = WS_PHASE_NONE;
    else if (strcmp(value, "server") == 0) *phase = WS_PHASE_SERVER;
    else if (strcmp(value, "client") == 0) *phase = WS_PHASE_CLIENT;
    else return -1;
    return 0;
}

static const char *ws_skip_space(const char *cursor) {
    while (*cursor == ' ' || *cursor == '\t' || *cursor == '\r' || *cursor == '\n') cursor++;
    return cursor;
}

static int ws_json_string(const char **cursor, char *destination, size_t capacity) {
    const char *start;
    size_t length;
    if (**cursor != '"') return -1;
    (*cursor)++; start = *cursor;
    while (**cursor != '\0' && **cursor != '"') {
        unsigned char value = (unsigned char)**cursor;
        if (value < 0x20U || value >= 128U || value == '\\') return -1;
        (*cursor)++;
    }
    if (**cursor != '"') return -1;
    length = (size_t)(*cursor - start);
    if (length == 0U || length >= capacity) return -1;
    memcpy(destination, start, length); destination[length] = '\0'; (*cursor)++;
    return 0;
}

static int ws_json_number(const char **cursor, uint64_t *value) {
    char number[32]; size_t length = 0U; const char *start = *cursor;
    while (**cursor >= '0' && **cursor <= '9') {
        if (length + 1U >= sizeof(number)) return -1;
        number[length++] = **cursor; (*cursor)++;
    }
    if (*cursor == start) return -1;
    number[length] = '\0'; return wine_decimal(number, value);
}

static int ws_parse_command(const char *line, struct ws_command *command) {
    const char *cursor = ws_skip_space(line);
    unsigned int fields = 0U;
    memset(command, 0, sizeof(*command)); command->kind = WS_START;
    if (*cursor++ != '{') return -1;
    for (;;) {
        char key[32], string_value[80]; uint64_t number = 0U; unsigned int bit;
        cursor = ws_skip_space(cursor);
        if (*cursor == '}') { cursor++; break; }
        if (ws_json_string(&cursor, key, sizeof(key)) != 0) return -1;
        cursor = ws_skip_space(cursor); if (*cursor++ != ':') return -1;
        if (strcmp(key, "schema") == 0) bit = 1U;
        else if (strcmp(key, "runId") == 0) bit = 2U;
        else if (strcmp(key, "nonce") == 0) bit = 4U;
        else if (strcmp(key, "seq") == 0) bit = 8U;
        else if (strcmp(key, "command") == 0) bit = 16U;
        else if (strcmp(key, "phase") == 0) bit = 32U;
        else return -1;
        if ((fields & bit) != 0U) return -1;
        fields |= bit; cursor = ws_skip_space(cursor);
        if (bit == 2U) {
            if (ws_json_string(&cursor, command->run_id, sizeof(command->run_id)) != 0) return -1;
        } else if (bit == 4U) {
            if (ws_json_string(&cursor, command->nonce, sizeof(command->nonce)) != 0) return -1;
        } else if (bit == 16U || bit == 32U) {
            if (ws_json_string(&cursor, string_value, sizeof(string_value)) != 0) return -1;
            if (bit == 16U) {
                if (strcmp(string_value, "START") == 0) command->kind = WS_START;
                else if (strcmp(string_value, "STOP") == 0) command->kind = WS_STOP;
                else if (strcmp(string_value, "CLOSE") == 0) command->kind = WS_CLOSE;
                else return -1;
            } else if (ws_phase_parse(string_value, &command->phase) != 0) return -1;
        } else {
            if (ws_json_number(&cursor, &number) != 0 || number > UINT64_MAX) return -1;
            if (bit == 1U && number != WINE_SUPERVISOR_SCHEMA) return -1;
            if (bit == 8U) command->sequence = number;
        }
        cursor = ws_skip_space(cursor);
        if (*cursor == ',') { cursor++; continue; }
        if (*cursor == '}') { cursor++; break; }
        return -1;
    }
    cursor = ws_skip_space(cursor);
    if (*cursor != '\0' || fields != 63U || command->sequence == 0U ||
        !ws_valid_run_id(command->run_id) || !ws_valid_hex(command->nonce, 32U)) return -1;
    if (command->kind == WS_START) {
        if (command->phase != WS_PHASE_SERVER && command->phase != WS_PHASE_CLIENT) return -1;
    } else if (command->phase != WS_PHASE_NONE) return -1;
    return 0;
}

static int ws_emit_event(const char *event, enum ws_phase phase, const char *role,
                         const char *status, const struct wine_process_identity *identity,
                         int exit_code, int echild, int set_result, int get_result, int get_value,
                         int include_namespace) {
    struct wine_process_identity self;
    char line[WINE_SUPERVISOR_MAX_LINE];
    const struct wine_process_identity *value = identity;
    int pid = -1, ppid = -1;
    long long ticks = -1;
    int uid0 = -1, uid1 = -1, uid2 = -1, uid3 = -1;
    long long prefix_dev = -1, prefix_ino = -1, socket_dev = -1, socket_ino = -1;
    long long lock_dev = -1, lock_ino = -1;
    size_t live_children = 0U;
    if (value == NULL) {
        if (wine_process_identity(getpid(), &self) != 0) return -1;
        value = &self;
    }
    if (value->pid > 0) {
        pid = (int)value->pid; ppid = (int)value->ppid; ticks = (long long)value->start_ticks;
        uid0 = (int)value->uid[0]; uid1 = (int)value->uid[1];
        uid2 = (int)value->uid[2]; uid3 = (int)value->uid[3];
    }
    if (current_context->prefix_stat.st_dev != 0) {
        prefix_dev = (long long)current_context->prefix_stat.st_dev;
        prefix_ino = (long long)current_context->prefix_stat.st_ino;
    }
    if (include_namespace) {
        socket_dev = (long long)current_context->socket_stat.st_dev;
        socket_ino = (long long)current_context->socket_stat.st_ino;
        lock_dev = (long long)current_context->lock_stat.st_dev;
        lock_ino = (long long)current_context->lock_stat.st_ino;
    }
    for (size_t index = 0U; index < current_context->child_count; index++) {
        if (current_context->children[index].used && !current_context->children[index].reaped) live_children++;
    }
    int length = snprintf(line, sizeof(line),
        "{\"schema\":2,\"runId\":\"%s\",\"nonce\":\"%s\",\"seq\":%llu,"
        "\"event\":\"%s\",\"phase\":\"%s\",\"pid\":%d,\"startTicks\":%lld,"
        "\"ppid\":%d,\"uid0\":%d,\"uid1\":%d,\"uid2\":%d,\"uid3\":%d,"
        "\"role\":\"%s\",\"status\":\"%s\",\"exitCode\":%d,\"childCount\":%zu,"
        "\"echild\":%s,\"setResult\":%d,\"getResult\":%d,\"getValue\":%d,"
        "\"prefixDev\":%lld,\"prefixIno\":%lld,\"socketDev\":%lld,\"socketIno\":%lld,"
        "\"lockDev\":%lld,\"lockIno\":%lld}\n",
        current_context->run_id, current_context->nonce, (unsigned long long)++current_context->event_sequence,
        event, ws_phase_name(phase), pid, ticks, ppid, uid0, uid1, uid2, uid3, role, status,
        exit_code, live_children, echild ? "true" : "false", set_result, get_result,
        get_value, prefix_dev, prefix_ino, socket_dev, socket_ino, lock_dev, lock_ino);
    if (length <= 0 || (size_t)length >= sizeof(line) ||
        current_context->output_bytes > WINE_SUPERVISOR_MAX_OUTPUT - (size_t)length) return ws_output_failure();
    if (wine_set_nonblocking(STDOUT_FILENO) != 0) return ws_output_failure();
    size_t written = 0U;
    while (written < (size_t)length) {
        int ready = wine_poll_wait(STDOUT_FILENO, POLLOUT | POLLERR | POLLHUP,
                                   current_context->watchdog_deadline);
        if (ready <= 0) return ws_output_failure();
        ssize_t count = write(STDOUT_FILENO, line + written, (size_t)length - written);
        if (count < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (count <= 0) return ws_output_failure();
        written += (size_t)count;
    }
    current_context->output_bytes += (size_t)length;
    return 0;
}

static void ws_close_source(struct wine_source_file *source) {
    if (source->fd >= 0) { close(source->fd); source->fd = -1; }
}

static size_t ws_live_children(void) {
    size_t count = 0U;
    for (size_t index = 0U; index < current_context->child_count; index++) {
        if (!current_context->children[index].reaped) count++;
    }
    return count;
}

static int ws_child_index(pid_t pid, const struct wine_process_identity *identity) {
    for (size_t index = 0U; index < current_context->child_count; index++) {
        struct ws_child *child = &current_context->children[index];
        if (child->used && child->identity.pid == pid && wine_identity_same(&child->identity, identity)) {
            return (int)index;
        }
    }
    return -1;
}

static int ws_wait_one(struct ws_child *child) {
    siginfo_t information;
    int status;
    pid_t result;
    memset(&information, 0, sizeof(information));
    if (child->reaped) return 0;
    if (waitid(P_PID, child->identity.pid, &information, WEXITED | WNOHANG | WNOWAIT) != 0) {
        return errno == EINTR ? 0 : -1;
    }
    if (information.si_pid != child->identity.pid) return 0;
    struct wine_process_identity current;
    if (wine_process_identity(child->identity.pid, &current) != 0 ||
        !wine_identity_same(&child->identity, &current) || current.ppid != getpid()) return -1;
    do { result = waitpid(child->identity.pid, &status, WNOHANG); } while (result < 0 && errno == EINTR);
    if (result != child->identity.pid) return result < 0 && errno == ECHILD ? -1 : 0;
    child->status = status; child->reaped = 1;
    return 1;
}

static int ws_emit_exit(struct ws_child *child) {
    if (child->exit_emitted) return 0;
    if (ws_emit_event("EXIT", child->phase, child->role, "REAPED", &child->identity,
                      wine_exit_code(child->status), 0, -1, -1, -1, 0) != 0) return -1;
    child->exit_emitted = 1; return 0;
}

static int ws_reconcile(int *unexpected_exit, int emit_events) {
    pid_t pids[WINE_SUPERVISOR_MAX_CHILDREN]; size_t count;
    (void)ws_drain_logs();
    if (unexpected_exit != NULL) *unexpected_exit = 0;
    if (wine_read_children(getpid(), pids, &count) != 0) return -1;
    for (size_t position = 0U; position < count; position++) {
        struct wine_process_identity identity;
        int identity_result = -1;
        for (unsigned int attempt = 0U; attempt < 20U && identity_result != 0; attempt++) {
            identity_result = wine_process_identity(pids[position], &identity);
            if (identity_result != 0) (void)poll(NULL, 0U, 1);
        }
        if (identity_result != 0 ||
            !wine_identity_owned(&identity, current_context->owner, getpid())) return -1;
        int index = ws_child_index(pids[position], &identity);
        if (index >= 0) continue;
        if (current_context->child_count == WINE_SUPERVISOR_MAX_CHILDREN) return -1;
        struct ws_child *child = &current_context->children[current_context->child_count++];
        memset(child, 0, sizeof(*child)); child->used = 1; child->phase = current_context->phase;
        strcpy(child->role, "adopted"); child->identity = identity; child->status = -1;
        if (emit_events && ws_emit_event("OWNED_ADOPT", child->phase, child->role, "RUNNING", &identity,
                                         -1, 0, -1, -1, -1, 0) != 0 && !current_context->output_failed) return -1;
    }
    for (size_t index = 0U; index < current_context->child_count; index++) {
        struct ws_child *child = &current_context->children[index];
        if (!child->used || child->reaped) continue;
        int result = ws_wait_one(child);
        if (result < 0) return -1;
        if (result > 0) {
            if (emit_events && ws_emit_exit(child) != 0 && !current_context->output_failed) return -1;
            if (child->identity.pid == (current_context->server_index >= 0 ?
                                        current_context->children[current_context->server_index].identity.pid : -1) &&
                current_context->phase != WS_PHASE_NONE && !current_context->server_termination_intended) {
                if (unexpected_exit != NULL) *unexpected_exit = 1;
                current_context->unexpected_server_exit = 1;
            }
        }
    }
    return 0;
}

static int ws_identity_live(const struct ws_child *child) {
    struct wine_process_identity current;
    return child != NULL && !child->reaped && wine_process_identity(child->identity.pid, &current) == 0 &&
           wine_identity_same(&child->identity, &current) && current.ppid == getpid() &&
           wine_identity_owned(&current, current_context->owner, getpid());
}

static int ws_signal_child(struct ws_child *child, int signal_number) {
    if (child->reaped) return 0;
    if (!ws_identity_live(child)) return -1;
    if (current_context->server_index >= 0 &&
        child->identity.pid == current_context->children[current_context->server_index].identity.pid &&
        signal_number == SIGTERM) current_context->server_termination_intended = 1;
    if (kill(child->identity.pid, signal_number) != 0 && errno != ESRCH) return -1;
    return 0;
}

static int ws_probe_echild(void) {
    siginfo_t information;
    memset(&information, 0, sizeof(information));
    if (waitid(P_ALL, 0, &information, WEXITED | WNOHANG | WNOWAIT) != 0) {
        if (errno == EINTR) return 0;
        if (errno == ECHILD) { current_context->echild = 1; return 1; }
        return -1;
    }
    if (information.si_pid != 0) return 0;
    pid_t children[WINE_SUPERVISOR_MAX_CHILDREN]; size_t count = 0U;
    if (wine_read_children(getpid(), children, &count) != 0) return -1;
    (void)count;
    return 0;
}

static int ws_cleanup(void) {
    uint64_t deadline = wine_now_ms() + WINE_SUPERVISOR_CLEANUP_MS;
    if (deadline > current_context->watchdog_deadline) deadline = current_context->watchdog_deadline;
    int ok = 1;
    uint64_t term_deadline = wine_now_ms() + 500U;
    if (term_deadline > deadline) term_deadline = deadline;
    int kill_sent = 0;
    for (;;) {
        int unexpected = 0;
        if (ws_reconcile(&unexpected, current_context->output_failed ? 0 : 1) != 0) { ok = 0; break; }
        size_t live = ws_live_children();
        if (live == 0U) {
            (void)ws_drain_logs();
            if (!ws_logs_eof()) {
                if (wine_now_ms() >= deadline) { ok = 0; break; }
                (void)wine_poll_wait(-1, 0, wine_now_ms() + 25U);
                continue;
            }
            int echild = ws_probe_echild();
            if (echild == 1) {
                if (ws_logs_durable() == 0) break;
                ok = 0;
                break;
            }
            if (echild < 0) { ok = 0; break; }
        } else {
            int signal_number = (!kill_sent && wine_now_ms() >= term_deadline) ? SIGKILL : SIGTERM;
            if (signal_number == SIGKILL) kill_sent = 1;
            for (size_t index = 0U; index < current_context->child_count; index++) {
                struct ws_child *child = &current_context->children[index];
                if (child->used && !child->reaped && ws_signal_child(child, signal_number) != 0) ok = 0;
            }
        }
        if (!ok || wine_now_ms() >= deadline) { ok = 0; break; }
        (void)wine_poll_wait(-1, 0, wine_now_ms() + 25U);
    }
    current_context->cleanup_ok = ok; return ok ? 0 : -1;
}

static int ws_read_command(struct ws_command *command, uint64_t deadline) {
    char line[WINE_SUPERVISOR_MAX_LINE]; size_t length = 0U;
    for (;;) {
        (void)ws_drain_logs();
        if (wine_now_ms() >= deadline) return WS_INPUT_INVALID;
        int ready = wine_poll_wait(STDIN_FILENO, POLLIN | POLLHUP | POLLERR, deadline);
        if (ready < 0) return WS_INPUT_INVALID;
        if (ready == 0) {
            int unexpected = 0;
            if (ws_reconcile(&unexpected, current_context->output_failed ? 0 : 1) != 0 || unexpected != 0) return WS_INPUT_INVALID;
            continue;
        }
        char value; ssize_t count = read(STDIN_FILENO, &value, 1U);
        if (count == 0) return length == 0U ? WS_INPUT_EOF : WS_INPUT_INVALID;
        if (count < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (count < 0 || length + 1U >= sizeof(line)) return WS_INPUT_INVALID;
        line[length++] = value;
        if (value == '\n') {
            line[length] = '\0'; return ws_parse_command(line, command) == 0 ? WS_INPUT_OK : WS_INPUT_INVALID;
        }
    }
}

static int ws_emit_error(void) {
    if (current_context->error_emitted || current_context->output_failed) return -1;
    current_context->error_emitted = 1;
    return ws_emit_event("ERROR", current_context->phase, "supervisor", "UNKNOWN", NULL,
                         -1, 0, -1, -1, -1, 0);
}

static int ws_same_stat_identity(const struct stat *left, const struct stat *right) {
    return left->st_dev == right->st_dev && left->st_ino == right->st_ino &&
           left->st_uid == right->st_uid && left->st_mode == right->st_mode;
}

static int ws_prefix_current(void) {
    struct stat current;
    char canonical[PATH_MAX];
    if (current_context->prefix_fd < 0 || fstat(current_context->prefix_fd, &current) != 0 ||
        !S_ISDIR(current.st_mode) || current.st_uid != current_context->owner ||
        (current.st_mode & 07777U) != 0700U || !ws_same_stat_identity(&current, &current_context->prefix_stat) ||
        realpath(current_context->prefix, canonical) == NULL || strcmp(canonical, current_context->prefix) != 0 ||
        lstat(current_context->prefix, &current) != 0 || !ws_same_stat_identity(&current, &current_context->prefix_stat)) return -1;
    return 0;
}

static int ws_private_dir_current(int fd, const char *path, const struct stat *expected) {
    struct stat current;
    char canonical[PATH_MAX];
    return fd >= 0 && fstat(fd, &current) == 0 && S_ISDIR(current.st_mode) &&
           current.st_uid == current_context->owner && (current.st_mode & 07777U) == 0700U &&
           ws_same_stat_identity(&current, expected) && realpath(path, canonical) != NULL &&
           strcmp(path, canonical) == 0 && lstat(path, &current) == 0 &&
           ws_same_stat_identity(&current, expected) ? 0 : -1;
}

static int ws_logs_current(void) {
    struct stat current;
    char canonical[PATH_MAX];
    if (ws_private_dir_current(current_context->output_dir_fd, current_context->output_dir_path,
                               &current_context->output_dir_stat) != 0) return -1;
    for (int phase = WS_PHASE_SERVER; phase <= WS_PHASE_CLIENT; phase++) {
        for (int stream = 0; stream < 2; stream++) {
            struct ws_log_channel *channel = &current_context->logs[phase][stream];
            if (channel->file_fd < 0 || fstat(channel->file_fd, &current) != 0 || !S_ISREG(current.st_mode) ||
                current.st_uid != current_context->owner || (current.st_mode & 07777U) != 0600U ||
                 current.st_nlink != 1 || !ws_same_stat_identity(&current, &channel->file_stat) ||
                 fstatat(current_context->output_dir_fd, strrchr(channel->path, '/') + 1,
                         &current, AT_SYMLINK_NOFOLLOW) != 0 || current.st_nlink != 1 ||
                 !S_ISREG(current.st_mode) || current.st_uid != current_context->owner ||
                 (current.st_mode & 07777U) != 0600U || realpath(channel->path, canonical) == NULL ||
                strcmp(channel->path, canonical) != 0 || !ws_same_stat_identity(&current, &channel->file_stat)) return -1;
        }
    }
    return 0;
}

static int ws_private_data_current(void) {
    return ws_private_dir_current(current_context->home_fd, current_context->home_path,
                                  &current_context->home_stat) == 0 &&
           ws_private_dir_current(current_context->tmp_fd, current_context->tmp_path,
                                  &current_context->tmp_stat) == 0 && ws_logs_current() == 0 ? 0 : -1;
}

static int ws_create_private_dir(const char *name, char *path, size_t path_capacity,
                                 int *fd_out, struct stat *stat_out) {
    struct stat value;
    char canonical[PATH_MAX];
    if (mkdirat(current_context->prefix_fd, name, 0700) != 0 ||
        fstatat(current_context->prefix_fd, name, &value, AT_SYMLINK_NOFOLLOW) != 0 ||
        !S_ISDIR(value.st_mode) || value.st_uid != current_context->owner ||
        (value.st_mode & 07777U) != 0700U ||
        (*fd_out = openat(current_context->prefix_fd, name, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW)) < 0 ||
        fstat(*fd_out, &value) != 0 || !S_ISDIR(value.st_mode) ||
        value.st_uid != current_context->owner || (value.st_mode & 07777U) != 0700U) {
        return -1;
    }
    if (stat_out != NULL) *stat_out = value;
    if (wine_path_join(path, path_capacity, current_context->prefix, name) != 0 ||
        realpath(path, canonical) == NULL || strcmp(path, canonical) != 0 || lstat(path, &value) != 0 || !S_ISDIR(value.st_mode) ||
        value.st_uid != current_context->owner || (value.st_mode & 07777U) != 0700U) return -1;
    if (fsync(current_context->prefix_fd) != 0) return -1;
    return 0;
}

static int ws_create_private_data(void) {
    /* Check the entire closed name set before the first mutation. */
    static const char *directories[] = {"home", "tmp", ".supervisor-output"};
    struct stat existing;
    for (size_t index = 0; index < sizeof(directories) / sizeof(directories[0]); index++) {
        if (fstatat(current_context->prefix_fd, directories[index], &existing,
                    AT_SYMLINK_NOFOLLOW) == 0 || errno != ENOENT) return -1;
    }
    if (ws_create_private_dir("home", current_context->home_path, sizeof(current_context->home_path),
                             &current_context->home_fd, &current_context->home_stat) != 0 ||
        ws_create_private_dir("tmp", current_context->tmp_path, sizeof(current_context->tmp_path),
                              &current_context->tmp_fd, &current_context->tmp_stat) != 0 ||
        ws_create_private_dir(".supervisor-output", current_context->output_dir_path,
                              sizeof(current_context->output_dir_path), &current_context->output_dir_fd,
                              &current_context->output_dir_stat) != 0) return -1;
    static const char *names[2][2] = {{"server.stdout", "server.stderr"}, {"client.stdout", "client.stderr"}};
    for (int phase = WS_PHASE_SERVER; phase <= WS_PHASE_CLIENT; phase++) {
        for (int stream = 0; stream < 2; stream++) {
            struct ws_log_channel *channel = &current_context->logs[phase][stream];
            if (wine_path_join(channel->path, sizeof(channel->path), current_context->output_dir_path,
                               names[phase - WS_PHASE_SERVER][stream]) != 0 ||
                (channel->file_fd = openat(current_context->output_dir_fd,
                                           names[phase - WS_PHASE_SERVER][stream],
                                           O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600)) < 0 ||
                fstat(channel->file_fd, &channel->file_stat) != 0 || !S_ISREG(channel->file_stat.st_mode) ||
                channel->file_stat.st_uid != current_context->owner ||
                (channel->file_stat.st_mode & 07777U) != 0600U) return -1;
            channel->read_fd = -1;
            channel->eof = 1;
        }
    }
    if (fsync(current_context->output_dir_fd) != 0 || ws_logs_current() != 0) return -1;
    return 0;
}

static struct ws_log_channel *ws_log_channel_for(enum ws_phase phase, int stream) {
    return &current_context->logs[phase][stream];
}

static void ws_log_failure(void) {
    current_context->log_failure = 1;
    current_context->failed = 1;
}

static int ws_write_log(struct ws_log_channel *channel, const unsigned char *data, size_t length) {
    while (length != 0U) {
        ssize_t written = write(channel->file_fd, data, length);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) { ws_log_failure(); return -1; }
        data += (size_t)written; length -= (size_t)written;
    }
    return 0;
}

static int ws_drain_logs(void) {
    unsigned char buffer[4096];
    for (int phase = WS_PHASE_SERVER; phase <= WS_PHASE_CLIENT; phase++) {
        for (int stream = 0; stream < 2; stream++) {
            struct ws_log_channel *channel = ws_log_channel_for((enum ws_phase)phase, stream);
            if (channel->read_fd < 0 || channel->eof) continue;
            size_t budget = WINE_SUPERVISOR_MAX_LOG_STREAM;
            while (budget != 0U) {
                ssize_t count = read(channel->read_fd, buffer, sizeof(buffer));
                if (count < 0 && (errno == EINTR)) continue;
                if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) break;
                if (count < 0) { ws_log_failure(); break; }
                if (count == 0) {
                    channel->eof = 1;
                    if (fsync(channel->file_fd) != 0) ws_log_failure();
                    close(channel->read_fd); channel->read_fd = -1; channel->fsynced = 1;
                    break;
                }
                size_t received = (size_t)count;
                size_t allowed = received;
                if (channel->bytes > WINE_SUPERVISOR_MAX_LOG_STREAM ||
                    current_context->log_bytes > WINE_SUPERVISOR_MAX_OUTPUT ||
                    allowed > WINE_SUPERVISOR_MAX_LOG_STREAM - channel->bytes ||
                    allowed > WINE_SUPERVISOR_MAX_OUTPUT - current_context->log_bytes) {
                    size_t per_stream = channel->bytes >= WINE_SUPERVISOR_MAX_LOG_STREAM ? 0U :
                        WINE_SUPERVISOR_MAX_LOG_STREAM - channel->bytes;
                    size_t combined = current_context->log_bytes >= WINE_SUPERVISOR_MAX_OUTPUT ? 0U :
                        WINE_SUPERVISOR_MAX_OUTPUT - current_context->log_bytes;
                    allowed = per_stream < combined ? per_stream : combined;
                    ws_log_failure(); current_context->log_overflow = 1;
                }
                if (allowed != 0U && ws_write_log(channel, buffer, allowed) == 0) {
                    channel->bytes += allowed; current_context->log_bytes += allowed;
                }
                if (received > allowed) { ws_log_failure(); current_context->log_overflow = 1; }
                budget = budget > received ? budget - received : 0U;
            }
        }
    }
    return 0;
}

static int ws_logs_eof(void) {
    for (int phase = WS_PHASE_SERVER; phase <= WS_PHASE_CLIENT; phase++) {
        for (int stream = 0; stream < 2; stream++) {
            if (!current_context->logs[phase][stream].eof) return 0;
        }
    }
    return 1;
}

static int ws_logs_durable(void) {
    if (!ws_logs_eof() || ws_logs_current() != 0) return -1;
    for (int phase = WS_PHASE_SERVER; phase <= WS_PHASE_CLIENT; phase++) {
        for (int stream = 0; stream < 2; stream++) {
            if (fsync(current_context->logs[phase][stream].file_fd) != 0) return -1;
            current_context->logs[phase][stream].fsynced = 1;
        }
    }
    if (fsync(current_context->output_dir_fd) != 0) return -1;
    return ws_logs_current();
}

static int ws_prefix_footprint_clean(void);

static int ws_prefix_footprint_clean(void) {
    struct stat value;
    char parent[PATH_MAX];
    if (wine_path_join(parent, sizeof(parent), current_context->prefix, ".wineserver") != 0) return -1;
    if (lstat(parent, &value) == 0) return -1;
    return errno == ENOENT ? 0 : -1;
}

static int ws_stat_namespace(void) {
    struct stat directory, lock, socket;
    struct flock requested, held;
    int lock_fd;
    char top[PATH_MAX];
    if (lstat(current_context->wineserver_dir, &directory) != 0 || !S_ISDIR(directory.st_mode) ||
        directory.st_uid != current_context->owner || (directory.st_mode & 07777U) != 0700U ||
        lstat(current_context->lock_path, &lock) != 0 || !S_ISREG(lock.st_mode) ||
        lock.st_uid != current_context->owner || (lock.st_mode & 07777U) != 0600U ||
        lstat(current_context->socket_path, &socket) != 0 || !S_ISSOCK(socket.st_mode) ||
        socket.st_uid != current_context->owner || (socket.st_mode & 07777U) != 0600U ||
        wine_path_join(top, sizeof(top), current_context->prefix, ".wineserver") != 0 ||
        lstat(top, &directory) != 0 || !S_ISDIR(directory.st_mode) || directory.st_uid != current_context->owner ||
        (directory.st_mode & 07777U) != 0700U) return -1;
    lock_fd = open(current_context->lock_path, O_RDWR | O_CLOEXEC | O_NOFOLLOW);
    if (lock_fd < 0) return -1;
    memset(&requested, 0, sizeof(requested)); requested.l_type = F_WRLCK;
    requested.l_whence = SEEK_SET; requested.l_start = 0; requested.l_len = 1;
    memset(&held, 0, sizeof(held)); held = requested;
    int lock_result = fcntl(lock_fd, F_GETLK, &held);
    close(lock_fd);
    if (lock_result != 0 || held.l_type == F_UNLCK || held.l_pid !=
        current_context->children[current_context->server_index].identity.pid) return -1;
    current_context->lock_stat = lock; current_context->socket_stat = socket; return 0;
}

static int ws_validate_prefix_phase(enum ws_phase phase) {
    if (ws_prefix_current() != 0 || ws_private_data_current() != 0) return -1;
    if (phase == WS_PHASE_SERVER) return ws_prefix_footprint_clean();
    if (!current_context->server_ready || ws_stat_namespace() != 0 ||
        !ws_same_stat_identity(&current_context->prefix_stat, &current_context->ready_prefix_stat) ||
        !ws_same_stat_identity(&current_context->socket_stat, &current_context->ready_socket_stat) ||
        !ws_same_stat_identity(&current_context->lock_stat, &current_context->ready_lock_stat)) return -1;
    return 0;
}

static int ws_make_namespace_paths(void) {
    char top[PATH_MAX], name[128];
    if (wine_path_join(top, sizeof(top), current_context->prefix, ".wineserver") != 0 ||
        snprintf(name, sizeof(name), "server-%llx-%llx", (unsigned long long)current_context->prefix_stat.st_dev,
                 (unsigned long long)current_context->prefix_stat.st_ino) <= 0 ||
        wine_path_join(current_context->wineserver_dir, sizeof(current_context->wineserver_dir), top, name) != 0 ||
        wine_path_join(current_context->lock_path, sizeof(current_context->lock_path),
                       current_context->wineserver_dir, "lock") != 0 ||
        wine_path_join(current_context->socket_path, sizeof(current_context->socket_path),
                       current_context->wineserver_dir, "socket") != 0) return -1;
    return 0;
}

static int ws_source_set_stable(void) {
    return wine_source_stable(&current_context->sources.linker) == 0 &&
           wine_source_stable(&current_context->sources.server) == 0 &&
           wine_source_stable(&current_context->sources.wine) == 0 &&
           wine_source_stable(&current_context->sources.ntdll) == 0 ? 0 : -1;
}

static int ws_child_environment(void) {
    char path[PATH_MAX], library_path[PATH_MAX * 2];
    if (clearenv() != 0 || setenv("WINEPREFIX", current_context->prefix, 1) != 0 ||
        setenv("WINELOADER", current_context->sources.wine.path, 1) != 0 ||
        setenv("WINESERVER", current_context->sources.server.path, 1) != 0 ||
        setenv("WINEDLLPATH", current_context->windows_dll_path, 1) != 0 ||
        wine_path_join(path, sizeof(path), current_context->runtime_root, "bin") != 0 ||
        setenv("PATH", path, 1) != 0 ||
        setenv("HOME", current_context->home_path, 1) != 0 ||
        setenv("TMPDIR", current_context->tmp_path, 1) != 0 ||
        snprintf(library_path, sizeof(library_path), "%s/lib/wine/aarch64-unix:%s/lib:/system/lib64",
                 current_context->runtime_root, current_context->runtime_root) <= 0 ||
        setenv("LD_LIBRARY_PATH", library_path, 1) != 0 ||
        unsetenv("WINESERVERSOCKET") != 0 || unsetenv("LD_PRELOAD") != 0) return -1;
    return 0;
}

static void ws_close_standard_handles(int stdout_fd, int stderr_fd) {
    int null_fd = open("/dev/null", O_RDWR | O_CLOEXEC);
    if (null_fd < 0) _exit(126);
    if (dup2(null_fd, STDIN_FILENO) < 0 || dup2(stdout_fd, STDOUT_FILENO) < 0 ||
        dup2(stderr_fd, STDERR_FILENO) < 0) _exit(126);
    if (null_fd > STDERR_FILENO) close(null_fd);
    if (stdout_fd > STDERR_FILENO) close(stdout_fd);
    if (stderr_fd > STDERR_FILENO && stderr_fd != stdout_fd) close(stderr_fd);
}

static void ws_exec_child(enum ws_phase phase, int stdout_fd, int stderr_fd) {
    char *const server_argv[] = {(char *)current_context->sources.linker.path,
        (char *)current_context->sources.server.path, (char *)"-f", (char *)"-p10", NULL};
    char *const client_argv[] = {(char *)current_context->sources.linker.path,
        (char *)current_context->sources.wine.path, (char *)"cmd.exe", (char *)"/c", (char *)"exit", (char *)"0", NULL};
    if (ws_validate_prefix_phase(phase) != 0 || fchdir(current_context->prefix_fd) != 0 ||
        ws_child_environment() != 0 || ws_validate_prefix_phase(phase) != 0 || ws_source_set_stable() != 0) _exit(126);
    ws_close_standard_handles(stdout_fd, stderr_fd);
    if (phase == WS_PHASE_SERVER) execve(current_context->sources.linker.path, server_argv, environ);
    else execve(current_context->sources.linker.path, client_argv, environ);
    _exit(127);
}

static int ws_spawn(enum ws_phase phase) {
    if (current_context->child_count == WINE_SUPERVISOR_MAX_CHILDREN || ws_validate_prefix_phase(phase) != 0 ||
        ws_source_set_stable() != 0) return -1;
    int stdout_pipe[2] = {-1, -1};
    int stderr_pipe[2] = {-1, -1};
    if (pipe2(stdout_pipe, O_CLOEXEC) != 0 || pipe2(stderr_pipe, O_CLOEXEC) != 0) {
        if (stdout_pipe[0] >= 0) { close(stdout_pipe[0]); close(stdout_pipe[1]); }
        if (stderr_pipe[0] >= 0) { close(stderr_pipe[0]); close(stderr_pipe[1]); }
        return -1;
    }
    pid_t child = fork();
    if (child < 0) {
        close(stdout_pipe[0]); close(stdout_pipe[1]); close(stderr_pipe[0]); close(stderr_pipe[1]);
        return -1;
    }
    if (child == 0) {
        close(stdout_pipe[0]); close(stderr_pipe[0]);
        ws_exec_child(phase, stdout_pipe[1], stderr_pipe[1]);
    }
    close(stdout_pipe[1]); close(stderr_pipe[1]);
    struct ws_log_channel *stdout_channel = ws_log_channel_for(phase, 0);
    struct ws_log_channel *stderr_channel = ws_log_channel_for(phase, 1);
    stdout_channel->read_fd = stdout_pipe[0]; stderr_channel->read_fd = stderr_pipe[0];
    stdout_channel->eof = 0; stdout_channel->fsynced = 0;
    stderr_channel->eof = 0; stderr_channel->fsynced = 0;
    if (wine_set_nonblocking(stdout_channel->read_fd) != 0 || wine_set_nonblocking(stderr_channel->read_fd) != 0) return -1;
    struct wine_process_identity identity;
    memset(&identity, 0, sizeof(identity));
    uint64_t end = wine_now_ms() + 500U;
    while (wine_process_identity(child, &identity) != 0 && wine_now_ms() < end) {
        (void)ws_drain_logs();
        (void)poll(NULL, 0U, 1);
    }
    if (!wine_identity_owned(&identity, current_context->owner, getpid())) return -1;
    struct ws_child *record = &current_context->children[current_context->child_count++];
    memset(record, 0, sizeof(*record)); record->used = 1; record->expected = 1;
    record->phase = phase; record->identity = identity; record->status = -1;
    strcpy(record->role, phase == WS_PHASE_SERVER ? "server" : "client");
    if (phase == WS_PHASE_SERVER) current_context->server_index = (ssize_t)(current_context->child_count - 1U);
    else current_context->client_index = (ssize_t)(current_context->child_count - 1U);
    return ws_emit_event("SPAWN", phase, record->role, "RUNNING", &identity, -1, 0, -1, -1, -1, 0);
}

static int ws_wait_busy(enum ws_phase phase) {
    char line[WINE_SUPERVISOR_MAX_LINE];
    size_t length = 0U;
    while (wine_now_ms() < current_context->operation_deadline) {
        int unexpected = 0;
        if (ws_reconcile(&unexpected, current_context->output_failed ? 0 : 1) != 0 || unexpected != 0) return -1;
        if (phase == WS_PHASE_SERVER) {
            if (current_context->server_index < 0 ||
                current_context->children[current_context->server_index].reaped) return WS_WAIT_FAILED;
            if (ws_stat_namespace() == 0) {
                if (length != 0U) return WS_WAIT_FAILED;
                current_context->server_ready = 1;
                current_context->ready_prefix_stat = current_context->prefix_stat;
                current_context->ready_socket_stat = current_context->socket_stat;
                current_context->ready_lock_stat = current_context->lock_stat;
                return ws_emit_event("SERVER_READY", WS_PHASE_SERVER, "server", "RUNNING",
                                     &current_context->children[current_context->server_index].identity,
                                     -1, 0, -1, -1, -1, 1) == 0 ? WS_WAIT_COMPLETE : WS_WAIT_FAILED;
            }
        } else if (current_context->client_index >= 0 &&
                   current_context->children[current_context->client_index].reaped) {
            if (length != 0U) return WS_WAIT_FAILED;
            struct ws_child *client = &current_context->children[current_context->client_index];
            current_context->client_finished = 1;
            current_context->client_pass = WIFEXITED(client->status) && WEXITSTATUS(client->status) == 0;
            if (ws_emit_event("PHASE_DONE", WS_PHASE_CLIENT, "client",
                              current_context->client_pass ? "PASS" : "UNKNOWN", &client->identity,
                              -1, 0, -1, -1, -1, 0) != 0) return WS_WAIT_FAILED;
            return current_context->client_pass ? WS_WAIT_COMPLETE : WS_WAIT_FAILED;
        }
        int ready = wine_poll_wait(STDIN_FILENO, POLLIN | POLLHUP | POLLERR,
                                   current_context->operation_deadline);
        if (ready < 0) return WS_WAIT_FAILED;
        if (ready == 0) continue;
        char value;
        ssize_t count = read(STDIN_FILENO, &value, 1U);
        if (count == 0 || (count < 0 && errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK)) {
            return WS_WAIT_FAILED;
        }
        if (count < 0) continue;
        if (length + 1U >= sizeof(line)) return WS_WAIT_FAILED;
        line[length++] = value;
        if (value == '\n') {
            struct ws_command command;
            line[length] = '\0';
            if (ws_parse_command(line, &command) != 0 ||
                strcmp(command.run_id, current_context->run_id) != 0 ||
                strcmp(command.nonce, current_context->nonce) != 0 ||
                command.sequence != current_context->input_sequence + 1U ||
                command.phase != WS_PHASE_NONE || command.kind == WS_START) return WS_WAIT_FAILED;
            current_context->input_sequence++;
            return WS_WAIT_CANCELLED;
        }
    }
    return WS_WAIT_FAILED;
}

static int ws_preflight(const char *runtime_root, const char *prefix) {
    char path[PATH_MAX], canonical[PATH_MAX], target[PATH_MAX];
    struct stat value;
    if (wine_path_is_canonical(runtime_root, canonical, sizeof(canonical)) != 0 ||
        !S_ISDIR(stat(canonical, &value) == 0 ? value.st_mode : 0) ||
        strlen(canonical) + 1U > sizeof(current_context->runtime_root)) return -1;
#ifndef SUPERVISOR_HOST_TEST
    const char *last = strrchr(canonical, '/');
    const char *runtime_suffix = "/files/imagefs_shared/proton/proton-11.0-90624-arm64ec";
    size_t runtime_suffix_length = strlen(runtime_suffix);
    if (!wine_android_runtime_path(canonical) || last == NULL ||
        strcmp(last + 1, "proton-11.0-90624-arm64ec") != 0 ||
        strlen(canonical) < runtime_suffix_length ||
        strcmp(canonical + strlen(canonical) - runtime_suffix_length, runtime_suffix) != 0) return -1;
#endif
    strcpy(current_context->runtime_root, canonical);
    if (wine_path_is_canonical(prefix, canonical, sizeof(canonical)) != 0 ||
        !S_ISDIR(stat(canonical, &value) == 0 ? value.st_mode : 0) ||
        (value.st_mode & 07777U) != 0700U || value.st_uid != current_context->owner ||
        strlen(canonical) + 1U > sizeof(current_context->prefix)) return -1;
    strcpy(current_context->prefix, canonical); current_context->prefix_stat = value;
#ifndef SUPERVISOR_HOST_TEST
    char expected_prefix[PATH_MAX];
    char expected_alias[PATH_MAX];
    if (snprintf(expected_prefix, sizeof(expected_prefix),
                 "/data/data/com.wowforever.arm64probe/files/forever/wine-controls/%s/prefix/.wine",
                 current_context->run_id) <= 0 ||
        snprintf(expected_alias, sizeof(expected_alias),
                 "/data/user/0/com.wowforever.arm64probe/files/forever/wine-controls/%s/prefix/.wine",
                 current_context->run_id) <= 0) return -1;
    if (strcmp(canonical, expected_prefix) != 0 && strcmp(canonical, expected_alias) != 0) return -1;
#endif
    current_context->prefix_fd = open(current_context->prefix, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (current_context->prefix_fd < 0 || ws_prefix_current() != 0) return -1;
    struct stat windows_dll_stat;
    if (wine_path_join(path, sizeof(path), current_context->runtime_root, "lib/wine/aarch64-windows") != 0 ||
        wine_path_is_canonical(path, current_context->windows_dll_path,
                               sizeof(current_context->windows_dll_path)) != 0 ||
        stat(current_context->windows_dll_path, &windows_dll_stat) != 0 ||
        !S_ISDIR(windows_dll_stat.st_mode) || !wine_path_below(current_context->runtime_root,
                                                                current_context->windows_dll_path)) return -1;
    if (ws_make_namespace_paths() != 0 || ws_prefix_footprint_clean() != 0) return -1;
    if (wine_path_join(path, sizeof(path), current_context->runtime_root, "bin/wineserver") != 0 ||
        wine_open_source(path, WINE_SUPERVISOR_SERVER_SHA256, &current_context->sources.server) != 0 ||
        wine_path_join(path, sizeof(path), current_context->runtime_root, "lib/wine/aarch64-unix/wine") != 0 ||
        wine_open_source(path, WINE_SUPERVISOR_WINE_SHA256, &current_context->sources.wine) != 0 ||
        wine_path_join(path, sizeof(path), current_context->runtime_root, "lib/wine/aarch64-unix/ntdll.so") != 0 ||
        wine_open_source(path, WINE_SUPERVISOR_NTDLL_SHA256, &current_context->sources.ntdll) != 0) return -1;
    if (wine_path_join(path, sizeof(path), current_context->runtime_root, "bin/wine") != 0 ||
        lstat(path, &value) != 0 || !S_ISLNK(value.st_mode) || realpath(path, target) == NULL ||
        strcmp(target, current_context->sources.wine.path) != 0) return -1;
#ifdef SUPERVISOR_HOST_TEST
    if (wine_path_join(path, sizeof(path), current_context->runtime_root, WINE_SUPERVISOR_HOST_LINKER) != 0) return -1;
#else
    strcpy(path, "/system/bin/linker64");
    /* A fixed platform alias is allowed; arbitrary caller-provided loader
     * symlinks are not. API34 resolves this alias into the runtime APEX. */
    if (realpath(path, canonical) == NULL || !wine_platform_linker_path(canonical)) return -1;
    strcpy(path, canonical);
#endif
    if (wine_open_source(path, "", &current_context->sources.linker) != 0) {
        /* The linker is platform-owned and intentionally has no Wine digest pin. */
        struct stat linker_stat;
        if (wine_path_is_canonical(path, canonical, sizeof(canonical)) != 0 ||
            strcmp(path, canonical) != 0 || lstat(path, &linker_stat) != 0 || !S_ISREG(linker_stat.st_mode) ||
            wine_android_immutable_path(path, &linker_stat) != 0) return -1;
        int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0 || fstat(fd, &linker_stat) != 0 || wine_android_immutable_path(path, &linker_stat) != 0) {
            if (fd >= 0) close(fd);
            return -1;
        }
        strcpy(current_context->sources.linker.path, path); current_context->sources.linker.fd = fd;
        current_context->sources.linker.device = linker_stat.st_dev; current_context->sources.linker.inode = linker_stat.st_ino;
        if (wine_sha256_fd(fd, current_context->sources.linker.sha256) != 0) { close(fd); return -1; }
    }
    if (ws_prefix_current() != 0 || ws_prefix_footprint_clean() != 0 ||
        ws_source_set_stable() != 0) return -1;
    return ws_create_private_data();
}

static int ws_finish(int normal_candidate, int cancelled) {
    if (ws_cleanup() != 0) current_context->failed = 1;
    const char *status = (!current_context->failed && current_context->cleanup_ok &&
                          !current_context->unexpected_server_exit &&
                          (cancelled || (normal_candidate && current_context->client_pass))) ?
                         (cancelled ? "CANCELLED" : "PASS") : "UNKNOWN";
    int exit_value = current_context->output_failed ? 2 :
                     (strcmp(status, "PASS") == 0 || strcmp(status, "CANCELLED") == 0 ? 0 : 1);
    int result = ws_emit_event("RUN_DONE", WS_PHASE_NONE, "supervisor", status, NULL, -1,
                               current_context->echild, -1, -1, -1, 0);
    ws_close_source(&current_context->sources.linker);
    ws_close_source(&current_context->sources.server);
    ws_close_source(&current_context->sources.wine);
    ws_close_source(&current_context->sources.ntdll);
    for (int phase = WS_PHASE_SERVER; phase <= WS_PHASE_CLIENT; phase++) {
        for (int stream = 0; stream < 2; stream++) {
            struct ws_log_channel *channel = &current_context->logs[phase][stream];
            if (channel->read_fd >= 0) { close(channel->read_fd); channel->read_fd = -1; }
            if (channel->file_fd >= 0) { close(channel->file_fd); channel->file_fd = -1; }
        }
    }
    if (current_context->output_dir_fd >= 0) { close(current_context->output_dir_fd); current_context->output_dir_fd = -1; }
    if (current_context->home_fd >= 0) { close(current_context->home_fd); current_context->home_fd = -1; }
    if (current_context->tmp_fd >= 0) { close(current_context->tmp_fd); current_context->tmp_fd = -1; }
    if (current_context->prefix_fd >= 0) { close(current_context->prefix_fd); current_context->prefix_fd = -1; }
    if (result != 0 || current_context->output_failed) return 2;
    return exit_value;
}

int main(int argc, char **argv) {
    struct ws_context context;
    memset(&context, 0, sizeof(context)); current_context = &context;
    context.server_index = -1; context.client_index = -1; context.phase = WS_PHASE_NONE;
    context.prefix_fd = context.home_fd = context.tmp_fd = context.output_dir_fd = -1;
    for (int phase = WS_PHASE_SERVER; phase <= WS_PHASE_CLIENT; phase++) {
        for (int stream = 0; stream < 2; stream++) {
            context.logs[phase][stream].read_fd = -1;
            context.logs[phase][stream].file_fd = -1;
            context.logs[phase][stream].eof = 1;
        }
    }
    context.owner = geteuid();
    context.sources.server.fd = context.sources.wine.fd = context.sources.ntdll.fd = context.sources.linker.fd = -1;
    if (argc != 9 || strcmp(argv[1], "--run") != 0 || strcmp(argv[3], "--nonce") != 0 ||
        strcmp(argv[5], "--runtime-root") != 0 || strcmp(argv[7], "--prefix") != 0 ||
        !ws_valid_run_id(argv[2]) || !ws_valid_hex(argv[4], 32U)) return 2;
    strcpy(context.run_id, argv[2]); strcpy(context.nonce, argv[4]);
    if (ws_install_signal_policy() != 0) return 2;
#ifndef SUPERVISOR_HOST_TEST
    if (wine_self_image_guard((uintptr_t)(void *)&main) != 0) return 2;
#endif
    if (ws_preflight(argv[6], argv[8]) != 0) return 2;
    if (wine_process_identity(getpid(), &context.self) != 0 ||
        prctl(PR_SET_CHILD_SUBREAPER, 1L, 0L, 0L, 0L) != 0 ||
        prctl(PR_GET_CHILD_SUBREAPER, &context.subreaper_value, 0L, 0L, 0L) != 0 ||
        context.subreaper_value != 1 || wine_process_identity(getpid(), &context.self) != 0 ||
        wine_set_nonblocking(STDIN_FILENO) != 0 || wine_set_nonblocking(STDOUT_FILENO) != 0) return 2;
    context.set_result = 0; context.get_result = 0;
    context.watchdog_deadline = wine_now_ms() + WINE_SUPERVISOR_WATCHDOG_MS;
    context.operation_deadline = wine_now_ms() + WINE_SUPERVISOR_OPERATION_MS;
    if (ws_emit_event("READY", WS_PHASE_NONE, "supervisor", "READY", &context.self, -1, 0,
                      context.set_result, context.get_result, context.subreaper_value, 0) != 0) return 2;
    for (;;) {
        struct ws_command command;
        int input = ws_read_command(&command, context.operation_deadline);
        if (input != WS_INPUT_OK || strcmp(command.run_id, context.run_id) != 0 ||
            strcmp(command.nonce, context.nonce) != 0 || command.sequence != ++context.input_sequence) {
            (void)ws_emit_error(); context.failed = 1; return ws_finish(0, 0);
        }
        if (command.kind == WS_START) {
            if ((command.phase == WS_PHASE_SERVER && context.phase != WS_PHASE_NONE) ||
                (command.phase == WS_PHASE_CLIENT && (!context.server_ready || context.phase != WS_PHASE_SERVER))) {
                (void)ws_emit_error(); context.failed = 1; return ws_finish(0, 0);
            }
            context.phase = command.phase;
            if (ws_spawn(command.phase) != 0) { (void)ws_emit_error(); context.failed = 1; return ws_finish(0, 0); }
            if (command.phase == WS_PHASE_SERVER) {
                int wait_result = ws_wait_busy(WS_PHASE_SERVER);
                if (wait_result == WS_WAIT_CANCELLED) return ws_finish(0, 1);
                if (wait_result != WS_WAIT_COMPLETE) { (void)ws_emit_error(); context.failed = 1; return ws_finish(0, 0); }
            } else {
                int wait_result = ws_wait_busy(WS_PHASE_CLIENT);
                if (wait_result == WS_WAIT_CANCELLED) return ws_finish(0, 1);
                if (wait_result != WS_WAIT_COMPLETE) {
                if (!context.error_emitted) (void)ws_emit_error();
                context.failed = 1;
                return ws_finish(0, 0);
                }
            }
            continue;
        }
        if (command.kind == WS_STOP || command.kind == WS_CLOSE) {
            int cancelled = context.phase == WS_PHASE_NONE ||
                            (context.phase == WS_PHASE_SERVER && !context.client_finished);
            return ws_finish(context.client_finished && context.client_pass, cancelled);
        }
        (void)ws_emit_error(); context.failed = 1; return ws_finish(0, 0);
    }
}
