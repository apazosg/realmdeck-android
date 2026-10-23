#include "native_run_common.h"

#include <signal.h>

static int fixture_identity_json(const char *run_id, const char *nonce,
                                 const struct native_run_identity *identity) {
    char line[NATIVE_RUN_MAX_LINE];
    int length = snprintf(line, sizeof(line),
                          "{\"schema\":1,\"runId\":\"%s\",\"nonce\":\"%s\",\"seq\":1,"
                          "\"phase\":0,\"event\":\"READY\",\"pid\":%ld,\"startTicks\":%llu,"
                          "\"ppid\":%ld,\"uid0\":%u,\"uid1\":%u,\"uid2\":%u,\"uid3\":%u,"
                          "\"role\":\"witness\",\"status\":\"READY\",\"exitCode\":-1,"
                          "\"childCount\":0,\"echild\":false,\"setResult\":-1,\"getResult\":-1,\"getValue\":-1}\n",
                          run_id, nonce, (long)identity->pid,
                          (unsigned long long)identity->start_ticks, (long)identity->ppid,
                          identity->uid[0], identity->uid[1], identity->uid[2], identity->uid[3]);
    return length > 0 && (size_t)length < sizeof(line) &&
           native_run_write_full(STDOUT_FILENO, line, (size_t)length,
                                 native_run_now_ms() + NATIVE_RUN_OPERATION_MS, 0U, NULL) == 0 ? 0 : -1;
}

static int fixture_witness(const char *run_id, const char *nonce) {
    struct native_run_identity identity;
    char command[4];
    (void)alarm(NATIVE_RUN_FIXTURE_TTL_SECONDS);
    if (native_run_process_identity(getpid(), &identity) != 0 ||
        !native_run_identity_owned(&identity, getuid()) ||
        native_run_nonblocking(STDIN_FILENO) != 0 ||
        native_run_nonblocking(STDOUT_FILENO) != 0 ||
        fixture_identity_json(run_id, nonce, &identity) != 0) {
        return 127;
    }
    size_t used = 0U;
    for (;;) {
        struct pollfd pfd = {STDIN_FILENO, POLLIN | POLLHUP, 0};
        int ready = poll(&pfd, 1U, 1000);
        if (ready < 0 && errno == EINTR) {
            continue;
        }
        if (ready <= 0) {
            continue;
        }
        ssize_t count = read(STDIN_FILENO, command, sizeof(command));
        if (count <= 0) {
            return 126;
        }
        for (ssize_t index = 0; index < count; index++) {
            if (command[index] == 'Q') {
                return 0;
            }
            if (command[index] != '\n' && command[index] != '\r') {
                used++;
                if (used > 1U) {
                    return 126;
                }
            }
        }
    }
}

static int fixture_fd(const char *text, int *value) {
    uint64_t number;
    if (native_run_decimal(text, &number) != 0 || number > INT_MAX) {
        return -1;
    }
    *value = (int)number;
    return 0;
}

static int fixture_family(int argc, char **argv) {
    int birth_fd = -1;
    int ack_fd = -1;
    int gate_read = -1;
    int gate_write = -1;
    int release_fd = -1;
    for (int index = 3; index < argc; index += 2) {
        if (index + 1 >= argc) {
            return 127;
        }
        int *destination = NULL;
        if (strcmp(argv[index], "--birth-fd") == 0) {
            destination = &birth_fd;
        } else if (strcmp(argv[index], "--ack-fd") == 0) {
            destination = &ack_fd;
        } else if (strcmp(argv[index], "--gate-read-fd") == 0) {
            destination = &gate_read;
        } else if (strcmp(argv[index], "--gate-write-fd") == 0) {
            destination = &gate_write;
        } else if (strcmp(argv[index], "--release-fd") == 0) {
            destination = &release_fd;
        } else {
            return 127;
        }
        if (fixture_fd(argv[index + 1], destination) != 0) {
            return 127;
        }
    }
    if (birth_fd < 0 || ack_fd < 0 || gate_read < 0 || gate_write < 0 || release_fd < 0) {
        return 127;
    }
    (void)alarm(NATIVE_RUN_FIXTURE_TTL_SECONDS);
    pid_t grandchild = fork();
    if (grandchild < 0) {
        return 126;
    }
    if (grandchild > 0) {
        unsigned char acknowledgement = 0U;
        if (native_run_read_full(ack_fd, &acknowledgement, 1U,
                                 native_run_now_ms() + NATIVE_RUN_FIXTURE_TTL_SECONDS * 1000ULL, 0U) != 0 ||
            acknowledgement != 1U) {
            /* A cancelled supervisor still needs a deterministic adoption
             * window.  The grandchild remains held on release_fd until the
             * supervisor has observed its new PPID and reaped it. */
            unsigned char gate = 1U;
            if (write(gate_write, &gate, 1U) != 1) _exit(125);
            _exit(125);
        }
        unsigned char gate = 1U;
        if (write(gate_write, &gate, 1U) != 1) {
            _exit(125);
        }
        _exit(0);
    }
    (void)close(ack_fd);
    (void)close(gate_write);
    struct native_run_identity identity;
    if (native_run_process_identity(getpid(), &identity) != 0 ||
        !native_run_identity_owned(&identity, getuid()) ||
        native_run_write_full(birth_fd, &identity, sizeof(identity),
                              native_run_now_ms() + NATIVE_RUN_OPERATION_MS, 0U, NULL) != 0) {
        _exit(124);
    }
    unsigned char gate = 0U;
    if (native_run_read_full(gate_read, &gate, 1U,
                             native_run_now_ms() + NATIVE_RUN_OPERATION_MS, 0U) != 0 || gate != 1U) {
        _exit(124);
    }
    unsigned char release = 0U;
    if (native_run_read_full(release_fd, &release, 1U,
                             native_run_now_ms() + NATIVE_RUN_FIXTURE_TTL_SECONDS * 1000ULL, 0U) == 0 &&
        release == 'R') {
        _exit(0);
    }
    _exit(143);
}

int main(int argc, char **argv) {
    if (argc >= 4 && strcmp(argv[1], "--witness") == 0 &&
        native_run_valid_id(argv[2], "NS-") && native_run_valid_hex(argv[3], 32U)) {
        return fixture_witness(argv[2], argv[3]);
    }
    if (argc >= 2 && strcmp(argv[1], "--role") == 0 &&
        argc >= 4 && strcmp(argv[2], "family") == 0) {
        return fixture_family(argc, argv);
    }
    return 2;
}
