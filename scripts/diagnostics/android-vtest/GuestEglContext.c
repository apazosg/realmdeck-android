#define _GNU_SOURCE

/*
 * The loader control already contains the reviewed, bounded path, map, JSON,
 * environment and GLVND-owner helpers.  Include it as a translation-unit
 * helper only; its diagnostic main is renamed and is never called here.
 */
#define main GuestEglLoaderMainUnused
#include "GuestEglLoader.c"
#undef main

#include <ctype.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <sys/stat.h>

#define CONTEXT_SCHEMA "wowforever.android-guest-egl-context/version1"
#define CONTEXT_MAX_LINE 24576U
#define CONTEXT_MAX_OUTPUT (64U * 1024U)
#define CONTEXT_MAX_STRING 512U
#define CONTEXT_MAX_EXTENSION_SCAN (16U * 1024U)
#define CONTEXT_MAX_MAP_BYTES (2U * 1024U * 1024U)
#define CONTEXT_MAX_MAP_LINE 4096U
#define CONTEXT_MAX_IDENTITY 128U
#define CONTEXT_MAX_PROC_STAT (16U * 1024U)
#define CONTEXT_MAX_ENV_VALUE 4096U
#define CONTEXT_SERVER_IDENTITY "server.identity"

#define EGL_SUCCESS ((EGLint)0x3000)
#define EGL_NOT_INITIALIZED ((EGLint)0x3001)
#define EGL_BAD_DISPLAY ((EGLint)0x3008)
#define EGL_BAD_CONTEXT ((EGLint)0x3006)
#define EGL_BAD_MATCH ((EGLint)0x3009)
#define EGL_PLATFORM_SURFACELESS_MESA ((EGLenum)0x31DD)
#define EGL_VENDOR ((EGLint)0x3053)
#define EGL_VERSION ((EGLint)0x3054)
#define EGL_CLIENT_APIS ((EGLint)0x308D)
#define EGL_OPENGL_API ((EGLenum)0x30A2)
#define EGL_OPENGL_BIT ((EGLint)0x0008)
#define EGL_RED_SIZE ((EGLint)0x3024)
#define EGL_GREEN_SIZE ((EGLint)0x3023)
#define EGL_BLUE_SIZE ((EGLint)0x3022)
#define EGL_ALPHA_SIZE ((EGLint)0x3021)
#define EGL_RENDERABLE_TYPE ((EGLint)0x3040)
#define EGL_SURFACE_TYPE ((EGLint)0x3033)
#define EGL_NONE ((EGLint)0x3038)
#define EGL_CONTEXT_MAJOR_VERSION_KHR ((EGLint)0x3098)
#define EGL_CONTEXT_MINOR_VERSION_KHR ((EGLint)0x30FB)
#define EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR ((EGLint)0x30FD)
#define EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR ((EGLint)0x00000001)
#define EGL_NO_CONTEXT ((EGLContext)0)
#define EGL_NO_SURFACE ((EGLSurface)0)
#define GL_VENDOR 0x1F00U
#define GL_RENDERER 0x1F01U
#define GL_VERSION 0x1F02U
#define GL_SHADING_LANGUAGE_VERSION 0x8B8CU

typedef intptr_t EGLAttrib;
typedef const unsigned char *(*ContextGlGetStringFn)(unsigned int);
typedef unsigned int (*ContextGlGetErrorFn)(void);
typedef void (*ContextGlGetIntegerFn)(unsigned int, int *);

typedef struct ContextServerIdentity {
    pid_t pid;
    uint64_t startTicks;
} ContextServerIdentity;

typedef struct ContextState {
    int measuredMajor;
    int measuredMinor;
    Identity self;
    Paths paths;
    Api api;
    void *handle;
    EGLDisplay display;
    EGLContext context;
    EGLConfig config;
    ContextServerIdentity server;
    char loaderCanonical[MAX_PATH_STRING];
    char vendorCanonical[MAX_PATH_STRING];
    char error[MAX_STRING];
    char missingSymbol[MAX_STRING];
    char wrongOwnerSymbol[MAX_STRING];
    char ownerPath[MAX_PATH_STRING];
    char mappingError[MAX_STRING];
    char clientExtensions[MAX_EXTENSION_PREVIEW + 1U];
    char displayExtensions[MAX_EXTENSION_PREVIEW + 1U];
    char displayVendor[MAX_STRING];
    char displayVersion[MAX_STRING];
    char displayClientApis[MAX_STRING];
    char glVendor[MAX_STRING];
    char glRenderer[MAX_STRING];
    char glVersion[MAX_STRING];
    char glslVersion[MAX_STRING];
    EGLint eglError;
    EGLint cleanupError;
    uint32_t clientExtensionBytes;
    uint32_t displayExtensionBytes;
    uint32_t mapBytes;
    uint32_t mapLines;
    int scopeValid;
    int environmentValid;
    int selectionValid;
    int identityLoaded;
    int identityValid;
    int identityCleanupValid;
    int libraryLoaded;
    int exportsValid;
    int clientQueryValid;
    int clientExtensionsValid;
    int clientExtensionsTruncated;
    int clientPlatformBase;
    int displayCreated;
    int displayInitialized;
    int displayExtensionsValid;
    int displayExtensionsTruncated;
    int displayCreateContextExtension;
    int displaySurfacelessExtension;
    int boundApi;
    int configChosen;
    int contextCreated;
    int contextCore;
    int madeCurrent;
    int glResolved;
    int glValid;
    int rendererVirgl;
    int softwareFallback;
    unsigned int glError;
    int mapsComplete;
    int mapsTruncated;
    int privateLoaderMapped;
    int privateVendorMapped;
    int privateGalliumMapped;
    int privateLlvmMapped;
    int androidEglMapped;
    int androidGlesMapped;
    int unbindAttempted;
    int unbindOk;
    int destroyAttempted;
    int destroyOk;
    int terminateAttempted;
    int terminateOk;
    int handleClosed;
    int cleanupFailures;
    int contextFailure;
    int outputFailure;
    int clockFailure;
} ContextState;

static size_t contextOutputBytes;

/* HOST-TEST-BEGIN: encoder and bounded, non-EGL context predicates. */
static int context_valid_run_id(const char *value) {
    return valid_run_id(value);
}

static int context_copy_string(char *out, size_t capacity, const char *value) {
    size_t length;
    int terminated = 0;
    if (out == NULL || capacity == 0U || value == NULL) return 0;
    length = bounded_length(value, capacity - 1U, &terminated);
    if (!terminated) return 0;
    memcpy(out, value, length);
    out[length] = '\0';
    return 1;
}

static int context_contains_token(const char *value, const char *token) {
    size_t length;
    size_t tokenLength;
    size_t at;
    if (value == NULL || token == NULL) return 0;
    length = bounded_length(value, CONTEXT_MAX_EXTENSION_SCAN, NULL);
    tokenLength = bounded_length(token, CONTEXT_MAX_STRING, NULL);
    if (tokenLength == 0U || tokenLength > length) return 0;
    for (at = 0U; at + tokenLength <= length; ++at) {
        if ((at == 0U || value[at - 1U] == ' ') &&
            (at + tokenLength == length || value[at + tokenLength] == ' ') &&
            memcmp(value + at, token, tokenLength) == 0) return 1;
    }
    return 0;
}

static int context_contains_casefold(const char *value, const char *needle) {
    size_t length;
    size_t needleLength;
    size_t at;
    size_t index;
    if (value == NULL || needle == NULL) return 0;
    length = bounded_length(value, CONTEXT_MAX_STRING, NULL);
    needleLength = bounded_length(needle, CONTEXT_MAX_STRING, NULL);
    if (needleLength == 0U || needleLength > length) return 0;
    for (at = 0U; at + needleLength <= length; ++at) {
        for (index = 0U; index < needleLength; ++index) {
            if (tolower((unsigned char)value[at + index]) !=
                tolower((unsigned char)needle[index])) break;
        }
        if (index == needleLength) return 1;
    }
    return 0;
}
/* HOST-TEST-END */

static int context_emit(const char *runId, const Identity *identity,
                        const char *event, const char *fields) {
    char line[CONTEXT_MAX_LINE];
    struct timespec now;
    int length;
    uint64_t timeNs;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0;
    timeNs = (uint64_t)now.tv_sec * UINT64_C(1000000000) + (uint64_t)now.tv_nsec;
    if (timeNs == 0U) return 0;
    length = snprintf(line, sizeof(line),
        "{\"schema\":\"%s\",\"runId\":\"%s\",\"event\":\"%s\",\"timeNs\":%llu,"
        "\"pid\":%ld,\"uid\":%lu,\"pgid\":%ld,\"sid\":%ld%s}\n",
        CONTEXT_SCHEMA, runId, event, (unsigned long long)timeNs,
        (long)identity->pid, (unsigned long)identity->uid,
        (long)identity->pgid, (long)identity->sid, fields);
    if (length <= 0 || (size_t)length >= sizeof(line) ||
        contextOutputBytes + (size_t)length > CONTEXT_MAX_OUTPUT ||
        fputs(line, stdout) == EOF || fflush(stdout) == EOF || ferror(stdout) != 0) return 0;
    contextOutputBytes += (size_t)length;
    return 1;
}

static void context_json(char *out, size_t capacity, const char *value) {
    if (value == NULL || value[0] == '\0') {
        (void)snprintf(out, capacity, "null");
    } else {
        json_string(out, capacity, value, CONTEXT_MAX_STRING, NULL);
    }
}

static int context_parse_uint(const unsigned char *data, size_t length, uint64_t maximum,
                              uint64_t *value) {
    size_t index;
    uint64_t parsed = 0U;
    if (data == NULL || value == NULL || length == 0U) return 0;
    for (index = 0U; index < length; ++index) {
        uint64_t digit;
        if (data[index] < (unsigned char)'0' || data[index] > (unsigned char)'9') return 0;
        digit = (uint64_t)(data[index] - (unsigned char)'0');
        if (parsed > (maximum - digit) / 10U) return 0;
        parsed = parsed * 10U + digit;
    }
    *value = parsed;
    return 1;
}

static int context_parse_identity(const unsigned char *data, size_t length,
                                  ContextServerIdentity *identity) {
    size_t split = 0U;
    size_t end;
    uint64_t pid;
    uint64_t ticks;
    if (data == NULL || identity == NULL || length == 0U || length > CONTEXT_MAX_IDENTITY) return 0;
    while (split < length && data[split] != (unsigned char)' ') ++split;
    if (split == 0U || split >= length || !context_parse_uint(data, split, INT_MAX, &pid)) return 0;
    end = split + 1U;
    while (end < length && data[end] != (unsigned char)'\n') ++end;
    if (end <= split + 1U || end + 1U != length ||
        !context_parse_uint(data + split + 1U, end - split - 1U, UINT64_MAX, &ticks) ||
        pid == 0U || ticks == 0U) return 0;
    identity->pid = (pid_t)pid;
    identity->startTicks = ticks;
    return 1;
}

static int context_load_server_identity(ContextServerIdentity *identity) {
    struct stat listed;
    struct stat opened;
    unsigned char data[CONTEXT_MAX_IDENTITY];
    size_t expected;
    size_t used = 0U;
    int fd = -1;
    int result = 0;
    if (identity == NULL || lstat(CONTEXT_SERVER_IDENTITY, &listed) != 0 ||
        !S_ISREG(listed.st_mode) || listed.st_uid != getuid() ||
        (listed.st_mode & 07777U) != 0600 || listed.st_size <= 0 ||
        (uintmax_t)listed.st_size > CONTEXT_MAX_IDENTITY) return 0;
    expected = (size_t)listed.st_size;
    fd = open(CONTEXT_SERVER_IDENTITY, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0 || fstat(fd, &opened) != 0 || !S_ISREG(opened.st_mode) ||
        opened.st_uid != getuid() || (opened.st_mode & 07777U) != 0600 ||
        opened.st_dev != listed.st_dev || opened.st_ino != listed.st_ino ||
        opened.st_size != listed.st_size) goto done;
    while (used < expected) {
        ssize_t count = read(fd, data + used, expected - used);
        if (count > 0) used += (size_t)count;
        else if (count < 0 && errno == EINTR) continue;
        else goto done;
    }
    {
        unsigned char extra;
        ssize_t count;
        do { count = read(fd, &extra, sizeof(extra)); } while (count < 0 && errno == EINTR);
        if (count != 0) goto done;
    }
    if (fstat(fd, &opened) != 0 || opened.st_dev != listed.st_dev ||
        opened.st_ino != listed.st_ino || opened.st_size != listed.st_size ||
        !context_parse_identity(data, used, identity)) goto done;
    result = 1;
done:
    if (fd >= 0 && close(fd) != 0) result = 0;
    return result;
}

static int context_read_proc_ticks(pid_t pid, uint64_t *ticks) {
    char path[64];
    unsigned char data[CONTEXT_MAX_PROC_STAT];
    size_t used = 0U;
    size_t first = 0U;
    size_t closeParen = 0U;
    size_t cursor;
    uint64_t observed;
    uint64_t parsed = 0U;
    size_t field;
    int fd;
    int written = snprintf(path, sizeof(path), "/proc/%ld/stat", (long)pid);
    if (written <= 0 || (size_t)written >= sizeof(path) || ticks == NULL || pid <= 0) return 0;
    fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return 0;
    while (used < sizeof(data)) {
        ssize_t count = read(fd, data + used, sizeof(data) - used);
        if (count > 0) used += (size_t)count;
        else if (count == 0) break;
        else if (errno != EINTR) { (void)close(fd); return 0; }
    }
    if (close(fd) != 0 || used == sizeof(data)) return 0;
    while (first < used && data[first] != (unsigned char)' ') ++first;
    cursor = used;
    while (cursor > first + 1U) {
        --cursor;
        if (data[cursor] == (unsigned char)')' && cursor + 1U < used && data[cursor + 1U] == (unsigned char)' ') {
            closeParen = cursor;
            break;
        }
    }
    if (first == 0U || first >= used || closeParen == 0U ||
        !context_parse_uint(data, first, INT_MAX, &observed) || observed != (uint64_t)pid) return 0;
    cursor = closeParen + 2U;
    if (cursor >= used || data[cursor] == 'Z' || data[cursor] == 'X') return 0;
    for (field = 0U; field <= 19U; ++field) {
        size_t start;
        while (cursor < used && isspace(data[cursor])) ++cursor;
        start = cursor;
        while (cursor < used && !isspace(data[cursor])) ++cursor;
        if (start == cursor) return 0;
        if (field == 19U && !context_parse_uint(data + start, cursor - start, UINT64_MAX, &parsed)) return 0;
    }
    if (parsed == 0U) return 0;
    *ticks = parsed;
    return 1;
}

static int context_server_live(const ContextServerIdentity *identity, const char *cwd) {
    char path[64];
    unsigned char status[4096];
    size_t used = 0U;
    uint64_t ticks;
    int fd;
    int written;
    if (identity == NULL || !context_read_proc_ticks(identity->pid, &ticks) || ticks != identity->startTicks) return 0;
    written = snprintf(path, sizeof(path), "/proc/%ld/status", (long)identity->pid);
    if (written <= 0 || (size_t)written >= sizeof(path)) return 0;
    fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return 0;
    while (used < sizeof(status)) {
        ssize_t count = read(fd, status + used, sizeof(status) - used);
        if (count > 0) used += (size_t)count;
        else if (count == 0) break;
        else if (errno != EINTR) { (void)close(fd); return 0; }
    }
    if (close(fd) != 0 || used == sizeof(status)) return 0;
    for (size_t at = 0U; at + 4U < used; ++at) {
        if (memcmp(status + at, "Uid:\t", 5U) == 0) {
            size_t cursor = at + 5U;
            uint64_t after;
            for (unsigned i = 0U; i < 4U; ++i) {
                size_t start;
                uint64_t uid;
                while (cursor < used && (status[cursor] == ' ' || status[cursor] == '\t')) ++cursor;
                start = cursor;
                while (cursor < used && status[cursor] >= '0' && status[cursor] <= '9') ++cursor;
                if (!context_parse_uint(status + start, cursor - start, UINT_MAX, &uid) || uid != EXPECTED_UID) return 0;
            }
            if (cursor >= used || status[cursor] != '\n') return 0;
            {
                char link[4096], expected[4096], command[8192], expectedCommand[8192];
                size_t size = 0U, want = 0U;
                ssize_t linked;
                int cfd, n;
                const char *args[] = {"--no-loop-or-fork", "--use-gles", "--socket-path", "vtest.sock"};
                n = snprintf(path, sizeof(path), "/proc/%ld/exe", (long)identity->pid);
                if (n <= 0 || (size_t)n >= sizeof(path)) return 0;
                linked = readlink(path, link, sizeof(link) - 1U);
                if (linked <= 0 || (size_t)linked >= sizeof(link) - 1U) return 0;
                link[linked] = '\0';
                if (strcmp(link, "/system/bin/linker64") && strcmp(link, "/apex/com.android.runtime/bin/linker64")) return 0;
                n = snprintf(expected, sizeof(expected), "%s/bin/virgl_test_server", cwd);
                if (n <= 0 || (size_t)n >= sizeof(expected)) return 0;
                n = snprintf(path, sizeof(path), "/proc/%ld/cmdline", (long)identity->pid);
                if (n <= 0 || (size_t)n >= sizeof(path)) return 0;
                cfd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
                if (cfd < 0) return 0;
                while (size < sizeof(command)) {
                    ssize_t got = read(cfd, command + size, sizeof(command) - size);
                    if (got > 0) size += (size_t)got;
                    else if (got == 0) break;
                    else if (errno != EINTR) { (void)close(cfd); return 0; }
                }
                if (close(cfd) || !size || size == sizeof(command)) return 0;
                if (size >= sizeof("/system/bin/linker64") && !memcmp(command, "/system/bin/linker64", sizeof("/system/bin/linker64"))) {
                    memcpy(expectedCommand, "/system/bin/linker64", sizeof("/system/bin/linker64"));
                    want = sizeof("/system/bin/linker64");
                }
                {
                    size_t length = strlen(expected) + 1U;
                    if (want + length > sizeof(expectedCommand)) return 0;
                    memcpy(expectedCommand + want, expected, length); want += length;
                }
                for (unsigned j = 0U; j < 4U; ++j) {
                    size_t length = strlen(args[j]) + 1U;
                    if (want + length > sizeof(expectedCommand)) return 0;
                    memcpy(expectedCommand + want, args[j], length); want += length;
                }
                if (size != want || memcmp(command, expectedCommand, want)) return 0;
                return context_read_proc_ticks(identity->pid, &after) && after == identity->startTicks;
            }
        }
    }
    return 0;
}

static int context_scan_extensions(const char *value, char *preview, size_t previewCapacity,
                                   uint32_t *bytes, int *truncated, int *platformBase,
                                   int *createContext, int *surfaceless) {
    size_t length;
    int terminated = 0;
    if (bytes != NULL) *bytes = 0U;
    if (truncated != NULL) *truncated = 0;
    if (platformBase != NULL) *platformBase = 0;
    if (createContext != NULL) *createContext = 0;
    if (surfaceless != NULL) *surfaceless = 0;
    if (value == NULL) return 0;
    length = bounded_length(value, CONTEXT_MAX_EXTENSION_SCAN, &terminated);
    if (bytes != NULL) *bytes = (uint32_t)length;
    if (!terminated) return 0;
    if (preview != NULL && previewCapacity > 0U) {
        size_t copy = length < previewCapacity - 1U ? length : previewCapacity - 1U;
        memcpy(preview, value, copy);
        preview[copy] = '\0';
        if (truncated != NULL) *truncated = length >= previewCapacity - 1U;
    }
    if (platformBase != NULL) *platformBase = context_contains_token(value, "EGL_EXT_platform_base");
    if (createContext != NULL) *createContext = context_contains_token(value, "EGL_KHR_create_context");
    if (surfaceless != NULL) *surfaceless = context_contains_token(value, "EGL_KHR_surfaceless_context");
    return 1;
}

static int context_copy_symbol(void *target, size_t targetSize, void *symbol) {
    if (target == NULL || symbol == NULL || targetSize != sizeof(symbol)) return 0;
    memcpy(target, &symbol, sizeof(symbol));
    return 1;
}

static int context_resolve_exports(ContextState *state) {
    int ok = 1;
#define CONTEXT_RESOLVE(field, name) do { \
        state->ownerPath[0] = '\0'; \
        void *symbol = dlsym(state->handle, name); \
        const char *error = dlerror(); \
        if (symbol == NULL || error != NULL || !context_copy_symbol(&state->api.field, sizeof(state->api.field), symbol) || \
            !owner_is_loader(symbol, state->loaderCanonical, state->ownerPath, sizeof(state->ownerPath))) { \
            if (state->missingSymbol[0] == '\0') (void)copy_bounded(state->missingSymbol, sizeof(state->missingSymbol), name, MAX_STRING - 1U); \
            if (state->ownerPath[0] != '\0' && state->wrongOwnerSymbol[0] == '\0') (void)copy_bounded(state->wrongOwnerSymbol, sizeof(state->wrongOwnerSymbol), name, MAX_STRING - 1U); \
            ok = 0; \
        } \
    } while (0)
    (void)dlerror();
    CONTEXT_RESOLVE(bindApi, "eglBindAPI");
    CONTEXT_RESOLVE(chooseConfig, "eglChooseConfig");
    CONTEXT_RESOLVE(createContext, "eglCreateContext");
    CONTEXT_RESOLVE(getError, "eglGetError");
    CONTEXT_RESOLVE(getPlatformDisplay, "eglGetPlatformDisplay");
    CONTEXT_RESOLVE(getProcAddress, "eglGetProcAddress");
    CONTEXT_RESOLVE(initialize, "eglInitialize");
    CONTEXT_RESOLVE(makeCurrent, "eglMakeCurrent");
    CONTEXT_RESOLVE(queryString, "eglQueryString");
    CONTEXT_RESOLVE(terminate, "eglTerminate");
#undef CONTEXT_RESOLVE
    state->exportsValid = ok;
    return ok;
}

static void context_mark_map(ContextState *state, const char *line) {
    if (map_line_has_exact_path(line, state->loaderCanonical)) state->privateLoaderMapped = 1;
    if (map_line_has_exact_path(line, state->vendorCanonical)) state->privateVendorMapped = 1;
    if (map_line_has_exact_path(line, state->paths.gallium)) state->privateGalliumMapped = 1;
    if (map_line_has_exact_path(line, state->paths.llvm)) state->privateLlvmMapped = 1;
    if (map_line_has_android_graphics(line, 0)) state->androidEglMapped = 1;
    if (map_line_has_android_graphics(line, 1)) state->androidGlesMapped = 1;
}

static int context_read_maps(ContextState *state) {
    FILE *file = fopen("/proc/self/maps", "rb");
    char line[CONTEXT_MAX_MAP_LINE + 2U];
    size_t total = 0U;
    int complete = 1;
    if (file == NULL) {
        (void)copy_bounded(state->mappingError, sizeof(state->mappingError), strerror(errno), MAX_STRING - 1U);
        return 0;
    }
    while (total < CONTEXT_MAX_MAP_BYTES) {
        size_t length;
        if (CONTEXT_MAX_MAP_BYTES - total < sizeof(line)) {
            (void)fread(line, 1U, CONTEXT_MAX_MAP_BYTES - total, file);
            total = CONTEXT_MAX_MAP_BYTES;
            complete = 0;
            state->mapsTruncated = 1;
            break;
        }
        if (fgets(line, sizeof(line), file) == NULL) break;
        length = strlen(line);
        total += length;
        ++state->mapLines;
        if (length == 0U || line[length - 1U] != '\n' || length - 1U > CONTEXT_MAX_MAP_LINE) {
            complete = 0;
            state->mapsTruncated = 1;
        } else {
            context_mark_map(state, line);
        }
    }
    if (ferror(file) != 0 || total >= CONTEXT_MAX_MAP_BYTES) complete = 0;
    (void)fclose(file);
    state->mapBytes = total > UINT32_MAX ? UINT32_MAX : (uint32_t)total;
    state->mapsComplete = complete && !state->mapsTruncated;
    return state->mapsComplete;
}

static int context_copy_observation(char *out, size_t capacity, const char *value) {
    return value != NULL && context_copy_string(out, capacity, value);
}

static void context_resolve_gl(ContextState *state) {
    ContextGlGetStringFn getString = NULL;
    ContextGlGetErrorFn getError = NULL;
    ContextGlGetIntegerFn getInteger = NULL;
    void *symbol;
    const unsigned char *value;
    if (!state->madeCurrent) return;
    symbol = state->api.getProcAddress("glGetString");
    if (symbol != NULL) (void)context_copy_symbol(&getString, sizeof(getString), symbol);
    symbol = state->api.getProcAddress("glGetError");
    if (symbol != NULL) (void)context_copy_symbol(&getError, sizeof(getError), symbol);
    symbol = state->api.getProcAddress("glGetIntegerv");
    if (symbol != NULL) (void)context_copy_symbol(&getInteger, sizeof(getInteger), symbol);
    state->glResolved = getString != NULL && getError != NULL && getInteger != NULL;
    if (!state->glResolved) {
        state->contextFailure = 1;
        return;
    }
    value = getString(GL_VENDOR);
    if (!context_copy_observation(state->glVendor, sizeof(state->glVendor), (const char *)value)) state->contextFailure = 1;
    value = getString(GL_RENDERER);
    if (!context_copy_observation(state->glRenderer, sizeof(state->glRenderer), (const char *)value)) state->contextFailure = 1;
    value = getString(GL_VERSION);
    if (!context_copy_observation(state->glVersion, sizeof(state->glVersion), (const char *)value)) state->contextFailure = 1;
    value = getString(GL_SHADING_LANGUAGE_VERSION);
    if (!context_copy_observation(state->glslVersion, sizeof(state->glslVersion), (const char *)value)) state->contextFailure = 1;
    state->glError = getError();
    {
        int profile = 0;
        getInteger(0x821bU, &state->measuredMajor);
        getInteger(0x821cU, &state->measuredMinor);
        getInteger(0x9126U, &profile);
        state->contextCore = (profile & 1) != 0;
        if (getError() != 0U || state->measuredMajor < 3 || (state->measuredMajor == 3 && state->measuredMinor < 3) || !state->contextCore) state->contextFailure = 1;
    }
    state->rendererVirgl = context_contains_casefold(state->glRenderer, "virgl");
    state->softwareFallback = context_contains_casefold(state->glRenderer, "llvmpipe") ||
                              context_contains_casefold(state->glRenderer, "softpipe") ||
                              context_contains_casefold(state->glRenderer, "swrast") ||
                              context_contains_casefold(state->glRenderer, "software");
    state->glValid = !state->contextFailure && state->glError == 0U && state->rendererVirgl && !state->softwareFallback;
    if (!state->glValid) state->contextFailure = 1;
}

/* eglDestroyContext is deliberately kept out of the ten audited loader exports. */
typedef EGLBoolean (*ContextDestroyContextFn)(EGLDisplay, EGLContext);

static int context_run(const char *runId, Identity *identity) {
    ContextState state;
    char json[CONTEXT_MAX_LINE];
    char a[CONTEXT_MAX_STRING * 2U];
    char b[CONTEXT_MAX_STRING * 2U];
    char c[CONTEXT_MAX_STRING * 2U];
    char d[CONTEXT_MAX_STRING * 2U];
    char e[CONTEXT_MAX_STRING * 2U];
    int clientPlatform = 0;
    int displayCreate = 0;
    int displaySurface = 0;
    int endPass;
    int endEmitted;
    const char *extensions;
    EGLint major = 0;
    EGLint minor = 0;
    EGLint configCount = 0;
    EGLint attributes[] = {
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_SURFACE_TYPE, 0, EGL_NONE
    };
    EGLint contextAttributes[] = {
        EGL_CONTEXT_MAJOR_VERSION_KHR, 3, EGL_CONTEXT_MINOR_VERSION_KHR, 3,
        EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR, EGL_NONE
    };
    ContextDestroyContextFn destroyContext = NULL;
    memset(&state, 0, sizeof(state));
    state.self = *identity;
    state.display = EGL_NO_DISPLAY;
    state.context = EGL_NO_CONTEXT;
    state.scopeValid = validate_scope(runId, identity, &state.paths);
    state.environmentValid = state.scopeValid;
    state.selectionValid = state.scopeValid;
    state.identityLoaded = context_load_server_identity(&state.server);
    state.identityValid = state.identityLoaded && context_server_live(&state.server, state.paths.cwd);
    if (!state.identityValid) state.contextFailure = 1;
    context_json(a, sizeof(a), state.paths.cwd);
    context_json(b, sizeof(b), state.paths.loader);
    (void)snprintf(json, sizeof(json),
        ",\"status\":\"%s\",\"scopeValid\":%s,\"runIdValid\":%s,\"uidValid\":%s,\"cwdValid\":%s,"
        "\"environmentValid\":%s,\"selectionValid\":%s,\"cwd\":%s,\"libraryPath\":%s,"
        "\"serverIdentityLoaded\":%s,\"serverIdentityVerified\":%s,\"expectedServerPid\":%ld,\"expectedServerStartTicks\":%llu",
        state.scopeValid ? "VALID" : "UNKNOWN", state.scopeValid ? "true" : "false",
        valid_run_id(runId) ? "true" : "false", identity->uid == (uid_t)EXPECTED_UID ? "true" : "false",
        state.scopeValid ? "true" : "false", state.environmentValid ? "true" : "false",
        state.selectionValid ? "true" : "false", a, b, state.identityLoaded ? "true" : "false",
        state.identityValid ? "true" : "false", (long)state.server.pid,
        (unsigned long long)state.server.startTicks);
    if (!context_emit(runId, identity, "begin", ",\"status\":\"STARTED\"")) state.outputFailure = 1;
    if (!context_emit(runId, identity, "scope", json)) state.outputFailure = 1;

    if (state.scopeValid && state.identityValid) {
        state.handle = dlopen("guest/lib/libEGL.so.1.1.0", RTLD_NOW | RTLD_LOCAL);
        state.libraryLoaded = state.handle != NULL;
        if (!state.libraryLoaded) {
            const char *error = dlerror();
            (void)copy_bounded(state.error, sizeof(state.error), error == NULL ? "dlopen failed" : error, MAX_STRING - 1U);
        } else if (!canonical_path(state.paths.loader, state.loaderCanonical, sizeof(state.loaderCanonical))) {
            (void)copy_bounded(state.error, sizeof(state.error), "private loader canonicalization failed", MAX_STRING - 1U);
            state.contextFailure = 1;
        }
    }
    context_json(a, sizeof(a), state.paths.loader);
    context_json(b, sizeof(b), state.loaderCanonical);
    context_json(c, sizeof(c), state.error);
    (void)snprintf(json, sizeof(json), ",\"status\":\"%s\",\"loaded\":%s,\"privatePath\":%s,\"canonicalPath\":%s,\"loadError\":%s",
                   state.libraryLoaded && state.loaderCanonical[0] != '\0' ? "LOADED" : (state.scopeValid ? "CONTEXT_FAILED" : "UNKNOWN"),
                   state.libraryLoaded ? "true" : "false", a, b, c);
    if (!context_emit(runId, identity, "library", json)) state.outputFailure = 1;

    if (state.libraryLoaded && state.loaderCanonical[0] != '\0') (void)context_resolve_exports(&state);
    context_json(a, sizeof(a), state.loaderCanonical);
    context_json(b, sizeof(b), state.missingSymbol);
    context_json(c, sizeof(c), state.wrongOwnerSymbol);
    context_json(d, sizeof(d), state.ownerPath);
    (void)snprintf(json, sizeof(json), ",\"status\":\"%s\",\"exportCount\":%u,\"expectedExportCount\":10,\"allExports\":%s,\"ownerExact\":%s,\"ownerPath\":%s,\"missingSymbol\":%s,\"wrongOwnerSymbol\":%s",
                   state.exportsValid ? "EXPORTED" : (state.libraryLoaded ? "CONTEXT_FAILED" : "UNKNOWN"),
                   state.exportsValid ? 10U : 0U, state.exportsValid ? "true" : "false",
                   state.exportsValid ? "true" : "false", d, b, c);
    if (!context_emit(runId, identity, "exports", json)) state.outputFailure = 1;

    if (state.exportsValid) {
        extensions = state.api.queryString(EGL_NO_DISPLAY, EGL_EXTENSIONS);
        state.eglError = state.api.getError();
        state.clientQueryValid = extensions != NULL;
        state.clientExtensionsValid = context_scan_extensions(extensions, state.clientExtensions,
            sizeof(state.clientExtensions), &state.clientExtensionBytes, &state.clientExtensionsTruncated,
            &clientPlatform, NULL, NULL);
        state.clientPlatformBase = clientPlatform;
        if (!state.clientExtensionsValid || state.eglError != EGL_SUCCESS || !clientPlatform) state.contextFailure = 1;
    }
    /* The fifth row is deliberately the display observation, including the client query. */
    if (state.exportsValid && state.clientExtensionsValid && clientPlatform && state.identityValid) {
        state.display = state.api.getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, NULL, NULL);
        state.displayCreated = state.display != EGL_NO_DISPLAY;
        state.eglError = state.api.getError();
        if (!state.displayCreated || state.eglError != EGL_SUCCESS) state.contextFailure = 1;
        if (state.displayCreated) {
            state.displayInitialized = state.api.initialize(state.display, &major, &minor) != 0U;
            state.eglError = state.api.getError();
            if (!state.displayInitialized || state.eglError != EGL_SUCCESS) state.contextFailure = 1;
        }
        if (state.displayInitialized) {
            extensions = state.api.queryString(state.display, EGL_EXTENSIONS);
            state.eglError = state.api.getError();
            state.displayExtensionsValid = context_scan_extensions(extensions, state.displayExtensions,
                sizeof(state.displayExtensions), &state.displayExtensionBytes, &state.displayExtensionsTruncated,
                NULL, &displayCreate, &displaySurface);
            state.displayCreateContextExtension = displayCreate;
            state.displaySurfacelessExtension = displaySurface;
            state.displayExtensionsValid = state.displayExtensionsValid && displayCreate && displaySurface;
            if (!state.displayExtensionsValid || state.eglError != EGL_SUCCESS) state.contextFailure = 1;
            if (!context_copy_observation(state.displayVendor, sizeof(state.displayVendor), state.api.queryString(state.display, EGL_VENDOR)) || state.api.getError() != EGL_SUCCESS) state.contextFailure = 1;
            if (!context_copy_observation(state.displayVersion, sizeof(state.displayVersion), state.api.queryString(state.display, EGL_VERSION)) || state.api.getError() != EGL_SUCCESS) state.contextFailure = 1;
            if (!context_copy_observation(state.displayClientApis, sizeof(state.displayClientApis), state.api.queryString(state.display, EGL_CLIENT_APIS)) || state.api.getError() != EGL_SUCCESS) state.contextFailure = 1;
        }
    }
    context_json(a, sizeof(a), state.displayExtensions);
    context_json(b, sizeof(b), state.displayVendor);
    context_json(c, sizeof(c), state.displayVersion);
    context_json(d, sizeof(d), state.displayClientApis);
    context_json(e, sizeof(e), state.clientExtensions);
    (void)snprintf(json, sizeof(json), ",\"status\":\"%s\",\"clientQueryValid\":%s,\"clientExtensionScanValid\":%s,\"clientExtensions\":%s,\"clientExtensionScanBytes\":%u,\"clientExtensionsTruncated\":%s,\"hasEGL_EXT_platform_base\":%s,\"displayCreated\":%s,\"displayInitialized\":%s,\"displayExtensions\":%s,\"displayExtensionScanBytes\":%u,\"displayExtensionsTruncated\":%s,\"hasEGL_KHR_create_context\":%s,\"hasEGL_KHR_surfaceless_context\":%s,\"vendor\":%s,\"version\":%s,\"clientApis\":%s,\"major\":%d,\"minor\":%d,\"eglError\":%d,\"serverIdentityVerified\":%s",
                   state.displayInitialized && state.displayExtensionsValid ? "MEASURED" : (state.exportsValid ? "CONTEXT_FAILED" : "UNKNOWN"),
                   state.clientQueryValid ? "true" : "false", state.clientExtensionsValid ? "true" : "false", e,
                   (unsigned)state.clientExtensionBytes, state.clientExtensionsTruncated ? "true" : "false",
                   state.clientExtensionsValid ? (clientPlatform ? "true" : "false") : "null",
                   state.displayCreated ? "true" : "false", state.displayInitialized ? "true" : "false", a,
                   (unsigned)state.displayExtensionBytes, state.displayExtensionsTruncated ? "true" : "false",
                   state.displayExtensionsValid ? (displayCreate ? "true" : "false") : "null", state.displayExtensionsValid ? (displaySurface ? "true" : "false") : "null",
                   b, c, d, (int)major, (int)minor, (int)state.eglError, state.identityValid ? "true" : "false");
    if (!context_emit(runId, identity, "display", json)) state.outputFailure = 1;

    if (state.displayInitialized && state.displayExtensionsValid && state.identityValid) {
        state.boundApi = state.api.bindApi(EGL_OPENGL_API) != 0U;
        state.eglError = state.api.getError();
        if (!state.boundApi || state.eglError != EGL_SUCCESS) state.contextFailure = 1;
        if (state.boundApi) {
            state.configChosen = state.api.chooseConfig(state.display, attributes, &state.config, 1, &configCount) != 0U && configCount == 1;
            state.eglError = state.api.getError();
            if (!state.configChosen || state.eglError != EGL_SUCCESS) state.contextFailure = 1;
        }
        if (state.configChosen) {
            state.context = state.api.createContext(state.display, state.config, EGL_NO_CONTEXT, contextAttributes);
            state.contextCreated = state.context != EGL_NO_CONTEXT;
            state.eglError = state.api.getError();
            if (!state.contextCreated || state.eglError != EGL_SUCCESS) state.contextFailure = 1;
        }
        if (state.contextCreated) {
            state.madeCurrent = state.api.makeCurrent(state.display, EGL_NO_SURFACE, EGL_NO_SURFACE, state.context) != 0U;
            state.eglError = state.api.getError();
            if (!state.madeCurrent || state.eglError != EGL_SUCCESS) state.contextFailure = 1;
        }
    }
    context_resolve_gl(&state);
    (void)snprintf(json, sizeof(json), ",\"status\":\"%s\",\"boundApi\":%s,\"configCount\":%d,\"configChosen\":%s,\"contextCreated\":%s,\"contextMajor\":%d,\"contextMinor\":%d,\"profileCore\":%s,\"makeCurrent\":%s,\"eglError\":%d,\"serverIdentityVerified\":%s",
                   state.madeCurrent ? "CURRENT" : (state.exportsValid ? "CONTEXT_FAILED" : "UNKNOWN"), state.boundApi ? "true" : "false",
                   (int)configCount, state.configChosen ? "true" : "false", state.contextCreated ? "true" : "false",
                    state.measuredMajor, state.measuredMinor, state.contextCore ? "true" : "false", state.madeCurrent ? "true" : "false", (int)state.eglError,
                   state.identityValid ? "true" : "false");
    if (!context_emit(runId, identity, "context", json)) state.outputFailure = 1;

    context_json(a, sizeof(a), state.glVendor);
    context_json(b, sizeof(b), state.glRenderer);
    context_json(c, sizeof(c), state.glVersion);
    context_json(d, sizeof(d), state.glslVersion);
    (void)snprintf(json, sizeof(json), ",\"status\":\"%s\",\"glExportsResolved\":%s,\"vendor\":%s,\"renderer\":%s,\"version\":%s,\"glslVersion\":%s,\"rendererContainsVirgl\":%s,\"softwareFallback\":%s,\"glError\":%u,\"serverIdentityVerified\":%s",
                   state.glValid ? "MEASURED" : (state.madeCurrent ? "CONTEXT_FAILED" : "UNKNOWN"), state.glResolved ? "true" : "false",
                   a, b, c, d, state.rendererVirgl ? "true" : "false", state.softwareFallback ? "true" : "false",
                   state.glError, state.identityValid ? "true" : "false");
    if (!context_emit(runId, identity, "gl", json)) state.outputFailure = 1;

    if (state.libraryLoaded && state.loaderCanonical[0] != '\0') {
        (void)canonical_path(state.paths.vendor, state.vendorCanonical, sizeof(state.vendorCanonical));
        (void)context_read_maps(&state);
    }
    context_json(a, sizeof(a), state.mappingError);
    (void)snprintf(json, sizeof(json), ",\"status\":\"%s\",\"mapsRead\":%s,\"mapsComplete\":%s,\"mapsTruncated\":%s,\"mapsBytes\":%u,\"mapLines\":%u,\"privateLoaderMapped\":%s,\"privateVendorMapped\":%s,\"privateGalliumMapped\":%s,\"privateLlvmMapped\":%s,\"androidEglMapped\":%s,\"androidGlesMapped\":%s,\"mappingError\":%s",
                   state.mapsComplete && state.privateLoaderMapped && state.privateVendorMapped && state.privateGalliumMapped && state.privateLlvmMapped && !state.androidEglMapped && !state.androidGlesMapped ? "MAPPED" : "UNKNOWN",
                   state.mapBytes != 0U ? "true" : "false", state.mapsComplete ? "true" : "false", state.mapsTruncated ? "true" : "false",
                   (unsigned)state.mapBytes, (unsigned)state.mapLines, state.privateLoaderMapped ? "true" : "null", state.privateVendorMapped ? "true" : "null",
                   state.privateGalliumMapped ? "true" : "null", state.privateLlvmMapped ? "true" : "null",
                   state.mapsComplete ? (state.androidEglMapped ? "true" : "false") : "null", state.mapsComplete ? (state.androidGlesMapped ? "true" : "false") : "null", a);
    if (!context_emit(runId, identity, "mappings", json)) state.outputFailure = 1;

    if (state.display != EGL_NO_DISPLAY && state.madeCurrent && state.api.makeCurrent != NULL) {
        state.unbindAttempted = 1;
        state.unbindOk = state.api.makeCurrent(state.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) != 0U;
        state.cleanupError = state.api.getError();
        if (!state.unbindOk || state.cleanupError != EGL_SUCCESS) ++state.cleanupFailures;
    }
    if (state.display != EGL_NO_DISPLAY && state.context != EGL_NO_CONTEXT) {
        void *destroySymbol = state.api.getProcAddress == NULL ? NULL : state.api.getProcAddress("eglDestroyContext");
        if (destroySymbol != NULL && owner_is_loader(destroySymbol, state.loaderCanonical, NULL, 0U))
            (void)context_copy_symbol(&destroyContext, sizeof(destroyContext), destroySymbol);
        state.destroyAttempted = 1;
        state.destroyOk = destroyContext != NULL && destroyContext(state.display, state.context) != 0U;
        state.cleanupError = state.api.getError();
        if (!state.destroyOk || state.cleanupError != EGL_SUCCESS) ++state.cleanupFailures;
    }
    state.identityCleanupValid = state.identityLoaded && context_server_live(&state.server, state.paths.cwd);
    if (!state.identityCleanupValid) ++state.cleanupFailures;
    if (state.display != EGL_NO_DISPLAY && state.api.terminate != NULL) {
        state.terminateAttempted = 1;
        state.terminateOk = state.api.terminate(state.display) != 0U;
        state.cleanupError = state.api.getError();
        if (!state.terminateOk || state.cleanupError != EGL_SUCCESS) ++state.cleanupFailures;
    }
    if (state.handle != NULL) {
        state.handleClosed = dlclose(state.handle) == 0;
        if (!state.handleClosed) ++state.cleanupFailures;
    }
    (void)snprintf(json, sizeof(json), ",\"status\":\"%s\",\"unbindAttempted\":%s,\"unbindOk\":%s,\"destroyAttempted\":%s,\"destroyOk\":%s,\"terminateAttempted\":%s,\"terminateOk\":%s,\"handleClosed\":%s,\"cleanupFailures\":%d,\"serverIdentityRevalidated\":%s,\"cleanupStatus\":\"%s\"",
                   state.cleanupFailures == 0 ? "CLEAN" : "UNKNOWN", state.unbindAttempted ? "true" : "false", state.unbindOk ? "true" : "false",
                   state.destroyAttempted ? "true" : "false", state.destroyOk ? "true" : "false", state.terminateAttempted ? "true" : "false",
                   state.terminateOk ? "true" : "false", state.handleClosed ? "true" : "false", state.cleanupFailures,
                   state.identityCleanupValid ? "true" : "false", state.cleanupFailures == 0 ? "CLEAN" : "UNKNOWN");
    if (!context_emit(runId, identity, "cleanup", json)) state.outputFailure = 1;

    endPass = state.scopeValid && state.environmentValid && state.selectionValid && state.identityValid && state.identityCleanupValid &&
              state.libraryLoaded && state.loaderCanonical[0] != '\0' && state.exportsValid && state.displayInitialized &&
              state.displayExtensionsValid && state.boundApi && state.configChosen && state.contextCreated && state.madeCurrent &&
              state.glValid && state.rendererVirgl && !state.softwareFallback && state.mapsComplete && state.privateLoaderMapped &&
              state.privateVendorMapped && state.privateGalliumMapped && state.privateLlvmMapped && !state.androidEglMapped &&
              !state.androidGlesMapped && state.cleanupFailures == 0 && !state.contextFailure && !state.outputFailure;
    (void)snprintf(json, sizeof(json), ",\"status\":\"%s\",\"privateLoaderVerified\":%s,\"exportsVerified\":%s,\"displayVerified\":%s,\"eglContextVerified\":%s,\"virpipeVerified\":%s,\"glRendererVerified\":%s,\"identityVerified\":%s,\"mappingsVerified\":%s,\"cleanupVerified\":%s,\"hardwareRenderingVerified\":false,\"triangleVerified\":false,\"presentationVerified\":false,\"d3dVerified\":false,\"wowVerified\":false,\"nativeExit\":%d",
                   endPass ? "GUEST_CONTEXT_PASS" : (state.contextFailure ? "CONTEXT_FAILED" : "UNKNOWN"),
                   state.libraryLoaded && state.loaderCanonical[0] != '\0' ? "true" : "false", state.exportsValid ? "true" : "false",
                   state.displayInitialized && state.displayExtensionsValid ? "true" : "false", state.contextCreated && state.madeCurrent ? "true" : "false",
                   state.glValid ? "true" : "false", state.glValid && state.rendererVirgl && !state.softwareFallback ? "true" : "false",
                   state.identityValid && state.identityCleanupValid ? "true" : "false", state.mapsComplete && state.privateLoaderMapped && state.privateVendorMapped && state.privateGalliumMapped && state.privateLlvmMapped && !state.androidEglMapped && !state.androidGlesMapped ? "true" : "false",
                   state.cleanupFailures == 0 ? "true" : "false", endPass ? 0 : 1);
    endEmitted = context_emit(runId, identity, "end", json);
    return endPass && endEmitted && !state.outputFailure;
}

int main(int argc, char **argv) {
    Identity identity;
    if (argc != 3 || argv == NULL || argv[1] == NULL || argv[2] == NULL ||
        strcmp(argv[1], "--run-id") != 0 || !context_valid_run_id(argv[2])) return 2;
    memset(&identity, 0, sizeof(identity));
    identity.pid = getpid();
    identity.uid = getuid();
    identity.pgid = getpgid(0);
    identity.sid = getsid(0);
    return context_run(argv[2], &identity) ? 0 : 1;
}
