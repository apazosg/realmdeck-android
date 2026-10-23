#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
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

#define SCHEMA "wowforever.android-run-supervisor/version2"
#define TEST_BUDGET_MS 15000U
#define CLEANUP_BUDGET_MS 5000U
#define CHILD_TTL_SECONDS 10U
#define SUPERVISOR_TTL_SECONDS 12U
#define NORMAL_INTERMEDIATE_EXIT 41
#define NORMAL_GRANDCHILD_EXIT 42
#define CLEANUP_INTERMEDIATE_EXIT 43
#define CLEANUP_GRANDCHILD_EXIT 143

enum message_kind {
    MSG_STARTUP = 1,
    MSG_NORMAL_INTERMEDIATE = 2,
    MSG_NORMAL_ADOPTION = 3,
    MSG_NORMAL_EXIT = 4,
    MSG_CLEANUP_INTERMEDIATE = 5,
    MSG_CLEANUP_ADOPTION = 6,
    MSG_CLEANUP_EXIT = 7,
    MSG_ECHILD = 8,
    MSG_DONE = 9,
};

struct identity {
    int32_t pid;
    int32_t ppid;
    uint32_t uid;
    uint32_t euid;
    uint32_t suid;
    uint32_t fsuid;
    uint64_t start_ticks;
};

struct message {
    int32_t kind;
    int32_t status;
    int32_t error_number;
    int32_t set_result;
    int32_t get_result;
    int32_t get_value;
    struct identity identity;
};

struct family {
    int root_birth_r;
    int root_birth_w;
    int sup_birth_r;
    int sup_birth_w;
    int root_ack_r;
    int root_ack_w;
    int sup_ack_r;
    int sup_ack_w;
    int ready_r;
    int ready_w;
    int root_adopt_r;
    int root_adopt_w;
    int sup_adopt_r;
    int sup_adopt_w;
    int control_r;
    int control_w;
    pid_t intermediate;
    pid_t grandchild;
    struct identity intermediate_identity;
    struct identity birth;
    struct identity adopted;
    int have_intermediate;
    int have_birth;
    int have_adopted;
    int intermediate_status;
    int grandchild_status;
    int intermediate_reaped;
    int grandchild_reaped;
};

struct config {
    char run_id[64];
    const char *fault;
};

static uint32_t expected_uid;
static uint64_t root_watchdog_deadline;

static uint64_t bounded_deadline(uint64_t requested) {
    return root_watchdog_deadline != 0U && requested > root_watchdog_deadline ? root_watchdog_deadline : requested;
}

static uint64_t monotonic_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return (uint64_t)ts.tv_sec * 1000U + (uint64_t)ts.tv_nsec / 1000000U;
}

static int identity_equal(const struct identity *left, const struct identity *right) {
    return left->pid == right->pid && left->ppid == right->ppid && left->uid == right->uid &&
           left->euid == right->euid && left->suid == right->suid && left->fsuid == right->fsuid &&
           left->start_ticks == right->start_ticks;
}

static int identity_same_process(const struct identity *left, const struct identity *right) {
    return left->pid == right->pid && left->uid == right->uid && left->euid == right->euid &&
           left->suid == right->suid && left->fsuid == right->fsuid && left->start_ticks == right->start_ticks;
}

static int write_timed(int fd, const void *buffer, size_t length, uint64_t deadline) {
    deadline = bounded_deadline(deadline);
    const unsigned char *bytes = (const unsigned char *)buffer;
    while (length != 0U) {
        uint64_t now = monotonic_ms();
        int timeout = now >= deadline ? 0 : (int)(deadline - now);
        struct pollfd pfd = {fd, POLLOUT | POLLERR | POLLHUP, 0};
        int ready = poll(&pfd, 1, timeout);
        if (ready <= 0) {
            errno = ready == 0 ? ETIMEDOUT : errno;
            return -1;
        }
        ssize_t count = write(fd, bytes, length);
        if (count < 0) {
            if (errno == EINTR) {
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

static int read_timed(int fd, void *buffer, size_t length, uint64_t deadline) {
    deadline = bounded_deadline(deadline);
    unsigned char *bytes = (unsigned char *)buffer;
    while (length != 0U) {
        uint64_t now = monotonic_ms();
        int timeout = now >= deadline ? 0 : (int)(deadline - now);
        struct pollfd pfd = {fd, POLLIN | POLLERR | POLLHUP, 0};
        int ready = poll(&pfd, 1, timeout);
        if (ready <= 0) {
            errno = ready == 0 ? ETIMEDOUT : errno;
            return -1;
        }
        ssize_t count = read(fd, bytes, length);
        if (count < 0) {
            if (errno == EINTR) {
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

static int parse_decimal(const char *text, unsigned long long *value) {
    char *end = NULL;
    unsigned long long parsed;
    errno = 0;
    parsed = strtoull(text, &end, 10);
    if (errno != 0 || end == text || *end != '\0') {
        return -1;
    }
    *value = parsed;
    return 0;
}

static int process_identity(pid_t pid, struct identity *result) {
    char path[64];
    char data[4096];
    char *close_paren;
    char *cursor;
    char *end;
    unsigned long long ppid_value = 0;
    unsigned long long start_ticks = 0;
    unsigned long uid_values[4] = {0, 0, 0, 0};
    int have_uid = 0;
    FILE *stream;

    if (snprintf(path, sizeof(path), "/proc/%ld/stat", (long)pid) < 0) {
        return -1;
    }
    stream = fopen(path, "r");
    if (stream == NULL) {
        return -1;
    }
    size_t length = fread(data, 1, sizeof(data) - 1U, stream);
    int stat_error = ferror(stream);
    (void)fclose(stream);
    if (stat_error != 0 || length == 0U) {
        return -1;
    }
    data[length] = '\0';
    close_paren = strrchr(data, ')');
    if (close_paren == NULL) {
        return -1;
    }
    cursor = close_paren + 2;
    while (*cursor != '\0' && *cursor != ' ') {
        cursor++;
    }
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
    char ppid_text[32];
    size_t ppid_length = (size_t)(end - cursor);
    if (ppid_length >= sizeof(ppid_text)) {
        return -1;
    }
    memcpy(ppid_text, cursor, ppid_length);
    ppid_text[ppid_length] = '\0';
    if (parse_decimal(ppid_text, &ppid_value) != 0 || ppid_value > INT32_MAX) {
        return -1;
    }
    cursor = end;
    for (int field = 5; field <= 22; field++) {
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
        if (field == 22) {
            char start_text[32];
            size_t start_length = (size_t)(end - cursor);
            if (start_length >= sizeof(start_text)) {
                return -1;
            }
            memcpy(start_text, cursor, start_length);
            start_text[start_length] = '\0';
            if (parse_decimal(start_text, &start_ticks) != 0 || start_ticks == 0U) {
                return -1;
            }
        }
        cursor = end;
    }

    if (snprintf(path, sizeof(path), "/proc/%ld/status", (long)pid) < 0) {
        return -1;
    }
    stream = fopen(path, "r");
    if (stream == NULL) {
        return -1;
    }
    while (fgets(data, sizeof(data), stream) != NULL) {
        if (strncmp(data, "Uid:", 4) == 0) {
            if (sscanf(data + 4, "%lu %lu %lu %lu", &uid_values[0], &uid_values[1], &uid_values[2], &uid_values[3]) == 4) {
                have_uid = 1;
                break;
            }
        }
    }
    int status_error = ferror(stream);
    (void)fclose(stream);
    if (status_error != 0 || !have_uid || uid_values[0] > UINT32_MAX || uid_values[1] > UINT32_MAX ||
        uid_values[2] > UINT32_MAX || uid_values[3] > UINT32_MAX || (uint32_t)getuid() != expected_uid ||
        (uint32_t)geteuid() != expected_uid || uid_values[0] != expected_uid || uid_values[1] != expected_uid ||
        uid_values[2] != expected_uid || uid_values[3] != expected_uid) {
        return -1;
    }
    result->pid = (int32_t)pid;
    result->ppid = (int32_t)ppid_value;
    result->uid = (uint32_t)uid_values[0];
    result->euid = (uint32_t)uid_values[1];
    result->suid = (uint32_t)uid_values[2];
    result->fsuid = (uint32_t)uid_values[3];
    result->start_ticks = start_ticks;
    return 0;
}

static int identity_valid(const struct identity *identity) {
    return identity->pid > 0 && identity->ppid > 0 && identity->start_ticks > 0 &&
           identity->uid == expected_uid && identity->euid == expected_uid && identity->suid == expected_uid &&
           identity->fsuid == expected_uid;
}

static int wait_exact(pid_t pid, int *status, uint64_t deadline, int *reaped) {
    deadline = bounded_deadline(deadline);
    *reaped = 0;
    *status = -1;
    for (;;) {
        pid_t waited = waitpid(pid, status, WNOHANG);
        if (waited == pid) {
            *reaped = 1;
            return 0;
        }
        if (waited < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        if (monotonic_ms() >= deadline) {
            errno = ETIMEDOUT;
            return -1;
        }
        (void)poll(NULL, 0, 10);
    }
}

static int exit_code(int status, int available) {
    if (!available) {
        return -1;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return -1;
}

static int send_message(int fd, int kind, int status, int error_number, int set_result, int get_result, int get_value,
                        const struct identity *identity, uint64_t deadline) {
    struct message message;
    memset(&message, 0, sizeof(message));
    message.kind = kind;
    message.status = status;
    message.error_number = error_number;
    message.set_result = set_result;
    message.get_result = get_result;
    message.get_value = get_value;
    if (identity != NULL) {
        message.identity = *identity;
    }
    return write_timed(fd, &message, sizeof(message), deadline);
}

static int receive_message(int fd, struct message *message, uint64_t deadline) {
    return read_timed(fd, message, sizeof(*message), deadline);
}

static void family_init(struct family *family) {
    memset(family, 0, sizeof(*family));
    family->root_birth_r = family->root_birth_w = family->sup_birth_r = family->sup_birth_w = -1;
    family->root_ack_r = family->root_ack_w = family->sup_ack_r = family->sup_ack_w = -1;
    family->ready_r = family->ready_w = family->root_adopt_r = family->root_adopt_w = -1;
    family->sup_adopt_r = family->sup_adopt_w = family->control_r = family->control_w = -1;
    family->intermediate = family->grandchild = -1;
    family->intermediate_status = family->grandchild_status = -1;
}

static int make_pipe(int pipefd[2]) {
    return pipe(pipefd) == 0 ? 0 : -1;
}

static void close_family(struct family *family);

static int family_create(struct family *family) {
    int *pipes[] = {
        &family->root_birth_r, &family->root_birth_w, &family->sup_birth_r, &family->sup_birth_w,
        &family->root_ack_r, &family->root_ack_w, &family->sup_ack_r, &family->sup_ack_w,
        &family->ready_r, &family->ready_w, &family->root_adopt_r, &family->root_adopt_w,
        &family->sup_adopt_r, &family->sup_adopt_w, &family->control_r, &family->control_w,
    };
    int raw[2];
    for (size_t index = 0; index < 8U; index++) {
        if (make_pipe(raw) != 0) {
            close_family(family);
            return -1;
        }
        *pipes[index * 2U] = raw[0];
        *pipes[index * 2U + 1U] = raw[1];
    }
    return 0;
}

static void close_except(const struct family *family, const int *keep, size_t keep_count) {
    const int all[] = {
        family->root_birth_r, family->root_birth_w, family->sup_birth_r, family->sup_birth_w,
        family->root_ack_r, family->root_ack_w, family->sup_ack_r, family->sup_ack_w,
        family->ready_r, family->ready_w, family->root_adopt_r, family->root_adopt_w,
        family->sup_adopt_r, family->sup_adopt_w, family->control_r, family->control_w,
    };
    for (size_t index = 0; index < sizeof(all) / sizeof(all[0]); index++) {
        int retained = 0;
        for (size_t item = 0; item < keep_count; item++) {
            if (all[index] >= 0 && all[index] == keep[item]) {
                retained = 1;
                break;
            }
        }
        if (!retained && all[index] >= 0) {
            (void)close(all[index]);
        }
    }
}

static void close_family(struct family *family) {
    int *all[] = {
        &family->root_birth_r, &family->root_birth_w, &family->sup_birth_r, &family->sup_birth_w,
        &family->root_ack_r, &family->root_ack_w, &family->sup_ack_r, &family->sup_ack_w,
        &family->ready_r, &family->ready_w, &family->root_adopt_r, &family->root_adopt_w,
        &family->sup_adopt_r, &family->sup_adopt_w, &family->control_r, &family->control_w,
    };
    for (size_t index = 0; index < sizeof(all) / sizeof(all[0]); index++) {
        if (*all[index] >= 0) {
            (void)close(*all[index]);
            *all[index] = -1;
        }
    }
}

static int spawn_family(struct family *family, int intermediate_exit, int force_fork_failure, int event_fd,
                        uint64_t deadline) {
    pid_t intermediate = fork();
    if (intermediate < 0) {
        return -1;
    }
    if (intermediate == 0) {
        (void)alarm(CHILD_TTL_SECONDS);
        (void)close(event_fd);
        if (force_fork_failure) {
            _exit(125);
        }
        pid_t grandchild = fork();
        if (grandchild < 0) {
            _exit(126);
        }
        if (grandchild > 0) {
            const int keep_intermediate[] = {family->ready_r};
            close_except(family, keep_intermediate, 1U);
            unsigned char ready = 0;
            if (read_timed(family->ready_r, &ready, 1, monotonic_ms() + CHILD_TTL_SECONDS * 1000U) != 0 || ready != 1) {
                _exit(127);
            }
            _exit(intermediate_exit);
        }
        const int keep_grandchild[] = {
            family->root_birth_w, family->sup_birth_w, family->root_ack_r, family->sup_ack_r,
            family->ready_w, family->root_adopt_w, family->sup_adopt_w, family->control_r,
        };
        close_except(family, keep_grandchild, sizeof(keep_grandchild) / sizeof(keep_grandchild[0]));
        struct identity birth;
        if (process_identity(getpid(), &birth) != 0 || !identity_valid(&birth) ||
            write_timed(family->root_birth_w, &birth, sizeof(birth), deadline) != 0 ||
            write_timed(family->sup_birth_w, &birth, sizeof(birth), deadline) != 0) {
            _exit(127);
        }
        unsigned char acknowledgement = 0;
        if (read_timed(family->root_ack_r, &acknowledgement, 1, deadline) != 0 || acknowledgement != 1 ||
            read_timed(family->sup_ack_r, &acknowledgement, 1, deadline) != 0 || acknowledgement != 1 ||
            write_timed(family->ready_w, "\1", 1, deadline) != 0) {
            _exit(124);
        }
        /* Birth was captured while the intermediate was held by both ACKs.
         * Reading getppid() after ready lets an already-adopted child mistake
         * the supervisor for its old parent and wait for the supervisor's exit. */
        pid_t original_parent = (pid_t)birth.ppid;
        uint64_t adoption_deadline = deadline;
        while (getppid() == original_parent && monotonic_ms() < adoption_deadline) {
            (void)poll(NULL, 0, 5);
        }
        struct identity adopted;
        if (getppid() <= 1 || process_identity(getpid(), &adopted) != 0 || !identity_valid(&adopted) ||
            write_timed(family->root_adopt_w, &adopted, sizeof(adopted), deadline) != 0 ||
            write_timed(family->sup_adopt_w, &adopted, sizeof(adopted), deadline) != 0) {
            _exit(124);
        }
        unsigned char command = 0;
        if (read_timed(family->control_r, &command, 1, deadline) != 0) {
            _exit(124);
        }
        _exit(command == 'E' ? NORMAL_GRANDCHILD_EXIT : 127);
    }
    family->intermediate = intermediate;
    if (process_identity(intermediate, &family->intermediate_identity) != 0 ||
        family->intermediate_identity.pid != (int32_t)intermediate ||
        family->intermediate_identity.ppid != (int32_t)getpid() ||
        family->intermediate_identity.uid != expected_uid) {
        family->have_intermediate = 0;
        return -1;
    }
    family->have_intermediate = 1;
    return 0;
}

static int validate_child_identity(const struct identity *identity, pid_t expected_parent, int require_parent) {
    struct identity current;
    if (!identity_valid(identity) || (require_parent && identity->ppid != (int32_t)expected_parent) ||
        process_identity((pid_t)identity->pid, &current) != 0 || !identity_equal(identity, &current)) {
        return -1;
    }
    return 0;
}

static int family_birth_supervisor(struct family *family, uint64_t deadline) {
    if (read_timed(family->sup_birth_r, &family->birth, sizeof(family->birth), deadline) != 0 ||
        validate_child_identity(&family->birth, family->intermediate, 1) != 0) {
        return -1;
    }
    family->grandchild = (pid_t)family->birth.pid;
    family->have_birth = 1;
    return 0;
}

static int family_adoption_supervisor(struct family *family, pid_t supervisor_pid, uint64_t deadline) {
    if (read_timed(family->sup_adopt_r, &family->adopted, sizeof(family->adopted), deadline) != 0 ||
        validate_child_identity(&family->adopted, supervisor_pid, 1) != 0 ||
        !identity_same_process(&family->birth, &family->adopted)) {
        return -1;
    }
    family->have_adopted = 1;
    return 0;
}

static int terminate_identity(const struct identity *identity, pid_t allowed_parent, uint64_t deadline, int *confirmed,
                              int *status_out) {
    deadline = bounded_deadline(deadline);
    *confirmed = 0;
    if (status_out != NULL) {
        *status_out = -1;
    }
    struct identity current;
    if (!identity_valid(identity) || process_identity((pid_t)identity->pid, &current) != 0 ||
        !identity_same_process(identity, &current) || (allowed_parent > 0 && current.ppid != allowed_parent)) {
        return -1;
    }
    if (kill((pid_t)identity->pid, SIGTERM) != 0 && errno != ESRCH) {
        return -1;
    }
    int status = -1;
    int reaped = 0;
    uint64_t term_deadline = monotonic_ms() + 500U;
    if (term_deadline > deadline) {
        term_deadline = deadline;
    }
    if (wait_exact((pid_t)identity->pid, &status, term_deadline, &reaped) == 0 && reaped) {
        *confirmed = 1;
        if (status_out != NULL) {
            *status_out = status;
        }
        return 0;
    }
    if (process_identity((pid_t)identity->pid, &current) != 0) {
        return -1;
    }
    if (!identity_same_process(identity, &current) || (allowed_parent > 0 && current.ppid != allowed_parent)) {
        return -1;
    }
    if (kill((pid_t)identity->pid, SIGKILL) != 0 && errno != ESRCH) {
        return -1;
    }
    if (wait_exact((pid_t)identity->pid, &status, deadline, &reaped) != 0 || !reaped) {
        return -1;
    }
    *confirmed = 1;
    if (status_out != NULL) {
        *status_out = status;
    }
    return 0;
}

/* A direct child may be cleaned up from a live PID alone only after taking a
 * fresh, exact identity snapshot.  If /proc no longer proves ownership, this
 * helper never sends a signal and only attempts to reap the already-known
 * direct child PID. */
static int cleanup_direct_child(pid_t pid, const struct identity *hint, uint64_t deadline, int *reaped, int *status_out) {
    struct identity current;
    int confirmed = 0;
    *reaped = 0;
    if (status_out != NULL) {
        *status_out = -1;
    }
    if (pid <= 0) {
        return -1;
    }
    if (hint != NULL) {
        if (identity_valid(hint) && process_identity(pid, &current) == 0 &&
            identity_same_process(hint, &current) && current.ppid == (int32_t)getpid() &&
            terminate_identity(hint, (pid_t)getpid(), deadline, &confirmed, status_out) == 0 && confirmed) {
            *reaped = 1;
            return 0;
        }
    } else if (process_identity(pid, &current) == 0 && identity_valid(&current) &&
               current.pid == (int32_t)pid && current.ppid == (int32_t)getpid()) {
        if (terminate_identity(&current, (pid_t)getpid(), deadline, &confirmed, status_out) == 0 && confirmed) {
            *reaped = 1;
            return 0;
        }
    }
    int status = -1;
    int result = wait_exact(pid, &status, deadline, reaped);
    if (status_out != NULL && *reaped) {
        *status_out = status;
    }
    return result;
}

static int drain_to_echild(uint64_t deadline, int *unexpected) {
    *unexpected = 0;
    for (;;) {
        int status = -1;
        pid_t child = waitpid(-1, &status, WNOHANG);
        if (child < 0) {
            if (errno == EINTR) {
                continue;
            }
            return errno == ECHILD ? 1 : -1;
        }
        if (child == 0) {
            if (monotonic_ms() >= deadline) {
                return -1;
            }
            (void)poll(NULL, 0, 10);
            continue;
        }
        *unexpected = 1;
    }
}

static int supervisor_cleanup_family(struct family *family, uint64_t deadline, int *confirmed) {
    *confirmed = 1;
    if (family->have_adopted && !family->grandchild_reaped) {
        int one = 0;
        int status = -1;
        if (terminate_identity(&family->adopted, (pid_t)getpid(), deadline, &one, NULL) != 0 || !one) {
            int reaped = 0;
            if (wait_exact((pid_t)family->adopted.pid, &status, deadline, &reaped) != 0 || !reaped) {
                *confirmed = 0;
            } else {
                family->grandchild_reaped = 1;
            }
        }
    }
    if (family->have_intermediate) {
        if (!family->intermediate_reaped) {
            int one = 0;
            if (terminate_identity(&family->intermediate_identity, (pid_t)getpid(), deadline, &one, NULL) == 0 && one) {
                family->intermediate_reaped = 1;
            }
        }
        int reaped = 0;
        int status = -1;
        if (!family->intermediate_reaped && wait_exact(family->intermediate, &status, deadline, &reaped) == 0 && reaped) {
            family->intermediate_reaped = 1;
        } else if (!family->intermediate_reaped) {
            *confirmed = 0;
        }
    }
    if (family->have_birth && !family->have_adopted) {
        struct identity current;
        int one = 0;
        int status = -1;
        if (process_identity((pid_t)family->birth.pid, &current) == 0 &&
            current.ppid == (int32_t)getpid() && terminate_identity(&family->birth, (pid_t)getpid(), deadline, &one, NULL) == 0 && one) {
            family->grandchild_reaped = 1;
        } else {
            int reaped = 0;
            if (wait_exact((pid_t)family->birth.pid, &status, deadline, &reaped) == 0 && reaped) {
                family->grandchild_reaped = 1;
            } else {
                *confirmed = 0;
            }
        }
    }
    return *confirmed ? 0 : -1;
}

static int supervisor_run(int event_fd, struct family *normal, struct family *cleanup, const char *fault,
                          uint64_t deadline) {
    (void)alarm(SUPERVISOR_TTL_SECONDS);
    struct identity self;
    memset(&self, 0, sizeof(self));
    int set_result = 0;
    int get_result = 0;
    int get_value = 0;
    set_result = prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0);
    get_result = prctl(PR_GET_CHILD_SUBREAPER, &get_value);
    int subreaper_ok = set_result == 0 && get_result == 0 && get_value == 1 && process_identity(getpid(), &self) == 0;
    if (fault != NULL && strcmp(fault, "prctl") == 0) {
        set_result = -1;
        get_result = 0;
        get_value = 0;
        subreaper_ok = 0;
    }
    if (send_message(event_fd, MSG_STARTUP, subreaper_ok, errno, set_result, get_result, get_value, &self, deadline) != 0) {
        return 1;
    }
    if (!subreaper_ok) {
        int unexpected = 0;
        (void)drain_to_echild(deadline, &unexpected);
        (void)send_message(event_fd, MSG_DONE, 0, ENOTSUP, set_result, get_result, get_value, &self, deadline);
        return 1;
    }
    if (spawn_family(normal, NORMAL_INTERMEDIATE_EXIT, fault != NULL && strcmp(fault, "fork") == 0, event_fd, deadline) != 0) {
        (void)send_message(event_fd, MSG_DONE, 0, errno, set_result, get_result, get_value, &self, deadline);
        return 1;
    }
    if (family_birth_supervisor(normal, deadline) != 0) {
        int confirmed = 0;
        (void)supervisor_cleanup_family(normal, deadline, &confirmed);
        (void)send_message(event_fd, MSG_DONE, 0, EPROTO, set_result, get_result, get_value, &self, deadline);
        return 1;
    }
    if (fault != NULL && strcmp(fault, "timeout") == 0) {
        /* Root has the birth record and ACK; supervisor deliberately withholds
           its ACK, leaving only its own verified descendants to the root. */
        (void)poll(NULL, 0, (int)(deadline > monotonic_ms() ? deadline - monotonic_ms() : 0));
        return 1;
    }
    if (write_timed(normal->sup_ack_w, "\1", 1, deadline) != 0) {
        int confirmed = 0;
        (void)supervisor_cleanup_family(normal, deadline, &confirmed);
        return 1;
    }
    if (fault != NULL && strcmp(fault, "kill") == 0) {
        (void)raise(SIGSTOP);
    }
    if (family_adoption_supervisor(normal, getpid(), deadline) != 0) {
        int confirmed = 0;
        (void)supervisor_cleanup_family(normal, deadline, &confirmed);
        (void)send_message(event_fd, MSG_DONE, 0, EPROTO, set_result, get_result, get_value, &self, deadline);
        return 1;
    }
    if (fault != NULL && strcmp(fault, "ipc") == 0) {
        (void)close(event_fd);
    } else {
        (void)send_message(event_fd, MSG_NORMAL_ADOPTION, 1, 0, 0, 0, 0, &normal->adopted, deadline);
    }
    int intermediate_status = -1;
    int intermediate_reaped = 0;
    if (wait_exact(normal->intermediate, &intermediate_status, deadline, &intermediate_reaped) == 0 && intermediate_reaped) {
        normal->intermediate_status = intermediate_status;
        normal->intermediate_reaped = 1;
        (void)send_message(event_fd, MSG_NORMAL_INTERMEDIATE, exit_code(intermediate_status, 1), 0, 0, 0, 0, NULL, deadline);
    }
    char command = 'E';
    if (fault != NULL && strcmp(fault, "reap") == 0) {
        int confirmed = 0;
        int terminated_status = -1;
        (void)terminate_identity(&normal->adopted, (pid_t)getpid(), deadline, &confirmed, &terminated_status);
        normal->grandchild_reaped = confirmed;
        normal->grandchild_status = terminated_status;
    } else if (write_timed(normal->control_w, &command, 1, deadline) != 0) {
        int confirmed = 0;
        (void)supervisor_cleanup_family(normal, deadline, &confirmed);
    }
    int status = -1;
    int reaped = 0;
    if (wait_exact(normal->grandchild, &status, deadline, &reaped) == 0 && reaped) {
        normal->grandchild_status = status;
        normal->grandchild_reaped = 1;
    }
    (void)send_message(event_fd, MSG_NORMAL_EXIT,
                       normal->grandchild_reaped ? (normal->grandchild_status >= 0 ? exit_code(normal->grandchild_status, 1) : -1) : -1,
                       normal->grandchild_reaped ? 0 : ECHILD, 0, 0, 0, NULL, deadline);

    if (spawn_family(cleanup, CLEANUP_INTERMEDIATE_EXIT, 0, event_fd, deadline) != 0 ||
        family_birth_supervisor(cleanup, deadline) != 0) {
        int confirmed = 0;
        (void)supervisor_cleanup_family(cleanup, deadline, &confirmed);
    } else if (write_timed(cleanup->sup_ack_w, "\1", 1, deadline) != 0 ||
               family_adoption_supervisor(cleanup, getpid(), deadline) != 0) {
        int confirmed = 0;
        (void)supervisor_cleanup_family(cleanup, deadline, &confirmed);
    } else {
        (void)send_message(event_fd, MSG_CLEANUP_ADOPTION, 1, 0, 0, 0, 0, &cleanup->adopted, deadline);
        int confirmed = 0;
        (void)terminate_identity(&cleanup->adopted, (pid_t)getpid(), deadline, &confirmed, NULL);
        cleanup->grandchild_reaped = confirmed;
        status = -1;
        reaped = 0;
        if (confirmed) {
            cleanup->grandchild_status = CLEANUP_GRANDCHILD_EXIT;
        }
        (void)send_message(event_fd, MSG_CLEANUP_EXIT, confirmed ? 143 : -1, confirmed ? 0 : ECHILD,
                           0, 0, 0, NULL, deadline);
        if (wait_exact(cleanup->intermediate, &status, deadline, &reaped) == 0 && reaped) {
            cleanup->intermediate_status = status;
            cleanup->intermediate_reaped = 1;
            (void)send_message(event_fd, MSG_CLEANUP_INTERMEDIATE, exit_code(status, 1), 0, 0, 0, 0, NULL, deadline);
        }
    }
    int unexpected = 0;
    int echild = drain_to_echild(deadline, &unexpected);
    struct identity after;
    memset(&after, 0, sizeof(after));
    int stable = process_identity(getpid(), &after) == 0 && identity_same_process(&self, &after);
    int ok = echild == 1 && !unexpected && stable && normal->grandchild_reaped && cleanup->grandchild_reaped &&
             normal->intermediate_reaped && cleanup->intermediate_reaped && normal->grandchild_status >= 0;
    (void)send_message(event_fd, MSG_ECHILD, echild == 1 && !unexpected, echild == 1 ? 0 : errno, 0, 0, 0, NULL, deadline);
    (void)send_message(event_fd, MSG_DONE, ok, ok ? 0 : ECHILD, set_result, get_result, get_value, &after, deadline);
    return ok ? 0 : 1;
}

static int emit_identity(const char *run_id, const char *event, const struct identity *identity) {
    int result = printf("{\"schema\":\"%s\",\"runId\":\"%s\",\"event\":\"%s\",\"pid\":%ld,\"ppid\":%ld,\"uid\":%u,\"euid\":%u,\"suid\":%u,\"fsuid\":%u,\"startTicks\":%llu}\n",
                        SCHEMA, run_id, event, (long)identity->pid, (long)identity->ppid, identity->uid,
                        identity->euid, identity->suid, identity->fsuid, (unsigned long long)identity->start_ticks);
    return result < 0 ? -1 : 0;
}

static int emit_result(const struct config *config, const char *status, const char *failure_stage, int native_exit,
                       const struct identity *root, const struct identity *supervisor, const struct identity *witness,
                       const struct family *normal, const struct family *cleanup, int root_set, int root_get, int root_value,
                       int supervisor_set, int supervisor_get, int supervisor_value, int echild, int root_echild,
                       int cleanup_confirmed,
                       int witness_stable, int supervisor_exit, const char *scope) {
    int result = printf("{\"schema\":\"%s\",\"runId\":\"%s\",\"event\":\"result\",\"status\":\"%s\",\"failureStage\":\"%s\",\"scope\":\"%s\",\"rootSetResult\":%d,\"rootGetResult\":%d,\"rootGetValue\":%d,\"supervisorSetResult\":%d,\"supervisorGetResult\":%d,\"supervisorGetValue\":%d,\"rootPid\":%ld,\"rootPpid\":%ld,\"rootUid\":%u,\"rootEuid\":%u,\"rootSuid\":%u,\"rootFsuid\":%u,\"rootStartTicks\":%llu,\"supervisorPid\":%ld,\"supervisorPpid\":%ld,\"supervisorUid\":%u,\"supervisorEuid\":%u,\"supervisorSuid\":%u,\"supervisorFsuid\":%u,\"supervisorStartTicks\":%llu,\"witnessPid\":%ld,\"witnessPpid\":%ld,\"witnessUid\":%u,\"witnessEuid\":%u,\"witnessSuid\":%u,\"witnessFsuid\":%u,\"witnessStartTicks\":%llu,\"normalIntermediatePid\":%ld,\"normalIntermediatePpid\":%ld,\"normalIntermediateUid\":%u,\"normalIntermediateEuid\":%u,\"normalIntermediateSuid\":%u,\"normalIntermediateFsuid\":%u,\"normalIntermediateStartTicks\":%llu,\"normalBirthPid\":%ld,\"normalBirthPpid\":%ld,\"normalBirthUid\":%u,\"normalBirthEuid\":%u,\"normalBirthSuid\":%u,\"normalBirthFsuid\":%u,\"normalBirthStartTicks\":%llu,\"normalAdoptionPid\":%ld,\"normalAdoptionPpid\":%ld,\"normalAdoptionUid\":%u,\"normalAdoptionEuid\":%u,\"normalAdoptionSuid\":%u,\"normalAdoptionFsuid\":%u,\"normalAdoptionStartTicks\":%llu,\"cleanupIntermediatePid\":%ld,\"cleanupIntermediatePpid\":%ld,\"cleanupIntermediateUid\":%u,\"cleanupIntermediateEuid\":%u,\"cleanupIntermediateSuid\":%u,\"cleanupIntermediateFsuid\":%u,\"cleanupIntermediateStartTicks\":%llu,\"cleanupBirthPid\":%ld,\"cleanupBirthPpid\":%ld,\"cleanupBirthUid\":%u,\"cleanupBirthEuid\":%u,\"cleanupBirthSuid\":%u,\"cleanupBirthFsuid\":%u,\"cleanupBirthStartTicks\":%llu,\"cleanupAdoptionPid\":%ld,\"cleanupAdoptionPpid\":%ld,\"cleanupAdoptionUid\":%u,\"cleanupAdoptionEuid\":%u,\"cleanupAdoptionSuid\":%u,\"cleanupAdoptionFsuid\":%u,\"cleanupAdoptionStartTicks\":%llu,\"normalIntermediateExit\":%d,\"normalGrandchildExit\":%d,\"cleanupIntermediateExit\":%d,\"cleanupGrandchildExit\":%d,\"waitpidEchild\":%s,\"rootWaitpidEchild\":%s,\"cleanupConfirmed\":%s,\"witnessStable\":%s,\"supervisorExit\":%d,\"nativeExit\":%d}\n",
                        SCHEMA, config->run_id, status, failure_stage, scope, root_set, root_get, root_value,
                        supervisor_set, supervisor_get, supervisor_value, (long)root->pid, (long)root->ppid, root->uid,
                        root->euid, root->suid, root->fsuid, (unsigned long long)root->start_ticks, (long)supervisor->pid,
                        (long)supervisor->ppid, supervisor->uid, supervisor->euid, supervisor->suid, supervisor->fsuid,
                        (unsigned long long)supervisor->start_ticks, (long)witness->pid, (long)witness->ppid, witness->uid,
                        witness->euid, witness->suid, witness->fsuid, (unsigned long long)witness->start_ticks,
                         (long)normal->intermediate, (long)normal->intermediate_identity.ppid, normal->intermediate_identity.uid,
                         normal->intermediate_identity.euid, normal->intermediate_identity.suid, normal->intermediate_identity.fsuid,
                         (unsigned long long)normal->intermediate_identity.start_ticks, (long)normal->birth.pid,
                         (long)normal->birth.ppid, normal->birth.uid, normal->birth.euid, normal->birth.suid, normal->birth.fsuid,
                         (unsigned long long)normal->birth.start_ticks, (long)normal->adopted.pid, (long)normal->adopted.ppid,
                         normal->adopted.uid, normal->adopted.euid, normal->adopted.suid, normal->adopted.fsuid,
                         (unsigned long long)normal->adopted.start_ticks, (long)cleanup->intermediate,
                         (long)cleanup->intermediate_identity.ppid, cleanup->intermediate_identity.uid, cleanup->intermediate_identity.euid,
                         cleanup->intermediate_identity.suid, cleanup->intermediate_identity.fsuid,
                         (unsigned long long)cleanup->intermediate_identity.start_ticks, (long)cleanup->birth.pid,
                         (long)cleanup->birth.ppid, cleanup->birth.uid, cleanup->birth.euid, cleanup->birth.suid, cleanup->birth.fsuid,
                         (unsigned long long)cleanup->birth.start_ticks, (long)cleanup->adopted.pid, (long)cleanup->adopted.ppid,
                         cleanup->adopted.uid, cleanup->adopted.euid, cleanup->adopted.suid, cleanup->adopted.fsuid,
                         (unsigned long long)cleanup->adopted.start_ticks,
                        normal->intermediate_status >= 0 ? normal->intermediate_status : -1,
                        normal->grandchild_status >= 0 ? normal->grandchild_status : -1,
                        cleanup->intermediate_status >= 0 ? cleanup->intermediate_status : -1,
                        cleanup_confirmed ? 143 : -1, echild ? "true" : "false", root_echild ? "true" : "false",
                        cleanup_confirmed ? "true" : "false",
                        witness_stable ? "true" : "false", supervisor_exit, native_exit);
    return result < 0 ? -1 : 0;
}

static int valid_run_id(const char *run_id) {
    size_t length = strlen(run_id);
    if (length != 35U || strncmp(run_id, "RS-", 3) != 0) {
        return 0;
    }
    for (size_t index = 3; index < length; index++) {
        char value = run_id[index];
        if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
            return 0;
        }
    }
    return 1;
}

static int cleanup_root_family(struct family *family, uint64_t deadline, int *confirmed) {
    *confirmed = 1;
    if (family->have_intermediate && !family->intermediate_reaped) {
        int one = 0;
        if (terminate_identity(&family->intermediate_identity, (pid_t)getpid(), deadline, &one, NULL) == 0 && one) {
            family->intermediate_reaped = 1;
        }
    }
    if (family->have_adopted && !family->grandchild_reaped) {
        int one = 0;
        int status = -1;
        if (terminate_identity(&family->adopted, (pid_t)getpid(), deadline, &one, NULL) != 0 || !one) {
            int reaped = 0;
            if (wait_exact((pid_t)family->adopted.pid, &status, deadline, &reaped) != 0 || !reaped) {
                *confirmed = 0;
            } else {
                family->grandchild_reaped = 1;
            }
        }
    }
    if (family->have_birth && !family->have_adopted) {
        struct identity current;
        int status = -1;
        int one = 0;
        if (process_identity((pid_t)family->birth.pid, &current) == 0 && current.ppid == (int32_t)getpid() &&
            terminate_identity(&family->birth, (pid_t)getpid(), deadline, &one, NULL) == 0 && one) {
            family->grandchild_reaped = 1;
        } else {
            int reaped = 0;
            if (wait_exact((pid_t)family->birth.pid, &status, deadline, &reaped) == 0 && reaped) {
                family->grandchild_reaped = 1;
            } else {
                *confirmed = 0;
            }
        }
    }
    if (family->have_intermediate) {
        int status = -1;
        int reaped = 0;
        if (wait_exact(family->intermediate, &status, deadline, &reaped) == 0 && reaped) {
            family->intermediate_reaped = 1;
            family->intermediate_status = status;
        } else if (!family->intermediate_reaped) {
            *confirmed = 0;
        }
    }
    /* Do not drain here: the witness and any other root-owned children are
     * still alive.  The caller checks root ECHILD only after all of them have
     * gone through the common cleanup path. */
    return *confirmed ? 0 : -1;
}

static int emit_setup_failure(const struct config *config, const struct identity *root, pid_t witness_pid,
                              const struct identity *witness, struct family *normal, struct family *cleanup,
                              int root_set, int root_get, int root_value, const char *failure_stage,
                              uint64_t cleanup_deadline) {
    int normal_cleanup = 1;
    int cleanup_cleanup = 1;
    (void)cleanup_root_family(normal, cleanup_deadline, &normal_cleanup);
    (void)cleanup_root_family(cleanup, cleanup_deadline, &cleanup_cleanup);
    int witness_reaped = 0;
    (void)cleanup_direct_child(witness_pid, witness, cleanup_deadline, &witness_reaped, NULL);
    int unexpected = 0;
    int root_echild = drain_to_echild(cleanup_deadline, &unexpected) == 1 && !unexpected;
    close_family(normal);
    close_family(cleanup);
    struct identity unavailable;
    memset(&unavailable, 0, sizeof(unavailable));
    const char *status = witness_reaped && root_echild ? "FAIL" : "UNKNOWN";
    int native_exit = strcmp(status, "FAIL") == 0 ? 1 : 2;
    (void)emit_result(config, status, failure_stage, native_exit, root, &unavailable, witness, normal, cleanup,
                      root_set, root_get, root_value, -1, -1, -1, 0, root_echild,
                      witness_reaped && root_echild, 0, -1, "orchestrator-only-safety-net");
    (void)fflush(stdout);
    return native_exit;
}

static int witness_run(int report_fd, int command_fd) {
    (void)alarm(CHILD_TTL_SECONDS);
    struct identity identity;
    if (process_identity(getpid(), &identity) != 0 ||
        write_timed(report_fd, &identity, sizeof(identity), monotonic_ms() + CHILD_TTL_SECONDS * 1000U) != 0) {
        _exit(127);
    }
    for (;;) {
        char command = 0;
        if (read_timed(command_fd, &command, 1, monotonic_ms() + CHILD_TTL_SECONDS * 1000U) != 0) {
            _exit(124);
        }
        if (command == 'Q') {
            _exit(0);
        }
        if (command == 'A' || command == 'B') {
            if (process_identity(getpid(), &identity) != 0 ||
                write_timed(report_fd, &identity, sizeof(identity), monotonic_ms() + CHILD_TTL_SECONDS * 1000U) != 0) {
                _exit(127);
            }
        }
    }
}

int main(int argc, char **argv) {
    struct config config;
    memset(&config, 0, sizeof(config));
    for (int index = 1; index < argc; index++) {
        if (strcmp(argv[index], "--run-id") == 0 && index + 1 < argc) {
            (void)snprintf(config.run_id, sizeof(config.run_id), "%s", argv[++index]);
        } else if (strcmp(argv[index], "--fault") == 0 && index + 1 < argc) {
            config.fault = argv[++index];
        } else {
            return 2;
        }
    }
    if (!valid_run_id(config.run_id) || (config.fault != NULL && strcmp(config.fault, "prctl") != 0 &&
                                          strcmp(config.fault, "fork") != 0 && strcmp(config.fault, "ipc") != 0 &&
                                          strcmp(config.fault, "reap") != 0 && strcmp(config.fault, "deadline") != 0 &&
                                          strcmp(config.fault, "timeout") != 0 && strcmp(config.fault, "kill") != 0 &&
                                          strcmp(config.fault, "family/create") != 0 && strcmp(config.fault, "eventpipe") != 0 &&
                                          strcmp(config.fault, "supervisorfork") != 0 && strcmp(config.fault, "witness-ipc") != 0)) {
        return 2;
    }
    (void)signal(SIGPIPE, SIG_IGN);
    expected_uid = (uint32_t)getuid();
    uint64_t started = monotonic_ms();
    root_watchdog_deadline = started + TEST_BUDGET_MS + CLEANUP_BUDGET_MS;
    int stdout_flags = fcntl(STDOUT_FILENO, F_GETFL, 0);
    if (stdout_flags >= 0) {
        (void)fcntl(STDOUT_FILENO, F_SETFL, stdout_flags | O_NONBLOCK);
    }
    uint64_t test_deadline = started + TEST_BUDGET_MS;
    if (config.fault != NULL && strcmp(config.fault, "deadline") == 0) {
        test_deadline = started + 100U;
    }
    uint64_t cleanup_deadline = test_deadline + CLEANUP_BUDGET_MS;
    struct identity root_before;
    struct identity root;
    memset(&root_before, 0, sizeof(root_before));
    memset(&root, 0, sizeof(root));
    int root_before_ok = process_identity(getpid(), &root_before) == 0;
    int root_set = prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0);
    int root_value = 0;
    int root_get = prctl(PR_GET_CHILD_SUBREAPER, &root_value);
    int root_after_ok = process_identity(getpid(), &root) == 0;
    int root_ok = root_before_ok && root_after_ok && identity_same_process(&root_before, &root) &&
                  root_set == 0 && root_get == 0 && root_value == 1;
    if (!root_ok) {
        struct identity unavailable;
        memset(&unavailable, 0, sizeof(unavailable));
        if (!root_before_ok) {
            memset(&root_before, 0, sizeof(root_before));
        }
        (void)emit_identity(config.run_id, "root", root_before_ok ? &root_before : &root);
        (void)emit_result(&config, "FAIL", "prctl", 1, &root, &unavailable, &unavailable, &(struct family){0}, &(struct family){0},
                          root_set, root_get, root_value, -1, -1, -1, 0, 0, 0, 1, -1, "orchestrator-only-safety-net");
        return 1;
    }
    (void)emit_identity(config.run_id, "root", &root);

    int witness_report[2];
    int witness_command[2];
    if (pipe(witness_report) != 0 || pipe(witness_command) != 0) {
        return 1;
    }
    pid_t witness_pid = fork();
    if (witness_pid < 0) {
        return 1;
    }
    if (witness_pid == 0) {
        (void)close(witness_report[0]);
        (void)close(witness_command[1]);
        return witness_run(witness_report[1], witness_command[0]);
    }
    (void)close(witness_report[1]);
    (void)close(witness_command[0]);
    struct family normal;
    struct family cleanup;
    family_init(&normal);
    family_init(&cleanup);
    struct identity witness;
    memset(&witness, 0, sizeof(witness));
    int witness_report_ok = config.fault == NULL || strcmp(config.fault, "witness-ipc") != 0;
    if (!witness_report_ok || read_timed(witness_report[0], &witness, sizeof(witness), test_deadline) != 0 ||
        validate_child_identity(&witness, getpid(), 1) != 0) {
        int witness_reaped = 0;
        (void)cleanup_direct_child(witness_pid, NULL, cleanup_deadline, &witness_reaped, NULL);
        int unexpected = 0;
        int root_echild = drain_to_echild(cleanup_deadline, &unexpected) == 1 && !unexpected;
        close_family(&normal);
        close_family(&cleanup);
        (void)close(witness_report[0]);
        (void)close(witness_command[1]);
        const char *failure = config.fault != NULL && strcmp(config.fault, "witness-ipc") == 0 ? "witness-ipc" : "ipc";
        const char *status = witness_reaped && root_echild ? "FAIL" : "UNKNOWN";
        int native_exit = strcmp(status, "FAIL") == 0 ? 1 : 2;
        struct identity unavailable;
        memset(&unavailable, 0, sizeof(unavailable));
        (void)emit_result(&config, status, failure, native_exit, &root, &unavailable, &witness,
                          &normal, &cleanup, root_set, root_get, root_value, -1, -1, -1, 0, root_echild,
                          witness_reaped && root_echild, 0, -1, "orchestrator-only-safety-net");
        (void)fflush(stdout);
        return native_exit;
    }
    (void)emit_identity(config.run_id, "witness_before", &witness);
    (void)write_timed(witness_command[1], "A", 1, test_deadline);
    struct identity witness_a;
    memset(&witness_a, 0, sizeof(witness_a));
    int witness_stable = read_timed(witness_report[0], &witness_a, sizeof(witness_a), test_deadline) == 0 &&
                         identity_equal(&witness, &witness_a);

    int families_ready = config.fault == NULL || strcmp(config.fault, "family/create") != 0;
    if (families_ready) {
        families_ready = family_create(&normal) == 0 && family_create(&cleanup) == 0;
    }
    if (!families_ready) {
        return emit_setup_failure(&config, &root, witness_pid, &witness, &normal, &cleanup,
                                  root_set, root_get, root_value, "family/create", cleanup_deadline);
    }
    int event_pipe[2];
    if ((config.fault != NULL && strcmp(config.fault, "eventpipe") == 0) || pipe(event_pipe) != 0) {
        return emit_setup_failure(&config, &root, witness_pid, &witness, &normal, &cleanup,
                                  root_set, root_get, root_value, "eventpipe", cleanup_deadline);
    }
    pid_t supervisor_pid = -1;
    if (config.fault == NULL || strcmp(config.fault, "supervisorfork") != 0) {
        supervisor_pid = fork();
    }
    if (supervisor_pid < 0) {
        (void)close(event_pipe[0]);
        (void)close(event_pipe[1]);
        return emit_setup_failure(&config, &root, witness_pid, &witness, &normal, &cleanup,
                                  root_set, root_get, root_value, "supervisorfork", cleanup_deadline);
    }
    if (supervisor_pid == 0) {
        (void)close(event_pipe[0]);
        int status = supervisor_run(event_pipe[1], &normal, &cleanup, config.fault, test_deadline);
        close_family(&normal);
        close_family(&cleanup);
        _exit(status);
    }
    (void)close(event_pipe[1]);
    const int root_normal[] = {normal.root_birth_r, normal.root_ack_w, normal.root_adopt_r};
    const int root_cleanup[] = {cleanup.root_birth_r, cleanup.root_ack_w, cleanup.root_adopt_r};
    close_except(&normal, root_normal, 3U);
    close_except(&cleanup, root_cleanup, 3U);
    struct message startup;
    memset(&startup, 0, sizeof(startup));
    int startup_received = receive_message(event_pipe[0], &startup, test_deadline) == 0 && startup.kind == MSG_STARTUP;
    struct identity supervisor = startup.identity;
    int supervisor_set = startup_received ? startup.set_result : -1;
    int supervisor_get = startup_received ? startup.get_result : -1;
    int supervisor_value = startup_received ? startup.get_value : -1;
    int supervisor_identity_ok = startup_received && identity_valid(&supervisor) && supervisor.pid == (int32_t)supervisor_pid &&
                                 supervisor.ppid == (int32_t)getpid();
    if (supervisor_identity_ok) {
        (void)emit_identity(config.run_id, "supervisor", &supervisor);
    }
    /* The supervisor owns the real fork.  Root receives its pre-exit birth. */
    if (read_timed(normal.root_birth_r, &normal.birth, sizeof(normal.birth), test_deadline) == 0 && identity_valid(&normal.birth)) {
        normal.have_birth = 1;
        normal.grandchild = (pid_t)normal.birth.pid;
        normal.intermediate = (pid_t)normal.birth.ppid;
        if (process_identity(normal.intermediate, &normal.intermediate_identity) == 0 &&
            normal.intermediate_identity.pid == normal.birth.ppid && normal.intermediate_identity.ppid == supervisor.pid &&
            process_identity(normal.grandchild, &normal.birth) == 0 && normal.birth.ppid == normal.intermediate &&
            write_timed(normal.root_ack_w, "\1", 1, test_deadline) == 0) {
            normal.have_intermediate = 1;
            (void)emit_identity(config.run_id, "normal_birth", &normal.birth);
            if (read_timed(normal.root_adopt_r, &normal.adopted, sizeof(normal.adopted), test_deadline) == 0 &&
                identity_valid(&normal.adopted) && normal.adopted.ppid == supervisor.pid && identity_same_process(&normal.birth, &normal.adopted)) {
                normal.have_adopted = 1;
                (void)emit_identity(config.run_id, "normal_adoption", &normal.adopted);
            }
        }
    }
    if (read_timed(cleanup.root_birth_r, &cleanup.birth, sizeof(cleanup.birth), test_deadline) == 0 &&
        identity_valid(&cleanup.birth)) {
        cleanup.have_birth = 1;
        cleanup.grandchild = (pid_t)cleanup.birth.pid;
        cleanup.intermediate = (pid_t)cleanup.birth.ppid;
        if (process_identity(cleanup.intermediate, &cleanup.intermediate_identity) == 0 &&
            cleanup.intermediate_identity.pid == cleanup.birth.ppid && cleanup.intermediate_identity.ppid == supervisor.pid &&
            process_identity(cleanup.grandchild, &cleanup.birth) == 0 && cleanup.birth.ppid == cleanup.intermediate &&
            write_timed(cleanup.root_ack_w, "\1", 1, test_deadline) == 0) {
            cleanup.have_intermediate = 1;
            (void)emit_identity(config.run_id, "cleanup_birth", &cleanup.birth);
            if (read_timed(cleanup.root_adopt_r, &cleanup.adopted, sizeof(cleanup.adopted), test_deadline) == 0 &&
                identity_valid(&cleanup.adopted) && cleanup.adopted.ppid == supervisor.pid &&
                identity_same_process(&cleanup.birth, &cleanup.adopted)) {
                cleanup.have_adopted = 1;
                (void)emit_identity(config.run_id, "cleanup_adoption", &cleanup.adopted);
            }
        }
    }
    /* If the supervisor stopped or its IPC closed, root cleanup is the safety net. */
    int supervisor_done = 0;
    int echild = 0;
    int cleanup_confirmed = 0;
    const char *failure_stage = !supervisor_identity_ok ? "identity" : (startup_received && startup.status != 1 ? "prctl" : NULL);
    struct message message;
    while (!supervisor_done && monotonic_ms() < test_deadline) {
        if (receive_message(event_pipe[0], &message, test_deadline) != 0) {
            failure_stage = monotonic_ms() >= test_deadline ? "timeout" : "ipc";
            break;
        }
        if (message.kind == MSG_STARTUP) {
            continue;
        }
        if (message.kind == MSG_NORMAL_INTERMEDIATE) {
            normal.intermediate_status = message.status;
            normal.intermediate_reaped = message.status >= 0;
        } else if (message.kind == MSG_NORMAL_EXIT) {
            normal.grandchild_status = message.status;
            normal.grandchild_reaped = message.status >= 0;
        } else if (message.kind == MSG_CLEANUP_INTERMEDIATE) {
            cleanup.intermediate_status = message.status;
            cleanup.intermediate_reaped = message.status >= 0;
        } else if (message.kind == MSG_CLEANUP_EXIT) {
            cleanup.grandchild_status = message.status;
            cleanup.grandchild_reaped = message.status >= 0;
        } else if (message.kind == MSG_CLEANUP_ADOPTION) {
            cleanup.adopted = message.identity;
            cleanup.have_adopted = message.status == 1;
        } else if (message.kind == MSG_ECHILD) {
            echild = message.status == 1;
        } else if (message.kind == MSG_DONE) {
            supervisor_done = 1;
            if (message.status != 1) {
                failure_stage = failure_stage == NULL ? "reap" : failure_stage;
            }
        } else if (message.kind == MSG_NORMAL_ADOPTION) {
            /* Root's own identity record is authoritative; this is only a cross-check. */
            if (!normal.have_adopted || !identity_equal(&message.identity, &normal.adopted)) {
                failure_stage = "identity";
            }
        }
    }
    if (!supervisor_done && failure_stage == NULL) {
        failure_stage = "timeout";
    }
    int supervisor_status = -1;
    int supervisor_reaped = 0;
    if (failure_stage != NULL || !supervisor_done) {
        /* Without a startup record, the fork PID is not an identity reservation
         * and must not be signalled.  cleanup_direct_child will only wait for
         * that known direct child in this case. */
        const struct identity *supervisor_hint = &supervisor;
        (void)cleanup_direct_child(supervisor_pid, supervisor_hint, cleanup_deadline, &supervisor_reaped, &supervisor_status);
        int one = 0;
        int other = 0;
        (void)cleanup_root_family(&normal, cleanup_deadline, &one);
        (void)cleanup_root_family(&cleanup, cleanup_deadline, &other);
        cleanup_confirmed = one && other;
        if (failure_stage == NULL) {
            failure_stage = "kill";
        }
    } else {
        int reaped = 0;
        (void)wait_exact(supervisor_pid, &supervisor_status, cleanup_deadline, &reaped);
        supervisor_reaped = reaped;
        cleanup_confirmed = normal.grandchild_reaped && cleanup.grandchild_reaped;
    }
    char command = 'B';
    int witness_after_ok = write_timed(witness_command[1], &command, 1, cleanup_deadline) == 0;
    struct identity witness_after;
    memset(&witness_after, 0, sizeof(witness_after));
    witness_after_ok = witness_after_ok && read_timed(witness_report[0], &witness_after, sizeof(witness_after), cleanup_deadline) == 0;
    witness_stable = witness_stable && witness_after_ok && identity_equal(&witness, &witness_after);
    command = 'Q';
    (void)write_timed(witness_command[1], &command, 1, cleanup_deadline);
    int witness_status = -1;
    int witness_reaped = 0;
    /* Q is cooperative exit, not a request to terminate immediately.  Keep
     * this child unreaped and allow a bounded grace before TERM/KILL fallback. */
    uint64_t witness_grace = monotonic_ms() + 200U;
    if (witness_grace > cleanup_deadline) {
        witness_grace = cleanup_deadline;
    }
    (void)wait_exact(witness_pid, &witness_status, witness_grace, &witness_reaped);
    if (!witness_reaped) {
        (void)cleanup_direct_child(witness_pid, &witness, cleanup_deadline, &witness_reaped, &witness_status);
    }
    (void)printf("{\"schema\":\"%s\",\"runId\":\"%s\",\"event\":\"witness_exit\",\"pid\":%ld,\"startTicks\":%llu,\"exitCode\":%d,\"reaped\":%s}\n",
                 SCHEMA, config.run_id, (long)witness.pid, (unsigned long long)witness.start_ticks,
                 exit_code(witness_status, witness_reaped), witness_reaped ? "true" : "false");
    int unexpected_root_child = 0;
    int root_echild = drain_to_echild(cleanup_deadline, &unexpected_root_child) == 1 && !unexpected_root_child;
    cleanup_confirmed = cleanup_confirmed && witness_reaped;
    close_family(&normal);
    close_family(&cleanup);
    (void)close(event_pipe[0]);
    (void)close(witness_report[0]);
    (void)close(witness_command[1]);
    int pass = startup_received && supervisor_done && supervisor_reaped && WIFEXITED(supervisor_status) &&
               WEXITSTATUS(supervisor_status) == 0 && normal.have_adopted && cleanup.have_adopted && echild &&
               cleanup_confirmed && normal.intermediate_status == NORMAL_INTERMEDIATE_EXIT &&
               normal.grandchild_status == NORMAL_GRANDCHILD_EXIT && cleanup.intermediate_status == CLEANUP_INTERMEDIATE_EXIT &&
               cleanup.grandchild_status == CLEANUP_GRANDCHILD_EXIT && witness_stable && witness_reaped &&
               WIFEXITED(witness_status) && WEXITSTATUS(witness_status) == 0 && root_echild &&
               root_set == 0 && root_get == 0 && root_value == 1 && supervisor_set == 0 && supervisor_get == 0 && supervisor_value == 1;
    if (config.fault != NULL && (strcmp(config.fault, "deadline") == 0 || strcmp(config.fault, "timeout") == 0)) {
        pass = 0;
        failure_stage = "timeout";
    } else if (config.fault != NULL && strcmp(config.fault, "fork") == 0) {
        pass = 0;
        failure_stage = "fork";
    } else if (config.fault != NULL && strcmp(config.fault, "reap") == 0) {
        pass = 0;
        failure_stage = "reap";
    } else if (pass) {
        failure_stage = "none";
    } else if (failure_stage == NULL) {
        failure_stage = "fact";
    }
    int cleanup_unresolved = !cleanup_confirmed || !root_echild || !witness_reaped;
    const char *status = pass ? "PASS" : (cleanup_unresolved || strcmp(failure_stage, "timeout") == 0 || strcmp(failure_stage, "kill") == 0 ? "UNKNOWN" : "FAIL");
    int native_exit = strcmp(status, "PASS") == 0 ? 0 : (strcmp(status, "UNKNOWN") == 0 ? 2 : 1);
    (void)emit_result(&config, status, failure_stage, native_exit, &root, &supervisor, &witness, &normal, &cleanup,
                       root_set, root_get, root_value, supervisor_set, supervisor_get, supervisor_value, echild, root_echild,
                      cleanup_confirmed, witness_stable, supervisor_reaped && WIFEXITED(supervisor_status) ? WEXITSTATUS(supervisor_status) : -1,
                      "orchestrator-only-safety-net");
    (void)fflush(stdout);
    return native_exit;
}
