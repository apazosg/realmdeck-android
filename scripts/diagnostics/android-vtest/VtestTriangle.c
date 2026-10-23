/*
 * Bounded protocol-0 vtest triangle client.
 *
 * This is a native off-screen rendering gate.  It deliberately has no EGL,
 * GLES, virglrenderer, WSI, presentation, or Wine dependency: the verified
 * vtest server owns the renderer and this file only emits the protocol that
 * the official virglrenderer tests emit.  Capability bytes are retained as an
 * opaque summary and are never used to manufacture a feature decision.
 */
#ifndef VTEST_TESTING
#define VTEST_TESTING 1
#define VTEST_TRIANGLE_DEFINED_TESTING 1
#endif

#include "VtestHandshake.c"

#define TRI_WIDTH 64U
#define TRI_HEIGHT 64U
#define TRI_PIXEL_BYTES 4U
#define TRI_READBACK_BYTES (TRI_WIDTH * TRI_HEIGHT * TRI_PIXEL_BYTES)
#define TRI_CMDBUF_CAPACITY 4096U
#define TRI_PACKET_CAPACITY (TRI_CMDBUF_CAPACITY + 16U)
#define TRI_OUTPUT_CAPACITY 4096U
#define TRI_STAGE_CAPACITY 48U

#define TRI_TARGET 1U
#define TRI_VBO 2U
#define TRI_SURFACE 3U
#define TRI_VERTEX_ELEMENTS 4U
#define TRI_VERTEX_SHADER 5U
#define TRI_FRAGMENT_SHADER 6U
#define TRI_BLEND 7U
#define TRI_DSA 8U
#define TRI_RASTERIZER 9U

/* Protocol and Gallium values copied by meaning from the pinned 1.3.0 headers. */
#define TRI_VTEST_CREATE_RENDERER 8U
#define TRI_VTEST_GET_CAPS 1U
#define TRI_VTEST_RESOURCE_CREATE 2U
#define TRI_VTEST_RESOURCE_UNREF 3U
#define TRI_VTEST_TRANSFER_GET 4U
#define TRI_VTEST_SUBMIT_CMD 6U
#define TRI_VTEST_BUSY_WAIT 7U
#define TRI_VTEST_BUSY_WAIT_FLAG_WAIT 1U
#define TRI_PIPE_BUFFER 0U
#define TRI_PIPE_TEXTURE_2D 2U
#define TRI_FORMAT_B8G8R8X8_UNORM 2U
#define TRI_FORMAT_R8_UNORM 64U
#define TRI_BIND_RENDER_TARGET (1U << 1)
#define TRI_BIND_VERTEX_BUFFER (1U << 4)
#define TRI_PIPE_CLEAR_COLOR0 (1U << 2)
#define TRI_PIPE_SHADER_VERTEX 0U
#define TRI_PIPE_SHADER_FRAGMENT 1U
#define TRI_PIPE_PRIM_TRIANGLES 4U
#define TRI_PIPE_FORMAT_R32G32B32A32_FLOAT 31U
#define TRI_PIPE_MASK_RGBA 0xfU
#define TRI_PIPE_FUNC_LESS 1U

#define TRI_CCMD_CREATE_OBJECT 1U
#define TRI_CCMD_BIND_OBJECT 2U
#define TRI_CCMD_DESTROY_OBJECT 3U
#define TRI_CCMD_SET_VIEWPORT_STATE 4U
#define TRI_CCMD_SET_FRAMEBUFFER_STATE 5U
#define TRI_CCMD_SET_VERTEX_BUFFERS 6U
#define TRI_CCMD_CLEAR 7U
#define TRI_CCMD_DRAW_VBO 8U
#define TRI_CCMD_RESOURCE_INLINE_WRITE 9U
#define TRI_CCMD_BIND_SHADER 31U
#define TRI_CCMD_LINK_SHADER 52U

#define TRI_OBJECT_BLEND 1U
#define TRI_OBJECT_RASTERIZER 2U
#define TRI_OBJECT_DSA 3U
#define TRI_OBJECT_SHADER 4U
#define TRI_OBJECT_VERTEX_ELEMENTS 5U
#define TRI_OBJECT_SURFACE 8U

#define TRI_SHADER_OFFSET_CONT (1U << 31)

struct triangle_packet {
    uint8_t *data;
    size_t length;
    size_t capacity;
    int failed;
};

struct triangle_readback {
    uint32_t width;
    uint32_t height;
    uint32_t readback_bytes;
    uint32_t clear_pixels;
    uint32_t triangle_pixels;
    uint32_t green_pixels;
    int clear_verified;
    int nonuniform_rgb;
    uint64_t fnv1a;
};

struct triangle_state {
    const char *run_id;
    pid_t native_pid;
    uid_t uid;
    pid_t peer_pid;
    uid_t peer_uid;
    struct server_identity expected_server;
    int server_identity_verified;
    uint64_t started_ms;
    const char *stages[TRI_STAGE_CAPACITY];
    size_t stage_count;
    struct caps_summary caps;
    int caps_received;
    int target_created;
    int vbo_created;
    int objects_created;
    int object_cleanup_sent;
    int resource_unref_sent;
    struct triangle_readback result;
    uint8_t readback[TRI_READBACK_BYTES];
};

static void triangle_add_stage(struct triangle_state *state, const char *stage) {
    if (state->stage_count < TRI_STAGE_CAPACITY) {
        state->stages[state->stage_count++] = stage;
    }
}

static void triangle_packet_init(struct triangle_packet *packet, uint8_t *data,
                                 size_t capacity) {
    packet->data = data;
    packet->length = 0U;
    packet->capacity = capacity;
    packet->failed = 0;
}

static void triangle_packet_word(struct triangle_packet *packet, uint32_t value) {
    if (packet->failed || packet->capacity < 4U ||
        packet->length > packet->capacity - 4U) {
        packet->failed = 1;
        return;
    }
    write_le32(packet->data + packet->length, value);
    packet->length += 4U;
}

static void triangle_packet_bytes(struct triangle_packet *packet, const uint8_t *data,
                                  size_t length) {
    size_t padding;
    if (packet->failed || data == NULL || length > packet->capacity - packet->length) {
        packet->failed = 1;
        return;
    }
    memcpy(packet->data + packet->length, data, length);
    packet->length += length;
    padding = (4U - (length & 3U)) & 3U;
    if (padding > packet->capacity - packet->length) {
        packet->failed = 1;
        return;
    }
    memset(packet->data + packet->length, 0, padding);
    packet->length += padding;
}

static void triangle_packet_command(struct triangle_packet *packet, uint32_t command,
                                    uint32_t object, uint32_t length_dw) {
    if (length_dw > 0xffffU) {
        packet->failed = 1;
        return;
    }
    triangle_packet_word(packet, command | (object << 8U) | (length_dw << 16U));
}

static size_t triangle_packet_finish(const struct triangle_packet *packet) {
    return packet->failed ? 0U : packet->length;
}

static void triangle_append_destroy(struct triangle_packet *packet, uint32_t object,
                                    uint32_t handle) {
    triangle_packet_command(packet, TRI_CCMD_DESTROY_OBJECT, object, 1U);
    triangle_packet_word(packet, handle);
}

static void triangle_append_resource_create(struct triangle_packet *packet,
                                            uint32_t handle, uint32_t target,
                                            uint32_t format, uint32_t bind,
                                            uint32_t width, uint32_t height) {
    triangle_packet_word(packet, 10U);
    triangle_packet_word(packet, TRI_VTEST_RESOURCE_CREATE);
    triangle_packet_word(packet, handle);
    triangle_packet_word(packet, target);
    triangle_packet_word(packet, format);
    triangle_packet_word(packet, bind);
    triangle_packet_word(packet, width);
    triangle_packet_word(packet, height);
    triangle_packet_word(packet, 1U);
    triangle_packet_word(packet, 1U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
}

static void triangle_append_create_surface(struct triangle_packet *packet) {
    triangle_packet_command(packet, TRI_CCMD_CREATE_OBJECT, TRI_OBJECT_SURFACE, 5U);
    triangle_packet_word(packet, TRI_SURFACE);
    triangle_packet_word(packet, TRI_TARGET);
    triangle_packet_word(packet, TRI_FORMAT_B8G8R8X8_UNORM);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
}

static void triangle_append_framebuffer(struct triangle_packet *packet) {
    triangle_packet_command(packet, TRI_CCMD_SET_FRAMEBUFFER_STATE, 0U, 3U);
    triangle_packet_word(packet, 1U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, TRI_SURFACE);
}

static void triangle_append_clear(struct triangle_packet *packet) {
    triangle_packet_command(packet, TRI_CCMD_CLEAR, 0U, 8U);
    triangle_packet_word(packet, TRI_PIPE_CLEAR_COLOR0);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, UINT32_C(0x3f800000));
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, UINT32_C(0x3f800000));
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
}

static void triangle_append_vertex_elements(struct triangle_packet *packet) {
    triangle_packet_command(packet, TRI_CCMD_CREATE_OBJECT,
                            TRI_OBJECT_VERTEX_ELEMENTS, 9U);
    triangle_packet_word(packet, TRI_VERTEX_ELEMENTS);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, TRI_PIPE_FORMAT_R32G32B32A32_FLOAT);
    triangle_packet_word(packet, 16U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, TRI_PIPE_FORMAT_R32G32B32A32_FLOAT);
    triangle_packet_command(packet, TRI_CCMD_BIND_OBJECT,
                            TRI_OBJECT_VERTEX_ELEMENTS, 1U);
    triangle_packet_word(packet, TRI_VERTEX_ELEMENTS);
}

struct triangle_vertex {
    float position[4];
    float color[4];
};

/* Literal vertex values from official tests/test_virgl_cmd.c lines 239-258. */
static const struct triangle_vertex triangle_vertices[3] = {
    {{0.0f, -0.9f, 0.0f, 1.0f}, {1.0f, 0.0f, 0.0f, 1.0f}},
    {{-0.9f, 0.9f, 0.0f, 1.0f}, {0.0f, 1.0f, 0.0f, 1.0f}},
    {{0.9f, 0.9f, 0.0f, 1.0f}, {0.0f, 0.0f, 1.0f, 1.0f}},
};

/* Literal shader text from official tests/test_virgl_cmd.c lines 342-370. */
static const char triangle_vertex_shader[] =
    "VERT\n"
    "DCL IN[0]\n"
    "DCL IN[1]\n"
    "DCL OUT[0], POSITION\n"
    "DCL OUT[1], COLOR\n"
    "  0: MOV OUT[1], IN[1]\n"
    "  1: MOV OUT[0], IN[0]\n"
    "  2: END\n";

static const char triangle_fragment_shader[] =
    "FRAG\n"
    "DCL IN[0], COLOR, LINEAR\n"
    "DCL OUT[0], COLOR\n"
    "  0: MOV OUT[0], IN[0]\n"
    "  1: END\n";

static void triangle_append_inline_vertices(struct triangle_packet *packet) {
    triangle_packet_command(packet, TRI_CCMD_RESOURCE_INLINE_WRITE, 0U,
                            11U + (uint32_t)(sizeof(triangle_vertices) / 4U));
    triangle_packet_word(packet, TRI_VBO);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, (uint32_t)sizeof(triangle_vertices));
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, (uint32_t)sizeof(triangle_vertices));
    triangle_packet_word(packet, 1U);
    triangle_packet_word(packet, 1U);
    triangle_packet_bytes(packet, (const uint8_t *)triangle_vertices,
                          sizeof(triangle_vertices));
}

static void triangle_append_vertex_buffer(struct triangle_packet *packet) {
    triangle_packet_command(packet, TRI_CCMD_SET_VERTEX_BUFFERS, 0U, 3U);
    triangle_packet_word(packet, (uint32_t)sizeof(struct triangle_vertex));
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, TRI_VBO);
}

static void triangle_append_shader(struct triangle_packet *packet, uint32_t handle,
                                   uint32_t shader_type, const char *text) {
    const size_t text_length = strlen(text) + 1U;
    const uint32_t text_words = (uint32_t)((text_length + 3U) / 4U);
    triangle_packet_command(packet, TRI_CCMD_CREATE_OBJECT, TRI_OBJECT_SHADER,
                            5U + text_words);
    triangle_packet_word(packet, handle);
    triangle_packet_word(packet, shader_type);
    triangle_packet_word(packet, (uint32_t)text_length);
    triangle_packet_word(packet, 300U);
    /* virgl_emit_shader_streamout(NULL): zero outputs before the TGSI text. */
    triangle_packet_word(packet, 0U);
    triangle_packet_bytes(packet, (const uint8_t *)text, text_length);
}

static void triangle_append_bind_shader(struct triangle_packet *packet, uint32_t handle,
                                        uint32_t shader_type) {
    triangle_packet_command(packet, TRI_CCMD_BIND_SHADER, 0U, 2U);
    triangle_packet_word(packet, handle);
    triangle_packet_word(packet, shader_type);
}

static void triangle_append_link_shader(struct triangle_packet *packet) {
    triangle_packet_command(packet, TRI_CCMD_LINK_SHADER, 0U, 6U);
    triangle_packet_word(packet, TRI_VERTEX_SHADER);
    triangle_packet_word(packet, TRI_FRAGMENT_SHADER);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
}

static void triangle_append_states(struct triangle_packet *packet) {
    unsigned index;
    triangle_packet_command(packet, TRI_CCMD_CREATE_OBJECT, TRI_OBJECT_BLEND, 11U);
    triangle_packet_word(packet, TRI_BLEND);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, UINT32_C(0x78000000));
    for (index = 1U; index < 8U; ++index) {
        triangle_packet_word(packet, 0U);
    }
    triangle_packet_command(packet, TRI_CCMD_BIND_OBJECT, TRI_OBJECT_BLEND, 1U);
    triangle_packet_word(packet, TRI_BLEND);

    triangle_packet_command(packet, TRI_CCMD_CREATE_OBJECT, TRI_OBJECT_DSA, 5U);
    triangle_packet_word(packet, TRI_DSA);
    triangle_packet_word(packet, 6U); /* depth writemask + PIPE_FUNC_LESS */
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_command(packet, TRI_CCMD_BIND_OBJECT, TRI_OBJECT_DSA, 1U);
    triangle_packet_word(packet, TRI_DSA);

    triangle_packet_command(packet, TRI_CCMD_CREATE_OBJECT, TRI_OBJECT_RASTERIZER, 9U);
    triangle_packet_word(packet, TRI_RASTERIZER);
    triangle_packet_word(packet, UINT32_C(0x60000002)); /* depth clip, half pixel, bottom edge */
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, 0U);
    triangle_packet_command(packet, TRI_CCMD_BIND_OBJECT, TRI_OBJECT_RASTERIZER, 1U);
    triangle_packet_word(packet, TRI_RASTERIZER);

    triangle_packet_command(packet, TRI_CCMD_SET_VIEWPORT_STATE, 0U, 7U);
    triangle_packet_word(packet, 0U);
    triangle_packet_word(packet, UINT32_C(0x42000000)); /* 32.0 */
    triangle_packet_word(packet, UINT32_C(0x42000000)); /* 32.0 */
    triangle_packet_word(packet, UINT32_C(0x3f000000)); /* 0.5 */
    triangle_packet_word(packet, UINT32_C(0x42000000));
    triangle_packet_word(packet, UINT32_C(0x42000000));
    triangle_packet_word(packet, UINT32_C(0x3f000000));
}

static size_t triangle_encode_setup(uint8_t *output, size_t capacity) {
    struct triangle_packet packet;
    triangle_packet_init(&packet, output, capacity);
    triangle_append_create_surface(&packet);
    triangle_append_framebuffer(&packet);
    triangle_append_clear(&packet);
    triangle_append_vertex_elements(&packet);
    triangle_append_inline_vertices(&packet);
    triangle_append_vertex_buffer(&packet);
    triangle_append_shader(&packet, TRI_VERTEX_SHADER, TRI_PIPE_SHADER_VERTEX,
                           triangle_vertex_shader);
    triangle_append_bind_shader(&packet, TRI_VERTEX_SHADER, TRI_PIPE_SHADER_VERTEX);
    triangle_append_shader(&packet, TRI_FRAGMENT_SHADER, TRI_PIPE_SHADER_FRAGMENT,
                           triangle_fragment_shader);
    triangle_append_bind_shader(&packet, TRI_FRAGMENT_SHADER, TRI_PIPE_SHADER_FRAGMENT);
    triangle_append_link_shader(&packet);
    triangle_append_states(&packet);
    return triangle_packet_finish(&packet);
}

static size_t triangle_encode_draw(uint8_t *output, size_t capacity) {
    struct triangle_packet packet;
    triangle_packet_init(&packet, output, capacity);
    triangle_packet_command(&packet, TRI_CCMD_DRAW_VBO, 0U, 12U);
    triangle_packet_word(&packet, 0U);
    triangle_packet_word(&packet, 3U);
    triangle_packet_word(&packet, TRI_PIPE_PRIM_TRIANGLES);
    triangle_packet_word(&packet, 0U);
    triangle_packet_word(&packet, 0U);
    triangle_packet_word(&packet, 0U);
    triangle_packet_word(&packet, 0U);
    triangle_packet_word(&packet, 0U);
    triangle_packet_word(&packet, 0U);
    triangle_packet_word(&packet, 0U);
    triangle_packet_word(&packet, 0U);
    triangle_packet_word(&packet, 0U);
    return triangle_packet_finish(&packet);
}

static size_t triangle_encode_cleanup(uint8_t *output, size_t capacity) {
    struct triangle_packet packet;
    triangle_packet_init(&packet, output, capacity);
    triangle_append_destroy(&packet, TRI_OBJECT_RASTERIZER, TRI_RASTERIZER);
    triangle_append_destroy(&packet, TRI_OBJECT_DSA, TRI_DSA);
    triangle_append_destroy(&packet, TRI_OBJECT_BLEND, TRI_BLEND);
    triangle_append_destroy(&packet, TRI_OBJECT_SHADER, TRI_FRAGMENT_SHADER);
    triangle_append_destroy(&packet, TRI_OBJECT_SHADER, TRI_VERTEX_SHADER);
    triangle_append_destroy(&packet, TRI_OBJECT_VERTEX_ELEMENTS, TRI_VERTEX_ELEMENTS);
    triangle_append_destroy(&packet, TRI_OBJECT_SURFACE, TRI_SURFACE);
    return triangle_packet_finish(&packet);
}

static size_t triangle_encode_submit(uint8_t *output, size_t capacity,
                                     const uint8_t *cmdbuf, size_t cmdbuf_length) {
    struct triangle_packet packet;
    if (cmdbuf == NULL || (cmdbuf_length & 3U) != 0U || cmdbuf_length > TRI_CMDBUF_CAPACITY) {
        return 0U;
    }
    triangle_packet_init(&packet, output, capacity);
    triangle_packet_word(&packet, (uint32_t)(cmdbuf_length / 4U));
    triangle_packet_word(&packet, TRI_VTEST_SUBMIT_CMD);
    triangle_packet_bytes(&packet, cmdbuf, cmdbuf_length);
    return triangle_packet_finish(&packet);
}

static size_t triangle_encode_transfer_get(uint8_t *output, size_t capacity) {
    struct triangle_packet packet;
    triangle_packet_init(&packet, output, capacity);
    triangle_packet_word(&packet, 11U);
    triangle_packet_word(&packet, TRI_VTEST_TRANSFER_GET);
    triangle_packet_word(&packet, TRI_TARGET);
    triangle_packet_word(&packet, 0U);
    triangle_packet_word(&packet, TRI_WIDTH * TRI_PIXEL_BYTES);
    triangle_packet_word(&packet, TRI_READBACK_BYTES);
    triangle_packet_word(&packet, 0U);
    triangle_packet_word(&packet, 0U);
    triangle_packet_word(&packet, 0U);
    triangle_packet_word(&packet, TRI_WIDTH);
    triangle_packet_word(&packet, TRI_HEIGHT);
    triangle_packet_word(&packet, 1U);
    triangle_packet_word(&packet, TRI_READBACK_BYTES);
    return triangle_packet_finish(&packet);
}

static size_t triangle_encode_busy_wait(uint8_t *output, size_t capacity) {
    struct triangle_packet packet;
    triangle_packet_init(&packet, output, capacity);
    triangle_packet_word(&packet, 2U);
    triangle_packet_word(&packet, TRI_VTEST_BUSY_WAIT);
    triangle_packet_word(&packet, 0U);
    triangle_packet_word(&packet, TRI_VTEST_BUSY_WAIT_FLAG_WAIT);
    return triangle_packet_finish(&packet);
}

static size_t triangle_encode_unref(uint8_t *output, size_t capacity, uint32_t handle) {
    struct triangle_packet packet;
    triangle_packet_init(&packet, output, capacity);
    triangle_packet_word(&packet, 1U);
    triangle_packet_word(&packet, TRI_VTEST_RESOURCE_UNREF);
    triangle_packet_word(&packet, handle);
    return triangle_packet_finish(&packet);
}

static int triangle_peer_matches(pid_t peer_pid, uid_t peer_uid,
                                 const struct server_identity *expected,
                                 uint64_t observed_ticks) {
    return expected != NULL && peer_pid > 0 && peer_uid == VTEST_UID &&
           peer_pid == expected->pid && observed_ticks == expected->start_ticks;
}

static int triangle_verify_peer(int fd, struct triangle_state *state) {
    struct ucred credentials;
    socklen_t credentials_length = sizeof(credentials);
    uint64_t observed_ticks;
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &credentials, &credentials_length) != 0 ||
        credentials_length != sizeof(credentials) ||
        !read_proc_start_ticks(&state->expected_server, &observed_ticks) ||
        !triangle_peer_matches(credentials.pid, credentials.uid,
                               &state->expected_server, observed_ticks)) {
        state->server_identity_verified = 0;
        return 0;
    }
    state->peer_pid = credentials.pid;
    state->peer_uid = credentials.uid;
    state->server_identity_verified = 1;
    return 1;
}

static uint64_t triangle_hash(const uint8_t *data, size_t length) {
    size_t index;
    uint64_t hash = UINT64_C(14695981039346656037);
    for (index = 0U; index < length; ++index) {
        hash ^= (uint64_t)data[index];
        hash *= UINT64_C(1099511628211);
    }
    return hash;
}

static int triangle_is_green(const uint8_t *pixel) {
    /* B8G8R8X8: X is padding and is intentionally not a color test. */
    return pixel[0] == 0U && pixel[1] == 255U && pixel[2] == 0U;
}

static int triangle_validate_clear(const uint8_t *data, size_t length) {
    size_t pixel;
    if (data == NULL || length != TRI_READBACK_BYTES) {
        return 0;
    }
    for (pixel = 0U; pixel < TRI_WIDTH * TRI_HEIGHT; ++pixel) {
        if (!triangle_is_green(data + pixel * TRI_PIXEL_BYTES)) {
            return 0;
        }
    }
    return 1;
}

static int triangle_validate_readback(const uint8_t *data, size_t length,
                                      struct triangle_readback *result) {
    size_t pixel;
    uint8_t first_changed[3] = {0U, 0U, 0U};
    int have_changed = 0;
    int changed_color = 0;
    int border_ok = 1;
    if (data == NULL || result == NULL || length != TRI_READBACK_BYTES) {
        return 0;
    }
    memset(result, 0, sizeof(*result));
    result->width = TRI_WIDTH;
    result->height = TRI_HEIGHT;
    result->readback_bytes = (uint32_t)length;
    result->fnv1a = triangle_hash(data, length);
    result->clear_verified = triangle_validate_clear(data, length);
    result->clear_pixels = result->clear_verified ? TRI_WIDTH * TRI_HEIGHT : 0U;
    for (pixel = 0U; pixel < TRI_WIDTH * TRI_HEIGHT; ++pixel) {
        const size_t x = pixel % TRI_WIDTH;
        const size_t y = pixel / TRI_WIDTH;
        const uint8_t *value = data + pixel * TRI_PIXEL_BYTES;
        const int green = triangle_is_green(value);
        if (green) {
            ++result->green_pixels;
        } else {
            ++result->triangle_pixels;
            if (!have_changed) {
                first_changed[0] = value[0];
                first_changed[1] = value[1];
                first_changed[2] = value[2];
                have_changed = 1;
            } else if (value[0] != first_changed[0] || value[1] != first_changed[1] ||
                       value[2] != first_changed[2]) {
                changed_color = 1;
            }
        }
        if (x == 0U || y == 0U || x == TRI_WIDTH - 1U || y == TRI_HEIGHT - 1U) {
            if (!green) {
                border_ok = 0;
            }
        }
    }
    result->nonuniform_rgb = have_changed && changed_color;
    return result->clear_verified == 0 && result->triangle_pixels >= 128U &&
           result->green_pixels >= 128U && result->nonuniform_rgb && border_ok &&
           result->fnv1a != 0U;
}

static int triangle_receive_busy_wait(int fd, uint64_t deadline_ms) {
    uint8_t response[12];
    if (!receive_exact(fd, response, sizeof(response), deadline_ms) ||
        read_le32(response) != 1U || read_le32(response + 4U) != TRI_VTEST_BUSY_WAIT ||
        read_le32(response + 8U) != 0U) {
        return 0;
    }
    return 1;
}

static int triangle_send_cmdbuf(int fd, const uint8_t *cmdbuf, size_t cmdbuf_length,
                                uint64_t deadline_ms) {
    uint8_t packet[TRI_PACKET_CAPACITY];
    const size_t length = triangle_encode_submit(packet, sizeof(packet), cmdbuf,
                                                 cmdbuf_length);
    return length != 0U && send_all(fd, packet, length, deadline_ms);
}

static int triangle_send_unref(int fd, uint32_t handle, uint64_t deadline_ms) {
    uint8_t packet[20];
    const size_t length = triangle_encode_unref(packet, sizeof(packet), handle);
    return length != 0U && send_all(fd, packet, length, deadline_ms);
}

static int triangle_release(int fd, struct triangle_state *state, uint64_t deadline_ms) {
    uint8_t cmdbuf[TRI_CMDBUF_CAPACITY];
    const size_t cleanup_length = triangle_encode_cleanup(cmdbuf, sizeof(cmdbuf));
    int result = 1;
    if (state->objects_created) {
        if (cleanup_length == 0U || !triangle_send_cmdbuf(fd, cmdbuf, cleanup_length, deadline_ms)) {
            result = 0;
        } else {
            state->object_cleanup_sent = 1;
            triangle_add_stage(state, "objects_cleanup_sent");
        }
    }
    if (state->vbo_created) {
        if (!triangle_send_unref(fd, TRI_VBO, deadline_ms)) {
            result = 0;
        }
    }
    if (state->target_created) {
        if (!triangle_send_unref(fd, TRI_TARGET, deadline_ms)) {
            result = 0;
        }
    }
    if (state->vbo_created && state->target_created && result) {
        state->resource_unref_sent = 1;
        triangle_add_stage(state, "resource_unref_sent");
    }
    return result;
}

static int triangle_emit_event(const struct triangle_state *state, const char *status) {
    char output[TRI_OUTPUT_CAPACITY];
    size_t used = 0U;
    size_t index;
    int written;
    const uint64_t finished_ms = monotonic_ms();
    uint64_t duration;
    const uint32_t cap_bytes = state->caps_received ? state->caps.bytes : 0U;
    const uint32_t cap_nonzero = state->caps_received ? state->caps.nonzero_bytes : 0U;
    const uint64_t caps_hash = state->caps_received ? state->caps.fnv1a : 0U;
    const int passed = strcmp(status, "TRIANGLE_OFFSCREEN") == 0;
    if (finished_ms == 0U || finished_ms < state->started_ms) {
        return 0;
    }
    duration = finished_ms - state->started_ms;
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
                       "],\"protocolCaps\":{\"capBytes\":%" PRIu32
                       ",\"capNonzeroBytes\":%" PRIu32 ",\"capsFNVhash\":\"%016" PRIx64 "\"},"
                       "\"capBytes\":%" PRIu32 ",\"capNonzeroBytes\":%" PRIu32
                       ",\"capsFNVhash\":\"%016" PRIx64 "\",\"durationMs\":%" PRIu64
                       ",\"width\":%u,\"height\":%u,\"readbackBytes\":%u,"
                       "\"clearVerified\":%s,\"clearPixels\":%u,\"trianglePixels\":%u,"
                       "\"greenPixels\":%u,\"nonuniformRGB\":%s,"
                       "\"readbackFNVhash\":\"%016" PRIx64 "\",\"unrefSent\":%s,"
                       "\"resourceUnrefSent\":%s,\"triangleVerified\":%s,"
                       "\"offscreenDrawVerified\":%s,\"presentationVerified\":false,"
                       "\"d3dVerified\":false,\"wowVerified\":false,"
                       "\"hardwareRenderingVerified\":false,\"egl\":false,\"gpu\":false,"
                       "\"triangle\":false,\"device\":false,\"hardware\":false,"
                       "\"present\":false,\"wowPass\":false,\"render\":false}\n",
                       cap_bytes, cap_nonzero, caps_hash, cap_bytes, cap_nonzero, caps_hash,
                       duration, state->result.width, state->result.height,
                       state->result.readback_bytes, state->result.clear_verified ? "true" : "false",
                       state->result.clear_pixels, state->result.triangle_pixels,
                       state->result.green_pixels, state->result.nonuniform_rgb ? "true" : "false",
                       state->result.fnv1a, state->resource_unref_sent ? "true" : "false",
                       state->resource_unref_sent ? "true" : "false", passed ? "true" : "false",
                       passed ? "true" : "false");
    if (written < 0 || (size_t)written >= sizeof(output) - used) {
        return 0;
    }
    used += (size_t)written;
    if (used > 4096U) {
        return 0;
    }
    if (fwrite(output, 1U, used, stdout) != used || fflush(stdout) != 0) {
        return 0;
    }
    return 1;
}

static VTEST_UNUSED int run_triangle(struct triangle_state *state) {
    int fd = -1;
    int success = 0;
    int cleanup_ok = 1;
    uint8_t packet[TRI_PACKET_CAPACITY];
    uint8_t cmdbuf[TRI_CMDBUF_CAPACITY];
    const uint64_t total_deadline = state->started_ms + (TOTAL_SECONDS * 1000U);
    size_t length;

    if (getuid() != VTEST_UID) {
        triangle_add_stage(state, "uid_rejected");
        goto finish;
    }
    triangle_add_stage(state, "uid_verified");
    if (!allowed_cwd(state->run_id)) {
        triangle_add_stage(state, "cwd_rejected");
        goto finish;
    }
    triangle_add_stage(state, "cwd_verified");
    if (!load_server_identity(&state->expected_server)) {
        triangle_add_stage(state, "server_identity_rejected");
        goto finish;
    }
    triangle_add_stage(state, "server_identity_loaded");
    if (!wait_for_socket(VTEST_UID, total_deadline)) {
        triangle_add_stage(state, "socket_timeout");
        goto finish;
    }
    triangle_add_stage(state, "socket_verified");
    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0 || !nonblocking_socket(fd) || !connect_socket(fd, total_deadline)) {
        triangle_add_stage(state, "connect_failed");
        goto finish;
    }
    triangle_add_stage(state, "connected");
    if (!triangle_verify_peer(fd, state)) {
        triangle_add_stage(state, "peer_rejected");
        goto finish;
    }
    triangle_add_stage(state, "peer_verified");
    triangle_add_stage(state, "server_identity_verified");

    length = 0U;
    write_le32(packet + length, 6U); length += 4U;
    write_le32(packet + length, TRI_VTEST_CREATE_RENDERER); length += 4U;
    memcpy(packet + length, "vtest\0", 6U); length += 6U;
    write_le32(packet + length, 0U); length += 4U;
    write_le32(packet + length, TRI_VTEST_GET_CAPS); length += 4U;
    if (!send_all(fd, packet, length, total_deadline)) {
        triangle_add_stage(state, "request_failed");
        goto finish;
    }
    triangle_add_stage(state, "request_sent");
    if (!receive_caps(fd, total_deadline, &state->caps)) {
        triangle_add_stage(state, "response_rejected");
        goto finish;
    }
    state->caps_received = 1;
    triangle_add_stage(state, "capabilities_received");
    if (!triangle_verify_peer(fd, state)) {
        triangle_add_stage(state, "server_identity_rejected");
        goto finish;
    }

    /* Rebuild the two fixed resource packets with the bounded packet helper. */
    {
        struct triangle_packet resource_packet;
        triangle_packet_init(&resource_packet, packet, sizeof(packet));
        triangle_append_resource_create(&resource_packet, TRI_TARGET, TRI_PIPE_TEXTURE_2D,
                                        TRI_FORMAT_B8G8R8X8_UNORM, TRI_BIND_RENDER_TARGET,
                                        TRI_WIDTH, TRI_HEIGHT);
        length = triangle_packet_finish(&resource_packet);
    }
    if (length == 0U || !send_all(fd, packet, length, total_deadline)) {
        triangle_add_stage(state, "render_target_create_failed");
        goto finish;
    }
    state->target_created = 1;
    triangle_add_stage(state, "render_target_create_sent");
    {
        struct triangle_packet resource_packet;
        triangle_packet_init(&resource_packet, packet, sizeof(packet));
        triangle_append_resource_create(&resource_packet, TRI_VBO, TRI_PIPE_BUFFER,
                                        TRI_FORMAT_R8_UNORM, TRI_BIND_VERTEX_BUFFER,
                                        (uint32_t)sizeof(triangle_vertices), 1U);
        length = triangle_packet_finish(&resource_packet);
    }
    if (length == 0U || !send_all(fd, packet, length, total_deadline)) {
        triangle_add_stage(state, "vbo_create_failed");
        goto finish;
    }
    state->vbo_created = 1;
    triangle_add_stage(state, "vbo_create_sent");
    if (!triangle_verify_peer(fd, state)) {
        triangle_add_stage(state, "server_identity_rejected");
        goto finish;
    }

    length = triangle_encode_setup(cmdbuf, sizeof(cmdbuf));
    if (length == 0U || !triangle_send_cmdbuf(fd, cmdbuf, length, total_deadline)) {
        triangle_add_stage(state, "setup_failed");
        goto finish;
    }
    state->objects_created = 1;
    triangle_add_stage(state, "setup_submitted");
    if (!triangle_verify_peer(fd, state)) {
        triangle_add_stage(state, "server_identity_rejected");
        goto finish;
    }

    length = triangle_encode_transfer_get(packet, sizeof(packet));
    if (length == 0U || !send_all(fd, packet, length, total_deadline) ||
        !receive_exact(fd, state->readback, TRI_READBACK_BYTES, total_deadline) ||
        !triangle_validate_clear(state->readback, TRI_READBACK_BYTES)) {
        triangle_add_stage(state, "clear_readback_failed");
        goto finish;
    }
    state->result.clear_verified = 1;
    state->result.clear_pixels = TRI_WIDTH * TRI_HEIGHT;
    triangle_add_stage(state, "clear_verified");
    if (!triangle_verify_peer(fd, state)) {
        triangle_add_stage(state, "server_identity_rejected");
        goto finish;
    }

    length = triangle_encode_draw(cmdbuf, sizeof(cmdbuf));
    if (length == 0U || !triangle_send_cmdbuf(fd, cmdbuf, length, total_deadline)) {
        triangle_add_stage(state, "draw_submit_failed");
        goto finish;
    }
    triangle_add_stage(state, "draw_submitted");
    length = triangle_encode_busy_wait(packet, sizeof(packet));
    if (length == 0U || !send_all(fd, packet, length, total_deadline) ||
        !triangle_receive_busy_wait(fd, total_deadline)) {
        triangle_add_stage(state, "busy_wait_failed");
        goto finish;
    }
    triangle_add_stage(state, "busy_wait_verified");
    if (!triangle_verify_peer(fd, state)) {
        triangle_add_stage(state, "server_identity_rejected");
        goto finish;
    }
    length = triangle_encode_transfer_get(packet, sizeof(packet));
    if (length == 0U || !send_all(fd, packet, length, total_deadline) ||
        !receive_exact(fd, state->readback, TRI_READBACK_BYTES, total_deadline) ||
        !triangle_validate_readback(state->readback, TRI_READBACK_BYTES, &state->result)) {
        triangle_add_stage(state, "draw_readback_failed");
        goto finish;
    }
    /* The preceding transfer was an exact all-green pre-draw baseline. */
    state->result.clear_verified = 1;
    state->result.clear_pixels = TRI_WIDTH * TRI_HEIGHT;
    triangle_add_stage(state, "triangle_readback_verified");
    if (!triangle_verify_peer(fd, state)) {
        triangle_add_stage(state, "server_identity_rejected");
        goto finish;
    }
    cleanup_ok = triangle_release(fd, state, total_deadline);
    if (!cleanup_ok || !triangle_verify_peer(fd, state)) {
        triangle_add_stage(state, "cleanup_failed");
        goto finish;
    }
    triangle_add_stage(state, "server_identity_revalidated");
    if (shutdown(fd, SHUT_WR) != 0 || !receive_expected_eof(fd, total_deadline)) {
        triangle_add_stage(state, "response_eof_rejected");
        goto finish;
    }
    triangle_add_stage(state, "response_eofverified");
    success = 1;

finish:
    if (fd >= 0 && !success && (state->target_created || state->vbo_created)) {
        cleanup_ok = triangle_release(fd, state, total_deadline) && cleanup_ok;
    }
    if (fd >= 0 && close(fd) != 0) {
        cleanup_ok = 0;
    }
    triangle_add_stage(state, "cleanup");
    if (!cleanup_ok) {
        success = 0;
    }
    if (!triangle_emit_event(state, success ? "TRIANGLE_OFFSCREEN" : "UNKNOWN")) {
        success = 0;
    }
    return success;
}

#ifdef VTEST_TESTING
size_t vtest_triangle_encode_target_create(uint8_t *output, size_t capacity) {
    struct triangle_packet packet;
    triangle_packet_init(&packet, output, capacity);
    triangle_append_resource_create(&packet, TRI_TARGET, TRI_PIPE_TEXTURE_2D,
                                    TRI_FORMAT_B8G8R8X8_UNORM, TRI_BIND_RENDER_TARGET,
                                    TRI_WIDTH, TRI_HEIGHT);
    return triangle_packet_finish(&packet);
}

size_t vtest_triangle_encode_vbo_create(uint8_t *output, size_t capacity) {
    struct triangle_packet packet;
    triangle_packet_init(&packet, output, capacity);
    triangle_append_resource_create(&packet, TRI_VBO, TRI_PIPE_BUFFER, TRI_FORMAT_R8_UNORM,
                                    TRI_BIND_VERTEX_BUFFER, (uint32_t)sizeof(triangle_vertices), 1U);
    return triangle_packet_finish(&packet);
}

size_t vtest_triangle_encode_setup_cmdbuf(uint8_t *output, size_t capacity) {
    return triangle_encode_setup(output, capacity);
}

size_t vtest_triangle_encode_draw_cmdbuf(uint8_t *output, size_t capacity) {
    return triangle_encode_draw(output, capacity);
}

size_t vtest_triangle_encode_submit_packet(uint8_t *output, size_t capacity,
                                           const uint8_t *cmdbuf, size_t length) {
    return triangle_encode_submit(output, capacity, cmdbuf, length);
}

size_t vtest_triangle_encode_busy_wait(uint8_t *output, size_t capacity) {
    return triangle_encode_busy_wait(output, capacity);
}

int vtest_triangle_validate_clear_readback(const uint8_t *data, size_t length) {
    return triangle_validate_clear(data, length);
}

int vtest_triangle_accept_readback(const uint8_t *data, size_t length,
                                  struct triangle_readback *result) {
    return triangle_validate_readback(data, length, result);
}

int vtest_triangle_test_event(void) {
    struct triangle_state state;
    size_t pixel;
    memset(&state, 0, sizeof(state));
    state.run_id = "VTEST-triangle";
    state.native_pid = getpid();
    state.uid = VTEST_UID;
    state.peer_pid = 202;
    state.peer_uid = VTEST_UID;
    state.expected_server.pid = 202;
    state.expected_server.start_ticks = 123U;
    state.server_identity_verified = 1;
    state.started_ms = monotonic_ms();
    state.caps.bytes = 308U;
    state.caps.nonzero_bytes = 300U;
    state.caps.fnv1a = UINT64_C(0x0123456789abcdef);
    state.caps_received = 1;
    for (pixel = 0U; pixel < TRI_WIDTH * TRI_HEIGHT; ++pixel) {
        state.readback[pixel * 4U] = 0U;
        state.readback[pixel * 4U + 1U] = 255U;
        state.readback[pixel * 4U + 2U] = 0U;
        state.readback[pixel * 4U + 3U] = (uint8_t)pixel;
    }
    for (pixel = 16U; pixel < 48U; ++pixel) {
        size_t x;
        for (x = 16U; x < 48U; ++x) {
            const size_t offset = (pixel * TRI_WIDTH + x) * TRI_PIXEL_BYTES;
            state.readback[offset] = (uint8_t)x;
            state.readback[offset + 1U] = (uint8_t)(64U + pixel);
            state.readback[offset + 2U] = (uint8_t)(128U + (x & 0x1fU));
        }
    }
    if (!triangle_validate_readback(state.readback, sizeof(state.readback), &state.result)) {
        return 1;
    }
    /* The fixture models the separately verified pre-draw baseline. */
    state.result.clear_verified = 1;
    state.result.clear_pixels = TRI_WIDTH * TRI_HEIGHT;
    state.resource_unref_sent = 1;
    triangle_add_stage(&state, "uid_verified");
    triangle_add_stage(&state, "cwd_verified");
    triangle_add_stage(&state, "server_identity_loaded");
    triangle_add_stage(&state, "socket_verified");
    triangle_add_stage(&state, "connected");
    triangle_add_stage(&state, "peer_verified");
    triangle_add_stage(&state, "server_identity_verified");
    triangle_add_stage(&state, "request_sent");
    triangle_add_stage(&state, "capabilities_received");
    triangle_add_stage(&state, "render_target_create_sent");
    triangle_add_stage(&state, "vbo_create_sent");
    triangle_add_stage(&state, "setup_submitted");
    triangle_add_stage(&state, "clear_verified");
    triangle_add_stage(&state, "draw_submitted");
    triangle_add_stage(&state, "busy_wait_verified");
    triangle_add_stage(&state, "triangle_readback_verified");
    triangle_add_stage(&state, "objects_cleanup_sent");
    triangle_add_stage(&state, "resource_unref_sent");
    triangle_add_stage(&state, "server_identity_revalidated");
    triangle_add_stage(&state, "response_eofverified");
    triangle_add_stage(&state, "cleanup");
    return triangle_emit_event(&state, "TRIANGLE_OFFSCREEN") ? 0 : 1;
}

int vtest_triangle_test_event_clock_error(void) {
    struct triangle_state state;
    memset(&state, 0, sizeof(state));
    state.run_id = "VTEST-triangle-clock";
    state.started_ms = monotonic_ms() + 1U;
    return triangle_emit_event(&state, "TRIANGLE_OFFSCREEN") ? 1 : 0;
}
#endif

#ifdef VTEST_TRIANGLE_DEFINED_TESTING
int main(int argc, char **argv) {
    struct triangle_state state;
    if (argc != 3 || strcmp(argv[1], "--run-id") != 0 || !valid_run_id(argv[2])) {
        return 2;
    }
    memset(&state, 0, sizeof(state));
    state.run_id = argv[2];
    state.native_pid = getpid();
    state.uid = getuid();
    state.started_ms = monotonic_ms();
    if (state.started_ms == 0U) {
        triangle_add_stage(&state, "clock_unavailable");
        (void)triangle_emit_event(&state, "UNKNOWN");
        return 1;
    }
    return run_triangle(&state) ? 0 : 1;
}
#endif
