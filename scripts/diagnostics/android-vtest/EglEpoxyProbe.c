#define _GNU_SOURCE

#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include <dlfcn.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/types.h>

#define SCHEMA "wowforever.android-egl-epoxy/version1"
#define LIBRARY_NAME "libepoxy.so"
#define MAX_OUTPUT_BYTES 65536U
#define MAX_LINE 24576U
#define MAX_RUN_ID 38U
#define MAX_STRING 512U
#define MAX_EXTENSION_SCAN 16384U
#define MAX_PATH_STRING 4096U
#define EXPECTED_UID 10330U

typedef EGLDisplay (*EglGetDisplayFn)(EGLNativeDisplayType);
typedef EGLint (*EglGetErrorFn)(void);
typedef const char *(*EglQueryStringFn)(EGLDisplay, EGLint);
typedef EGLBoolean (*EglInitializeFn)(EGLDisplay, EGLint *, EGLint *);
typedef EGLBoolean (*EglBindApiFn)(EGLenum);
typedef EGLBoolean (*EglChooseConfigFn)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
typedef EGLContext (*EglCreateContextFn)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
typedef EGLBoolean (*EglMakeCurrentFn)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
typedef EGLBoolean (*EglDestroyContextFn)(EGLDisplay, EGLContext);
typedef EGLBoolean (*EglTerminateFn)(EGLDisplay);
typedef const GLubyte *(*GlGetStringFn)(GLenum);
typedef GLenum (*GlGetErrorFn)(void);

typedef struct Api {
    EglGetDisplayFn eglGetDisplay;
    EglGetErrorFn eglGetError;
    EglQueryStringFn eglQueryString;
    EglInitializeFn eglInitialize;
    EglBindApiFn eglBindAPI;
    EglChooseConfigFn eglChooseConfig;
    EglCreateContextFn eglCreateContext;
    EglMakeCurrentFn eglMakeCurrent;
    EglDestroyContextFn eglDestroyContext;
    EglTerminateFn eglTerminate;
    GlGetStringFn glGetString;
    GlGetErrorFn glGetError;
} Api;

typedef struct Identity {
    pid_t pid;
    uid_t uid;
    pid_t pgid;
    pid_t sid;
} Identity;

typedef struct Probe {
    void *library;
    Api api;
    EGLDisplay display;
    EGLConfig config;
    EGLContext context;
    EGLint eglError;
    GLenum glError;
    EGLint eglMajor;
    EGLint eglMinor;
    EGLint configCount;
    int scopeValid;
    int slotValid;
    int allSymbols;
    int displayValid;
    int initialized;
    int displayInfoValid;
    int apiBound;
    int configValid;
    int contextValid;
    int current;
    int capsValid;
    int eglErrorsClear;
    int eglErrorFailures;
    int glErrorsClear;
    int cleanupFailures;
    int clientExtensionsValid;
    int clientEglErrorKnown;
    EGLint clientEglError;
    int clientExtensionsTruncated;
    size_t clientExtensionScanBytes;
    int displayExtensionsValid;
    int displayExtensionsTruncated;
    size_t displayExtensionScanBytes;
    int clientHasPlatformBase;
    int clientHasCreateContext;
    int clientHasSurfaceless;
    int displayHasPlatformBase;
    int displayHasCreateContext;
    int displayHasSurfaceless;
    const char *missingSymbol;
    char lookupError[MAX_STRING];
    char librarySlotPath[MAX_PATH_STRING];
    char libraryActualPath[MAX_PATH_STRING];
    char implementationPath[MAX_PATH_STRING];
    int implementationPathKnown;
    int implementationResolver;
    char vendor[MAX_STRING];
    char version[MAX_STRING];
    char clientApis[MAX_STRING];
    char displayExtensions[MAX_EXTENSION_SCAN + 1U];
    char clientExtensions[MAX_STRING];
    char glVendor[MAX_STRING];
    char renderer[MAX_STRING];
    char glVersion[MAX_STRING];
} Probe;

static int outputFailed;
static size_t outputBytes;

/* HOST-TEST-BEGIN: this block is intentionally independent of EGL/GLES. */
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
    if (capacity < 3U) {
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

static uint64_t monotonic_ns(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0U;
    return (uint64_t)now.tv_sec * UINT64_C(1000000000) + (uint64_t)now.tv_nsec;
}

static int emit_event(const char *runId, const Identity *identity, const char *event, const char *fields) {
    char line[MAX_LINE];
    char runIdJson[128];
    int length;
    json_string(runIdJson, sizeof(runIdJson), runId, MAX_RUN_ID, NULL);
    length = snprintf(line, sizeof(line),
        "{\"schema\":\"%s\",\"runId\":%s,\"event\":\"%s\",\"timeNs\":%llu,"
        "\"pid\":%ld,\"uid\":%lu,\"pgid\":%ld,\"sid\":%ld%s}\n",
        SCHEMA, runIdJson, event, (unsigned long long)monotonic_ns(),
        (long)identity->pid, (unsigned long)identity->uid, (long)identity->pgid,
        (long)identity->sid, fields != NULL ? fields : "");
    if (length <= 0 || (size_t)length >= sizeof(line) || outputBytes + (size_t)length > MAX_OUTPUT_BYTES) {
        outputFailed = 1;
        return 0;
    }
    if (fputs(line, stdout) == EOF || fflush(stdout) == EOF || ferror(stdout) != 0) {
        outputFailed = 1;
        return 0;
    }
    outputBytes += (size_t)length;
    return 1;
}

static int context_pass_eligible(int loadOk, int displayOk, int initOk, int apiBoundOk, int configOk,
                                 int currentOk, int capsOk, int eglOk, int glOk,
                                 int cleanupOk, int outputOk) {
    return loadOk && displayOk && initOk && apiBoundOk && configOk && currentOk && capsOk && eglOk && glOk &&
           cleanupOk && outputOk;
}

/* The client EGL_NO_DISPLAY query is optional and never gates this path. */
static int display_measurement_allowed(int libraryOk) {
    return libraryOk;
}

static int read_function_slot(void *symbolSlot, void *target, size_t targetSize) {
    if (symbolSlot == NULL || target == NULL || targetSize == 0U) return 0;
    memcpy(target, symbolSlot, targetSize);
    return 1;
}
/* HOST-TEST-END */

static int bounded_copy_plain(char *out, size_t capacity, const char *value, size_t limit);

static int all_zero(const void *value, size_t size) {
    const unsigned char *bytes = (const unsigned char *)value;
    size_t i;
    for (i = 0U; i < size; ++i) if (bytes[i] != 0U) return 0;
    return 1;
}

static int resolve_slot(void *handle, const char *name, void *target, size_t targetSize,
                        void **symbolSlot, char *error, size_t errorCapacity) {
    void *slot;
    const char *lookupError;
    if (symbolSlot != NULL) *symbolSlot = NULL;
    (void)dlerror();
    slot = dlsym(handle, name);
    lookupError = dlerror();
    if (slot == NULL || lookupError != NULL || !read_function_slot(slot, target, targetSize) ||
        all_zero(target, targetSize)) {
        if (error != NULL && errorCapacity > 0U) {
            if (lookupError != NULL) (void)bounded_copy_plain(error, errorCapacity, lookupError, MAX_STRING - 1U);
            else (void)snprintf(error, errorCapacity, "symbol lookup returned NULL");
        }
        return 0;
    }
    if (symbolSlot != NULL) *symbolSlot = slot;
    return 1;
}

static void remember_missing(Probe *probe, const char *name) {
    if (probe->missingSymbol == NULL) probe->missingSymbol = name;
}

static int resolve_all(Probe *probe, void *getDisplaySlot) {
    int ok = 1;
    if (getDisplaySlot == NULL || !read_function_slot(getDisplaySlot, &probe->api.eglGetDisplay,
                                                        sizeof(probe->api.eglGetDisplay)) ||
        all_zero(&probe->api.eglGetDisplay, sizeof(probe->api.eglGetDisplay))) {
        remember_missing(probe, "epoxy_eglGetDisplay");
        ok = 0;
    }
#define RESOLVE_REQUIRED(field, symbol) \
    do { \
        if (!resolve_slot(probe->library, symbol, &probe->api.field, sizeof(probe->api.field), \
                          NULL, probe->lookupError, sizeof(probe->lookupError))) { \
            remember_missing(probe, symbol); ok = 0; \
        } \
    } while (0)
    RESOLVE_REQUIRED(eglGetError, "epoxy_eglGetError");
    RESOLVE_REQUIRED(eglQueryString, "epoxy_eglQueryString");
    RESOLVE_REQUIRED(eglInitialize, "epoxy_eglInitialize");
    RESOLVE_REQUIRED(eglBindAPI, "epoxy_eglBindAPI");
    RESOLVE_REQUIRED(eglChooseConfig, "epoxy_eglChooseConfig");
    RESOLVE_REQUIRED(eglCreateContext, "epoxy_eglCreateContext");
    RESOLVE_REQUIRED(eglMakeCurrent, "epoxy_eglMakeCurrent");
    RESOLVE_REQUIRED(eglDestroyContext, "epoxy_eglDestroyContext");
    RESOLVE_REQUIRED(eglTerminate, "epoxy_eglTerminate");
    RESOLVE_REQUIRED(glGetString, "epoxy_glGetString");
    RESOLVE_REQUIRED(glGetError, "epoxy_glGetError");
#undef RESOLVE_REQUIRED
    probe->allSymbols = ok;
    return ok;
}

static int bounded_copy_plain(char *out, size_t capacity, const char *value, size_t limit) {
    size_t length;
    int terminated = 0;
    if (out == NULL || capacity == 0U || value == NULL) return 0;
    length = bounded_length(value, limit, &terminated);
    if (!terminated || length + 1U > capacity) return 0;
    memcpy(out, value, length);
    out[length] = '\0';
    return 1;
}

static int exact_token(const char *extensions, size_t length, const char *wanted) {
    size_t i = 0U;
    const size_t wantedLength = strlen(wanted);
    while (i < length) {
        size_t start;
        while (i < length && extensions[i] == ' ') ++i;
        start = i;
        while (i < length && extensions[i] != ' ') ++i;
        if (i - start == wantedLength && memcmp(extensions + start, wanted, wantedLength) == 0) return 1;
    }
    return 0;
}

static int scan_extensions(const char *extensions, int *platformBase, int *createContext, int *surfaceless,
                           size_t *scanBytes) {
    size_t length;
    int terminated = 0;
    if (platformBase != NULL) *platformBase = 0;
    if (createContext != NULL) *createContext = 0;
    if (surfaceless != NULL) *surfaceless = 0;
    if (scanBytes != NULL) *scanBytes = 0U;
    if (extensions == NULL) return 0;
    length = bounded_length(extensions, MAX_EXTENSION_SCAN, &terminated);
    if (scanBytes != NULL) *scanBytes = length;
    if (!terminated) return 0;
    if (platformBase != NULL) *platformBase = exact_token(extensions, length, "EGL_EXT_platform_base");
    if (createContext != NULL) *createContext = exact_token(extensions, length, "EGL_KHR_create_context");
    if (surfaceless != NULL) *surfaceless = exact_token(extensions, length, "EGL_KHR_surfaceless_context");
    return 1;
}

static int validate_scope(const char *runId, const Identity *identity, char *cwd, size_t cwdCapacity,
                          char *expectedLibrary, size_t expectedLibraryCapacity) {
    char expectedCwdA[MAX_PATH_STRING];
    char expectedCwdB[MAX_PATH_STRING];
    char expectedLdPath[MAX_PATH_STRING];
    const char *ldPath;
    int cwdOk;
    size_t ldLength;
    int ldTerminated;
    if (getcwd(cwd, cwdCapacity) == NULL) return 0;
    (void)snprintf(expectedCwdA, sizeof(expectedCwdA),
                   "/data/user/0/com.wowforever.arm64probe/files/forever/controls/vtest-loader/%s", runId);
    (void)snprintf(expectedCwdB, sizeof(expectedCwdB),
                   "/data/data/com.wowforever.arm64probe/files/forever/controls/vtest-loader/%s", runId);
    cwdOk = strcmp(cwd, expectedCwdA) == 0 || strcmp(cwd, expectedCwdB) == 0;
    if (!cwdOk || identity->uid != (uid_t)EXPECTED_UID) return 0;
    if (snprintf(expectedLibrary, expectedLibraryCapacity, "%s/lib/%s", cwd, LIBRARY_NAME) <= 0) return 0;
    (void)snprintf(expectedLdPath, sizeof(expectedLdPath), "%s/lib:/system/lib64", cwd);
    ldPath = getenv("LD_LIBRARY_PATH");
    if (ldPath == NULL) return 0;
    ldLength = bounded_length(ldPath, sizeof(expectedLdPath), &ldTerminated);
    return ldTerminated && ldLength == strlen(expectedLdPath) && strcmp(ldPath, expectedLdPath) == 0;
}

static int copy_dladdr_path(void *address, char *out, size_t capacity) {
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (address == NULL || dladdr(address, &info) == 0 || info.dli_fname == NULL) return 0;
    return bounded_copy_plain(out, capacity, info.dli_fname, MAX_PATH_STRING - 1U);
}

static int copy_function_dladdr(void *functionPointer, char *out, size_t capacity) {
    return copy_dladdr_path(functionPointer, out, capacity);
}

static int prove_library_slot(void *symbolSlot, const char *expectedPath, Probe *probe) {
    Dl_info info;
    char actualCanonical[MAX_PATH_STRING];
    char expectedCanonical[MAX_PATH_STRING];
    memset(&info, 0, sizeof(info));
    if (symbolSlot == NULL || expectedPath == NULL || dladdr(symbolSlot, &info) == 0 || info.dli_fname == NULL) return 0;
    if (!bounded_copy_plain(probe->librarySlotPath, sizeof(probe->librarySlotPath), info.dli_fname,
                            MAX_PATH_STRING - 1U)) return 0;
    if (realpath(expectedPath, expectedCanonical) == NULL ||
        realpath(info.dli_fname, actualCanonical) == NULL) return 0;
    if (!bounded_copy_plain(probe->libraryActualPath, sizeof(probe->libraryActualPath), actualCanonical,
                            MAX_PATH_STRING - 1U)) return 0;
    return strcmp(expectedCanonical, actualCanonical) == 0;
}

static int record_function_path(EglGetDisplayFn functionPointer, char *out, size_t capacity) {
    void *address = NULL;
    if (functionPointer == NULL || sizeof(functionPointer) != sizeof(address)) return 0;
    memcpy(&address, &functionPointer, sizeof(address));
    return copy_function_dladdr(address, out, capacity);
}

static int copy_extension_preview(char *out, size_t capacity, const char *value, size_t scanBytes, int *truncated) {
    size_t copied;
    if (out == NULL || capacity == 0U || value == NULL) return 0;
    copied = scanBytes < capacity - 1U ? scanBytes : capacity - 1U;
    memcpy(out, value, copied);
    out[copied] = '\0';
    if (truncated != NULL) *truncated = scanBytes >= capacity - 1U;
    return 1;
}

static EGLint take_egl_error(Probe *probe) {
    if (probe->api.eglGetError == NULL) return EGL_BAD_ACCESS;
    probe->eglError = probe->api.eglGetError();
    if (probe->eglError != EGL_SUCCESS) {
        probe->eglErrorsClear = 0;
        ++probe->eglErrorFailures;
    }
    return probe->eglError;
}

static void take_gl_error(Probe *probe) {
    if (probe->api.glGetError == NULL) {
        probe->glError = GL_INVALID_OPERATION;
        probe->glErrorsClear = 0;
        return;
    }
    probe->glError = probe->api.glGetError();
    if (probe->glError != GL_NO_ERROR) probe->glErrorsClear = 0;
}

static int run_probe(const char *runId, const Identity *identity) {
    Probe probe;
    char cwd[MAX_PATH_STRING];
    char expectedLibrary[MAX_PATH_STRING];
    char fields[MAX_LINE];
    char encoded[MAX_LINE];
    void *getDisplaySlot = NULL;
    const char *clientExtensions = NULL;
    const char *displayExtensions = NULL;
    const GLubyte *glString;
    EglGetDisplayFn resolvedGetDisplayFn = NULL;
    int clientScanValid = 0;
    int pass;
    int endEmitted;
    const EGLint configAttributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};

    memset(&probe, 0, sizeof(probe));
    probe.display = EGL_NO_DISPLAY;
    probe.context = EGL_NO_CONTEXT;
    probe.eglErrorsClear = 1;
    probe.glErrorsClear = 1;
    (void)emit_event(runId, identity, "begin", ",\"status\":\"STARTED\"");

    probe.scopeValid = validate_scope(runId, identity, cwd, sizeof(cwd),
                                      expectedLibrary, sizeof(expectedLibrary));
    if (probe.scopeValid) {
        probe.library = dlopen(LIBRARY_NAME, RTLD_NOW | RTLD_LOCAL);
        if (probe.library == NULL) {
            const char *loadError = dlerror();
            if (loadError != NULL) (void)bounded_copy_plain(probe.lookupError, sizeof(probe.lookupError),
                                                              loadError, MAX_STRING - 1U);
        }
    }
    if (probe.library != NULL) {
        if (!resolve_slot(probe.library, "epoxy_eglGetDisplay", &probe.api.eglGetDisplay,
                          sizeof(probe.api.eglGetDisplay), &getDisplaySlot, probe.lookupError,
                          sizeof(probe.lookupError))) {
            probe.missingSymbol = "epoxy_eglGetDisplay";
        }
        probe.slotValid = prove_library_slot(getDisplaySlot, expectedLibrary, &probe);
        (void)resolve_all(&probe, getDisplaySlot);
    }
    {
        char pathJson[18000];
        char actualJson[18000];
        char errorJson[18000];
        char missingJson[18000];
        json_optional_string(pathJson, sizeof(pathJson), probe.librarySlotPath[0] ? probe.librarySlotPath : NULL,
                            MAX_PATH_STRING, NULL);
        json_optional_string(actualJson, sizeof(actualJson), probe.libraryActualPath[0] ? probe.libraryActualPath : NULL,
                             MAX_PATH_STRING, NULL);
        json_optional_string(errorJson, sizeof(errorJson), probe.lookupError[0] ? probe.lookupError : NULL,
                             MAX_STRING, NULL);
        json_optional_string(missingJson, sizeof(missingJson), probe.missingSymbol, MAX_STRING, NULL);
        (void)snprintf(fields, sizeof(fields),
                       ",\"status\":\"%s\",\"scopeValid\":%s,\"slotAddressOwner\":%s,"
                       "\"slotPath\":%s,\"canonicalPath\":%s,\"missingSymbol\":%s,\"lookupError\":%s",
                       (probe.library != NULL && probe.scopeValid && probe.slotValid && probe.allSymbols) ? "LOADED" : "UNKNOWN",
                       probe.scopeValid ? "true" : "false", probe.slotValid ? "true" : "false", pathJson, actualJson,
                       missingJson, errorJson);
        (void)emit_event(runId, identity, "library", fields);
    }

    if (probe.library != NULL && probe.scopeValid && probe.slotValid && probe.allSymbols) {
        clientExtensions = probe.api.eglQueryString(EGL_NO_DISPLAY, EGL_EXTENSIONS);
        probe.clientEglError = probe.api.eglGetError();
        probe.clientEglErrorKnown = 1;
        clientScanValid = scan_extensions(clientExtensions, &probe.clientHasPlatformBase,
                                          &probe.clientHasCreateContext, &probe.clientHasSurfaceless,
                                          &probe.clientExtensionScanBytes);
        probe.clientExtensionsValid = clientScanValid && probe.clientEglError == EGL_SUCCESS;
        if (!probe.clientExtensionsValid) {
            probe.clientHasPlatformBase = 0;
            probe.clientHasCreateContext = 0;
            probe.clientHasSurfaceless = 0;
            probe.clientExtensionsTruncated = clientExtensions != NULL && !clientScanValid;
        }
        if (probe.clientExtensionsValid) {
            (void)copy_extension_preview(probe.clientExtensions, sizeof(probe.clientExtensions), clientExtensions,
                                          probe.clientExtensionScanBytes, &probe.clientExtensionsTruncated);
        }
    }
    {
        char extensionJson[MAX_STRING];
        char platformJson[32];
        char createJson[32];
        char surfaceJson[32];
        char clientErrorJson[32];
        int extensionOutputTruncated = 0;
        json_optional_string(extensionJson, sizeof(extensionJson), probe.clientExtensionsValid ? probe.clientExtensions : NULL,
                             MAX_STRING, &extensionOutputTruncated);
        json_optional_bool(platformJson, sizeof(platformJson), probe.clientExtensionsValid, probe.clientHasPlatformBase);
        json_optional_bool(createJson, sizeof(createJson), probe.clientExtensionsValid, probe.clientHasCreateContext);
        json_optional_bool(surfaceJson, sizeof(surfaceJson), probe.clientExtensionsValid, probe.clientHasSurfaceless);
        if (probe.clientEglErrorKnown) (void)snprintf(clientErrorJson, sizeof(clientErrorJson), "%d", (int)probe.clientEglError);
        else (void)snprintf(clientErrorJson, sizeof(clientErrorJson), "null");
        (void)snprintf(fields, sizeof(fields),
                       ",\"status\":\"%s\",\"extensions\":%s,\"extensionScanValid\":%s,"
                       "\"hasEGL_EXT_platform_base\":%s,\"hasEGL_KHR_create_context\":%s,"
                       "\"hasEGL_KHR_surfaceless_context\":%s,\"extensionScanBytes\":%u,"
                       "\"extensionsTruncated\":%s,\"eglError\":%s",
                       probe.clientExtensionsValid ? "MEASURED" : "UNKNOWN", extensionJson,
                       probe.clientExtensionsValid ? "true" : "false", platformJson, createJson, surfaceJson,
                       (unsigned)probe.clientExtensionScanBytes,
                       (probe.clientExtensionsTruncated || extensionOutputTruncated) ? "true" : "false",
                       clientErrorJson);
        (void)emit_event(runId, identity, "client_extensions", fields);
    }

    if (display_measurement_allowed(probe.library != NULL && probe.scopeValid && probe.slotValid && probe.allSymbols)) {
        probe.display = probe.api.eglGetDisplay(EGL_DEFAULT_DISPLAY);
        probe.displayValid = probe.display != EGL_NO_DISPLAY;
        (void)take_egl_error(&probe);
        if (read_function_slot(getDisplaySlot, &resolvedGetDisplayFn, sizeof(resolvedGetDisplayFn)) &&
            resolvedGetDisplayFn != NULL &&
            record_function_path(resolvedGetDisplayFn, probe.implementationPath, sizeof(probe.implementationPath))) {
            probe.implementationPathKnown = 1;
            probe.implementationResolver = strstr(probe.implementationPath, LIBRARY_NAME) != NULL;
        }
        if (probe.displayValid && probe.api.eglInitialize(probe.display, &probe.eglMajor, &probe.eglMinor) == EGL_TRUE) {
            probe.initialized = 1;
            (void)take_egl_error(&probe);
            probe.vendor[0] = '\0';
            probe.version[0] = '\0';
            probe.clientApis[0] = '\0';
            {
                const char *value = probe.api.eglQueryString(probe.display, EGL_VENDOR);
                if (value != NULL) (void)bounded_copy_plain(probe.vendor, sizeof(probe.vendor), value, MAX_STRING - 1U);
                (void)take_egl_error(&probe);
                value = probe.api.eglQueryString(probe.display, EGL_VERSION);
                if (value != NULL) (void)bounded_copy_plain(probe.version, sizeof(probe.version), value, MAX_STRING - 1U);
                (void)take_egl_error(&probe);
                value = probe.api.eglQueryString(probe.display, EGL_CLIENT_APIS);
                if (value != NULL) (void)bounded_copy_plain(probe.clientApis, sizeof(probe.clientApis), value, MAX_STRING - 1U);
                (void)take_egl_error(&probe);
                displayExtensions = probe.api.eglQueryString(probe.display, EGL_EXTENSIONS);
                probe.displayExtensionsValid = scan_extensions(displayExtensions, &probe.displayHasPlatformBase,
                                                                &probe.displayHasCreateContext, &probe.displayHasSurfaceless,
                                                                &probe.displayExtensionScanBytes);
                probe.displayExtensionsTruncated = displayExtensions != NULL && !probe.displayExtensionsValid;
                if (probe.displayExtensionsValid) {
                    (void)copy_extension_preview(probe.displayExtensions, sizeof(probe.displayExtensions), displayExtensions,
                                                  probe.displayExtensionScanBytes, &probe.displayExtensionsTruncated);
                }
                (void)take_egl_error(&probe);
            }
            if (probe.vendor[0] != '\0' && probe.version[0] != '\0' && probe.clientApis[0] != '\0' &&
                probe.displayExtensionsValid) {
                probe.displayInfoValid = 1;
            }
        } else if (probe.displayValid) {
            /* Preserve the exact EGL error when initialization itself fails. */
            (void)take_egl_error(&probe);
        }
    }
    {
        char vendorJson[18000];
        char versionJson[18000];
        char apisJson[18000];
        char extJson[18000];
        int displayOutputTruncated = 0;
        json_optional_string(vendorJson, sizeof(vendorJson), probe.vendor[0] ? probe.vendor : NULL, MAX_STRING, NULL);
        json_optional_string(versionJson, sizeof(versionJson), probe.version[0] ? probe.version : NULL, MAX_STRING, NULL);
        json_optional_string(apisJson, sizeof(apisJson), probe.clientApis[0] ? probe.clientApis : NULL, MAX_STRING, NULL);
        json_optional_string(extJson, sizeof(extJson), probe.displayExtensionsValid ? probe.displayExtensions : NULL,
                             MAX_EXTENSION_SCAN, &displayOutputTruncated);
        (void)snprintf(fields, sizeof(fields),
                       ",\"status\":\"%s\",\"displayValid\":%s,\"initialized\":%s,"
                       "\"major\":%d,\"minor\":%d,\"vendor\":%s,\"version\":%s,\"clientApis\":%s,"
                       "\"extensions\":%s,\"extensionScanValid\":%s,\"extensionScanBytes\":%u,"
                       "\"extensionsTruncated\":%s,\"mandatoryEglErrorCount\":%d,"
                       "\"hasEGL_EXT_platform_base\":%s,"
                       "\"hasEGL_KHR_create_context\":%s,\"hasEGL_KHR_surfaceless_context\":%s,\"eglError\":%d",
                       probe.displayInfoValid ? "MEASURED" : "UNKNOWN", probe.displayValid ? "true" : "false",
                       probe.initialized ? "true" : "false", (int)probe.eglMajor, (int)probe.eglMinor,
                       vendorJson, versionJson, apisJson, extJson, probe.displayExtensionsValid ? "true" : "false",
                       (unsigned)probe.displayExtensionScanBytes,
                       (probe.displayExtensionsTruncated || displayOutputTruncated) ? "true" : "false",
                       probe.eglErrorFailures,
                       probe.displayExtensionsValid ? (probe.displayHasPlatformBase ? "true" : "false") : "null",
                       probe.displayExtensionsValid ? (probe.displayHasCreateContext ? "true" : "false") : "null",
                       probe.displayExtensionsValid ? (probe.displayHasSurfaceless ? "true" : "false") : "null",
                       (int)probe.eglError);
        (void)emit_event(runId, identity, "egl", fields);
    }

    if (probe.initialized && probe.eglErrorsClear) {
        if (probe.api.eglBindAPI(EGL_OPENGL_ES_API) == EGL_TRUE) {
            (void)take_egl_error(&probe);
            probe.apiBound = probe.eglError == EGL_SUCCESS;
            if (probe.api.eglChooseConfig(probe.display, configAttributes, &probe.config, 1, &probe.configCount) == EGL_TRUE &&
                probe.configCount == 1) {
                probe.configValid = 1;
                (void)take_egl_error(&probe);
            } else {
                (void)take_egl_error(&probe);
            }
            if (probe.configValid) {
                probe.context = probe.api.eglCreateContext(probe.display, probe.config, EGL_NO_CONTEXT, contextAttributes);
                (void)take_egl_error(&probe);
                probe.contextValid = probe.context != EGL_NO_CONTEXT;
            }
            if (probe.contextValid) {
                if (probe.api.eglMakeCurrent(probe.display, EGL_NO_SURFACE, EGL_NO_SURFACE, probe.context) == EGL_TRUE) {
                    probe.current = 1;
                }
                (void)take_egl_error(&probe);
            }
        } else {
            (void)take_egl_error(&probe);
        }
    }
    {
        (void)snprintf(fields, sizeof(fields),
                       ",\"status\":\"%s\",\"apiBound\":%s,\"configChosen\":%s,"
                       "\"configCount\":%d,\"contextCreated\":%s,\"current\":%s,"
                       "\"surface\":\"EGL_NO_SURFACE\",\"eglError\":%d",
                       probe.current ? "CREATED" : "CONTEXT_FAILED", probe.apiBound ? "true" : "false",
                       probe.configValid ? "true" : "false", (int)probe.configCount,
                       probe.contextValid ? "true" : "false", probe.current ? "true" : "false", (int)probe.eglError);
        (void)emit_event(runId, identity, "context", fields);
    }

    if (probe.current) {
        glString = probe.api.glGetString(GL_VENDOR);
        if (glString != NULL) (void)bounded_copy_plain(probe.glVendor, sizeof(probe.glVendor), (const char *)glString, MAX_STRING - 1U);
        glString = probe.api.glGetString(GL_RENDERER);
        if (glString != NULL) (void)bounded_copy_plain(probe.renderer, sizeof(probe.renderer), (const char *)glString, MAX_STRING - 1U);
        glString = probe.api.glGetString(GL_VERSION);
        if (glString != NULL) (void)bounded_copy_plain(probe.glVersion, sizeof(probe.glVersion), (const char *)glString, MAX_STRING - 1U);
        take_gl_error(&probe);
        probe.capsValid = probe.glVendor[0] != '\0' && probe.renderer[0] != '\0' && probe.glVersion[0] != '\0';
    }
    {
        char vendorJson[18000];
        char rendererJson[18000];
        char versionJson[18000];
        char implementationJson[18000];
        json_optional_string(vendorJson, sizeof(vendorJson), probe.glVendor[0] ? probe.glVendor : NULL, MAX_STRING, NULL);
        json_optional_string(rendererJson, sizeof(rendererJson), probe.renderer[0] ? probe.renderer : NULL, MAX_STRING, NULL);
        json_optional_string(versionJson, sizeof(versionJson), probe.glVersion[0] ? probe.glVersion : NULL, MAX_STRING, NULL);
        json_optional_string(implementationJson, sizeof(implementationJson),
                             probe.implementationPath[0] ? probe.implementationPath : NULL, MAX_PATH_STRING, NULL);
        (void)snprintf(fields, sizeof(fields),
                       ",\"status\":\"%s\",\"glVendor\":%s,\"glRenderer\":%s,\"glVersion\":%s,"
                       "\"actualImplementationPath\":%s,\"actualImplementationPathKnown\":%s,"
                       "\"implementationPathKind\":\"%s\",\"glError\":%u",
                       probe.capsValid && probe.glErrorsClear ? "MEASURED" : "CONTEXT_FAILED", vendorJson,
                       rendererJson, versionJson, implementationJson, probe.implementationPathKnown ? "true" : "null",
                       probe.implementationResolver ? "EPOXY_RESOLVER" :
                           (probe.implementationPathKnown ? "IMPLEMENTATION" : "UNKNOWN"),
                       (unsigned)probe.glError);
        (void)emit_event(runId, identity, "caps", fields);
    }

    if (probe.initialized && probe.api.eglMakeCurrent != NULL &&
        probe.api.eglMakeCurrent(probe.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) != EGL_TRUE) {
        ++probe.cleanupFailures;
    }
    if (probe.initialized && probe.api.eglMakeCurrent != NULL && take_egl_error(&probe) != EGL_SUCCESS) {
        ++probe.cleanupFailures;
    }
    if (probe.contextValid && probe.api.eglDestroyContext(probe.display, probe.context) != EGL_TRUE) ++probe.cleanupFailures;
    if (probe.contextValid && take_egl_error(&probe) != EGL_SUCCESS) ++probe.cleanupFailures;
    if (probe.initialized && probe.api.eglTerminate(probe.display) != EGL_TRUE) ++probe.cleanupFailures;
    if (probe.initialized && take_egl_error(&probe) != EGL_SUCCESS) ++probe.cleanupFailures;
    if (probe.initialized) (void)take_egl_error(&probe);
    if (probe.library != NULL && dlclose(probe.library) != 0) ++probe.cleanupFailures;
    {
        (void)snprintf(fields, sizeof(fields), ",\"status\":\"%s\",\"cleanupFailures\":%d,\"cleanupStatus\":\"%s\"",
                       probe.cleanupFailures == 0 ? "CLEAN" : "UNKNOWN", probe.cleanupFailures,
                       probe.cleanupFailures == 0 ? "CLEAN" : "FAILED");
        (void)emit_event(runId, identity, "cleanup", fields);
    }

    pass = context_pass_eligible(probe.library != NULL && probe.scopeValid && probe.slotValid && probe.allSymbols,
                                 probe.displayValid, probe.initialized && probe.displayInfoValid, probe.apiBound, probe.configValid,
                                 probe.current,
                                 probe.capsValid, probe.eglErrorsClear, probe.glErrorsClear,
                                 probe.cleanupFailures == 0, outputFailed == 0);
    (void)snprintf(encoded, sizeof(encoded),
                   ",\"status\":\"%s\",\"eglContextVerified\":%s,\"graphicsVerified\":false,"
                   "\"hardwareRenderingVerified\":false,\"vtestVerified\":false,\"presentationVerified\":false,"
                   "\"d3dVerified\":false,\"wowVerified\":false,\"eglSuccess\":%s,\"cleanup0\":%s,"
                   "\"mandatoryEglErrorCount\":%d,\"nativeExit\":%d",
                   pass ? "EPOXY_CONTEXT_PASS" : "CONTEXT_FAILED", pass ? "true" : "false",
                   probe.eglErrorsClear ? "true" : "false", probe.cleanupFailures == 0 ? "true" : "false",
                   probe.eglErrorFailures, pass ? 0 : 1);
    endEmitted = emit_event(runId, identity, "end", encoded);
    return pass && endEmitted && !outputFailed;
}

int main(int argc, char **argv) {
    Identity identity;
    const char *runId;
    if (argc != 3 || argv == NULL || argv[1] == NULL || strcmp(argv[1], "--run-id") != 0 || argv[2] == NULL ||
        !valid_run_id(argv[2])) return 2;
    runId = argv[2];
    memset(&identity, 0, sizeof(identity));
    identity.pid = getpid();
    identity.uid = getuid();
    identity.pgid = getpgid(0);
    identity.sid = getsid(0);
    return run_probe(runId, &identity) ? 0 : 1;
}
