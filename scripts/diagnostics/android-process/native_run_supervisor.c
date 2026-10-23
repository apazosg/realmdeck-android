#include "native_run_common.h"

#include <limits.h>
#include <signal.h>

enum command_kind { COMMAND_START, COMMAND_ACK, COMMAND_STOP, COMMAND_CLOSE };
enum input_result { INPUT_OK, INPUT_EOF, INPUT_INVALID };

struct command {
    enum command_kind kind;
    char run_id[64];
    char nonce[64];
    char scenario[16];
    uint64_t sequence;
    int phase;
    int64_t pid;
    uint64_t start_ticks;
    uint64_t event_sequence;
};

struct child_family {
    int phase;
    pid_t intermediate;
    pid_t grandchild;
    struct native_run_identity intermediate_identity;
    struct native_run_identity birth;
    struct native_run_identity adopted;
    int have_intermediate;
    int have_birth;
    int have_adopted;
    int intermediate_reaped;
    int grandchild_reaped;
    int intermediate_status;
    int grandchild_status;
    int emitted_intermediate_spawn;
    int emitted_grandchild_spawn;
    int emitted_adopt;
    int emitted_intermediate_exit;
    int emitted_grandchild_exit;
    int birth_fd;
    int ack_fd;
    int gate_read_fd;
    int gate_write_fd;
    int release_fd;
};

static uid_t application_uid;
static uint64_t operation_deadline;
static uint64_t operation_ceiling;
static size_t stdout_bytes;
static uint64_t event_sequence;
static uint64_t command_sequence;
static int registered_children;
static char run_id[64];
static char nonce[64];
static struct native_run_identity supervisor_identity;
static struct native_run_fixture_guard fixture_guard;

static int early_failure(const char *stage) {
    (void)fprintf(stderr, "native-run-supervisor: stage=%s\n", stage);
    return 2;
}

static void family_init(struct child_family *family) {
    memset(family, 0, sizeof(*family));
    family->intermediate = -1;
    family->grandchild = -1;
    family->birth_fd = -1;
    family->ack_fd = -1;
    family->gate_read_fd = -1;
    family->gate_write_fd = -1;
    family->release_fd = -1;
    family->intermediate_status = -1;
    family->grandchild_status = -1;
}

static void close_fd(int *fd) {
    if (*fd >= 0) {
        (void)close(*fd);
        *fd = -1;
    }
}

static int json_identifier(const char *value, size_t length) {
    if (length == 0U || length >= 64U) {
        return -1;
    }
    for (size_t index = 0U; index < length; index++) {
        unsigned char c = (unsigned char)value[index];
        if (c >= 128U || !(c == '-' || c == '_' || (c >= '0' && c <= '9') ||
                           (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
            return -1;
        }
    }
    return 0;
}

static const char *json_skip_space(const char *cursor) {
    while (*cursor == ' ' || *cursor == '\t' || *cursor == '\r' || *cursor == '\n') {
        cursor++;
    }
    return cursor;
}

static int json_string(const char **cursor, char *destination, size_t capacity) {
    const char *start;
    size_t length;
    if (**cursor != '"') {
        return -1;
    }
    (*cursor)++;
    start = *cursor;
    while (**cursor != '\0' && **cursor != '"') {
        unsigned char c = (unsigned char)**cursor;
        if (c < 0x20U || c >= 128U || c == '\\') {
            return -1;
        }
        (*cursor)++;
    }
    if (**cursor != '"') {
        return -1;
    }
    length = (size_t)(*cursor - start);
    if (length == 0U || length >= capacity) {
        return -1;
    }
    memcpy(destination, start, length);
    destination[length] = '\0';
    (*cursor)++;
    return 0;
}

static int json_number(const char **cursor, uint64_t *value) {
    char number[32];
    size_t length = 0U;
    const char *start = *cursor;
    while (**cursor >= '0' && **cursor <= '9') {
        if (length + 1U >= sizeof(number)) {
            return -1;
        }
        number[length++] = **cursor;
        (*cursor)++;
    }
    if (*cursor == start || length == 0U) {
        return -1;
    }
    number[length] = '\0';
    return native_run_decimal(number, value);
}

static int parse_command(const char *line, struct command *command) {
    const char *cursor = json_skip_space(line);
    unsigned int fields = 0U;
    memset(command, 0, sizeof(*command));
    if (*cursor++ != '{') {
        return -1;
    }
    for (;;) {
        char key[32];
        char string_value[64];
        uint64_t number = 0U;
        unsigned int bit;
        cursor = json_skip_space(cursor);
        if (*cursor == '}') {
            cursor++;
            break;
        }
        if (json_string(&cursor, key, sizeof(key)) != 0 ||
            json_identifier(key, strlen(key)) != 0) {
            return -1;
        }
        cursor = json_skip_space(cursor);
        if (*cursor++ != ':') {
            return -1;
        }
        if (strcmp(key, "schema") == 0) bit = 1U;
        else if (strcmp(key, "runId") == 0) bit = 2U;
        else if (strcmp(key, "nonce") == 0) bit = 4U;
        else if (strcmp(key, "seq") == 0) bit = 8U;
        else if (strcmp(key, "phase") == 0) bit = 16U;
        else if (strcmp(key, "command") == 0) bit = 32U;
        else if (strcmp(key, "scenario") == 0) bit = 64U;
        else if (strcmp(key, "pid") == 0) bit = 128U;
        else if (strcmp(key, "startTicks") == 0) bit = 256U;
        else if (strcmp(key, "eventSeq") == 0) bit = 512U;
        else return -1;
        if ((fields & bit) != 0U) {
            return -1;
        }
        fields |= bit;
        cursor = json_skip_space(cursor);
        if (bit == 2U) {
            if (json_string(&cursor, command->run_id, sizeof(command->run_id)) != 0) return -1;
        } else if (bit == 4U) {
            if (json_string(&cursor, command->nonce, sizeof(command->nonce)) != 0) return -1;
        } else if (bit == 32U || bit == 64U) {
            if (json_string(&cursor, string_value, sizeof(string_value)) != 0) return -1;
            if (bit == 32U && strcmp(string_value, "START") == 0) command->kind = COMMAND_START;
            else if (bit == 32U && strcmp(string_value, "ACK") == 0) command->kind = COMMAND_ACK;
            else if (bit == 32U && strcmp(string_value, "STOP") == 0) command->kind = COMMAND_STOP;
            else if (bit == 32U && strcmp(string_value, "CLOSE") == 0) command->kind = COMMAND_CLOSE;
            else if (bit == 32U) return -1;
            if (bit == 64U) {
                if (strlen(string_value) >= sizeof(command->scenario)) return -1;
                (void)strcpy(command->scenario, string_value);
            }
        } else {
            if (json_number(&cursor, &number) != 0 || number > INT64_MAX) return -1;
            if (bit == 1U && number != NATIVE_RUN_SCHEMA) return -1;
            if (bit == 8U) command->sequence = number;
            if (bit == 16U) {
                if (number > INT_MAX) return -1;
                command->phase = (int)number;
            }
            if (bit == 128U) command->pid = (int64_t)number;
            if (bit == 256U) command->start_ticks = number;
            if (bit == 512U) command->event_sequence = number;
        }
        cursor = json_skip_space(cursor);
        if (*cursor == ',') {
            cursor++;
            continue;
        }
        if (*cursor == '}') {
            cursor++;
            break;
        }
        return -1;
    }
    cursor = json_skip_space(cursor);
    if (*cursor != '\0' || fields != 1023U || command->sequence == 0U ||
        command->phase < 0 || command->phase > 2 || command->pid < 0 ||
        command->start_ticks > INT64_MAX || command->event_sequence > INT64_MAX) {
        return -1;
    }
    if (!native_run_valid_id(command->run_id, "NS-") ||
        !native_run_valid_hex(command->nonce, 32U)) return -1;
    if (command->kind == COMMAND_START) {
        if (strcmp(command->scenario, "normal") != 0 && strcmp(command->scenario, "hold") != 0) return -1;
        if (command->pid != 0 || command->start_ticks != 0U || command->event_sequence != 0U) return -1;
    } else if (command->kind == COMMAND_ACK) {
        if (strcmp(command->scenario, "none") != 0 || command->pid <= 0 ||
            command->start_ticks == 0U || command->event_sequence == 0U) return -1;
    } else if (command->kind == COMMAND_STOP) {
        if (strcmp(command->scenario, "none") != 0 || command->pid != 0 ||
            command->start_ticks != 0U || command->event_sequence != 0U) return -1;
    } else if (command->kind == COMMAND_CLOSE) {
        if (command->phase != 0 || strcmp(command->scenario, "none") != 0 || command->pid != 0 ||
            command->start_ticks != 0U || command->event_sequence != 0U) return -1;
    }
    return 0;
}

static enum input_result read_command(struct command *command, uint64_t deadline) {
    char line[NATIVE_RUN_MAX_LINE];
    size_t length = 0U;
    for (;;) {
        uint64_t now = native_run_now_ms();
        int timeout = now >= deadline ? 0 : (int)(deadline - now);
        struct pollfd pfd = {STDIN_FILENO, POLLIN | POLLHUP, 0};
        int ready = poll(&pfd, 1U, timeout);
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0) return INPUT_INVALID;
        char c;
        ssize_t count = read(STDIN_FILENO, &c, 1U);
        if (count == 0) return length == 0U ? INPUT_EOF : INPUT_INVALID;
        if (count < 0) {
            if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) continue;
            return INPUT_INVALID;
        }
        if (length + 1U >= sizeof(line)) return INPUT_INVALID;
        line[length++] = c;
        if (c == '\n') {
            line[length] = '\0';
            return parse_command(line, command) == 0 ? INPUT_OK : INPUT_INVALID;
        }
    }
}

static int emit_event(const char *event, int phase, const struct native_run_identity *identity,
                      const char *role, const char *status, int exit_code, int child_count,
                      int echild, int set_result, int get_result, int get_value) {
    char line[NATIVE_RUN_MAX_LINE];
    struct native_run_identity self;
    if (identity == NULL) {
        if (native_run_process_identity(getpid(), &self) != 0) return -1;
        identity = &self;
    }
    int length = snprintf(line, sizeof(line),
                          "{\"schema\":1,\"runId\":\"%s\",\"nonce\":\"%s\",\"seq\":%llu,"
                          "\"phase\":%d,\"event\":\"%s\",\"pid\":%ld,\"startTicks\":%llu,"
                          "\"ppid\":%ld,\"uid0\":%u,\"uid1\":%u,\"uid2\":%u,\"uid3\":%u,"
                          "\"role\":\"%s\",\"status\":\"%s\",\"exitCode\":%d,\"childCount\":%d,"
                          "\"echild\":%s,\"setResult\":%d,\"getResult\":%d,\"getValue\":%d}\n",
                          run_id, nonce, (unsigned long long)++event_sequence, phase, event,
                          (long)identity->pid, (unsigned long long)identity->start_ticks,
                          (long)identity->ppid, identity->uid[0], identity->uid[1], identity->uid[2],
                          identity->uid[3], role, status, exit_code, child_count,
                          echild ? "true" : "false", set_result, get_result, get_value);
    if (length <= 0 || (size_t)length >= sizeof(line)) return -1;
    return native_run_write_full(STDOUT_FILENO, line, (size_t)length,
                                 operation_ceiling, operation_ceiling, &stdout_bytes);
}

static int verify_identity(const struct native_run_identity *wanted, pid_t expected_parent) {
    struct native_run_identity current;
    return native_run_process_identity((pid_t)wanted->pid, &current) == 0 &&
           native_run_identity_equal(wanted, &current) &&
           current.ppid == (int32_t)expected_parent &&
           native_run_identity_owned(&current, application_uid);
}

static int terminate_owned(const struct native_run_identity *wanted, uint64_t deadline, int *status_out) {
    if (status_out != NULL) *status_out = -1;
    /* The snapshot is checked immediately before the signal.  The target is
     * still this supervisor's unreaped direct/adopted child, so this
     * single-threaded supervisor cannot observe PID reuse between the check
     * and kill(2); waitpid is the only reaper and all callers gate on !reaped. */
    if (!verify_identity(wanted, getpid())) return -1;
    if (kill((pid_t)wanted->pid, SIGTERM) != 0 && errno != ESRCH) return -1;
    int status = -1;
    int reaped = 0;
    uint64_t term_deadline = native_run_now_ms() + 500U;
    if (term_deadline > deadline) term_deadline = deadline;
    if (native_run_wait_exact((pid_t)wanted->pid, &status, term_deadline, &reaped) == 0 && reaped) {
        if (status_out != NULL) *status_out = status;
        return 0;
    }
    if (!verify_identity(wanted, getpid())) return -1;
    if (kill((pid_t)wanted->pid, SIGKILL) != 0 && errno != ESRCH) return -1;
    if (native_run_wait_exact((pid_t)wanted->pid, &status, deadline, &reaped) != 0 || !reaped) return -1;
    if (status_out != NULL) *status_out = status;
    return 0;
}

static int wait_echild(uint64_t deadline) {
    int unexpected = 0;
    for (;;) {
        int status = -1;
        pid_t pid = waitpid(-1, &status, WNOHANG);
        if (pid < 0) {
            if (errno == EINTR) continue;
            return errno == ECHILD ? (unexpected ? -1 : 1) : -1;
        }
        if (pid == 0) {
            if (native_run_now_ms() >= deadline) return -1;
            (void)poll(NULL, 0U, 10);
            continue;
        }
        unexpected = 1;
    }
}

static int cleanup_family(struct child_family *family, uint64_t deadline) {
    int ok = 1;
    if (!family->have_birth && family->birth_fd >= 0) {
        /* Release the fixture's ACK gate first.  This makes an intermediate
         * that is still between fork and its birth report observe EOF, while
         * retaining the birth pipe long enough to register the reserved PID. */
        close_fd(&family->ack_fd);
        close_fd(&family->gate_read_fd);
        close_fd(&family->gate_write_fd);
        struct native_run_identity birth;
        uint64_t birth_deadline = native_run_now_ms() + 500U;
        if (birth_deadline > deadline) birth_deadline = deadline;
        if (native_run_read_full(family->birth_fd, &birth, sizeof(birth), birth_deadline,
                                 operation_ceiling) == 0 &&
            native_run_identity_owned(&birth, application_uid) && birth.ppid == (int32_t)family->intermediate &&
            native_run_process_identity((pid_t)birth.pid, &birth) == 0) {
            family->birth = birth;
            family->grandchild = (pid_t)birth.pid;
            family->have_birth = 1;
        } else {
            ok = 0;
        }
    }
    close_fd(&family->birth_fd);
    close_fd(&family->ack_fd);
    close_fd(&family->gate_read_fd);
    close_fd(&family->gate_write_fd);
    if (family->have_intermediate && !family->intermediate_reaped) {
        int status = -1;
        int reaped = 0;
        uint64_t soft = native_run_now_ms() + 500U;
        if (soft > deadline) soft = deadline;
        if (native_run_wait_exact(family->intermediate, &status, soft, &reaped) == 0 && reaped) {
            family->intermediate_reaped = 1;
            family->intermediate_status = status;
        } else if (terminate_owned(&family->intermediate_identity, deadline, &status) == 0) {
            family->intermediate_reaped = 1;
            family->intermediate_status = status;
        } else {
            ok = 0;
        }
    }
    if (family->emitted_intermediate_spawn && family->intermediate_reaped &&
        !family->emitted_intermediate_exit) {
        if (emit_event("EXIT", family->phase, &family->intermediate_identity, "intermediate", "REAPED",
                       native_run_exit_code(family->intermediate_status),
                       registered_children - 1, 0, -1, -1, -1) != 0) {
            ok = 0;
        }
        family->emitted_intermediate_exit = 1;
        registered_children--;
    }
    if (family->have_birth && !family->grandchild_reaped) {
        int status = -1;
        int reaped = 0;
        if (!family->have_adopted) {
            while (native_run_now_ms() < deadline) {
                struct native_run_identity current;
                if (native_run_process_identity(family->grandchild, &current) == 0 &&
                    native_run_identity_same_process(&family->birth, &current) &&
                    current.ppid == (int32_t)getpid() &&
                    native_run_identity_owned(&current, application_uid)) {
                    family->adopted = current;
                    family->have_adopted = 1;
                    if (family->emitted_grandchild_spawn && !family->emitted_adopt) {
                        if (emit_event("ADOPT", family->phase, &family->adopted, "grandchild", "RUNNING",
                                       -1, 1, 0, -1, -1, -1) != 0) ok = 0;
                        family->emitted_adopt = 1;
                    }
                    break;
                }
                pid_t waited = waitpid(family->grandchild, &status, WNOHANG);
                if (waited == family->grandchild) {
                    family->grandchild_reaped = 1;
                    family->grandchild_status = status;
                    break;
                }
                if (waited < 0 && errno != EINTR) {
                    ok = 0;
                    break;
                }
                (void)poll(NULL, 0U, 2);
            }
        }
        if (family->have_adopted && !family->grandchild_reaped) {
            if (terminate_owned(&family->adopted, deadline, &status) == 0) {
                family->grandchild_reaped = 1;
                family->grandchild_status = status;
            } else {
                ok = 0;
            }
        } else if (!family->grandchild_reaped && native_run_wait_exact(family->grandchild, &status,
                                                                       deadline, &reaped) == 0 && reaped) {
            family->grandchild_reaped = 1;
            family->grandchild_status = status;
        } else if (!family->grandchild_reaped) {
            ok = 0;
        }
    }
    if (family->emitted_grandchild_spawn && family->grandchild_reaped &&
        !family->emitted_grandchild_exit) {
        const struct native_run_identity *identity = family->have_adopted ?
            &family->adopted : &family->birth;
        if (emit_event("EXIT", family->phase, identity, "grandchild", "REAPED",
                       native_run_exit_code(family->grandchild_status), registered_children - 1, 0, -1, -1, -1) != 0) {
            ok = 0;
        }
        family->emitted_grandchild_exit = 1;
        registered_children--;
    }
    close_fd(&family->release_fd);
    if (wait_echild(deadline) != 1) ok = 0;
    return ok;
}

static int make_exec_fd(int fd) {
    int flags = fcntl(fd, F_GETFD, 0);
    return flags < 0 ? -1 : fcntl(fd, F_SETFD, flags & ~FD_CLOEXEC);
}

static int spawn_family(struct child_family *family,
                        const struct native_run_fixture_guard *guard, uint64_t deadline) {
    int birth[2], ack[2], gate[2], release[2];
    if (native_run_pipe(birth) != 0 || native_run_pipe(ack) != 0 ||
        native_run_pipe(gate) != 0 || native_run_pipe(release) != 0) return -1;
    if (native_run_fixture_guard_current(guard) != 0) {
        (void)close(birth[0]); (void)close(birth[1]); (void)close(ack[0]); (void)close(ack[1]);
        (void)close(gate[0]); (void)close(gate[1]); (void)close(release[0]); (void)close(release[1]);
        return -1;
    }
    pid_t child = fork();
    if (child < 0) {
        (void)close(birth[0]); (void)close(birth[1]); (void)close(ack[0]); (void)close(ack[1]);
        (void)close(gate[0]); (void)close(gate[1]); (void)close(release[0]); (void)close(release[1]);
        return -1;
    }
    if (child == 0) {
        char birth_text[16], ack_text[16], gate_read_text[16], gate_write_text[16], release_text[16];
        (void)close(birth[0]); (void)close(ack[1]); (void)close(release[1]);
        if (make_exec_fd(birth[1]) != 0 || make_exec_fd(ack[0]) != 0 || make_exec_fd(gate[0]) != 0 ||
            make_exec_fd(gate[1]) != 0 || make_exec_fd(release[0]) != 0) _exit(127);
        (void)snprintf(birth_text, sizeof(birth_text), "%d", birth[1]);
        (void)snprintf(ack_text, sizeof(ack_text), "%d", ack[0]);
        (void)snprintf(gate_read_text, sizeof(gate_read_text), "%d", gate[0]);
        (void)snprintf(gate_write_text, sizeof(gate_write_text), "%d", gate[1]);
        (void)snprintf(release_text, sizeof(release_text), "%d", release[0]);
        if (native_run_fixture_guard_current(guard) != 0) _exit(126);
#ifdef __ANDROID__
        execl("/system/bin/linker64", "linker64", guard->path, "--role", "family",
              "--birth-fd", birth_text, "--ack-fd", ack_text, "--gate-read-fd", gate_read_text,
              "--gate-write-fd", gate_write_text, "--release-fd", release_text, (char *)NULL);
#else
        execl(guard->path, guard->path, "--role", "family", "--birth-fd", birth_text, "--ack-fd", ack_text,
              "--gate-read-fd", gate_read_text, "--gate-write-fd", gate_write_text,
              "--release-fd", release_text, (char *)NULL);
#endif
        _exit(127);
    }
    (void)close(birth[1]); (void)close(ack[0]); (void)close(gate[0]); (void)close(gate[1]); (void)close(release[0]);
    family->birth_fd = birth[0];
    family->ack_fd = ack[1];
    family->gate_read_fd = -1;
    family->gate_write_fd = -1;
    family->release_fd = release[1];
    family->intermediate = child;
    for (;;) {
        if (native_run_process_identity(child, &family->intermediate_identity) == 0) break;
        if (native_run_now_ms() >= deadline) return -1;
        (void)poll(NULL, 0U, 2);
    }
    family->have_intermediate = native_run_identity_owned(&family->intermediate_identity, application_uid) &&
                                family->intermediate_identity.ppid == (int32_t)getpid();
    return family->have_intermediate ? 0 : -1;
}

static int next_command(struct command *command, uint64_t deadline) {
    enum input_result result = read_command(command, deadline);
    if (result != INPUT_OK || command->sequence != ++command_sequence ||
        strcmp(command->run_id, run_id) != 0 || strcmp(command->nonce, nonce) != 0) return -1;
    return 0;
}

static int await_ack_or_stop(const struct child_family *family, int phase,
                             uint64_t expected_event, pid_t expected_parent,
                             uint64_t deadline, int *cancelled) {
    struct command command;
    *cancelled = 0;
    if (next_command(&command, deadline) != 0) return -1;
    if (command.kind == COMMAND_STOP && command.phase == phase) {
        *cancelled = 1;
        return 0;
    }
    if (command.kind != COMMAND_ACK || command.phase != phase || command.event_sequence != expected_event ||
        command.pid != (int64_t)family->grandchild || command.start_ticks != family->birth.start_ticks) return -1;
    struct native_run_identity current;
    if (!family->have_birth || native_run_process_identity(family->grandchild, &current) != 0 ||
        !native_run_identity_same_process(&family->birth, &current) ||
        current.ppid != (int32_t)expected_parent || !native_run_identity_owned(&current, application_uid)) return -1;
    return 0;
}

static int await_intermediate_ack(const struct child_family *family, int phase,
                                  uint64_t expected_event, uint64_t deadline, int *cancelled) {
    struct command command;
    *cancelled = 0;
    if (next_command(&command, deadline) != 0) return -1;
    if (command.kind == COMMAND_STOP && command.phase == phase) {
        *cancelled = 1;
        return 0;
    }
    if (command.kind != COMMAND_ACK || command.phase != phase || command.event_sequence != expected_event ||
        command.pid != (int64_t)family->intermediate || command.start_ticks != family->intermediate_identity.start_ticks) return -1;
    return verify_identity(&family->intermediate_identity, getpid()) ? 0 : -1;
}

static int run_phase(struct child_family *family, const struct native_run_fixture_guard *guard, int phase,
                     const char *scenario, uint64_t deadline, int *cancelled) {
    family_init(family);
    family->phase = phase;
    *cancelled = 0;
    /* "hold" is intentionally not a second timer or fault mode: this same
     * finite fixture always exposes ACK gates, so cancel_fork can stop at a
     * deterministic reserved-birth boundary without extending its TTL. */
    (void)scenario;
    if (spawn_family(family, guard, deadline) != 0) return -1;
    if (emit_event("SPAWN", phase, &family->intermediate_identity, "intermediate", "RUNNING", -1,
                   1, 0, -1, -1, -1) != 0) return -1;
    family->emitted_intermediate_spawn = 1;
    registered_children++;
    if (await_intermediate_ack(family, phase, event_sequence, deadline, cancelled) != 0) return -1;
    if (*cancelled) return 0;
    if (native_run_read_full(family->birth_fd, &family->birth, sizeof(family->birth), deadline, operation_ceiling) != 0 ||
        !native_run_identity_owned(&family->birth, application_uid) ||
        family->birth.ppid != (int32_t)family->intermediate ||
        native_run_process_identity((pid_t)family->birth.pid, &family->birth) != 0) return -1;
    family->grandchild = (pid_t)family->birth.pid;
    family->have_birth = 1;
    if (emit_event("SPAWN", phase, &family->birth, "grandchild", "RUNNING", -1, 2, 0, -1, -1, -1) != 0) return -1;
    family->emitted_grandchild_spawn = 1;
    registered_children++;
    if (await_ack_or_stop(family, phase, event_sequence, family->birth.ppid,
                          deadline, cancelled) != 0) return -1;
    if (*cancelled) return 0;
    unsigned char acknowledgement = 1U;
    if (native_run_write_full(family->ack_fd, &acknowledgement, 1U, deadline, operation_ceiling, NULL) != 0) return -1;
    close_fd(&family->ack_fd);
    int status = -1;
    int reaped = 0;
    if (native_run_wait_exact(family->intermediate, &status, deadline, &reaped) != 0 || !reaped) return -1;
    family->intermediate_reaped = 1;
    family->intermediate_status = status;
    struct native_run_identity adopted;
    int adopted_ok = 0;
    while (native_run_now_ms() < deadline) {
        if (native_run_process_identity(family->grandchild, &adopted) == 0 &&
            native_run_identity_same_process(&family->birth, &adopted) && adopted.ppid == (int32_t)getpid()) {
            family->adopted = adopted;
            family->have_adopted = 1;
            adopted_ok = 1;
            break;
        }
        (void)poll(NULL, 0U, 2);
    }
    if (!adopted_ok) return -1;
    if (emit_event("ADOPT", phase, &family->adopted, "grandchild", "RUNNING", -1, 1, 0, -1, -1, -1) != 0) return -1;
    family->emitted_adopt = 1;
    if (await_ack_or_stop(family, phase, event_sequence, getpid(),
                          deadline, cancelled) != 0) return -1;
    if (*cancelled) return 0;
    if (native_run_write_full(family->release_fd, "R", 1U, deadline, operation_ceiling, NULL) != 0) return -1;
    close_fd(&family->release_fd);
    if (native_run_wait_exact(family->grandchild, &status, deadline, &reaped) != 0 || !reaped) return -1;
    family->grandchild_reaped = 1;
    family->grandchild_status = status;
    if (emit_event("EXIT", phase, &family->intermediate_identity, "intermediate", "REAPED",
                   native_run_exit_code(family->intermediate_status), registered_children - 1, 0, -1, -1, -1) != 0) return -1;
    family->emitted_intermediate_exit = 1;
    registered_children--;
    if (emit_event("EXIT", phase, &family->adopted, "grandchild", "REAPED",
                   native_run_exit_code(family->grandchild_status), registered_children - 1, 0, -1, -1, -1) != 0) return -1;
    family->emitted_grandchild_exit = 1;
    registered_children--;
    if (wait_echild(deadline) != 1) return -1;
    if (!WIFEXITED(family->intermediate_status) || WEXITSTATUS(family->intermediate_status) != 0 ||
        !WIFEXITED(family->grandchild_status) || WEXITSTATUS(family->grandchild_status) != 0) {
        /* Both children have already been reaped and their EXIT events were
         * emitted; a non-zero or signalled fixture is a failed control, never
         * a successful phase. */
        return -1;
    }
    return emit_event("PHASE_DONE", phase, NULL, "supervisor", "PASS", -1, 0, 1, -1, -1, -1);
}

static int finish_run(const char *status, int exit_status, uint64_t deadline) {
    struct native_run_identity current;
    memset(&current, 0, sizeof(current));
    current = supervisor_identity;
    int echild = native_run_process_identity(getpid(), &current) == 0 &&
                 native_run_identity_same_process(&supervisor_identity, &current) &&
                 wait_echild(deadline) == 1;
    if (!echild) status = "UNKNOWN";
    (void)emit_event("RUN_DONE", 0, &current, "supervisor", status, -1, registered_children, echild, -1, -1, -1);
    return strcmp(status, "PASS") == 0 || strcmp(status, "CANCELLED") == 0 ? exit_status : 2;
}

int main(int argc, char **argv) {
    if (argc != 7 || strcmp(argv[1], "--run") != 0 || strcmp(argv[3], "--nonce") != 0 ||
        strcmp(argv[5], "--fixture") != 0) return 2;
    if (!native_run_valid_id(argv[2], "NS-") || !native_run_valid_hex(argv[4], 32U)) return 2;
    if (native_run_same_parent_fixture(argv[6], &fixture_guard) != 0) return early_failure("image-guard");
    (void)snprintf(run_id, sizeof(run_id), "%s", argv[2]);
    (void)snprintf(nonce, sizeof(nonce), "%s", argv[4]);
    application_uid = getuid();
    operation_deadline = native_run_now_ms() + NATIVE_RUN_OPERATION_MS;
    operation_ceiling = operation_deadline + NATIVE_RUN_CLEANUP_MS;
    if (native_run_nonblocking(STDIN_FILENO) != 0 || native_run_nonblocking(STDOUT_FILENO) != 0) {
        return early_failure("stdio");
    }
    int set_result = prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0);
    int get_value = 0;
    int get_result = prctl(PR_GET_CHILD_SUBREAPER, &get_value);
    if (native_run_process_identity(getpid(), &supervisor_identity) != 0 ||
        !native_run_identity_owned(&supervisor_identity, application_uid) || set_result != 0 ||
        get_result != 0 || get_value != 1) return early_failure("subreaper");
    if (emit_event("READY", 0, &supervisor_identity, "supervisor", "READY", -1, 0, 0,
                   set_result, get_result, get_value) != 0) return early_failure("ready");

    command_sequence = 0U;
    struct command command;
    if (next_command(&command, operation_deadline) != 0) {
        (void)emit_event("ERROR", 0, NULL, "supervisor", "UNKNOWN", -1, registered_children, 0, -1, -1, -1);
        return finish_run("UNKNOWN", 2, operation_ceiling);
    }
    if (command.kind == COMMAND_CLOSE) return finish_run("CANCELLED", 0, operation_ceiling);
    if (command.kind != COMMAND_START || command.phase != 1) {
        (void)emit_event("ERROR", 0, NULL, "supervisor", "ERROR", -1, registered_children, 0, -1, -1, -1);
        return finish_run("ERROR", 1, operation_ceiling);
    }
    struct child_family phase_one;
    int cancelled = 0;
    int phase_result = run_phase(&phase_one, &fixture_guard, 1, command.scenario,
                                 operation_deadline, &cancelled);
    if (phase_result != 0 || cancelled) {
        int cleaned = cleanup_family(&phase_one, operation_ceiling);
        if (cancelled && cleaned) return finish_run("CANCELLED", 0, operation_ceiling);
        (void)emit_event("ERROR", 1, NULL, "supervisor", cleaned ? "UNKNOWN" : "ERROR", -1, registered_children, 0, -1, -1, -1);
        return finish_run(cleaned ? "UNKNOWN" : "ERROR", 2, operation_ceiling);
    }
    int received_phase_two = next_command(&command, operation_deadline) == 0;
    if (!received_phase_two ||
        command.kind != COMMAND_START || command.phase != 2 || strcmp(command.scenario, "normal") != 0) {
        int cleaned = cleanup_family(&phase_one, operation_ceiling);
        if (received_phase_two && cleaned && command.kind == COMMAND_STOP && command.phase == 1) {
            return finish_run("CANCELLED", 0, operation_ceiling);
        }
        (void)emit_event("ERROR", 1, NULL, "supervisor", cleaned ? "ERROR" : "UNKNOWN", -1, registered_children, 0, -1, -1, -1);
        return finish_run(cleaned ? "ERROR" : "UNKNOWN", 2, operation_ceiling);
    }
    struct child_family phase_two;
    phase_result = run_phase(&phase_two, &fixture_guard, 2, command.scenario,
                             operation_deadline, &cancelled);
    if (phase_result != 0 || cancelled) {
        int cleaned = cleanup_family(&phase_two, operation_ceiling);
        if (cancelled && cleaned) return finish_run("CANCELLED", 0, operation_ceiling);
        (void)emit_event("ERROR", 2, NULL, "supervisor", cleaned ? "UNKNOWN" : "ERROR", -1, registered_children, 0, -1, -1, -1);
        return finish_run(cleaned ? "UNKNOWN" : "ERROR", 2, operation_ceiling);
    }
    if (next_command(&command, operation_deadline) != 0 || command.kind != COMMAND_CLOSE) {
        (void)emit_event("ERROR", 0, NULL, "supervisor", "UNKNOWN", -1, registered_children, 0, -1, -1, -1);
        return finish_run("UNKNOWN", 2, operation_ceiling);
    }
    return finish_run("PASS", 0, operation_ceiling);
}
