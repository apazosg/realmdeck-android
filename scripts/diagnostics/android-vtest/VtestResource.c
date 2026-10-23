/*
 * Bounded vtest resource round-trip client.
 *
 * This is a protocol/resource qualification probe only.  It creates one
 * four-byte PIPE_BUFFER, writes four fixed bytes through TRANSFER_PUT, reads
 * them back through TRANSFER_GET, and then unreferences the resource.  It
 * deliberately does not draw, present, use Wine, or claim graphics support.
 */
#ifndef VTEST_TESTING
#define VTEST_TESTING 1
#define VTEST_RESOURCE_DEFINED_TESTING 1
#endif

/* Reuse the existing identity, /proc, socket, timeout, and caps helpers. */
#include "VtestHandshake.c"

#define RESOURCE_BYTES 4U
#define RESOURCE_STAGE_CAP 24U
#define RESOURCE_OUTPUT_CAP 8192U

static const uint8_t resource_written[RESOURCE_BYTES] = {0xa1U, 0xb2U, 0xc3U, 0xd4U};

struct resource_state {
    const char *run_id;
    pid_t native_pid;
    uid_t uid;
    pid_t peer_pid;
    uid_t peer_uid;
    struct server_identity expected_server;
    int server_identity_verified;
    uint64_t started_ms;
    const char *stages[RESOURCE_STAGE_CAP];
    size_t stage_count;
    struct caps_summary caps;
    int caps_received;
    uint8_t readback[RESOURCE_BYTES];
    size_t readback_len;
    int resource_transfer_verified;
    int resource_unref_sent;
};

static void resource_add_stage(struct resource_state *state, const char *stage) {
    if (state->stage_count < RESOURCE_STAGE_CAP) {
        state->stages[state->stage_count++] = stage;
    }
}

static size_t resource_encode_words(uint8_t *output, size_t capacity,
                                    uint32_t length_dw, uint32_t command,
                                    const uint32_t *words, size_t word_count,
                                    const uint8_t *tail, size_t tail_length) {
    const size_t total = 8U + (word_count * sizeof(uint32_t)) + tail_length;
    size_t index;

    if (output == NULL || words == NULL || capacity < total ||
        (tail_length > 0U && tail == NULL)) {
        return 0U;
    }
    write_le32(output, length_dw);
    write_le32(output + 4U, command);
    for (index = 0U; index < word_count; ++index) {
        write_le32(output + 8U + (index * sizeof(uint32_t)), words[index]);
    }
    if (tail_length > 0U) {
        memcpy(output + 8U + (word_count * sizeof(uint32_t)), tail, tail_length);
    }
    return total;
}

static size_t resource_encode_create(uint8_t *output, size_t capacity) {
    static const uint32_t words[10] = {
        1U, 0U, 64U, 0U, 4U, 1U, 1U, 1U, 0U, 0U,
    };
    return resource_encode_words(output, capacity, 10U, 2U, words,
                                 sizeof(words) / sizeof(words[0]), NULL, 0U);
}

static size_t resource_encode_put(uint8_t *output, size_t capacity) {
    static const uint32_t words[11] = {
        1U, 0U, 4U, 4U, 0U, 0U, 0U, 4U, 1U, 1U, 4U,
    };
    return resource_encode_words(output, capacity, 12U, 5U, words,
                                 sizeof(words) / sizeof(words[0]),
                                 resource_written, sizeof(resource_written));
}

static size_t resource_encode_get(uint8_t *output, size_t capacity) {
    static const uint32_t words[11] = {
        1U, 0U, 4U, 4U, 0U, 0U, 0U, 4U, 1U, 1U, 4U,
    };
    return resource_encode_words(output, capacity, 11U, 4U, words,
                                 sizeof(words) / sizeof(words[0]), NULL, 0U);
}

static size_t resource_encode_unref(uint8_t *output, size_t capacity) {
    static const uint32_t words[1] = {1U};
    return resource_encode_words(output, capacity, 1U, 3U, words, 1U, NULL, 0U);
}

static int resource_peer_values_match(pid_t peer_pid, uid_t peer_uid,
                                      const struct server_identity *expected,
                                      uint64_t observed_ticks) {
    return expected != NULL && peer_pid > 0 && peer_uid == VTEST_UID &&
           peer_pid == expected->pid && observed_ticks == expected->start_ticks;
}

static int resource_verify_peer(int fd, struct resource_state *state) {
    struct ucred credentials;
    socklen_t credentials_length = sizeof(credentials);
    uint64_t observed_ticks;

    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &credentials, &credentials_length) != 0 ||
        !read_proc_start_ticks(&state->expected_server, &observed_ticks) ||
        !resource_peer_values_match(credentials.pid, credentials.uid,
                                    &state->expected_server, observed_ticks)) {
        state->server_identity_verified = 0;
        return 0;
    }
    state->peer_pid = credentials.pid;
    state->peer_uid = credentials.uid;
    state->server_identity_verified = 1;
    return 1;
}

static int resource_readback_matches(const uint8_t *data, size_t length) {
    return data != NULL && length == RESOURCE_BYTES &&
           memcmp(data, resource_written, RESOURCE_BYTES) == 0;
}

static void resource_hex(const uint8_t *data, size_t length, char *output, size_t capacity) {
    static const char digits[] = "0123456789abcdef";
    size_t index;

    if (capacity == 0U) {
        return;
    }
    if (data == NULL || length > (capacity - 1U) / 2U) {
        output[0] = '\0';
        return;
    }
    for (index = 0U; index < length; ++index) {
        output[index * 2U] = digits[data[index] >> 4U];
        output[index * 2U + 1U] = digits[data[index] & 0x0fU];
    }
    output[length * 2U] = '\0';
}

static int resource_emit_event(const struct resource_state *state, const char *status) {
    char output[RESOURCE_OUTPUT_CAP];
    char readback_hex[(RESOURCE_BYTES * 2U) + 1U];
    const uint64_t finished_ms = monotonic_ms();
    const uint64_t duration = finished_ms >= state->started_ms ?
                              finished_ms - state->started_ms : 0U;
    const uint32_t cap_bytes = state->caps_received ? state->caps.bytes : 0U;
    const uint32_t cap_nonzero = state->caps_received ? state->caps.nonzero_bytes : 0U;
    const uint64_t caps_hash = state->caps_received ? state->caps.fnv1a : 0U;
    size_t used = 0U;
    size_t index;
    int written;

    resource_hex(state->readback, state->readback_len, readback_hex,
                 sizeof(readback_hex));
    written = snprintf(output + used, sizeof(output) - used,
                       "{\"runId\":\"%s\",\"status\":\"%s\","
                       "\"nativePid\":%ld,\"uid\":%lu,\"peerPid\":%ld,"
                       "\"peerUid\":%lu,\"expectedServerPid\":%ld,"
                       "\"expectedServerStartTicks\":%" PRIu64 ","
                       "\"serverIdentityVerified\":%s,\"stages\":[",
                       state->run_id, status, (long)state->native_pid,
                       (unsigned long)state->uid, (long)state->peer_pid,
                       (unsigned long)state->peer_uid, (long)state->expected_server.pid,
                       state->expected_server.start_ticks,
                       state->server_identity_verified ? "true" : "false");
    if (written < 0 || (size_t)written >= sizeof(output) - used) {
        return 0;
    }
    used += (size_t)written;
    for (index = 0U; index < state->stage_count; ++index) {
        written = snprintf(output + used, sizeof(output) - used, "%s\"%s\"",
                           index == 0U ? "" : ",", state->stages[index]);
        if (written < 0 || (size_t)written >= sizeof(output) - used) {
            return 0;
        }
        used += (size_t)written;
    }
    written = snprintf(output + used, sizeof(output) - used,
                       "],\"capBytes\":%" PRIu32 ",\"capNonzeroBytes\":%" PRIu32
                       ",\"capsFNVhash\":\"%016" PRIx64 "\",\"durationMs\":%" PRIu64
                       ",\"resourceId\":1,\"resourceBytes\":4,"
                       "\"writtenHex\":\"a1b2c3d4\",\"readbackHex\":\"%s\","
                       "\"resourceTransferVerified\":%s,\"resourceUnrefSent\":%s,"
                       "\"egl\":false,\"gpu\":false,\"triangle\":false,"
                       "\"device\":false,\"hardware\":false,\"present\":false,"
                       "\"wowPass\":false,\"render\":false}\n",
                       cap_bytes, cap_nonzero, caps_hash, duration, readback_hex,
                       state->resource_transfer_verified ? "true" : "false",
                       state->resource_unref_sent ? "true" : "false");
    if (written < 0 || (size_t)written >= sizeof(output) - used) {
        return 0;
    }
    used += (size_t)written;
    if (fwrite(output, 1U, used, stdout) != used || fflush(stdout) != 0) {
        return 0;
    }
    return 1;
}

static VTEST_UNUSED int run_resource(struct resource_state *state) {
    int fd = -1;
    int success = 0;
    int cleanup_ok = 1;
    uint8_t packet[64];
    const uint64_t total_deadline = state->started_ms + (TOTAL_SECONDS * 1000U);

    if (getuid() != VTEST_UID) {
        resource_add_stage(state, "uid_rejected");
        goto finish;
    }
    resource_add_stage(state, "uid_verified");
    if (!allowed_cwd(state->run_id)) {
        resource_add_stage(state, "cwd_rejected");
        goto finish;
    }
    resource_add_stage(state, "cwd_verified");
    if (!load_server_identity(&state->expected_server)) {
        resource_add_stage(state, "server_identity_rejected");
        goto finish;
    }
    resource_add_stage(state, "server_identity_loaded");
    if (!wait_for_socket(VTEST_UID, total_deadline)) {
        resource_add_stage(state, "socket_timeout");
        goto finish;
    }
    resource_add_stage(state, "socket_verified");

    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0 || !nonblocking_socket(fd)) {
        resource_add_stage(state, "socket_open_failed");
        goto finish;
    }
    if (!connect_socket(fd, total_deadline)) {
        resource_add_stage(state, "connect_failed");
        goto finish;
    }
    resource_add_stage(state, "connected");
    if (!resource_verify_peer(fd, state)) {
        resource_add_stage(state, "peer_rejected");
        goto finish;
    }
    resource_add_stage(state, "peer_verified");
    resource_add_stage(state, "server_identity_verified");

    if (resource_encode_words(packet, sizeof(packet), 6U, 8U,
                              (const uint32_t[]){0U}, 0U,
                              (const uint8_t[]){'v', 't', 'e', 's', 't', '\0'}, 6U) == 0U ||
        !send_all(fd, packet, 14U, total_deadline)) {
        resource_add_stage(state, "request_failed");
        goto finish;
    }
    write_le32(packet, 0U);
    write_le32(packet + 4U, 1U);
    if (!send_all(fd, packet, 8U, total_deadline)) {
        resource_add_stage(state, "request_failed");
        goto finish;
    }
    resource_add_stage(state, "request_sent");
    if (!receive_caps(fd, total_deadline, &state->caps)) {
        resource_add_stage(state, "response_rejected");
        goto finish;
    }
    state->caps_received = 1;
    resource_add_stage(state, "capabilities_received");

    if (!resource_verify_peer(fd, state)) {
        resource_add_stage(state, "server_identity_rejected");
        goto finish;
    }

    if (resource_encode_create(packet, sizeof(packet)) == 0U ||
        !send_all(fd, packet, 48U, total_deadline)) {
        resource_add_stage(state, "resource_create_failed");
        goto finish;
    }
    resource_add_stage(state, "resource_create_sent");
    if (resource_encode_put(packet, sizeof(packet)) == 0U ||
        !send_all(fd, packet, 56U, total_deadline)) {
        resource_add_stage(state, "transfer_put_failed");
        goto finish;
    }
    resource_add_stage(state, "transfer_put_sent");
    if (resource_encode_get(packet, sizeof(packet)) == 0U ||
        !send_all(fd, packet, 52U, total_deadline)) {
        resource_add_stage(state, "transfer_get_failed");
        goto finish;
    }
    resource_add_stage(state, "transfer_get_sent");
    if (!receive_exact(fd, state->readback, RESOURCE_BYTES, total_deadline)) {
        resource_add_stage(state, "readback_rejected");
        goto finish;
    }
    state->readback_len = RESOURCE_BYTES;
    if (!resource_readback_matches(state->readback, state->readback_len)) {
        resource_add_stage(state, "readback_rejected");
        goto finish;
    }
    state->resource_transfer_verified = 1;
    resource_add_stage(state, "readback_verified");
    if (!resource_verify_peer(fd, state)) {
        resource_add_stage(state, "server_identity_rejected");
        goto finish;
    }
    if (resource_encode_unref(packet, sizeof(packet)) == 0U ||
        !send_all(fd, packet, 12U, total_deadline)) {
        resource_add_stage(state, "resource_unref_failed");
        goto finish;
    }
    state->resource_unref_sent = 1;
    resource_add_stage(state, "resource_unref_sent");
    if (!resource_verify_peer(fd, state)) {
        resource_add_stage(state, "server_identity_rejected");
        goto finish;
    }
    resource_add_stage(state, "server_identity_revalidated");
    if (shutdown(fd, SHUT_WR) != 0) {
        resource_add_stage(state, "shutdown_failed");
        goto finish;
    }
    if (!receive_expected_eof(fd, total_deadline)) {
        resource_add_stage(state, "response_eof_rejected");
        goto finish;
    }
    resource_add_stage(state, "response_eofverified");
    success = 1;

finish:
    if (fd >= 0 && close(fd) != 0) {
        cleanup_ok = 0;
    }
    resource_add_stage(state, "cleanup");
    if (!cleanup_ok) {
        success = 0;
    }
    if (monotonic_ms() == 0U) {
        success = 0;
    }
    if (!resource_emit_event(state, success ? "RESOURCE_ROUNDTRIP" : "UNKNOWN")) {
        return 0;
    }
    return success;
}

#ifdef VTEST_TESTING
size_t vtest_resource_encode_create_request(uint8_t *output, size_t capacity) {
    return resource_encode_create(output, capacity);
}

size_t vtest_resource_encode_put_request(uint8_t *output, size_t capacity) {
    return resource_encode_put(output, capacity);
}

size_t vtest_resource_encode_get_request(uint8_t *output, size_t capacity) {
    return resource_encode_get(output, capacity);
}

size_t vtest_resource_encode_unref_request(uint8_t *output, size_t capacity) {
    return resource_encode_unref(output, capacity);
}

int vtest_resource_readback_is_exact(const uint8_t *data, size_t length) {
    return resource_readback_matches(data, length);
}

int vtest_resource_peer_matches(pid_t peer_pid, uid_t peer_uid,
                                pid_t expected_pid, uint64_t expected_ticks,
                                uint64_t observed_ticks) {
    const struct server_identity expected = {expected_pid, expected_ticks};
    return resource_peer_values_match(peer_pid, peer_uid, &expected, observed_ticks);
}

int vtest_resource_test_event(void) {
    struct resource_state state;
    memset(&state, 0, sizeof(state));
    state.run_id = "VTEST-resource";
    state.native_pid = getpid();
    state.uid = VTEST_UID;
    state.peer_pid = 202;
    state.peer_uid = VTEST_UID;
    state.expected_server.pid = 202;
    state.expected_server.start_ticks = 123U;
    state.server_identity_verified = 1;
    state.started_ms = monotonic_ms();
    state.caps.bytes = 4U;
    state.caps.nonzero_bytes = 4U;
    state.caps.fnv1a = UINT64_C(0x0123456789abcdef);
    state.caps_received = 1;
    memcpy(state.readback, resource_written, RESOURCE_BYTES);
    state.readback_len = RESOURCE_BYTES;
    state.resource_transfer_verified = 1;
    state.resource_unref_sent = 1;
    resource_add_stage(&state, "uid_verified");
    resource_add_stage(&state, "cwd_verified");
    resource_add_stage(&state, "server_identity_loaded");
    resource_add_stage(&state, "socket_verified");
    resource_add_stage(&state, "connected");
    resource_add_stage(&state, "peer_verified");
    resource_add_stage(&state, "server_identity_verified");
    resource_add_stage(&state, "request_sent");
    resource_add_stage(&state, "capabilities_received");
    resource_add_stage(&state, "resource_create_sent");
    resource_add_stage(&state, "transfer_put_sent");
    resource_add_stage(&state, "transfer_get_sent");
    resource_add_stage(&state, "readback_verified");
    resource_add_stage(&state, "resource_unref_sent");
    resource_add_stage(&state, "server_identity_revalidated");
    resource_add_stage(&state, "response_eofverified");
    resource_add_stage(&state, "cleanup");
    return resource_emit_event(&state, "RESOURCE_ROUNDTRIP") ? 0 : 1;
}
#endif

#ifdef VTEST_RESOURCE_DEFINED_TESTING
int main(int argc, char **argv) {
    struct resource_state state;

    if (argc != 3 || strcmp(argv[1], "--run-id") != 0 || !valid_run_id(argv[2])) {
        return 2;
    }
    memset(&state, 0, sizeof(state));
    state.run_id = argv[2];
    state.native_pid = getpid();
    state.uid = getuid();
    state.started_ms = monotonic_ms();
    if (state.started_ms == 0U) {
        resource_add_stage(&state, "clock_unavailable");
        (void)resource_emit_event(&state, "UNKNOWN");
        return 1;
    }
    return run_resource(&state) ? 0 : 1;
}
#endif
