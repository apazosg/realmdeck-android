#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/types.h>

#define SCHEMA "wowforever.android-egl-surfaceless/version1"
#define MAX_LINE 7900U
#define MAX_OUTPUT_BYTES 65536U
#define MAX_RUN_ID 100U
#define MAX_DRIVER_STRING 512U
#define MAX_EXTENSION_SCAN 16384U
#define MAX_EXTENSION_TEXT 2048U
#define MAX_SHADER_LOG 512U
#define WIDTH 64
#define HEIGHT 64
#define MIN_COLORED_PIXELS 128U

typedef struct Probe {
    EGLDisplay display;
    EGLConfig config;
    EGLContext context;
    GLuint texture;
    GLuint framebuffer;
    GLuint vertex_shader;
    GLuint fragment_shader;
    GLuint program;
    GLuint buffer;
    EGLint egl_error;
    EGLint egl_major;
    EGLint egl_minor;
    GLenum gl_error;
    int egl_initialized;
    int api_bound;
    int config_chosen;
    int context_created;
    int current;
    int extension_scan_valid;
    int has_create_context;
    int has_surfaceless_context;
    int caps_measured;
    int fbo_created;
    int fbo_complete;
    int shaders_measured;
    int vertex_compiled;
    int fragment_compiled;
    int program_linked;
    int draw_measured;
    int readback_measured;
    int cleanup_failures;
    unsigned colored_pixels;
    uint32_t pixel_hash;
    GLint max_texture_size;
    GLint max_viewport[2];
    char vertex_log[MAX_SHADER_LOG];
    char fragment_log[MAX_SHADER_LOG];
    char program_log[MAX_SHADER_LOG];
    int vertex_log_truncated;
    int fragment_log_truncated;
    int program_log_truncated;
} Probe;

static int output_failed;
static size_t output_bytes;

static uint64_t monotonic_ns(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0U;
    return (uint64_t)now.tv_sec * UINT64_C(1000000000) + (uint64_t)now.tv_nsec;
}

static size_t bounded_length(const char *value, size_t limit, int *terminated) {
    size_t i;
    if (terminated != NULL) *terminated = 0;
    if (value == NULL) return 0U;
    for (i = 0U; i < limit; ++i) {
        if (value[i] == '\0') {
            if (terminated != NULL) *terminated = 1;
            return i;
        }
    }
    return limit;
}

static int valid_run_id(const char *value) {
    const size_t length = bounded_length(value, MAX_RUN_ID + 1U, NULL);
    size_t i;
    if (value == NULL || length == 0U || length > MAX_RUN_ID) return 0;
    if (!((value[0] >= 'A' && value[0] <= 'Z') ||
          (value[0] >= 'a' && value[0] <= 'z') ||
          (value[0] >= '0' && value[0] <= '9'))) return 0;
    for (i = 1U; i < length; ++i) {
        const unsigned char c = (unsigned char)value[i];
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
              (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.')) return 0;
    }
    return 1;
}

static void json_string(char *out, size_t capacity, const char *value, size_t limit, int *truncated) {
    size_t at = 0U;
    size_t i;
    int terminated = 0;
    const size_t input_length = bounded_length(value, limit, &terminated);
    if (truncated != NULL) *truncated = 0;
    if (capacity < 3U) {
        if (capacity > 0U) out[0] = '\0';
        if (truncated != NULL) *truncated = 1;
        return;
    }
    out[at++] = '"';
    for (i = 0U; i < input_length; ++i) {
        const unsigned char c = (unsigned char)value[i];
        const size_t needed = (c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t') ? 2U :
                              ((c < 0x20U || c >= 0x7fU) ? 6U : 1U);
        if (at + needed + 2U > capacity) {
            if (truncated != NULL) *truncated = 1;
            break;
        }
        if (c == '"' || c == '\\') {
            out[at++] = '\\';
            out[at++] = (char)c;
        } else if (c == '\n' || c == '\r' || c == '\t') {
            out[at++] = '\\';
            out[at++] = c == '\n' ? 'n' : (c == '\r' ? 'r' : 't');
        } else if (c < 0x20U || c >= 0x7fU) {
            const int written = snprintf(out + at, capacity - at, "\\u%04X", (unsigned)c);
            if (written < 0 || (size_t)written >= capacity - at) {
                if (truncated != NULL) *truncated = 1;
                break;
            }
            at += (size_t)written;
        } else {
            out[at++] = (char)c;
        }
    }
    if (!terminated || input_length < bounded_length(value, limit, NULL)) {
        if (truncated != NULL) *truncated = 1;
    }
    if (at + 2U <= capacity) {
        out[at++] = '"';
        out[at] = '\0';
    } else {
        out[capacity - 1U] = '\0';
        if (truncated != NULL) *truncated = 1;
    }
}

static void json_optional_string(char *out, size_t capacity, const char *value, int *truncated) {
    if (value == NULL) {
        (void)snprintf(out, capacity, "null");
        if (truncated != NULL) *truncated = 0;
    } else {
        json_string(out, capacity, value, MAX_DRIVER_STRING, truncated);
    }
}

static void json_optional_bool(char *out, size_t capacity, int known, int value) {
    if (!known) (void)snprintf(out, capacity, "null");
    else (void)snprintf(out, capacity, "%s", value ? "true" : "false");
}

static int emit_event(const char *run_id, const char *event, const char *fields) {
    char line[MAX_LINE];
    char run_id_json[128];
    int length;
    json_string(run_id_json, sizeof(run_id_json), run_id, MAX_RUN_ID, NULL);
    length = snprintf(line, sizeof(line),
        "{\"schema\":\"%s\",\"runId\":%s,\"event\":\"%s\",\"timeNs\":%llu%s}\n",
        SCHEMA, run_id_json, event, (unsigned long long)monotonic_ns(), fields != NULL ? fields : "");
    if (length <= 0 || (size_t)length >= sizeof(line) || output_bytes + (size_t)length > MAX_OUTPUT_BYTES) {
        output_failed = 1;
        return 0;
    }
    if (fputs(line, stdout) == EOF || fflush(stdout) == EOF || ferror(stdout) != 0) {
        output_failed = 1;
        return 0;
    }
    output_bytes += (size_t)length;
    return 1;
}

static int surface_pass_eligible(int egl_ok, int context_ok, int caps_ok, int fbo_ok,
                                 int shaders_ok, int draw_ok, int readback_ok,
                                 int egl_errors_clear, int gl_errors_clear,
                                 int cleanup_failures, int native_exit) {
    return egl_ok && context_ok && caps_ok && fbo_ok && shaders_ok && draw_ok && readback_ok &&
           egl_errors_clear && gl_errors_clear && cleanup_failures == 0 && native_exit == 0;
}

static EGLint take_egl_error(void) {
    return eglGetError();
}

static GLenum take_gl_error(void) {
    return glGetError();
}

static int extension_token(const char *extensions, size_t length, const char *wanted) {
    size_t i = 0U;
    int wanted_terminated = 0;
    const size_t wanted_length = bounded_length(wanted, 64U, &wanted_terminated);
    if (!wanted_terminated) return 0;
    while (i < length) {
        size_t start;
        while (i < length && extensions[i] == ' ') ++i;
        start = i;
        while (i < length && extensions[i] != ' ') ++i;
        if (i - start == wanted_length && memcmp(extensions + start, wanted, wanted_length) == 0) return 1;
    }
    return 0;
}

static int scan_egl_extensions(const char *extensions, int *create_context, int *surfaceless_context) {
    size_t length;
    int terminated;
    if (create_context != NULL) *create_context = 0;
    if (surfaceless_context != NULL) *surfaceless_context = 0;
    if (extensions == NULL) return 0;
    length = bounded_length(extensions, MAX_EXTENSION_SCAN, &terminated);
    if (!terminated) return 0;
    if (create_context != NULL) {
        *create_context = extension_token(extensions, length, "EGL_KHR_create_context");
    }
    if (surfaceless_context != NULL) {
        *surfaceless_context = extension_token(extensions, length, "EGL_KHR_surfaceless_context");
    }
    return 1;
}

static int egl_setup(Probe *probe) {
    const EGLint config_attributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLint config_count = 0;

    probe->display = EGL_NO_DISPLAY;
    probe->config = (EGLConfig)0;
    probe->context = EGL_NO_CONTEXT;
    probe->egl_error = EGL_SUCCESS;
    (void)take_egl_error();
    probe->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    probe->egl_error = take_egl_error();
    if (probe->display == EGL_NO_DISPLAY || probe->egl_error != EGL_SUCCESS) return 0;
    if (eglInitialize(probe->display, &probe->egl_major, &probe->egl_minor) != EGL_TRUE) {
        probe->egl_error = take_egl_error();
        return 0;
    }
    probe->egl_error = take_egl_error();
    if (probe->egl_error != EGL_SUCCESS) return 0;
    probe->egl_initialized = 1;
    if (eglBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) {
        probe->egl_error = take_egl_error();
        return 0;
    }
    probe->egl_error = take_egl_error();
    if (probe->egl_error != EGL_SUCCESS) return 0;
    probe->api_bound = 1;
    if (eglChooseConfig(probe->display, config_attributes, &probe->config, 1, &config_count) != EGL_TRUE) {
        probe->egl_error = take_egl_error();
        return 0;
    }
    probe->egl_error = take_egl_error();
    if (probe->egl_error != EGL_SUCCESS || config_count != 1 || probe->config == (EGLConfig)0) return 0;
    probe->config_chosen = 1;
    return 1;
}

static int create_context(Probe *probe) {
    const EGLint context_attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    (void)take_egl_error();
    probe->context = eglCreateContext(probe->display, probe->config, EGL_NO_CONTEXT, context_attributes);
    probe->egl_error = take_egl_error();
    if (probe->context == EGL_NO_CONTEXT || probe->egl_error != EGL_SUCCESS) return 0;
    probe->context_created = 1;
    (void)take_egl_error();
    if (eglMakeCurrent(probe->display, EGL_NO_SURFACE, EGL_NO_SURFACE, probe->context) != EGL_TRUE) {
        probe->egl_error = take_egl_error();
        return 0;
    }
    probe->egl_error = take_egl_error();
    if (probe->egl_error != EGL_SUCCESS) return 0;
    probe->current = 1;
    return 1;
}

static const char *query_gl_string(GLenum name, Probe *probe) {
    const GLubyte *value;
    (void)take_gl_error();
    value = glGetString(name);
    probe->gl_error = take_gl_error();
    return probe->gl_error == GL_NO_ERROR ? (const char *)value : NULL;
}

static int query_caps(Probe *probe, const char **vendor, const char **renderer,
                      const char **version, const char **glsl) {
    int okay = 1;
    *vendor = query_gl_string(GL_VENDOR, probe);
    *renderer = query_gl_string(GL_RENDERER, probe);
    *version = query_gl_string(GL_VERSION, probe);
    *glsl = query_gl_string(GL_SHADING_LANGUAGE_VERSION, probe);
    if (*vendor == NULL || *renderer == NULL || *version == NULL || *glsl == NULL) okay = 0;
    (void)take_gl_error();
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &probe->max_texture_size);
    if (take_gl_error() != GL_NO_ERROR) okay = 0;
    (void)take_gl_error();
    glGetIntegerv(GL_MAX_VIEWPORT_DIMS, probe->max_viewport);
    if (take_gl_error() != GL_NO_ERROR) okay = 0;
    probe->caps_measured = okay;
    return okay;
}

static int compile_shader(GLenum type, const char *source, GLuint *shader,
                          char *log, size_t log_capacity, int *log_truncated, Probe *probe) {
    GLint compiled = GL_FALSE;
    GLsizei log_length = 0;
    *shader = 0U;
    log[0] = '\0';
    *log_truncated = 0;
    (void)take_gl_error();
    *shader = glCreateShader(type);
    if (*shader != 0U) {
        glShaderSource(*shader, 1, (const GLchar *const *)&source, NULL);
        glCompileShader(*shader);
        glGetShaderiv(*shader, GL_COMPILE_STATUS, &compiled);
        glGetShaderInfoLog(*shader, (GLsizei)log_capacity, &log_length, log);
    }
    probe->gl_error = take_gl_error();
    if (log_length < 0 || (size_t)log_length >= log_capacity - 1U) *log_truncated = 1;
    log[log_capacity - 1U] = '\0';
    return *shader != 0U && probe->gl_error == GL_NO_ERROR && compiled == GL_TRUE;
}

static int build_shaders(Probe *probe) {
    static const char vertex_source[] =
        "#version 100\n"
        "attribute vec2 aPosition;\n"
        "void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }\n";
    static const char fragment_source[] =
        "#version 100\n"
        "precision mediump float;\n"
        "void main() { gl_FragColor = vec4(0.10, 0.80, 0.20, 1.0); }\n";
    GLint linked = GL_FALSE;
    GLsizei log_length = 0;
    int vertex_ok;
    int fragment_ok;

    vertex_ok = compile_shader(GL_VERTEX_SHADER, vertex_source, &probe->vertex_shader,
                               probe->vertex_log, sizeof(probe->vertex_log), &probe->vertex_log_truncated, probe);
    probe->vertex_compiled = vertex_ok;
    fragment_ok = compile_shader(GL_FRAGMENT_SHADER, fragment_source, &probe->fragment_shader,
                                 probe->fragment_log, sizeof(probe->fragment_log), &probe->fragment_log_truncated, probe);
    probe->fragment_compiled = fragment_ok;
    if (!vertex_ok || !fragment_ok) return 0;
    (void)take_gl_error();
    probe->program = glCreateProgram();
    if (probe->program != 0U) {
        glAttachShader(probe->program, probe->vertex_shader);
        glAttachShader(probe->program, probe->fragment_shader);
        glBindAttribLocation(probe->program, 0U, "aPosition");
        glLinkProgram(probe->program);
        glGetProgramiv(probe->program, GL_LINK_STATUS, &linked);
        glGetProgramInfoLog(probe->program, (GLsizei)sizeof(probe->program_log), &log_length, probe->program_log);
    }
    probe->gl_error = take_gl_error();
    if (log_length < 0 || (size_t)log_length >= sizeof(probe->program_log) - 1U) probe->program_log_truncated = 1;
    probe->program_log[sizeof(probe->program_log) - 1U] = '\0';
    if (probe->gl_error != GL_NO_ERROR || probe->program == 0U || linked != GL_TRUE) return 0;
    probe->program_linked = 1;
    probe->shaders_measured = 1;
    return 1;
}

static int create_fbo(Probe *probe) {
    (void)take_gl_error();
    glGenTextures(1, &probe->texture);
    glBindTexture(GL_TEXTURE_2D, probe->texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, WIDTH, HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glGenFramebuffers(1, &probe->framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, probe->framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, probe->texture, 0);
    probe->fbo_created = probe->texture != 0U && probe->framebuffer != 0U;
    probe->fbo_complete = probe->fbo_created && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    probe->gl_error = take_gl_error();
    return probe->fbo_complete && probe->gl_error == GL_NO_ERROR;
}

static int draw_triangle(Probe *probe) {
    static const GLfloat vertices[] = {-0.80f, -0.75f, 0.80f, -0.75f, 0.0f, 0.85f};
    (void)take_gl_error();
    if (!create_fbo(probe)) return 0;
    glGenBuffers(1, &probe->buffer);
    glBindBuffer(GL_ARRAY_BUFFER, probe->buffer);
    glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr)sizeof(vertices), vertices, GL_STATIC_DRAW);
    glUseProgram(probe->program);
    glViewport(0, 0, WIDTH, HEIGHT);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glEnableVertexAttribArray(0U);
    glVertexAttribPointer(0U, 2, GL_FLOAT, GL_FALSE, 0, (const void *)0);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glFinish();
    probe->gl_error = take_gl_error();
    probe->draw_measured = probe->buffer != 0U && probe->gl_error == GL_NO_ERROR;
    return probe->draw_measured;
}

static int readback(Probe *probe) {
    unsigned char pixels[WIDTH * HEIGHT * 4];
    size_t i;
    memset(pixels, 0, sizeof(pixels));
    probe->colored_pixels = 0U;
    probe->pixel_hash = UINT32_C(2166136261);
    (void)take_gl_error();
    glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    probe->gl_error = take_gl_error();
    if (probe->gl_error != GL_NO_ERROR) return 0;
    for (i = 0U; i < sizeof(pixels); ++i) {
        probe->pixel_hash ^= (uint32_t)pixels[i];
        probe->pixel_hash *= UINT32_C(16777619);
        if ((i % 4U) == 0U && (pixels[i] >= 16U || pixels[i + 1U] >= 16U || pixels[i + 2U] >= 16U)) {
            ++probe->colored_pixels;
        }
    }
    probe->readback_measured = probe->colored_pixels >= MIN_COLORED_PIXELS && probe->pixel_hash != 0U;
    return probe->readback_measured;
}

static void cleanup(Probe *probe) {
    EGLBoolean egl_ok;
    if (probe->current) {
        (void)take_gl_error();
        glDisableVertexAttribArray(0U);
        if (probe->buffer != 0U) glDeleteBuffers(1, &probe->buffer);
        if (probe->framebuffer != 0U) glDeleteFramebuffers(1, &probe->framebuffer);
        if (probe->texture != 0U) glDeleteTextures(1, &probe->texture);
        if (probe->program != 0U) glDeleteProgram(probe->program);
        if (probe->vertex_shader != 0U) glDeleteShader(probe->vertex_shader);
        if (probe->fragment_shader != 0U) glDeleteShader(probe->fragment_shader);
        if (take_gl_error() != GL_NO_ERROR) ++probe->cleanup_failures;
        probe->buffer = 0U;
        probe->framebuffer = 0U;
        probe->texture = 0U;
        probe->program = 0U;
        probe->vertex_shader = 0U;
        probe->fragment_shader = 0U;
    } else if (probe->buffer != 0U || probe->framebuffer != 0U || probe->texture != 0U ||
               probe->program != 0U || probe->vertex_shader != 0U || probe->fragment_shader != 0U) {
        ++probe->cleanup_failures;
    }
    if (probe->display != EGL_NO_DISPLAY && probe->egl_initialized) {
        if (probe->current) {
            egl_ok = eglMakeCurrent(probe->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (egl_ok != EGL_TRUE || take_egl_error() != EGL_SUCCESS) ++probe->cleanup_failures;
            probe->current = 0;
        }
        if (probe->context != EGL_NO_CONTEXT) {
            egl_ok = eglDestroyContext(probe->display, probe->context);
            if (egl_ok != EGL_TRUE || take_egl_error() != EGL_SUCCESS) ++probe->cleanup_failures;
            probe->context = EGL_NO_CONTEXT;
        }
        egl_ok = eglTerminate(probe->display);
        if (egl_ok != EGL_TRUE || take_egl_error() != EGL_SUCCESS) ++probe->cleanup_failures;
        probe->display = EGL_NO_DISPLAY;
        probe->egl_initialized = 0;
    }
}

int main(int argc, char **argv) {
    Probe probe;
    const char *egl_vendor = NULL;
    const char *egl_version = NULL;
    const char *egl_client_apis = NULL;
    const char *egl_extensions = NULL;
    const char *gl_vendor = NULL;
    const char *gl_renderer = NULL;
    const char *gl_version = NULL;
    const char *glsl_version = NULL;
    char egl_vendor_json[1024];
    char egl_version_json[1024];
    char egl_client_apis_json[1024];
    char egl_extensions_json[MAX_EXTENSION_TEXT];
    char gl_vendor_json[1024];
    char gl_renderer_json[1024];
    char gl_version_json[1024];
    char glsl_version_json[1024];
    char fields[6800];
    char has_create_context_json[8];
    char has_surfaceless_context_json[8];
    const pid_t pid = getpid();
    const pid_t pgid = getpgid(0);
    const pid_t sid = getsid(0);
    const uid_t uid = getuid();
    int setup_ok;
    int context_ok;
    int caps_ok = 0;
    int shaders_ok = 0;
    int draw_ok = 0;
    int readback_ok = 0;
    int extension_truncated = 0;
    int string_truncated = 0;
    int gl_strings_truncated = 0;
    int egl_strings_truncated = 0;
    int measurement_pass;
    int native_exit;
    EGLint query_error = EGL_SUCCESS;
    EGLint current_query_error;
    int egl_capability_ok;
    size_t extension_length = 0U;

    if (argc != 3 || strcmp(argv[1], "--run-id") != 0 || !valid_run_id(argv[2])) return 2;
    memset(&probe, 0, sizeof(probe));
    probe.display = EGL_NO_DISPLAY;
    probe.context = EGL_NO_CONTEXT;
    probe.egl_error = EGL_SUCCESS;
    probe.gl_error = GL_NO_ERROR;

    (void)snprintf(fields, sizeof(fields),
        ",\"status\":\"STARTED\",\"pid\":%ld,\"uid\":%lu,\"pgid\":%ld,\"sid\":%ld,"
        "\"width\":64,\"height\":64,\"maxOutputBytes\":65536",
        (long)pid, (unsigned long)uid, (long)pgid, (long)sid);
    (void)emit_event(argv[2], "begin", fields);

    setup_ok = egl_setup(&probe);
    if (probe.egl_initialized) {
        (void)take_egl_error();
        egl_vendor = eglQueryString(probe.display, EGL_VENDOR);
        current_query_error = take_egl_error();
        if (current_query_error != EGL_SUCCESS && query_error == EGL_SUCCESS) query_error = current_query_error;
        if (current_query_error != EGL_SUCCESS) probe.egl_error = current_query_error;
        (void)take_egl_error();
        egl_version = eglQueryString(probe.display, EGL_VERSION);
        current_query_error = take_egl_error();
        if (current_query_error != EGL_SUCCESS && query_error == EGL_SUCCESS) query_error = current_query_error;
        if (current_query_error != EGL_SUCCESS) probe.egl_error = current_query_error;
        (void)take_egl_error();
        egl_client_apis = eglQueryString(probe.display, EGL_CLIENT_APIS);
        current_query_error = take_egl_error();
        if (current_query_error != EGL_SUCCESS && query_error == EGL_SUCCESS) query_error = current_query_error;
        if (current_query_error != EGL_SUCCESS) probe.egl_error = current_query_error;
        (void)take_egl_error();
        egl_extensions = eglQueryString(probe.display, EGL_EXTENSIONS);
        current_query_error = take_egl_error();
        if (current_query_error != EGL_SUCCESS && query_error == EGL_SUCCESS) query_error = current_query_error;
        if (current_query_error != EGL_SUCCESS) probe.egl_error = current_query_error;
        probe.extension_scan_valid = scan_egl_extensions(egl_extensions, &probe.has_create_context,
                                                         &probe.has_surfaceless_context);
        if (egl_extensions != NULL) {
            extension_length = bounded_length(egl_extensions, MAX_EXTENSION_SCAN, NULL);
        }
    }
    egl_capability_ok = setup_ok && probe.extension_scan_valid && query_error == EGL_SUCCESS &&
                        probe.has_create_context && probe.has_surfaceless_context;
    json_optional_string(egl_vendor_json, sizeof(egl_vendor_json), egl_vendor, &string_truncated);
    egl_strings_truncated |= string_truncated;
    json_optional_string(egl_version_json, sizeof(egl_version_json), egl_version, &string_truncated);
    egl_strings_truncated |= string_truncated;
    json_optional_string(egl_client_apis_json, sizeof(egl_client_apis_json), egl_client_apis, &string_truncated);
    egl_strings_truncated |= string_truncated;
    json_optional_string(egl_extensions_json, sizeof(egl_extensions_json), egl_extensions, &extension_truncated);
    json_optional_bool(has_create_context_json, sizeof(has_create_context_json), probe.extension_scan_valid,
                       probe.has_create_context);
    json_optional_bool(has_surfaceless_context_json, sizeof(has_surfaceless_context_json), probe.extension_scan_valid,
                       probe.has_surfaceless_context);
    (void)snprintf(fields, sizeof(fields),
        ",\"status\":\"%s\",\"display\":%s,\"initialized\":%s,\"eglMajor\":%d,\"eglMinor\":%d,"
        "\"apiBound\":%s,\"configChosen\":%s,\"surfaceCreated\":false,\"noSurfacePath\":true,"
        "\"eglError\":%d,\"vendor\":%s,\"version\":%s,\"clientAPIs\":%s,\"eglExtensions\":%s,"
        "\"extensionsTruncated\":%s,\"extensionScanBytes\":%u,\"extensionScanValid\":%s,"
        "\"hasEGLKHRCreateContext\":%s,\"hasEGLKHRSurfacelessContext\":%s,\"stringTruncated\":%s",
        egl_capability_ok ? "MEASURED" : "UNKNOWN",
        probe.display != EGL_NO_DISPLAY ? "true" : "false", probe.egl_initialized ? "true" : "false",
        probe.egl_major, probe.egl_minor, probe.api_bound ? "true" : "false", probe.config_chosen ? "true" : "false",
        query_error != EGL_SUCCESS ? query_error : probe.egl_error, egl_vendor_json, egl_version_json,
        egl_client_apis_json, egl_extensions_json,
        extension_truncated ? "true" : "false", (unsigned)extension_length,
        probe.extension_scan_valid ? "true" : "false", has_create_context_json, has_surfaceless_context_json,
        egl_strings_truncated ? "true" : "false");
    (void)emit_event(argv[2], "egl", fields);

    context_ok = setup_ok && create_context(&probe);
    (void)snprintf(fields, sizeof(fields),
        ",\"status\":\"%s\",\"created\":%s,\"current\":%s,\"noSurfaceCurrent\":%s,"
        "\"eglError\":%d,\"surface\":\"EGL_NO_SURFACE\"",
        !setup_ok ? "UNKNOWN" : (context_ok ? "CREATED" : "CONTEXT_FAILED"),
        probe.context_created ? "true" : "false", probe.current ? "true" : "false",
        probe.current ? "true" : "false", probe.egl_error);
    (void)emit_event(argv[2], "context", fields);

    if (context_ok) caps_ok = query_caps(&probe, &gl_vendor, &gl_renderer, &gl_version, &glsl_version);
    json_optional_string(gl_vendor_json, sizeof(gl_vendor_json), gl_vendor, &string_truncated);
    gl_strings_truncated |= string_truncated;
    json_optional_string(gl_renderer_json, sizeof(gl_renderer_json), gl_renderer, &string_truncated);
    gl_strings_truncated |= string_truncated;
    json_optional_string(gl_version_json, sizeof(gl_version_json), gl_version, &string_truncated);
    gl_strings_truncated |= string_truncated;
    json_optional_string(glsl_version_json, sizeof(glsl_version_json), glsl_version, &string_truncated);
    gl_strings_truncated |= string_truncated;
    (void)snprintf(fields, sizeof(fields),
        ",\"status\":\"%s\",\"glVendor\":%s,\"glRenderer\":%s,\"glVersion\":%s,"
        "\"glslVersion\":%s,\"maxTextureSize\":%s,\"maxViewport\":[%s,%s],"
        "\"glError\":%u,\"glStringsTruncated\":%s",
        !context_ok ? "UNKNOWN" : (caps_ok ? "MEASURED" : "UNKNOWN"), gl_vendor_json, gl_renderer_json,
        gl_version_json, glsl_version_json, "null", "null", "null",
        (unsigned)probe.gl_error, gl_strings_truncated ? "true" : "false");
    if (caps_ok) {
        (void)snprintf(fields, sizeof(fields),
            ",\"status\":\"MEASURED\",\"glVendor\":%s,\"glRenderer\":%s,\"glVersion\":%s,"
            "\"glslVersion\":%s,\"maxTextureSize\":%d,\"maxViewport\":[%d,%d],\"glError\":%u,"
            "\"glStringsTruncated\":%s", gl_vendor_json, gl_renderer_json, gl_version_json, glsl_version_json,
            probe.max_texture_size, probe.max_viewport[0], probe.max_viewport[1], (unsigned)probe.gl_error,
            gl_strings_truncated ? "true" : "false");
    }
    (void)emit_event(argv[2], "caps", fields);

    shaders_ok = context_ok && caps_ok && build_shaders(&probe);
    {
        char vertex_log_json[1024];
        char fragment_log_json[1024];
        char program_log_json[1024];
        int vertex_truncated;
        int fragment_truncated;
        int program_truncated;
        json_string(vertex_log_json, sizeof(vertex_log_json), probe.vertex_log, MAX_SHADER_LOG, &vertex_truncated);
        json_string(fragment_log_json, sizeof(fragment_log_json), probe.fragment_log, MAX_SHADER_LOG, &fragment_truncated);
        json_string(program_log_json, sizeof(program_log_json), probe.program_log, MAX_SHADER_LOG, &program_truncated);
        (void)snprintf(fields, sizeof(fields),
            ",\"status\":\"%s\",\"vertexCompiled\":%s,\"fragmentCompiled\":%s,\"programLinked\":%s,"
            "\"vertexLog\":%s,\"fragmentLog\":%s,\"programLog\":%s,\"logTruncated\":%s,\"glError\":%u",
            !context_ok || !caps_ok ? "UNKNOWN" : (shaders_ok ? "PASS" : "FAILED"),
            probe.vertex_compiled ? "true" : "false", probe.fragment_compiled ? "true" : "false",
            probe.program_linked ? "true" : "false", vertex_log_json, fragment_log_json, program_log_json,
            (probe.vertex_log_truncated || probe.fragment_log_truncated || probe.program_log_truncated ||
             vertex_truncated || fragment_truncated || program_truncated) ? "true" : "false", (unsigned)probe.gl_error);
    }
    (void)emit_event(argv[2], "shaders", fields);

    draw_ok = shaders_ok && draw_triangle(&probe);
    (void)snprintf(fields, sizeof(fields),
        ",\"status\":\"%s\",\"fboCreated\":%s,\"fboComplete\":%s,\"drawCalls\":%u,\"glError\":%u",
        !shaders_ok ? "UNKNOWN" : (draw_ok ? "PASS" : "FAILED"), probe.fbo_created ? "true" : "false",
        probe.fbo_complete ? "true" : "false", draw_ok ? 1U : 0U, (unsigned)probe.gl_error);
    (void)emit_event(argv[2], "draw", fields);

    readback_ok = draw_ok && readback(&probe);
    (void)snprintf(fields, sizeof(fields),
        ",\"status\":\"%s\",\"coloredPixels\":%u,\"requiredColoredPixels\":%u,\"totalPixels\":%u,"
        "\"rgbaFnv1a\":%u,\"glError\":%u",
        !draw_ok ? "UNKNOWN" : (readback_ok ? "PASS" : "FAILED"), probe.colored_pixels,
        MIN_COLORED_PIXELS, (unsigned)(WIDTH * HEIGHT), probe.pixel_hash, (unsigned)probe.gl_error);
    (void)emit_event(argv[2], "readback", fields);

    measurement_pass = egl_capability_ok && context_ok &&
                       caps_ok && probe.fbo_complete && shaders_ok && draw_ok && readback_ok &&
                       probe.egl_error == EGL_SUCCESS && probe.gl_error == GL_NO_ERROR;
    cleanup(&probe);
    native_exit = measurement_pass && probe.cleanup_failures == 0 && !output_failed ? 0 : 1;
    measurement_pass = surface_pass_eligible(measurement_pass, context_ok, caps_ok, probe.fbo_complete,
                                              shaders_ok, draw_ok, readback_ok, probe.egl_error == EGL_SUCCESS,
                                              probe.gl_error == GL_NO_ERROR, probe.cleanup_failures, native_exit);
    (void)snprintf(fields, sizeof(fields),
        ",\"status\":\"%s\",\"offscreenPass\":%s,\"cleanupStatus\":\"%s\",\"cleanupFailures\":%d,"
        "\"presentationVerified\":false,\"d3dVerified\":false,\"wowVerified\":false,\"vtestVerified\":false,"
        "\"pid\":%ld,\"uid\":%lu,\"pgid\":%ld,\"sid\":%ld,\"nativeExit\":%d",
        measurement_pass ? "SURFACELESS_OFFSCREEN_PASS" :
            (!setup_ok || !egl_capability_ok ? "UNKNOWN" : (!context_ok ? "CONTEXT_FAILED" : "FAILED")),
        measurement_pass ? "true" : "false", probe.cleanup_failures == 0 ? "CLEAN" : "FAILED",
        probe.cleanup_failures, (long)pid, (unsigned long)uid, (long)pgid, (long)sid, native_exit);
    (void)emit_event(argv[2], "end", fields);
    return native_exit;
}
