#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define SCHEMA "wowforever.android-egl-baseline/version1"
#define MAX_LINE 7900U
#define MAX_RUN_ID 100U
#define MAX_DRIVER_STRING 128U
#define MAX_SHADER_LOG 256U
#define WIDTH 64
#define HEIGHT 64
#define MIN_COLORED_PIXELS 128U

typedef struct Probe {
    EGLDisplay display;
    EGLConfig config;
    EGLSurface surface;
    EGLContext context;
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
    int surface_created;
    int context_created;
    int current;
    int caps_measured;
    int shaders_measured;
    int vertex_compiled;
    int fragment_compiled;
    int program_linked;
    int draw_measured;
    int readback_measured;
    int cleanup_failures;
    unsigned colored_pixels;
    uint32_t pixel_hash;
    char vertex_log[MAX_SHADER_LOG];
    char fragment_log[MAX_SHADER_LOG];
    char program_log[MAX_SHADER_LOG];
    int vertex_log_truncated;
    int fragment_log_truncated;
    int program_log_truncated;
    GLint max_texture_size;
    GLint max_vertex_attribs;
    GLint max_vertex_uniform_vectors;
    GLint max_fragment_uniform_vectors;
    GLint max_varying_vectors;
    GLint max_combined_texture_units;
    GLint max_texture_units;
    GLint max_draw_buffers;
    GLint max_renderbuffer_size;
    GLint max_array_texture_layers;
    GLint max_3d_texture_size;
    GLint max_samples;
    GLint max_uniform_buffer_bindings;
    GLint max_viewport[2];
} Probe;

static int output_failed;

static uint64_t monotonic_ns(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0U;
    return (uint64_t)now.tv_sec * UINT64_C(1000000000) + (uint64_t)now.tv_nsec;
}

static int valid_run_id(const char *value) {
    const size_t length = value == NULL ? 0U : strnlen(value, MAX_RUN_ID + 1U);
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
    size_t input_length = 0U;
    const size_t scan_limit = limit == SIZE_MAX ? limit : limit + 1U;
    if (truncated != NULL) *truncated = 0;
    if (capacity < 3U) {
        if (capacity > 0U) out[0] = '\0';
        if (truncated != NULL) *truncated = 1;
        return;
    }
    out[at++] = '"';
    if (value != NULL) {
        input_length = strnlen(value, scan_limit);
        for (i = 0U; i < input_length && i < limit; ++i) {
            const unsigned char c = (unsigned char)value[i];
            size_t needed;
            if (c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t') {
                needed = 2U;
            } else if (c < 0x20U || c >= 0x7fU) {
                needed = 6U;
            } else {
                needed = 1U;
            }
            if (at + needed + 2U > capacity) {
                if (truncated != NULL) *truncated = 1;
                break;
            }
            if (c == '"' || c == '\\') {
                out[at++] = '\\';
                out[at++] = (char)c;
            } else if (c == '\n') {
                out[at++] = '\\';
                out[at++] = 'n';
            } else if (c == '\r') {
                out[at++] = '\\';
                out[at++] = 'r';
            } else if (c == '\t') {
                out[at++] = '\\';
                out[at++] = 't';
            } else if (c < 0x20U || c >= 0x7fU) {
                const int written = snprintf(out + at, capacity - at, "\\u%04X", (unsigned)c);
                if (written < 0 || (size_t)written >= capacity - at) break;
                at += (size_t)written;
            } else {
                out[at++] = (char)c;
            }
        }
        if (input_length > limit || i < input_length) {
            if (truncated != NULL) *truncated = 1;
        }
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
        if (capacity > 0U) (void)snprintf(out, capacity, "null");
        if (truncated != NULL) *truncated = 0;
    } else {
        json_string(out, capacity, value, MAX_DRIVER_STRING, truncated);
    }
}

static int emit_event(const char *run_id, const char *event, const char *fields) {
    char line[MAX_LINE];
    char run_id_json[128];
    json_string(run_id_json, sizeof(run_id_json), run_id, MAX_RUN_ID, NULL);
    const int length = snprintf(line, sizeof(line),
        "{\"schema\":\"%s\",\"runId\":%s,\"event\":\"%s\",\"timeNs\":%llu%s}\n",
        SCHEMA, run_id_json, event, (unsigned long long)monotonic_ns(), fields != NULL ? fields : "");
    if (length <= 0 || (size_t)length >= sizeof(line)) {
        output_failed = 1;
        return 0;
    }
    if (fputs(line, stdout) == EOF || fflush(stdout) == EOF || ferror(stdout) != 0) {
        output_failed = 1;
        return 0;
    }
    return 1;
}

static EGLint egl_last_error(void) {
    return eglGetError();
}

static GLenum gl_last_error(void) {
    return glGetError();
}

static int egl_setup(Probe *probe) {
    const EGLint config_attributes[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 0,
        EGL_NONE
    };
    const EGLint pbuffer_attributes[] = {
        EGL_WIDTH, WIDTH,
        EGL_HEIGHT, HEIGHT,
        EGL_NONE
    };
    EGLint config_count = 0;

    probe->display = EGL_NO_DISPLAY;
    probe->config = (EGLConfig)0;
    probe->surface = EGL_NO_SURFACE;
    probe->context = EGL_NO_CONTEXT;
    (void)egl_last_error();
    probe->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    probe->egl_error = egl_last_error();
    if (probe->display == EGL_NO_DISPLAY || probe->egl_error != EGL_SUCCESS) return 0;
    if (eglInitialize(probe->display, &probe->egl_major, &probe->egl_minor) != EGL_TRUE) {
        probe->egl_error = egl_last_error();
        return 0;
    }
    probe->egl_error = egl_last_error();
    if (probe->egl_error != EGL_SUCCESS) return 0;
    probe->egl_initialized = 1;
    if (eglBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) {
        probe->egl_error = egl_last_error();
        return 0;
    }
    probe->egl_error = egl_last_error();
    if (probe->egl_error != EGL_SUCCESS) return 0;
    probe->api_bound = 1;
    if (eglChooseConfig(probe->display, config_attributes, &probe->config, 1, &config_count) != EGL_TRUE) {
        probe->egl_error = egl_last_error();
        return 0;
    }
    probe->egl_error = egl_last_error();
    if (probe->egl_error != EGL_SUCCESS || config_count != 1 || probe->config == (EGLConfig)0) return 0;
    probe->config_chosen = 1;
    probe->surface = eglCreatePbufferSurface(probe->display, probe->config, pbuffer_attributes);
    probe->egl_error = egl_last_error();
    if (probe->surface == EGL_NO_SURFACE || probe->egl_error != EGL_SUCCESS) return 0;
    probe->surface_created = 1;
    return 1;
}

static int create_context(Probe *probe) {
    const EGLint context_attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    (void)egl_last_error();
    probe->context = eglCreateContext(probe->display, probe->config, EGL_NO_CONTEXT, context_attributes);
    probe->egl_error = egl_last_error();
    if (probe->context == EGL_NO_CONTEXT || probe->egl_error != EGL_SUCCESS) return 0;
    probe->context_created = 1;
    if (eglMakeCurrent(probe->display, probe->surface, probe->surface, probe->context) != EGL_TRUE) {
        probe->egl_error = egl_last_error();
        return 0;
    }
    probe->egl_error = egl_last_error();
    if (probe->egl_error != EGL_SUCCESS) return 0;
    probe->current = 1;
    return 1;
}

static int query_integer(GLenum name, GLint *value) {
    (void)gl_last_error();
    glGetIntegerv(name, value);
    return gl_last_error() == GL_NO_ERROR;
}

static const char *query_string(GLenum name) {
    const GLubyte *value;
    (void)gl_last_error();
    value = glGetString(name);
    if (gl_last_error() != GL_NO_ERROR) return NULL;
    return (const char *)value;
}

static const char *query_egl_string(Probe *probe, EGLint name) {
    const char *value;
    (void)egl_last_error();
    value = eglQueryString(probe->display, name);
    probe->egl_error = egl_last_error();
    return probe->egl_error == EGL_SUCCESS ? value : NULL;
}

static const char *cap_value(char *out, size_t capacity, int available, GLint value) {
    if (!available) {
        (void)snprintf(out, capacity, "null");
    } else {
        (void)snprintf(out, capacity, "%d", value);
    }
    return out;
}

static int query_caps(Probe *probe) {
    const char *strings[4];
    int okay = 1;
    strings[0] = query_string(GL_VENDOR);
    strings[1] = query_string(GL_RENDERER);
    strings[2] = query_string(GL_VERSION);
    strings[3] = query_string(GL_SHADING_LANGUAGE_VERSION);
    if (strings[0] == NULL || strings[1] == NULL || strings[2] == NULL || strings[3] == NULL) okay = 0;
    probe->max_texture_size = 0;
    probe->max_vertex_attribs = 0;
    probe->max_vertex_uniform_vectors = 0;
    probe->max_fragment_uniform_vectors = 0;
    probe->max_varying_vectors = 0;
    probe->max_combined_texture_units = 0;
    probe->max_texture_units = 0;
    probe->max_draw_buffers = 0;
    probe->max_renderbuffer_size = 0;
    probe->max_array_texture_layers = 0;
    probe->max_3d_texture_size = 0;
    probe->max_samples = 0;
    probe->max_uniform_buffer_bindings = 0;
    probe->max_viewport[0] = 0;
    probe->max_viewport[1] = 0;
    if (!query_integer(GL_MAX_TEXTURE_SIZE, &probe->max_texture_size)) okay = 0;
    if (!query_integer(GL_MAX_VERTEX_ATTRIBS, &probe->max_vertex_attribs)) okay = 0;
    if (!query_integer(GL_MAX_VERTEX_UNIFORM_VECTORS, &probe->max_vertex_uniform_vectors)) okay = 0;
    if (!query_integer(GL_MAX_FRAGMENT_UNIFORM_VECTORS, &probe->max_fragment_uniform_vectors)) okay = 0;
    if (!query_integer(GL_MAX_VARYING_VECTORS, &probe->max_varying_vectors)) okay = 0;
    if (!query_integer(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, &probe->max_combined_texture_units)) okay = 0;
    if (!query_integer(GL_MAX_TEXTURE_IMAGE_UNITS, &probe->max_texture_units)) okay = 0;
    if (!query_integer(GL_MAX_DRAW_BUFFERS, &probe->max_draw_buffers)) okay = 0;
    if (!query_integer(GL_MAX_RENDERBUFFER_SIZE, &probe->max_renderbuffer_size)) okay = 0;
    if (!query_integer(GL_MAX_ARRAY_TEXTURE_LAYERS, &probe->max_array_texture_layers)) okay = 0;
    if (!query_integer(GL_MAX_3D_TEXTURE_SIZE, &probe->max_3d_texture_size)) okay = 0;
    if (!query_integer(GL_MAX_SAMPLES, &probe->max_samples)) okay = 0;
    if (!query_integer(GL_MAX_UNIFORM_BUFFER_BINDINGS, &probe->max_uniform_buffer_bindings)) okay = 0;
    (void)gl_last_error();
    glGetIntegerv(GL_MAX_VIEWPORT_DIMS, probe->max_viewport);
    if (gl_last_error() != GL_NO_ERROR) okay = 0;
    probe->caps_measured = okay;
    return okay;
}

static int compile_shader(GLenum type, const char *source, GLuint *shader, char *log, size_t log_capacity, int *log_truncated) {
    GLint compiled = GL_FALSE;
    GLsizei log_length = 0;
    *shader = 0U;
    log[0] = '\0';
    *log_truncated = 0;
    (void)gl_last_error();
    *shader = glCreateShader(type);
    glShaderSource(*shader, 1, (const GLchar *const *)&source, NULL);
    glCompileShader(*shader);
    glGetShaderiv(*shader, GL_COMPILE_STATUS, &compiled);
    if (gl_last_error() != GL_NO_ERROR || *shader == 0U) return 0;
    glGetShaderInfoLog(*shader, (GLsizei)log_capacity, &log_length, log);
    if (log_length < 0 || (size_t)log_length >= log_capacity - 1U) *log_truncated = 1;
    log[log_capacity - 1U] = '\0';
    if (gl_last_error() != GL_NO_ERROR || compiled != GL_TRUE) return 0;
    return 1;
}

static int build_shaders(Probe *probe) {
    static const char vertex_source[] =
        "#version 300 es\n"
        "layout(location = 0) in vec2 aPosition;\n"
        "void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }\n";
    static const char fragment_source[] =
        "#version 300 es\n"
        "precision mediump float;\n"
        "out vec4 fragColor;\n"
        "void main() { fragColor = vec4(0.10, 0.80, 0.20, 1.0); }\n";
    GLint linked = GL_FALSE;
    GLsizei log_length = 0;
    int vertex_ok;
    int fragment_ok;

    vertex_ok = compile_shader(GL_VERTEX_SHADER, vertex_source, &probe->vertex_shader,
                               probe->vertex_log, sizeof(probe->vertex_log), &probe->vertex_log_truncated);
    probe->vertex_compiled = vertex_ok;
    fragment_ok = compile_shader(GL_FRAGMENT_SHADER, fragment_source, &probe->fragment_shader,
                                 probe->fragment_log, sizeof(probe->fragment_log), &probe->fragment_log_truncated);
    probe->fragment_compiled = fragment_ok;
    if (!vertex_ok || !fragment_ok) return 0;
    (void)gl_last_error();
    probe->program = glCreateProgram();
    glAttachShader(probe->program, probe->vertex_shader);
    glAttachShader(probe->program, probe->fragment_shader);
    glLinkProgram(probe->program);
    glGetProgramiv(probe->program, GL_LINK_STATUS, &linked);
    glGetProgramInfoLog(probe->program, (GLsizei)sizeof(probe->program_log), &log_length, probe->program_log);
    if (log_length < 0 || (size_t)log_length >= sizeof(probe->program_log) - 1U) probe->program_log_truncated = 1;
    probe->program_log[sizeof(probe->program_log) - 1U] = '\0';
    if (gl_last_error() != GL_NO_ERROR || probe->program == 0U || linked != GL_TRUE) return 0;
    probe->program_linked = 1;
    probe->shaders_measured = 1;
    return 1;
}

static int draw_triangle(Probe *probe) {
    static const GLfloat vertices[] = {-0.80f, -0.75f, 0.80f, -0.75f, 0.0f, 0.85f};
    (void)gl_last_error();
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
    probe->gl_error = gl_last_error();
    probe->draw_measured = probe->buffer != 0U && probe->gl_error == GL_NO_ERROR;
    return probe->draw_measured;
}

static int readback(Probe *probe) {
    unsigned char pixels[WIDTH * HEIGHT * 4];
    size_t i;
    memset(pixels, 0, sizeof(pixels));
    probe->colored_pixels = 0U;
    probe->pixel_hash = UINT32_C(2166136261);
    (void)gl_last_error();
    glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    probe->gl_error = gl_last_error();
    if (probe->gl_error != GL_NO_ERROR) return 0;
    for (i = 0U; i < sizeof(pixels); ++i) {
        probe->pixel_hash ^= (uint32_t)pixels[i];
        probe->pixel_hash *= UINT32_C(16777619);
        if ((i % 4U) == 0U && (pixels[i] >= 16U || pixels[i + 1U] >= 16U || pixels[i + 2U] >= 16U)) {
            ++probe->colored_pixels;
        }
    }
    probe->readback_measured = probe->colored_pixels >= MIN_COLORED_PIXELS;
    return probe->readback_measured;
}

static void cleanup(Probe *probe) {
    EGLBoolean egl_ok;
    if (probe->current) {
        (void)gl_last_error();
        glDisableVertexAttribArray(0U);
        if (probe->buffer != 0U) glDeleteBuffers(1, &probe->buffer);
        if (probe->program != 0U) glDeleteProgram(probe->program);
        if (probe->vertex_shader != 0U) glDeleteShader(probe->vertex_shader);
        if (probe->fragment_shader != 0U) glDeleteShader(probe->fragment_shader);
        if (gl_last_error() != GL_NO_ERROR) ++probe->cleanup_failures;
        probe->buffer = 0U;
        probe->program = 0U;
        probe->vertex_shader = 0U;
        probe->fragment_shader = 0U;
    } else if (probe->buffer != 0U || probe->program != 0U || probe->vertex_shader != 0U || probe->fragment_shader != 0U) {
        ++probe->cleanup_failures;
    }
    if (probe->display != EGL_NO_DISPLAY && probe->egl_initialized) {
        if (probe->current) {
            egl_ok = eglMakeCurrent(probe->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (egl_ok != EGL_TRUE || egl_last_error() != EGL_SUCCESS) ++probe->cleanup_failures;
            probe->current = 0;
        }
        if (probe->surface != EGL_NO_SURFACE) {
            egl_ok = eglDestroySurface(probe->display, probe->surface);
            if (egl_ok != EGL_TRUE || egl_last_error() != EGL_SUCCESS) ++probe->cleanup_failures;
            probe->surface = EGL_NO_SURFACE;
        }
        if (probe->context != EGL_NO_CONTEXT) {
            egl_ok = eglDestroyContext(probe->display, probe->context);
            if (egl_ok != EGL_TRUE || egl_last_error() != EGL_SUCCESS) ++probe->cleanup_failures;
            probe->context = EGL_NO_CONTEXT;
        }
        egl_ok = eglTerminate(probe->display);
        if (egl_ok != EGL_TRUE || egl_last_error() != EGL_SUCCESS) ++probe->cleanup_failures;
        probe->display = EGL_NO_DISPLAY;
        probe->egl_initialized = 0;
    }
}

int main(int argc, char **argv) {
    Probe probe;
    const char *egl_vendor;
    const char *egl_version;
    const char *egl_client_apis;
    const char *gl_vendor = NULL;
    const char *gl_renderer = NULL;
    const char *gl_version = NULL;
    const char *glsl_version = NULL;
    const char *extensions = NULL;
    char egl_vendor_json[4096];
    char egl_version_json[4096];
    char egl_client_apis_json[4096];
    char gl_vendor_json[4096];
    char gl_renderer_json[4096];
    char gl_version_json[4096];
    char glsl_version_json[4096];
    char extensions_json[4096];
    char fields[7000];
    char cap_values[15][24];
    const pid_t pid = getpid();
    const pid_t pgid = getpgid(0);
    const pid_t sid = getsid(0);
    uid_t uid;
    int egl_ok;
    int context_ok;
    int caps_ok = 0;
    int shaders_ok = 0;
    int draw_ok = 0;
    int readback_ok = 0;
    int graphics_ok;
    int egl_string_truncated;
    int gl_string_truncated;
    int extension_truncated;
    int string_truncated;
    EGLint setup_error;
    EGLint query_error;

    if (argc != 3 || strcmp(argv[1], "--run-id") != 0 || !valid_run_id(argv[2])) return 2;
    uid = getuid();
    memset(&probe, 0, sizeof(probe));
    probe.display = EGL_NO_DISPLAY;
    probe.surface = EGL_NO_SURFACE;
    probe.context = EGL_NO_CONTEXT;
    (void)snprintf(fields, sizeof(fields),
        ",\"status\":\"STARTED\",\"pid\":%ld,\"uid\":%lu,\"pgid\":%ld,\"sid\":%ld,\"width\":%d,\"height\":%d,\"maxOutputBytes\":65536",
        (long)pid, (unsigned long)uid, (long)pgid, (long)sid, WIDTH, HEIGHT);
    if (!emit_event(argv[2], "begin", fields)) return 1;

    egl_ok = egl_setup(&probe);
    setup_error = probe.egl_error;
    query_error = EGL_SUCCESS;
    egl_vendor = probe.egl_initialized ? query_egl_string(&probe, EGL_VENDOR) : NULL;
    if (probe.egl_error != EGL_SUCCESS) query_error = probe.egl_error;
    egl_version = probe.egl_initialized ? query_egl_string(&probe, EGL_VERSION) : NULL;
    if (probe.egl_error != EGL_SUCCESS && query_error == EGL_SUCCESS) query_error = probe.egl_error;
    egl_client_apis = probe.egl_initialized ? query_egl_string(&probe, EGL_CLIENT_APIS) : NULL;
    if (probe.egl_error != EGL_SUCCESS && query_error == EGL_SUCCESS) query_error = probe.egl_error;
    egl_string_truncated = 0;
    string_truncated = 0;
    json_optional_string(egl_vendor_json, sizeof(egl_vendor_json), egl_vendor, &string_truncated);
    egl_string_truncated |= string_truncated;
    json_optional_string(egl_version_json, sizeof(egl_version_json), egl_version, &string_truncated);
    egl_string_truncated |= string_truncated;
    json_optional_string(egl_client_apis_json, sizeof(egl_client_apis_json), egl_client_apis, &string_truncated);
    egl_string_truncated |= string_truncated;
    if (!egl_ok) {
        probe.egl_error = setup_error;
    } else if (query_error != EGL_SUCCESS) {
        probe.egl_error = query_error;
        egl_ok = 0;
    }
    (void)snprintf(fields, sizeof(fields),
        ",\"status\":\"%s\",\"display\":%s,\"initialized\":%s,\"eglMajor\":%d,\"eglMinor\":%d,\"apiBound\":%s,\"configChosen\":%s,\"pbufferCreated\":%s,\"eglError\":%d,\"vendor\":%s,\"version\":%s,\"clientAPIs\":%s,\"stringTruncated\":%s",
        egl_ok ? "MEASURED" : "UNKNOWN", probe.display != EGL_NO_DISPLAY ? "true" : "false",
        probe.egl_initialized ? "true" : "false", probe.egl_major, probe.egl_minor, probe.api_bound ? "true" : "false",
        probe.config_chosen ? "true" : "false", probe.surface_created ? "true" : "false",
        probe.egl_error, egl_vendor_json, egl_version_json, egl_client_apis_json,
        egl_string_truncated ? "true" : "false");
    (void)emit_event(argv[2], "egl", fields);

    context_ok = egl_ok && create_context(&probe);
    (void)snprintf(fields, sizeof(fields),
        ",\"status\":\"%s\",\"created\":%s,\"current\":%s,\"eglError\":%d",
        !egl_ok ? "UNKNOWN" : (context_ok ? "CREATED" : "CONTEXT_FAILED"),
        probe.context_created ? "true" : "false", probe.current ? "true" : "false", probe.egl_error);
    (void)emit_event(argv[2], "context", fields);

    if (context_ok) {
        caps_ok = query_caps(&probe);
        gl_vendor = query_string(GL_VENDOR);
        gl_renderer = query_string(GL_RENDERER);
        gl_version = query_string(GL_VERSION);
        glsl_version = query_string(GL_SHADING_LANGUAGE_VERSION);
        extensions = query_string(GL_EXTENSIONS);
    }
    gl_string_truncated = 0;
    json_optional_string(gl_vendor_json, sizeof(gl_vendor_json), gl_vendor, &string_truncated);
    gl_string_truncated |= string_truncated;
    json_optional_string(gl_renderer_json, sizeof(gl_renderer_json), gl_renderer, &string_truncated);
    gl_string_truncated |= string_truncated;
    json_optional_string(gl_version_json, sizeof(gl_version_json), gl_version, &string_truncated);
    gl_string_truncated |= string_truncated;
    json_optional_string(glsl_version_json, sizeof(glsl_version_json), glsl_version, &string_truncated);
    gl_string_truncated |= string_truncated;
    extension_truncated = 0;
    json_optional_string(extensions_json, sizeof(extensions_json), extensions, &extension_truncated);
    caps_ok = caps_ok && gl_vendor != NULL && gl_renderer != NULL && gl_version != NULL && glsl_version != NULL;
    (void)cap_value(cap_values[0], sizeof(cap_values[0]), caps_ok, probe.max_texture_size);
    (void)cap_value(cap_values[1], sizeof(cap_values[1]), caps_ok, probe.max_vertex_attribs);
    (void)cap_value(cap_values[2], sizeof(cap_values[2]), caps_ok, probe.max_vertex_uniform_vectors);
    (void)cap_value(cap_values[3], sizeof(cap_values[3]), caps_ok, probe.max_fragment_uniform_vectors);
    (void)cap_value(cap_values[4], sizeof(cap_values[4]), caps_ok, probe.max_varying_vectors);
    (void)cap_value(cap_values[5], sizeof(cap_values[5]), caps_ok, probe.max_combined_texture_units);
    (void)cap_value(cap_values[6], sizeof(cap_values[6]), caps_ok, probe.max_texture_units);
    (void)cap_value(cap_values[7], sizeof(cap_values[7]), caps_ok, probe.max_draw_buffers);
    (void)cap_value(cap_values[8], sizeof(cap_values[8]), caps_ok, probe.max_renderbuffer_size);
    (void)cap_value(cap_values[9], sizeof(cap_values[9]), caps_ok, probe.max_array_texture_layers);
    (void)cap_value(cap_values[10], sizeof(cap_values[10]), caps_ok, probe.max_3d_texture_size);
    (void)cap_value(cap_values[11], sizeof(cap_values[11]), caps_ok, probe.max_samples);
    (void)cap_value(cap_values[12], sizeof(cap_values[12]), caps_ok, probe.max_uniform_buffer_bindings);
    (void)cap_value(cap_values[13], sizeof(cap_values[13]), caps_ok, probe.max_viewport[0]);
    (void)cap_value(cap_values[14], sizeof(cap_values[14]), caps_ok, probe.max_viewport[1]);
    (void)snprintf(fields, sizeof(fields),
        ",\"status\":\"%s\",\"glVendor\":%s,\"glRenderer\":%s,\"glVersion\":%s,\"glslVersion\":%s,\"extensions\":%s,\"extensionsTruncated\":%s,\"glStringsTruncated\":%s,\"maxTextureSize\":%s,\"maxVertexAttribs\":%s,\"maxVertexUniformVectors\":%s,\"maxFragmentUniformVectors\":%s,\"maxVaryingVectors\":%s,\"maxCombinedTextureImageUnits\":%s,\"maxTextureImageUnits\":%s,\"maxDrawBuffers\":%s,\"maxRenderbufferSize\":%s,\"maxArrayTextureLayers\":%s,\"max3DTextureSize\":%s,\"maxSamples\":%s,\"maxUniformBufferBindings\":%s,\"maxViewport\":[%s,%s]",
        !context_ok ? "UNKNOWN" : (caps_ok ? "MEASURED" : "UNKNOWN"), gl_vendor_json, gl_renderer_json,
        gl_version_json, glsl_version_json, extensions_json, extension_truncated ? "true" : "false",
        gl_string_truncated ? "true" : "false", cap_values[0], cap_values[1], cap_values[2], cap_values[3],
        cap_values[4], cap_values[5], cap_values[6], cap_values[7], cap_values[8], cap_values[9],
        cap_values[10], cap_values[11], cap_values[12], cap_values[13], cap_values[14]);
    (void)emit_event(argv[2], "caps", fields);

    shaders_ok = context_ok && caps_ok && build_shaders(&probe);
    {
        char vertex_log_json[2048];
        char fragment_log_json[2048];
        char program_log_json[2048];
        int vertex_truncated;
        int fragment_truncated;
        int program_truncated;
        json_string(vertex_log_json, sizeof(vertex_log_json), probe.vertex_log, MAX_SHADER_LOG, &vertex_truncated);
        json_string(fragment_log_json, sizeof(fragment_log_json), probe.fragment_log, MAX_SHADER_LOG, &fragment_truncated);
        json_string(program_log_json, sizeof(program_log_json), probe.program_log, MAX_SHADER_LOG, &program_truncated);
        (void)snprintf(fields, sizeof(fields),
            ",\"status\":\"%s\",\"vertexCompiled\":%s,\"fragmentCompiled\":%s,\"programLinked\":%s,\"vertexLog\":%s,\"fragmentLog\":%s,\"programLog\":%s,\"logTruncated\":%s",
            !context_ok || !caps_ok ? "UNKNOWN" : (shaders_ok ? "PASS" : "FAILED"),
            probe.vertex_compiled ? "true" : "false", probe.fragment_compiled ? "true" : "false",
            probe.program_linked ? "true" : "false", vertex_log_json,
            fragment_log_json, program_log_json,
            (probe.vertex_log_truncated || probe.fragment_log_truncated || probe.program_log_truncated ||
             vertex_truncated || fragment_truncated || program_truncated) ? "true" : "false");
    }
    (void)emit_event(argv[2], "shaders", fields);

    draw_ok = shaders_ok && draw_triangle(&probe);
    (void)snprintf(fields, sizeof(fields), ",\"status\":\"%s\",\"drawCalls\":%u,\"glError\":%u",
        !shaders_ok ? "UNKNOWN" : (draw_ok ? "PASS" : "FAILED"), draw_ok ? 1U : 0U, (unsigned)probe.gl_error);
    (void)emit_event(argv[2], "draw", fields);

    readback_ok = draw_ok && readback(&probe);
    (void)snprintf(fields, sizeof(fields),
        ",\"status\":\"%s\",\"coloredPixels\":%u,\"requiredColoredPixels\":%u,\"totalPixels\":%u,\"coloredProportion\":%.6f,\"rgbaFnv1a\":%u,\"glError\":%u",
        !draw_ok ? "UNKNOWN" : (readback_ok ? "PASS" : "FAILED"), probe.colored_pixels,
        MIN_COLORED_PIXELS, (unsigned)(WIDTH * HEIGHT),
        (double)probe.colored_pixels / (double)(WIDTH * HEIGHT), probe.pixel_hash, (unsigned)probe.gl_error);
    (void)emit_event(argv[2], "readback", fields);

    graphics_ok = egl_ok && context_ok && caps_ok && shaders_ok && draw_ok && readback_ok;
    cleanup(&probe);
    (void)snprintf(fields, sizeof(fields),
        ",\"status\":\"%s\",\"offscreenPass\":%s,\"cleanupStatus\":\"%s\",\"cleanupFailures\":%d,\"presentationVerified\":false,\"d3dVerified\":false,\"wowVerified\":false,\"pid\":%ld,\"uid\":%lu,\"pgid\":%ld,\"sid\":%ld",
        graphics_ok ? "OFFSCREEN_PASS" : (!egl_ok ? "UNKNOWN" : (!context_ok ? "CONTEXT_FAILED" : "FAILED")),
        graphics_ok ? "true" : "false", probe.cleanup_failures == 0 ? "CLEAN" : "FAILED",
        probe.cleanup_failures, (long)pid, (unsigned long)uid, (long)pgid, (long)sid);
    (void)emit_event(argv[2], "end", fields);
    return graphics_ok && probe.cleanup_failures == 0 && !output_failed ? 0 : 1;
}
