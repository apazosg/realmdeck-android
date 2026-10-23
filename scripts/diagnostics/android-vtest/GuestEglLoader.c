#define _GNU_SOURCE

#include <dlfcn.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/types.h>

#define SCHEMA "wowforever.android-guest-egl-loader/version1"
#define MAX_OUTPUT_BYTES 65536U
#define MAX_LINE 24576U
#define MAX_STRING 512U
#define MAX_PATH_STRING 4096U
#define MAX_RUN_ID 38U
#define MAX_EXTENSION_SCAN 16384U
#define MAX_EXTENSION_PREVIEW 512U
#define MAX_MAP_BYTES (2U * 1024U * 1024U)
#define MAX_MAP_LINE 4096U
#define EXPECTED_UID 10330U
#define PRIVATE_LOADER_RELATIVE "guest/lib/libEGL.so.1.1.0"
#define PRIVATE_VENDOR_RELATIVE "guest/lib/libEGL_mesa.so"
#define PRIVATE_VENDOR_JSON_RELATIVE "guest/share/glvnd/egl_vendor.d/50_mesa.json"
#define PRIVATE_GALLIUM_RELATIVE "guest/lib/libgallium-26.2.4.so"
#define PRIVATE_LLVM_RELATIVE "guest/lib/libLLVM.so"

typedef unsigned int EGLBoolean;
typedef unsigned int EGLenum;
typedef int EGLint;
typedef void *EGLDisplay;
typedef void *EGLConfig;
typedef void *EGLContext;
typedef void *EGLSurface;
#define EGL_NO_DISPLAY ((EGLDisplay)0)
#define EGL_EXTENSIONS ((EGLint)0x3055)

typedef EGLBoolean (*EglBindApiFn)(EGLenum);
typedef EGLBoolean (*EglChooseConfigFn)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
typedef EGLContext (*EglCreateContextFn)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
typedef EGLint (*EglGetErrorFn)(void);
typedef EGLDisplay (*EglGetPlatformDisplayFn)(EGLenum, void *, const intptr_t *);
typedef void *(*EglGetProcAddressFn)(const char *);
typedef EGLDisplay (*EglGetDisplayFn)(void *);
typedef EGLBoolean (*EglInitializeFn)(EGLDisplay, EGLint *, EGLint *);
typedef EGLBoolean (*EglMakeCurrentFn)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
typedef const char *(*EglQueryStringFn)(EGLDisplay, EGLint);
typedef EGLBoolean (*EglTerminateFn)(EGLDisplay);

typedef struct Api {
    EglBindApiFn bindApi;
    EglChooseConfigFn chooseConfig;
    EglCreateContextFn createContext;
    EglGetErrorFn getError;
    EglGetPlatformDisplayFn getPlatformDisplay;
    EglGetProcAddressFn getProcAddress;
    EglInitializeFn initialize;
    EglMakeCurrentFn makeCurrent;
    EglQueryStringFn queryString;
    EglTerminateFn terminate;
} Api;

typedef struct Identity {
    pid_t pid;
    uid_t uid;
    pid_t pgid;
    pid_t sid;
} Identity;

typedef struct Paths {
    char cwd[MAX_PATH_STRING];
    char loader[MAX_PATH_STRING];
    char vendor[MAX_PATH_STRING];
    char vendorJson[MAX_PATH_STRING];
    char gallium[MAX_PATH_STRING];
    char llvm[MAX_PATH_STRING];
    char ldLibraryPath[MAX_PATH_STRING];
} Paths;

typedef struct State {
    Paths paths;
    char loaderCanonical[MAX_PATH_STRING];
    char vendorCanonical[MAX_PATH_STRING];
    char error[MAX_STRING];
    char missingSymbol[MAX_STRING];
    char wrongOwnerSymbol[MAX_STRING];
    char ownerPath[MAX_PATH_STRING];
    char extensionPreview[MAX_EXTENSION_PREVIEW + 1U];
    char mapError[MAX_STRING];
    Api api;
    void *handle;
    EGLint clientEglError;
    uint32_t extensionScanBytes;
    uint32_t mapBytes;
    uint32_t mapLines;
    int scopeValid;
    int environmentValid;
    int selectionValid;
    int libraryLoaded;
    int loaderOwnerValid;
    int exportsValid;
    int queryValid;
    int clientErrorKnown;
    int extensionScanValid;
    int extensionTruncated;
    int hasPlatformBase;
    int hasCreateContext;
    int hasSurfaceless;
    int mapsRead;
    int mapsComplete;
    int mapsTruncated;
    int privateLoaderMapped;
    int privateVendorMapped;
    int privateGalliumMapped;
    int privateLlvmMapped;
    int androidEglMapped;
    int androidGlesMapped;
    int cleanupAttempted;
    int handleClosed;
    int cleanupFailures;
    int outputFailed;
    int clockFailed;
    int concreteLoaderFailure;
} State;

static size_t outputBytes;

/* HOST-TEST-BEGIN: pure bounded helpers; this block does not depend on EGL. */
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
    size_t i;
    const size_t length = bounded_length(value, MAX_RUN_ID + 1U, NULL);
    if (value == NULL || length != MAX_RUN_ID || memcmp(value, "VTEST-", 6U) != 0) return 0;
    for (i = 6U; i < length; ++i) {
        const unsigned char c = (unsigned char)value[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return 0;
    }
    return 1;
}

static void json_string(char *out, size_t capacity, const char *value, size_t limit, int *truncated) {
    size_t at = 0U;
    size_t i;
    int terminated = 0;
    const size_t inputLength = bounded_length(value, limit, &terminated);
    if (truncated != NULL) *truncated = 0;
    if (capacity < 3U || value == NULL) {
        if (capacity > 0U) out[0] = '\0';
        if (truncated != NULL) *truncated = 1;
        return;
    }
    out[at++] = '"';
    for (i = 0U; i < inputLength; ++i) {
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
    if (!terminated && truncated != NULL) *truncated = 1;
    if (at + 2U <= capacity) {
        out[at++] = '"';
        out[at] = '\0';
    } else {
        out[capacity - 1U] = '\0';
        if (truncated != NULL) *truncated = 1;
    }
}

static void json_optional_string(char *out, size_t capacity, const char *value, size_t limit, int *truncated) {
    if (value == NULL) {
        (void)snprintf(out, capacity, "null");
        if (truncated != NULL) *truncated = 0;
    } else {
        json_string(out, capacity, value, limit, truncated);
    }
}

static void json_optional_bool(char *out, size_t capacity, int known, int value) {
    (void)snprintf(out, capacity, "%s", known ? (value ? "true" : "false") : "null");
}

static int copy_bounded(char *out, size_t capacity, const char *value, size_t limit) {
    size_t length;
    int terminated = 0;
    if (out == NULL || capacity == 0U || value == NULL) return 0;
    length = bounded_length(value, limit, &terminated);
    if (!terminated || length + 1U > capacity) return 0;
    memcpy(out, value, length);
    out[length] = '\0';
    return 1;
}

static int scan_extension_string(const char *value, int *platformBase, int *createContext,
                                 int *surfaceless, size_t *scanBytes) {
    size_t length;
    size_t i;
    int terminated = 0;
    if (platformBase != NULL) *platformBase = 0;
    if (createContext != NULL) *createContext = 0;
    if (surfaceless != NULL) *surfaceless = 0;
    if (scanBytes != NULL) *scanBytes = 0U;
    if (value == NULL) return 0;
    length = bounded_length(value, MAX_EXTENSION_SCAN, &terminated);
    if (scanBytes != NULL) *scanBytes = length;
    if (!terminated) return 0;
    i = 0U;
    while (i < length) {
        size_t start;
        while (i < length && value[i] == ' ') ++i;
        start = i;
        while (i < length && value[i] != ' ') ++i;
        if (i - start == strlen("EGL_EXT_platform_base") &&
            memcmp(value + start, "EGL_EXT_platform_base", i - start) == 0 && platformBase != NULL) *platformBase = 1;
        if (i - start == strlen("EGL_KHR_create_context") &&
            memcmp(value + start, "EGL_KHR_create_context", i - start) == 0 && createContext != NULL) *createContext = 1;
        if (i - start == strlen("EGL_KHR_surfaceless_context") &&
            memcmp(value + start, "EGL_KHR_surfaceless_context", i - start) == 0 && surfaceless != NULL) *surfaceless = 1;
    }
    return 1;
}

static int map_line_has_exact_path(const char *line, const char *path) {
    const size_t pathLength = bounded_length(path, MAX_PATH_STRING, NULL);
    int lineTerminated = 0;
    const size_t lineLength = bounded_length(line, MAX_MAP_LINE + 1U, &lineTerminated);
    size_t i;
    if (line == NULL || path == NULL || pathLength == 0U) return 0;
    for (i = 0U; i < lineLength; ++i) {
        const int beforeOk = i == 0U || line[i - 1U] == ' ' || line[i - 1U] == '\t';
        if (beforeOk && i + pathLength <= lineLength && memcmp(line + i, path, pathLength) == 0) {
            if (i + pathLength == lineLength) return lineTerminated;
            const char after = line[i + pathLength];
            if (after == '\0' || after == '\n' || after == ' ' || after == '\t') return 1;
        }
    }
    return 0;
}

static int map_line_has_android_graphics(const char *line, int gles) {
    static const char *const roots[] = {"/system/", "/apex/", "/vendor/", "/odm/", "/product/", "/system_ext/"};
    const char *path = line == NULL ? NULL : strchr(line, '/');
    const char *base;
    size_t i;
    int allowed = 0;
    if (path == NULL) return 0;
    for (i = 0; i < sizeof(roots) / sizeof(roots[0]); ++i)
        if (strncmp(path, roots[i], strlen(roots[i])) == 0) allowed = 1;
    if (!allowed || (base = strrchr(path, '/')) == NULL) return 0;
    ++base;
    return strncmp(base, gles ? "libGLES" : "libEGL", gles ? 7U : 6U) == 0;
}
/* HOST-TEST-END */

static int output_failed;

static int emit_event(const char *runId, const Identity *identity, const char *event, const char *fields) {
    char line[MAX_LINE];
    struct timespec now;
    int length;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        output_failed = 1;
        return 0;
    }
    length = snprintf(line, sizeof(line),
        "{\"schema\":\"%s\",\"runId\":\"%s\",\"event\":\"%s\",\"timeNs\":%llu,"
        "\"pid\":%ld,\"uid\":%lu,\"pgid\":%ld,\"sid\":%ld%s}\n",
        SCHEMA, runId, event, (unsigned long long)((uint64_t)now.tv_sec * UINT64_C(1000000000) + (uint64_t)now.tv_nsec),
        (long)identity->pid, (unsigned long)identity->uid, (long)identity->pgid, (long)identity->sid,
        fields != NULL ? fields : "");
    if (length <= 0 || (size_t)length >= sizeof(line) || outputBytes + (size_t)length > MAX_OUTPUT_BYTES) {
        output_failed = 1;
        return 0;
    }
    if (fputs(line, stdout) == EOF || fflush(stdout) == EOF || ferror(stdout) != 0) {
        output_failed = 1;
        return 0;
    }
    outputBytes += (size_t)length;
    return 1;
}

static int path_append(char *out, size_t capacity, const char *cwd, const char *relative) {
    const int written = snprintf(out, capacity, "%s/%s", cwd, relative);
    return written > 0 && (size_t)written < capacity;
}

static int environment_name_allowed(const char *name) {
    static const char *const allowed[] = {
        "PATH", "ANDROID_ROOT", "ANDROID_DATA", "LD_LIBRARY_PATH", "TMPDIR",
        "__EGL_VENDOR_LIBRARY_FILENAMES", "GALLIUM_DRIVER", "VTEST_SOCKET_NAME"
    };
    size_t i;
    for (i = 0U; i < sizeof(allowed) / sizeof(allowed[0]); ++i) if (strcmp(name, allowed[i]) == 0) return 1;
    return 0;
}

static int exact_environment(const Paths *paths) {
    extern char **environ;
    static const char *const names[] = {
        "PATH", "ANDROID_ROOT", "ANDROID_DATA", "LD_LIBRARY_PATH", "TMPDIR",
        "__EGL_VENDOR_LIBRARY_FILENAMES", "GALLIUM_DRIVER", "VTEST_SOCKET_NAME"
    };
    const char *values[sizeof(names) / sizeof(names[0])];
    size_t i;
    size_t count = 0U;
    for (i = 0U; i < sizeof(names) / sizeof(names[0]); ++i) values[i] = NULL;
    if (environ == NULL) return 0;
    for (i = 0U; environ[i] != NULL; ++i) {
        const char *equals = strchr(environ[i], '=');
        size_t j;
        char name[96];
        const size_t nameLength = equals == NULL ? 0U : (size_t)(equals - environ[i]);
        if (equals == NULL || nameLength == 0U || nameLength >= sizeof(name)) return 0;
        memcpy(name, environ[i], nameLength);
        name[nameLength] = '\0';
        if (!environment_name_allowed(name)) return 0;
        for (j = 0U; j < sizeof(names) / sizeof(names[0]); ++j) {
            if (strcmp(name, names[j]) == 0) {
                if (values[j] != NULL) return 0;
                values[j] = equals + 1;
                ++count;
            }
        }
    }
    if (count != sizeof(names) / sizeof(names[0])) return 0;
    return strcmp(values[0], "/system/bin:/system/xbin") == 0 &&
           strcmp(values[1], "/system") == 0 && strcmp(values[2], "/data") == 0 &&
           strcmp(values[3], paths->ldLibraryPath) == 0 && strcmp(values[4], paths->cwd) == 0 &&
           strcmp(values[5], paths->vendorJson) == 0 && strcmp(values[6], "virpipe") == 0 &&
           strcmp(values[7], "vtest.sock") == 0;
}

static int validate_scope(const char *runId, const Identity *identity, Paths *paths) {
    char expectedA[MAX_PATH_STRING];
    char expectedB[MAX_PATH_STRING];
    if (getcwd(paths->cwd, sizeof(paths->cwd)) == NULL || !valid_run_id(runId)) return 0;
    (void)snprintf(expectedA, sizeof(expectedA),
                   "/data/user/0/com.wowforever.arm64probe/files/forever/controls/vtest-loader/%s", runId);
    (void)snprintf(expectedB, sizeof(expectedB),
                   "/data/data/com.wowforever.arm64probe/files/forever/controls/vtest-loader/%s", runId);
    if ((strcmp(paths->cwd, expectedA) != 0 && strcmp(paths->cwd, expectedB) != 0) ||
        identity->uid != (uid_t)EXPECTED_UID) return 0;
    if (!path_append(paths->loader, sizeof(paths->loader), paths->cwd, PRIVATE_LOADER_RELATIVE) ||
        !path_append(paths->vendor, sizeof(paths->vendor), paths->cwd, PRIVATE_VENDOR_RELATIVE) ||
        !path_append(paths->vendorJson, sizeof(paths->vendorJson), paths->cwd, PRIVATE_VENDOR_JSON_RELATIVE) ||
        !path_append(paths->gallium, sizeof(paths->gallium), paths->cwd, PRIVATE_GALLIUM_RELATIVE) ||
        !path_append(paths->llvm, sizeof(paths->llvm), paths->cwd, PRIVATE_LLVM_RELATIVE) ||
        !path_append(paths->ldLibraryPath, sizeof(paths->ldLibraryPath), paths->cwd, "guest/lib:/system/lib64")) return 0;
    return exact_environment(paths);
}

static int canonical_path(const char *path, char *out, size_t capacity) {
    char *resolved;
    resolved = realpath(path, NULL);
    if (resolved == NULL) return 0;
    if (!copy_bounded(out, capacity, resolved, MAX_PATH_STRING - 1U)) {
        free(resolved);
        return 0;
    }
    free(resolved);
    return 1;
}

static int owner_is_loader(void *address, const char *loaderCanonical, char *ownerPath, size_t ownerCapacity) {
    Dl_info info;
    char canonical[MAX_PATH_STRING];
    memset(&info, 0, sizeof(info));
    if (address == NULL || dladdr(address, &info) == 0 || info.dli_fname == NULL ||
        !canonical_path(info.dli_fname, canonical, sizeof(canonical))) return 0;
    if (ownerPath != NULL) (void)copy_bounded(ownerPath, ownerCapacity, canonical, MAX_PATH_STRING - 1U);
    return strcmp(canonical, loaderCanonical) == 0;
}

static int copy_symbol(void *target, size_t targetSize, void *symbol) {
    if (target == NULL || symbol == NULL || targetSize != sizeof(symbol)) return 0;
    memcpy(target, &symbol, sizeof(symbol));
    return 1;
}

static int resolve_one(void *handle, const char *name, void *target, size_t targetSize,
                       const char *loaderCanonical, char *ownerPath, size_t ownerCapacity) {
    void *symbol;
    const char *error;
    (void)dlerror();
    symbol = dlsym(handle, name);
    error = dlerror();
    if (symbol == NULL || error != NULL || !copy_symbol(target, targetSize, symbol)) return 0;
    return owner_is_loader(symbol, loaderCanonical, ownerPath, ownerCapacity);
}

static int resolve_exports(State *state) {
    int ok = 1;
    state->missingSymbol[0] = '\0';
    state->wrongOwnerSymbol[0] = '\0';
#define RESOLVE(field, symbol) do { \
        state->ownerPath[0] = '\0'; \
        if (!resolve_one(state->handle, symbol, &state->api.field, sizeof(state->api.field), \
                         state->loaderCanonical, state->ownerPath, sizeof(state->ownerPath))) { \
            if (state->missingSymbol[0] == '\0') (void)copy_bounded(state->missingSymbol, sizeof(state->missingSymbol), symbol, MAX_STRING - 1U); \
            if (state->ownerPath[0] != '\0' && state->wrongOwnerSymbol[0] == '\0') \
                (void)copy_bounded(state->wrongOwnerSymbol, sizeof(state->wrongOwnerSymbol), symbol, MAX_STRING - 1U); \
            ok = 0; \
        } \
    } while (0)
    RESOLVE(bindApi, "eglBindAPI");
    RESOLVE(chooseConfig, "eglChooseConfig");
    RESOLVE(createContext, "eglCreateContext");
    RESOLVE(getError, "eglGetError");
    RESOLVE(getPlatformDisplay, "eglGetPlatformDisplay");
    RESOLVE(getProcAddress, "eglGetProcAddress");
    RESOLVE(initialize, "eglInitialize");
    RESOLVE(makeCurrent, "eglMakeCurrent");
    RESOLVE(queryString, "eglQueryString");
    RESOLVE(terminate, "eglTerminate");
#undef RESOLVE
    state->exportsValid = ok;
    return ok;
}

static void map_mark(State *state, const char *line) {
    if (map_line_has_exact_path(line, state->loaderCanonical)) state->privateLoaderMapped = 1;
    if (map_line_has_exact_path(line, state->vendorCanonical)) state->privateVendorMapped = 1;
    if (map_line_has_exact_path(line, state->paths.gallium)) state->privateGalliumMapped = 1;
    if (map_line_has_exact_path(line, state->paths.llvm)) state->privateLlvmMapped = 1;
    if (map_line_has_android_graphics(line, 0)) state->androidEglMapped = 1;
    if (map_line_has_android_graphics(line, 1)) state->androidGlesMapped = 1;
}

static int read_maps(State *state) {
    FILE *file;
    char line[MAX_MAP_LINE + 2U];
    size_t total = 0U;
    int complete = 1;
    state->mapsRead = 0;
    state->mapsComplete = 0;
    state->mapsTruncated = 0;
    state->mapBytes = 0U;
    state->mapLines = 0U;
    file = fopen("/proc/self/maps", "rb");
    if (file == NULL) {
        (void)copy_bounded(state->mapError, sizeof(state->mapError), strerror(errno), MAX_STRING - 1U);
        return 0;
    }
    state->mapsRead = 1;
    while (total < MAX_MAP_BYTES) {
        size_t length;
        const size_t remaining = MAX_MAP_BYTES - total;
        if (remaining < sizeof(line)) {
            length = fread(line, 1U, remaining, file);
            total += length;
            complete = 0;
            state->mapsTruncated = 1;
            break;
        }
        if (fgets(line, sizeof(line), file) == NULL) break;
        length = strlen(line);
        total += length;
        ++state->mapLines;
        if (length == 0U || line[length - 1U] != '\n') {
            complete = 0;
            state->mapsTruncated = 1;
        } else if (length - 1U > MAX_MAP_LINE) {
            complete = 0;
            state->mapsTruncated = 1;
        } else {
            map_mark(state, line);
        }
    }
    if (total >= MAX_MAP_BYTES) {
        complete = 0;
        state->mapsTruncated = 1;
    }
    if (ferror(file) != 0) complete = 0;
    (void)fclose(file);
    state->mapBytes = total > UINT32_MAX ? UINT32_MAX : (uint32_t)total;
    state->mapsComplete = complete && !state->mapsTruncated;
    return state->mapsComplete;
}

static int scan_client_extensions(State *state) {
    const char *extensions;
    size_t scanBytes = 0U;
    extensions = state->api.queryString(EGL_NO_DISPLAY, EGL_EXTENSIONS);
    state->clientEglError = state->api.getError();
    state->clientErrorKnown = 1;
    state->queryValid = extensions != NULL;
    state->extensionScanValid = scan_extension_string(extensions, &state->hasPlatformBase,
                                                       &state->hasCreateContext, &state->hasSurfaceless, &scanBytes);
    state->extensionScanBytes = scanBytes > UINT32_MAX ? UINT32_MAX : (uint32_t)scanBytes;
    state->extensionTruncated = extensions != NULL && scanBytes > MAX_EXTENSION_PREVIEW;
    if (state->extensionScanValid && extensions != NULL) {
        size_t preview = scanBytes < MAX_EXTENSION_PREVIEW ? scanBytes : MAX_EXTENSION_PREVIEW;
        memcpy(state->extensionPreview, extensions, preview);
        state->extensionPreview[preview] = '\0';
    }
    return state->queryValid && state->extensionScanValid && state->clientEglError == 0x3000;
}

static int cleanup(State *state) {
    if (state->handle == NULL) return 0;
    state->cleanupAttempted = 1;
    if (dlclose(state->handle) != 0) {
        state->cleanupFailures = 1;
        return 0;
    }
    state->handle = NULL;
    state->handleClosed = 1;
    return 1;
}

static void optional_json(char *out, size_t capacity, const char *value, size_t limit) {
    json_optional_string(out, capacity, value == NULL || value[0] == '\0' ? NULL : value, limit, NULL);
}

static int run_loader(const char *runId, const Identity *identity) {
    State state;
    char fields[MAX_LINE];
    char cwdJson[MAX_PATH_STRING * 2U];
    char pathJson[MAX_PATH_STRING * 2U];
    char errorJson[MAX_STRING * 2U];
    char missingJson[MAX_STRING * 2U];
    char ownerJson[MAX_PATH_STRING * 2U];
    char previewJson[MAX_EXTENSION_PREVIEW * 6U];
    char boolJson[16];
    int pass;
    int endEmitted;
    memset(&state, 0, sizeof(state));
    state.clockFailed = 0;
    (void)emit_event(runId, identity, "begin", ",\"status\":\"STARTED\"");

    state.scopeValid = validate_scope(runId, identity, &state.paths);
    state.environmentValid = state.scopeValid;
    state.selectionValid = state.scopeValid;
    optional_json(cwdJson, sizeof(cwdJson), state.paths.cwd, MAX_PATH_STRING - 1U);
    optional_json(pathJson, sizeof(pathJson), state.paths.loader, MAX_PATH_STRING - 1U);
    (void)snprintf(fields, sizeof(fields),
                   ",\"status\":\"%s\",\"scopeValid\":%s,\"runIdValid\":%s,\"uidValid\":%s,"
                   "\"cwdValid\":%s,\"environmentValid\":%s,\"selectionValid\":%s,\"cwd\":%s,\"libraryPath\":%s",
                   state.scopeValid ? "VALID" : "UNKNOWN", state.scopeValid ? "true" : "false",
                   valid_run_id(runId) ? "true" : "false", identity->uid == (uid_t)EXPECTED_UID ? "true" : "false",
                   state.scopeValid ? "true" : "false", state.environmentValid ? "true" : "false",
                   state.selectionValid ? "true" : "false", cwdJson, pathJson);
    (void)emit_event(runId, identity, "scope", fields);

    if (state.scopeValid) {
        state.handle = dlopen(PRIVATE_LOADER_RELATIVE, RTLD_NOW | RTLD_LOCAL);
        state.libraryLoaded = state.handle != NULL;
        if (!state.libraryLoaded) {
            const char *error = dlerror();
            state.concreteLoaderFailure = 1;
            if (error != NULL) (void)copy_bounded(state.error, sizeof(state.error), error, MAX_STRING - 1U);
            else (void)copy_bounded(state.error, sizeof(state.error), "dlopen failed", sizeof(state.error) - 1U);
        } else if (!canonical_path(state.paths.loader, state.loaderCanonical, sizeof(state.loaderCanonical))) {
            state.concreteLoaderFailure = 1;
            (void)copy_bounded(state.error, sizeof(state.error), "private loader canonicalization failed", sizeof(state.error) - 1U);
        }
    }
    optional_json(errorJson, sizeof(errorJson), state.error, MAX_STRING - 1U);
    optional_json(pathJson, sizeof(pathJson), state.paths.loader, MAX_PATH_STRING - 1U);
    optional_json(ownerJson, sizeof(ownerJson), state.loaderCanonical, MAX_PATH_STRING - 1U);
    (void)snprintf(fields, sizeof(fields),
                   ",\"status\":\"%s\",\"loaded\":%s,\"privatePath\":%s,\"canonicalPath\":%s,\"loadError\":%s",
                   state.libraryLoaded && state.loaderCanonical[0] != '\0' ? "LOADED" :
                       (state.scopeValid ? "LOADER_FAILED" : "UNKNOWN"), state.libraryLoaded ? "true" : "false",
                   pathJson, ownerJson, errorJson);
    (void)emit_event(runId, identity, "library", fields);

    if (state.libraryLoaded && state.loaderCanonical[0] != '\0') {
        state.exportsValid = resolve_exports(&state);
        state.loaderOwnerValid = state.exportsValid;
        if (!state.exportsValid) state.concreteLoaderFailure = 1;
    }
    optional_json(missingJson, sizeof(missingJson), state.missingSymbol, MAX_STRING - 1U);
    optional_json(ownerJson, sizeof(ownerJson), state.ownerPath, MAX_PATH_STRING - 1U);
    optional_json(errorJson, sizeof(errorJson), state.wrongOwnerSymbol, MAX_STRING - 1U);
    (void)snprintf(fields, sizeof(fields),
                   ",\"status\":\"%s\",\"exportCount\":%u,\"expectedExportCount\":10,\"allExports\":%s,"
                   "\"ownerExact\":%s,\"ownerPath\":%s,\"missingSymbol\":%s,\"wrongOwnerSymbol\":%s",
                   state.exportsValid ? "EXPORTED" : (state.libraryLoaded ? "LOADER_FAILED" : "UNKNOWN"),
                   state.exportsValid ? 10U : 0U, state.exportsValid ? "true" : "false",
                    state.loaderOwnerValid ? "true" : "false", ownerJson, missingJson, errorJson);
    (void)emit_event(runId, identity, "exports", fields);

    if (state.exportsValid && !scan_client_extensions(&state)) state.concreteLoaderFailure = 1;
    if (state.extensionScanValid)
        json_string(previewJson, sizeof(previewJson), state.extensionPreview, MAX_EXTENSION_PREVIEW + 1U, NULL);
    else
        (void)snprintf(previewJson, sizeof(previewJson), "null");
    (void)snprintf(fields, sizeof(fields),
                   ",\"status\":\"%s\",\"queryValid\":%s,\"extensionScanValid\":%s,\"extensions\":%s,"
                   "\"extensionScanBytes\":%u,\"extensionsTruncated\":%s,\"hasEGL_EXT_platform_base\":%s,"
                   "\"hasEGL_KHR_create_context\":%s,\"hasEGL_KHR_surfaceless_context\":%s,\"eglError\":%s",
                   state.extensionScanValid && state.clientEglError == 0x3000 ? "MEASURED" :
                       (state.exportsValid ? "LOADER_FAILED" : "UNKNOWN"), state.queryValid ? "true" : "false",
                   state.extensionScanValid ? "true" : "false", previewJson, (unsigned)state.extensionScanBytes,
                   state.extensionTruncated ? "true" : "false", state.extensionScanValid ?
                   (state.hasPlatformBase ? "true" : "false") : "null", state.extensionScanValid ?
                   (state.hasCreateContext ? "true" : "false") : "null", state.extensionScanValid ?
                   (state.hasSurfaceless ? "true" : "false") : "null",
                   state.clientErrorKnown ? (snprintf(boolJson, sizeof(boolJson), "%d", (int)state.clientEglError), boolJson) : "null");
    (void)emit_event(runId, identity, "client_extensions", fields);

    if (state.scopeValid && state.libraryLoaded && state.exportsValid) {
        (void)canonical_path(state.paths.vendor, state.vendorCanonical, sizeof(state.vendorCanonical));
        (void)read_maps(&state);
    }
    json_optional_bool(boolJson, sizeof(boolJson), state.mapsRead, state.mapsRead);
    optional_json(errorJson, sizeof(errorJson), state.mapError, MAX_STRING - 1U);
    (void)snprintf(fields, sizeof(fields),
                   ",\"status\":\"%s\",\"mapsRead\":%s,\"mapsComplete\":%s,\"mapsTruncated\":%s,"
                   "\"mapsBytes\":%u,\"mapLines\":%u,\"privateLoaderMapped\":%s,\"privateVendorMapped\":%s,"
                   "\"privateGalliumMapped\":%s,\"privateLlvmMapped\":%s,\"androidEglMapped\":%s,\"androidGlesMapped\":%s,\"mappingError\":%s",
                   state.mapsComplete && state.privateLoaderMapped && state.privateVendorMapped &&
                   state.privateGalliumMapped && state.privateLlvmMapped && !state.androidEglMapped && !state.androidGlesMapped ? "MAPPED" :
                        (state.mapsRead ? "UNKNOWN" : "UNKNOWN"), boolJson,
                   state.mapsComplete ? "true" : "false", state.mapsTruncated ? "true" : "false",
                   (unsigned)state.mapBytes, (unsigned)state.mapLines,
                   state.privateLoaderMapped ? "true" : "null", state.privateVendorMapped ? "true" : "null",
                   state.privateGalliumMapped ? "true" : "null", state.privateLlvmMapped ? "true" : "null",
                     state.androidEglMapped ? "true" : (state.mapsComplete ? "false" : "null"),
                     state.androidGlesMapped ? "true" : (state.mapsComplete ? "false" : "null"), errorJson);
    (void)emit_event(runId, identity, "mappings", fields);

    if (!cleanup(&state) && state.handle != NULL) state.concreteLoaderFailure = 1;
    (void)snprintf(fields, sizeof(fields), ",\"status\":\"%s\",\"cleanupFailures\":%d,\"handleClosed\":%s,\"cleanupStatus\":\"%s\"",
                   state.handleClosed && state.cleanupFailures == 0 ? "CLEAN" : "UNKNOWN", state.cleanupFailures,
                   state.handleClosed ? "true" : "false", state.handleClosed && state.cleanupFailures == 0 ? "CLEAN" : "UNKNOWN");
    (void)emit_event(runId, identity, "cleanup", fields);

    pass = state.scopeValid && state.libraryLoaded && state.loaderCanonical[0] != '\0' && state.exportsValid &&
           state.queryValid && state.extensionScanValid && state.clientErrorKnown && state.clientEglError == 0x3000 &&
           state.mapsComplete && state.privateLoaderMapped && state.privateVendorMapped && state.privateGalliumMapped &&
           state.privateLlvmMapped && !state.androidEglMapped && !state.androidGlesMapped && state.handleClosed &&
           state.cleanupFailures == 0 && !output_failed;
    (void)snprintf(fields, sizeof(fields),
                   ",\"status\":\"%s\",\"privateLoaderVerified\":%s,\"exportsVerified\":%s,"
                   "\"clientExtensionsVerified\":%s,\"mappingsVerified\":%s,\"cleanupVerified\":%s,"
                   "\"eglContextVerified\":false,\"virpipeVerified\":false,\"hardwareRenderingVerified\":false,"
                   "\"triangleVerified\":false,\"presentationVerified\":false,\"d3dVerified\":false,\"wowVerified\":false,\"nativeExit\":%d",
                   pass ? "GUEST_LOADER_PASS" : (state.concreteLoaderFailure ? "LOADER_FAILED" : "UNKNOWN"),
                   state.libraryLoaded && state.loaderCanonical[0] != '\0' ? "true" : "false",
                   state.exportsValid ? "true" : "false", state.queryValid && state.extensionScanValid && state.clientEglError == 0x3000 ? "true" : "false",
                   state.mapsComplete && state.privateLoaderMapped && state.privateVendorMapped && state.privateGalliumMapped && state.privateLlvmMapped &&
                   !state.androidEglMapped && !state.androidGlesMapped ? "true" : "false", state.handleClosed && state.cleanupFailures == 0 ? "true" : "false",
                   pass ? 0 : 1);
    endEmitted = emit_event(runId, identity, "end", fields);
    return pass && endEmitted && !output_failed;
}

int main(int argc, char **argv) {
    Identity identity;
    const char *runId;
    if (argc != 3 || argv == NULL || argv[1] == NULL || argv[2] == NULL ||
        strcmp(argv[1], "--run-id") != 0 || !valid_run_id(argv[2])) return 2;
    runId = argv[2];
    memset(&identity, 0, sizeof(identity));
    identity.pid = getpid();
    identity.uid = getuid();
    identity.pgid = getpgid(0);
    identity.sid = getsid(0);
    return run_loader(runId, &identity) ? 0 : 1;
}
