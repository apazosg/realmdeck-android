#define _GNU_SOURCE

/*
 * This is a classification probe, not a context qualification gate.  The
 * Keep the reviewed context translation unit immutable.  The build recipe
 * makes a private temporary copy whose old diagnostic main is renamed before
 * this exact include is compiled; no repository source is modified.
 */
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wmacro-redefined"
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-function"
#define main unused_context_main
#include "GuestEglContext.c"
#undef main
#pragma GCC diagnostic pop
#pragma clang diagnostic pop

#include <inttypes.h>
#include <ctype.h>
#include <fcntl.h>
#include <stdarg.h>
#include <sys/stat.h>

#define PROFILES_SCHEMA "wowforever.android-guest-egl-profiles/version1"
#define PROFILES_OUTPUT_MAX (16U * 1024U)
#define PROFILES_ATTEMPT_COUNT 4U
#define PROFILES_MAX_TIME_NS (UINT64_C(15) * UINT64_C(1000000000))
#define EGL_CONFORMANT ((EGLint)0x3042)
#define EGL_CONFIG_ID ((EGLint)0x3028)
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
#define EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR ((EGLint)0x00000002)
#define EGL_SUCCESS ((EGLint)0x3000)
#define EGL_PLATFORM_SURFACELESS_MESA ((EGLenum)0x31DD)
#define EGL_VENDOR ((EGLint)0x3053)
#define EGL_VERSION ((EGLint)0x3054)
#define EGL_CLIENT_APIS ((EGLint)0x308D)
#define EGL_NO_CONTEXT ((EGLContext)0)
#define EGL_NO_SURFACE ((EGLSurface)0)
#define GL_VENDOR 0x1F00U
#define GL_RENDERER 0x1F01U
#define GL_VERSION 0x1F02U
#define GL_SHADING_LANGUAGE_VERSION 0x8B8CU
#define GL_MAJOR_VERSION 0x821BU
#define GL_MINOR_VERSION 0x821CU
#define GL_CONTEXT_PROFILE_MASK 0x9126U

typedef EGLint (*ProfilesGetConfigAttribFn)(EGLDisplay, EGLConfig, EGLint, EGLint *);
typedef EGLBoolean (*ProfilesDestroyContextFn)(EGLDisplay, EGLContext);
typedef const unsigned char *(*ProfilesGlGetStringFn)(unsigned int);
typedef unsigned int (*ProfilesGlGetErrorFn)(void);
typedef void (*ProfilesGlGetIntegerFn)(unsigned int, int *);

typedef struct ProfilesServerIdentity {
    pid_t pid;
    uint64_t startTicks;
} ProfilesServerIdentity;

typedef struct ProfilesAttempt {
    const char *name;
    int requestedMajor;
    int requestedMinor;
    int requestedProfileKnown;
    int requestedCore;
    int preServerLive;
    int createCalled;
    int createKnown;
    int createError;
    int created;
    int makeCurrentCalled;
    int makeCurrentKnown;
    int makeCurrentOk;
    int makeCurrentError;
    int actualValid;
    int actualMajor;
    int actualMinor;
    int actualProfileKnown;
    int actualProfile;
    int actualCore;
    unsigned int glError;
    char vendor[MAX_STRING];
    char renderer[MAX_STRING];
    char version[MAX_STRING];
    char glslVersion[MAX_STRING];
    int rendererVirgl;
    int softwareFallback;
    int unbindAttempted;
    int unbindOk;
    int unbindError;
    int destroyAttempted;
    int destroyOk;
    int destroyError;
    int cleanupValid;
    int postServerLive;
    int deadlineValid;
} ProfilesAttempt;

typedef struct ProfilesState {
    State context;
    Identity self;
    Paths paths;
    ProfilesServerIdentity server;
    void *handle;
    EGLDisplay display;
    EGLConfig config;
    Api api;
    ProfilesGetConfigAttribFn getConfigAttrib;
    ProfilesDestroyContextFn destroyContext;
    char loaderCanonical[MAX_PATH_STRING];
    char vendorCanonical[MAX_PATH_STRING];
    char missingSymbol[MAX_STRING];
    char wrongOwnerSymbol[MAX_STRING];
    char ownerPath[MAX_PATH_STRING];
    char loadError[MAX_STRING];
    char mappingError[MAX_STRING];
    char clientExtensions[MAX_EXTENSION_PREVIEW + 1U];
    char displayExtensions[MAX_EXTENSION_PREVIEW + 1U];
    char displayVendor[MAX_STRING];
    char displayVersion[MAX_STRING];
    char displayClientApis[MAX_STRING];
    EGLint clientEglError;
    EGLint displayEglError;
    EGLint bindEglError;
    EGLint chooseEglError;
    int configRenderableType;
    int configConformant;
    int configSurfaceType;
    int configId;
    int configCount;
    int configRenderableError;
    int configConformantError;
    int configSurfaceError;
    int configIdError;
    int scopeValid;
    int environmentValid;
    int selectionValid;
    int identityLoaded;
    int identityValid;
    int libraryLoaded;
    int exportsValid;
    int configAttribExportValid;
    int clientExtensionsValid;
    int displayCreated;
    int displayInitialized;
    int displayExtensionsValid;
    int displayCreateContextExtension;
    int displaySurfacelessExtension;
    int boundApi;
    int configChosen;
    int configAttributesValid;
    int mapsComplete;
    int mapsTruncated;
    int privateLoaderMapped;
    int privateVendorMapped;
    int privateGalliumMapped;
    int privateLlvmMapped;
    int androidEglMapped;
    int androidGlesMapped;
    int deadlineValid;
    int outputFailure;
    int cleanupFailures;
    int serverLiveBeforeTerminate;
    int terminateAttempted;
    int terminateOk;
    int terminateError;
    int handleClosed;
    uint32_t clientExtensionBytes;
    uint32_t displayExtensionBytes;
    uint32_t mapBytes;
    uint32_t mapLines;
    uint64_t startedNs;
    uint64_t finishedNs;
    ProfilesAttempt attempts[PROFILES_ATTEMPT_COUNT];
} ProfilesState;

static int profiles_parse_uint(const unsigned char *data, size_t length, uint64_t maximum,
                               uint64_t *value) {
    uint64_t parsed = 0U;
    size_t index;
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

static int profiles_load_server_identity(ProfilesServerIdentity *identity) {
    struct stat listed, opened;
    unsigned char data[128];
    size_t expected, used = 0U, split = 0U, end;
    uint64_t pid, ticks;
    int fd = -1, result = 0;
    if (identity == NULL || lstat("server.identity", &listed) != 0 || !S_ISREG(listed.st_mode) ||
        listed.st_uid != getuid() || (listed.st_mode & 07777U) != 0600 || listed.st_size <= 0 ||
        (uintmax_t)listed.st_size > sizeof(data)) return 0;
    expected = (size_t)listed.st_size;
    fd = open("server.identity", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0 || fstat(fd, &opened) != 0 || !S_ISREG(opened.st_mode) || opened.st_uid != getuid() ||
        (opened.st_mode & 07777U) != 0600 || opened.st_dev != listed.st_dev || opened.st_ino != listed.st_ino ||
        opened.st_size != listed.st_size) goto done;
    while (used < expected) {
        ssize_t count = read(fd, data + used, expected - used);
        if (count > 0) used += (size_t)count;
        else if (count < 0 && errno == EINTR) continue;
        else goto done;
    }
    while (split < used && data[split] != (unsigned char)' ') ++split;
    end = split + 1U;
    while (end < used && data[end] != (unsigned char)'\n') ++end;
    if (split == 0U || end + 1U != used || !profiles_parse_uint(data, split, INT_MAX, &pid) ||
        !profiles_parse_uint(data + split + 1U, end - split - 1U, UINT64_MAX, &ticks) || pid == 0U || ticks == 0U) goto done;
    identity->pid = (pid_t)pid;
    identity->startTicks = ticks;
    result = 1;
done:
    if (fd >= 0 && close(fd) != 0) result = 0;
    return result;
}

static int profiles_server_live(const ProfilesServerIdentity *identity, const char *cwd) {
    ContextServerIdentity contextIdentity;
    if (identity == NULL) return 0;
    contextIdentity.pid = identity->pid;
    contextIdentity.startTicks = identity->startTicks;
    return context_server_live(&contextIdentity, cwd);
}

static int profiles_contains_casefold(const char *value, const char *needle) {
    size_t length = bounded_length(value, MAX_STRING, NULL), needleLength = bounded_length(needle, MAX_STRING, NULL), at, index;
    if (needleLength == 0U || needleLength > length) return 0;
    for (at = 0U; at + needleLength <= length; ++at) {
        for (index = 0U; index < needleLength; ++index)
            if (tolower((unsigned char)value[at + index]) != tolower((unsigned char)needle[index])) break;
        if (index == needleLength) return 1;
    }
    return 0;
}

static int profiles_scan_extensions(const char *value, char *preview, size_t capacity,
                                    uint32_t *bytes, int *create, int *surface) {
    size_t scan = 0U;
    int platform = 0;
    int valid = scan_extension_string(value, &platform, create, surface, &scan);
    if (bytes != NULL) *bytes = scan > UINT32_MAX ? UINT32_MAX : (uint32_t)scan;
    if (preview != NULL && capacity > 0U && value != NULL) {
        size_t copy = scan < capacity - 1U ? scan : capacity - 1U;
        memcpy(preview, value, copy);
        preview[copy] = '\0';
    }
    return valid;
}

/* HOST-TEST-BEGIN: inert request, result, cleanup, and bounded encoder logic. */
static const char *profiles_attempt_name(size_t index) {
    static const char *const names[PROFILES_ATTEMPT_COUNT] = {
        "OpenGL3.3CORE", "OpenGL3.3COMPAT", "OpenGL3.2CORE", "OpenGL3.0DEFAULT"
    };
    return index < PROFILES_ATTEMPT_COUNT ? names[index] : NULL;
}

static int profiles_request_uses_profile(size_t index) {
    return index < 3U;
}

static int profiles_request_is_core(size_t index) {
    return index == 0U || index == 2U;
}

static int profiles_create_result_valid(int created, int errorKnown, int error) {
    return errorKnown && ((!created && error != EGL_SUCCESS) || (created && error == EGL_SUCCESS));
}

static int profiles_config_attribute_result_valid(int exportResolved, int callOk,
                                                  int errorKnown, int error) {
    return exportResolved && callOk && errorKnown && error == EGL_SUCCESS;
}

static int profiles_cleanup_result_valid(int attempted, int ok, int error) {
    return !attempted || (ok && error == EGL_SUCCESS);
}

static int profiles_attempt_result_valid(const ProfilesAttempt *attempt) {
    if (attempt == NULL || !attempt->deadlineValid || !attempt->preServerLive ||
        !attempt->postServerLive || !profiles_create_result_valid(
            attempt->created, attempt->createKnown, attempt->createError) ||
        !profiles_cleanup_result_valid(attempt->unbindAttempted, attempt->unbindOk,
                                       attempt->unbindError) ||
        !profiles_cleanup_result_valid(attempt->destroyAttempted, attempt->destroyOk,
                                       attempt->destroyError)) return 0;
    if (!attempt->created) return 1;
    if (!attempt->makeCurrentKnown || (attempt->makeCurrentOk && !attempt->actualValid) ||
        (attempt->makeCurrentOk && attempt->makeCurrentError != EGL_SUCCESS) ||
        (!attempt->makeCurrentOk && attempt->makeCurrentError == EGL_SUCCESS)) return 0;
    return 1;
}

static int profiles_matrix_measured(int setupValid, int timingValid, int mapsValid,
                                    int identityValid, int cleanupValid,
                                    const ProfilesAttempt attempts[PROFILES_ATTEMPT_COUNT]) {
    size_t index;
    if (!setupValid || !timingValid || !mapsValid || !identityValid || !cleanupValid || attempts == NULL) return 0;
    for (index = 0U; index < PROFILES_ATTEMPT_COUNT; ++index)
        if (!profiles_attempt_result_valid(&attempts[index])) return 0;
    return 1;
}

static int __attribute__((unused)) profiles_encode_status(char *out, size_t capacity, const char *status) {
    int written;
    if (out == NULL || capacity == 0U || status == NULL) return 0;
    written = snprintf(out, capacity, "{\"status\":\"%s\"}", status);
    return written > 0 && (size_t)written < capacity;
}
/* HOST-TEST-END */

static uint64_t profiles_clock_ns(int *ok) {
    struct timespec now;
    if (ok != NULL) *ok = 0;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0 || now.tv_sec < 0 || now.tv_nsec < 0) return 0U;
    if (ok != NULL) *ok = 1;
    return (uint64_t)now.tv_sec * UINT64_C(1000000000) + (uint64_t)now.tv_nsec;
}

static int profiles_before_deadline(const ProfilesState *state) {
    int ok;
    uint64_t now;
    if (state == NULL || state->startedNs == 0U) return 0;
    now = profiles_clock_ns(&ok);
    if (!ok || now < state->startedNs || now - state->startedNs > PROFILES_MAX_TIME_NS) return 0;
    return 1;
}

static int profiles_resolve_extra(ProfilesState *state) {
    void *symbol;
    const char *error;
    if (state == NULL || state->handle == NULL || state->loaderCanonical[0] == '\0') return 0;
    (void)dlerror();
    symbol = dlsym(state->handle, "eglGetConfigAttrib");
    error = dlerror();
    state->ownerPath[0] = '\0';
    if (symbol == NULL || error != NULL || sizeof(symbol) != sizeof(state->getConfigAttrib) ||
        !owner_is_loader(symbol, state->loaderCanonical, state->ownerPath, sizeof(state->ownerPath))) {
        (void)copy_bounded(state->missingSymbol, sizeof(state->missingSymbol),
                           "eglGetConfigAttrib", MAX_STRING - 1U);
        if (state->ownerPath[0] != '\0')
            (void)copy_bounded(state->wrongOwnerSymbol, sizeof(state->wrongOwnerSymbol),
                               "eglGetConfigAttrib", MAX_STRING - 1U);
        return 0;
    }
    memcpy(&state->getConfigAttrib, &symbol, sizeof(symbol));
    return state->getConfigAttrib != NULL;
}

static void profiles_sync_context(ProfilesState *state) {
    state->context.paths = state->paths;
    state->context.handle = state->handle;
    state->context.loaderCanonical[0] = '\0';
    (void)copy_bounded(state->context.loaderCanonical, sizeof(state->context.loaderCanonical),
                       state->loaderCanonical, MAX_PATH_STRING - 1U);
    state->context.vendorCanonical[0] = '\0';
    (void)copy_bounded(state->context.vendorCanonical, sizeof(state->context.vendorCanonical),
                       state->vendorCanonical, MAX_PATH_STRING - 1U);
}

static int profiles_resolve_base_exports(ProfilesState *state) {
    int result;
    profiles_sync_context(state);
    result = resolve_exports(&state->context);
    state->api = state->context.api;
    (void)copy_bounded(state->missingSymbol, sizeof(state->missingSymbol),
                       state->context.missingSymbol, MAX_STRING - 1U);
    (void)copy_bounded(state->wrongOwnerSymbol, sizeof(state->wrongOwnerSymbol),
                       state->context.wrongOwnerSymbol, MAX_STRING - 1U);
    (void)copy_bounded(state->ownerPath, sizeof(state->ownerPath),
                       state->context.ownerPath, MAX_PATH_STRING - 1U);
    return result;
}

static int profiles_read_maps(ProfilesState *state) {
    int result;
    profiles_sync_context(state);
    result = read_maps(&state->context);
    state->mapBytes = state->context.mapBytes;
    state->mapLines = state->context.mapLines;
    state->mapsComplete = state->context.mapsComplete;
    state->mapsTruncated = state->context.mapsTruncated;
    state->privateLoaderMapped = state->context.privateLoaderMapped;
    state->privateVendorMapped = state->context.privateVendorMapped;
    state->privateGalliumMapped = state->context.privateGalliumMapped;
    state->privateLlvmMapped = state->context.privateLlvmMapped;
    state->androidEglMapped = state->context.androidEglMapped;
    state->androidGlesMapped = state->context.androidGlesMapped;
    (void)copy_bounded(state->mappingError, sizeof(state->mappingError),
                       state->context.mapError, MAX_STRING - 1U);
    return result;
}

static int profiles_resolve_destroy(ProfilesState *state) {
    void *symbol;
    if (state == NULL || state->api.getProcAddress == NULL) return 0;
    symbol = state->api.getProcAddress("eglDestroyContext");
    if (symbol == NULL || !owner_is_loader(symbol, state->loaderCanonical, NULL, 0U) ||
        sizeof(symbol) != sizeof(state->destroyContext)) return 0;
    memcpy(&state->destroyContext, &symbol, sizeof(symbol));
    return state->destroyContext != NULL;
}

static int profiles_copy_gl_string(char *out, size_t capacity,
                                   const unsigned char *value) {
    return value != NULL && copy_bounded(out, capacity, (const char *)value, capacity - 1U);
}

static void profiles_measure_actual(ProfilesAttempt *attempt, Api *api) {
    ProfilesGlGetStringFn getString = NULL;
    ProfilesGlGetErrorFn getError = NULL;
    ProfilesGlGetIntegerFn getInteger = NULL;
    void *symbol;
    int major = 0;
    int minor = 0;
    int profile = 0;
    if (attempt == NULL || api == NULL || api->getProcAddress == NULL || !attempt->makeCurrentOk) return;
    symbol = api->getProcAddress("glGetString");
    if (symbol != NULL) (void)copy_symbol(&getString, sizeof(getString), symbol);
    symbol = api->getProcAddress("glGetError");
    if (symbol != NULL) (void)copy_symbol(&getError, sizeof(getError), symbol);
    symbol = api->getProcAddress("glGetIntegerv");
    if (symbol != NULL) (void)copy_symbol(&getInteger, sizeof(getInteger), symbol);
    if (getString == NULL || getError == NULL || getInteger == NULL) return;
    if (!profiles_copy_gl_string(attempt->vendor, sizeof(attempt->vendor), getString(GL_VENDOR)) ||
        !profiles_copy_gl_string(attempt->renderer, sizeof(attempt->renderer), getString(GL_RENDERER)) ||
        !profiles_copy_gl_string(attempt->version, sizeof(attempt->version), getString(GL_VERSION)) ||
        !profiles_copy_gl_string(attempt->glslVersion, sizeof(attempt->glslVersion), getString(GL_SHADING_LANGUAGE_VERSION))) return;
    getInteger(GL_MAJOR_VERSION, &major);
    getInteger(GL_MINOR_VERSION, &minor);
    if (getError() != 0U) return;
    attempt->actualMajor = major;
    attempt->actualMinor = minor;
    if (major >= 3 && (major > 3 || minor >= 2)) {
        getInteger(GL_CONTEXT_PROFILE_MASK, &profile);
        if (getError() != 0U) return;
        attempt->actualProfileKnown = 1;
        attempt->actualProfile = profile;
        attempt->actualCore = (profile & EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR) != 0;
    }
    attempt->glError = getError();
    if (attempt->glError != 0U) return;
    attempt->actualValid = 1;
    attempt->rendererVirgl = profiles_contains_casefold(attempt->renderer, "virgl");
    attempt->softwareFallback = profiles_contains_casefold(attempt->renderer, "llvmpipe") ||
                                profiles_contains_casefold(attempt->renderer, "softpipe") ||
                                profiles_contains_casefold(attempt->renderer, "swrast") ||
                                profiles_contains_casefold(attempt->renderer, "software");
}

static int profiles_measure_config(ProfilesState *state) {
    EGLint *values[4] = {
        &state->configRenderableType, &state->configConformant,
        &state->configSurfaceType, &state->configId
    };
    const EGLint attributes[4] = {EGL_RENDERABLE_TYPE, EGL_CONFORMANT, EGL_SURFACE_TYPE, EGL_CONFIG_ID};
    EGLint *errors[4] = {
        &state->configRenderableError, &state->configConformantError,
        &state->configSurfaceError, &state->configIdError
    };
    size_t index;
    int valid = state != NULL && state->configAttribExportValid && state->getConfigAttrib != NULL;
    if (!valid) return 0;
    for (index = 0U; index < 4U; ++index) {
        int called = state->getConfigAttrib(state->display, state->config, attributes[index], values[index]) != 0U;
        EGLint error = state->api.getError();
        *errors[index] = error;
        if (!profiles_config_attribute_result_valid(1, called, 1, error)) valid = 0;
    }
    state->configAttributesValid = valid;
    return valid;
}

static void profiles_init_attempt(ProfilesAttempt *attempt, size_t index) {
    memset(attempt, 0, sizeof(*attempt));
    attempt->name = profiles_attempt_name(index);
    attempt->requestedMajor = index == 3U ? 3 : (index == 2U ? 3 : 3);
    attempt->requestedMinor = index == 3U ? 0 : (index == 2U ? 2 : 3);
    attempt->requestedProfileKnown = profiles_request_uses_profile(index);
    attempt->requestedCore = profiles_request_is_core(index);
}

static int profiles_run_attempt(ProfilesState *state, size_t index) {
    ProfilesAttempt *attempt = &state->attempts[index];
    EGLint contextAttributes[7];
    EGLContext context = EGL_NO_CONTEXT;
    if (!profiles_before_deadline(state)) {
        state->deadlineValid = 0;
        return 0;
    }
    attempt->deadlineValid = 1;
    attempt->preServerLive = profiles_server_live(&state->server, state->paths.cwd);
    if (!attempt->preServerLive) return 0;
    if (attempt->requestedProfileKnown) {
        contextAttributes[0] = EGL_CONTEXT_MAJOR_VERSION_KHR;
        contextAttributes[1] = attempt->requestedMajor;
        contextAttributes[2] = EGL_CONTEXT_MINOR_VERSION_KHR;
        contextAttributes[3] = attempt->requestedMinor;
        contextAttributes[4] = EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR;
        contextAttributes[5] = attempt->requestedCore ? EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR : EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR;
        contextAttributes[6] = EGL_NONE;
    } else {
        contextAttributes[0] = EGL_CONTEXT_MAJOR_VERSION_KHR;
        contextAttributes[1] = attempt->requestedMajor;
        contextAttributes[2] = EGL_CONTEXT_MINOR_VERSION_KHR;
        contextAttributes[3] = attempt->requestedMinor;
        contextAttributes[4] = EGL_NONE;
    }
    attempt->createCalled = 1;
    context = state->api.createContext(state->display, state->config, EGL_NO_CONTEXT, contextAttributes);
    attempt->created = context != EGL_NO_CONTEXT;
    attempt->createError = state->api.getError();
    attempt->createKnown = 1;
    if (!profiles_create_result_valid(attempt->created, attempt->createKnown, attempt->createError)) return 0;
    if (attempt->created) {
        attempt->makeCurrentCalled = 1;
        attempt->makeCurrentOk = state->api.makeCurrent(state->display, EGL_NO_SURFACE, EGL_NO_SURFACE, context) != 0U;
        attempt->makeCurrentError = state->api.getError();
        attempt->makeCurrentKnown = 1;
        if (attempt->makeCurrentOk && attempt->makeCurrentError == EGL_SUCCESS) profiles_measure_actual(attempt, &state->api);
        if (attempt->makeCurrentOk) {
            attempt->unbindAttempted = 1;
            attempt->unbindOk = state->api.makeCurrent(state->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) != 0U;
            attempt->unbindError = state->api.getError();
        }
        attempt->destroyAttempted = 1;
        attempt->destroyOk = state->destroyContext != NULL && state->destroyContext(state->display, context) != 0U;
        attempt->destroyError = state->api.getError();
        attempt->cleanupValid = profiles_cleanup_result_valid(attempt->unbindAttempted, attempt->unbindOk, attempt->unbindError) &&
                                profiles_cleanup_result_valid(attempt->destroyAttempted, attempt->destroyOk, attempt->destroyError);
        if (!attempt->cleanupValid) ++state->cleanupFailures;
    } else {
        attempt->cleanupValid = 1;
    }
    attempt->postServerLive = profiles_server_live(&state->server, state->paths.cwd);
    if (!attempt->postServerLive) return 0;
    if (!profiles_before_deadline(state)) {
        attempt->deadlineValid = 0;
        state->deadlineValid = 0;
        return 0;
    }
    return 1;
}

static void profiles_json(char *out, size_t capacity, const char *value, size_t limit) {
    json_optional_string(out, capacity, value == NULL || value[0] == '\0' ? NULL : value, limit, NULL);
}

static void profiles_int_json(char *out, size_t capacity, int known, int value) {
    (void)snprintf(out, capacity, known ? "%d" : "null", value);
}

static int profiles_append(char *out, size_t capacity, size_t *used, const char *format, ...) {
    va_list args;
    int written;
    if (out == NULL || used == NULL || *used >= capacity || format == NULL) return 0;
    va_start(args, format);
    written = vsnprintf(out + *used, capacity - *used, format, args);
    va_end(args);
    if (written < 0 || (size_t)written >= capacity - *used) return 0;
    *used += (size_t)written;
    return 1;
}

static int profiles_append_attempt(char *out, size_t capacity, size_t *used,
                                   const ProfilesAttempt *attempt, int first) {
    char vendor[1024], renderer[1024], version[1024], glsl[1024];
    char makeError[32], major[32], minor[32], profile[32], unbindError[32], destroyError[32];
    profiles_json(vendor, sizeof(vendor), attempt->vendor, MAX_STRING - 1U);
    profiles_json(renderer, sizeof(renderer), attempt->renderer, MAX_STRING - 1U);
    profiles_json(version, sizeof(version), attempt->version, MAX_STRING - 1U);
    profiles_json(glsl, sizeof(glsl), attempt->glslVersion, MAX_STRING - 1U);
    profiles_int_json(makeError, sizeof(makeError), attempt->makeCurrentCalled, attempt->makeCurrentError);
    profiles_int_json(major, sizeof(major), attempt->actualValid, attempt->actualMajor);
    profiles_int_json(minor, sizeof(minor), attempt->actualValid, attempt->actualMinor);
    profiles_int_json(profile, sizeof(profile), attempt->actualProfileKnown, attempt->actualProfile);
    profiles_int_json(unbindError, sizeof(unbindError), attempt->unbindAttempted, attempt->unbindError);
    profiles_int_json(destroyError, sizeof(destroyError), attempt->destroyAttempted, attempt->destroyError);
    return profiles_append(out, capacity, used,
        "%s{\"name\":\"%s\",\"requested\":{\"major\":%d,\"minor\":%d,\"profile\":%s,\"core\":%s},"
        "\"serverLiveBefore\":%s,\"createCalled\":%s,\"createError\":%d,\"created\":%s,"
        "\"makeCurrentCalled\":%s,\"makeCurrentError\":%s,\"madeCurrent\":%s,"
        "\"actual\":{\"valid\":%s,\"major\":%s,\"minor\":%s,\"profileKnown\":%s,\"profileMask\":%s,"
        "\"core\":%s,\"vendor\":%s,\"renderer\":%s,\"version\":%s,\"glslVersion\":%s,\"glError\":%u,"
        "\"rendererVirgl\":%s,\"softwareFallback\":%s},\"cleanup\":{\"unbindAttempted\":%s,\"unbindOk\":%s,"
        "\"unbindError\":%s,\"destroyAttempted\":%s,\"destroyOk\":%s,\"destroyError\":%s,\"valid\":%s},"
        "\"serverLiveAfter\":%s,\"deadlineValid\":%s}%s",
        first ? "" : ",", attempt->name, attempt->requestedMajor, attempt->requestedMinor,
        attempt->requestedProfileKnown ? (attempt->requestedCore ? "\"CORE\"" : "\"COMPAT\"") : "null",
        attempt->requestedProfileKnown ? (attempt->requestedCore ? "true" : "false") : "null",
        attempt->preServerLive ? "true" : "false", attempt->createCalled ? "true" : "false",
        (int)attempt->createError, attempt->created ? "true" : "false",
        attempt->makeCurrentCalled ? "true" : "false", makeError,
        attempt->makeCurrentOk ? "true" : "false", attempt->actualValid ? "true" : "false",
        major, minor, attempt->actualProfileKnown ? "true" : "false", profile,
        attempt->actualProfileKnown ? (attempt->actualCore ? "true" : "false") : "null",
        vendor, renderer, version, glsl, attempt->glError, attempt->rendererVirgl ? "true" : "false",
        attempt->softwareFallback ? "true" : "false", attempt->unbindAttempted ? "true" : "false",
        attempt->unbindOk ? "true" : "false", unbindError,
        attempt->destroyAttempted ? "true" : "false", attempt->destroyOk ? "true" : "false",
        destroyError, attempt->cleanupValid ? "true" : "false",
        attempt->postServerLive ? "true" : "false", attempt->deadlineValid ? "true" : "false",
        "");
}

static int profiles_build_output(ProfilesState *state, const char *runId, const Identity *identity,
                                 char *output, size_t capacity) {
    char cwd[1024], library[1024], ldLibrary[1024], vendorJson[1024], loader[1024], vendor[1024], error[1024], mapError[1024];
    char missing[1024], wrongOwner[1024], owner[1024], clientExt[1024], displayExt[1024];
    char displayVendor[1024], displayVersion[1024], displayApis[1024];
    size_t used = 0U;
    size_t index;
    int measured;
    profiles_json(cwd, sizeof(cwd), state->paths.cwd, 512U);
    profiles_json(library, sizeof(library), state->paths.loader, 512U);
    profiles_json(ldLibrary, sizeof(ldLibrary), state->paths.ldLibraryPath, 512U);
    profiles_json(vendorJson, sizeof(vendorJson), state->paths.vendorJson, 512U);
    profiles_json(loader, sizeof(loader), state->loaderCanonical, 512U);
    profiles_json(vendor, sizeof(vendor), state->vendorCanonical, 512U);
    profiles_json(error, sizeof(error), state->loadError, MAX_STRING - 1U);
    profiles_json(missing, sizeof(missing), state->missingSymbol, MAX_STRING - 1U);
    profiles_json(wrongOwner, sizeof(wrongOwner), state->wrongOwnerSymbol, MAX_STRING - 1U);
    profiles_json(owner, sizeof(owner), state->ownerPath, 512U);
    profiles_json(clientExt, sizeof(clientExt), state->clientExtensions, MAX_EXTENSION_PREVIEW);
    profiles_json(displayExt, sizeof(displayExt), state->displayExtensions, MAX_EXTENSION_PREVIEW);
    profiles_json(displayVendor, sizeof(displayVendor), state->displayVendor, MAX_STRING - 1U);
    profiles_json(displayVersion, sizeof(displayVersion), state->displayVersion, MAX_STRING - 1U);
    profiles_json(displayApis, sizeof(displayApis), state->displayClientApis, MAX_STRING - 1U);
    profiles_json(mapError, sizeof(mapError), state->mappingError, MAX_STRING - 1U);
    measured = profiles_matrix_measured(state->scopeValid && state->environmentValid && state->selectionValid &&
        state->libraryLoaded && state->exportsValid && state->configAttribExportValid && state->displayInitialized &&
        state->displayExtensionsValid && state->boundApi && state->configChosen && state->configAttributesValid,
        state->deadlineValid, state->mapsComplete && state->privateLoaderMapped && state->privateVendorMapped &&
        state->privateGalliumMapped && state->privateLlvmMapped && !state->androidEglMapped && !state->androidGlesMapped,
        state->identityValid && state->serverLiveBeforeTerminate, state->cleanupFailures == 0, state->attempts);
    if (!profiles_append(output, capacity, &used,
        "{\"schema\":\"%s\",\"runId\":\"%s\",\"pid\":%ld,\"uid\":%lu,\"pgid\":%ld,\"sid\":%ld,"
        "\"times\":{\"startedNs\":%llu,\"finishedNs\":%llu,\"durationNs\":%llu},"
        "\"status\":\"%s\",\"contextQualified\":%s,\"hardwareRenderingVerified\":false,\"triangleVerified\":false,"
        "\"presentationVerified\":false,\"d3dVerified\":false,\"wowVerified\":false,"
        "\"scope\":{\"valid\":%s,\"environmentValid\":%s,\"selectionValid\":%s,\"cwd\":%s,"
        "\"libraryPath\":%s,\"ldLibraryPath\":%s,\"vendorJson\":%s,"
        "\"guestManifestSha256\":\"30c43814697a22dc72ee4e3b1eeb8ec9d6b6af228ee9c4ffc21e15b3fe8d9ce9\",\"guestTreeSha256\":\"1cc803fbcbef9a7d1743966702c733fc63b8e311a3aa77f8020ee7594e11d216\","
        "\"serverManifestSha256\":\"b318221194b72c0bff5318457625a4595cf874a420be7c0bc3be08d43d1afa28\"},"
        "\"server\":{\"expectedPid\":%ld,\"expectedStartTicks\":%llu,\"identityLoaded\":%s,\"live\":%s,\"liveBeforeTerminate\":%s},"
        "\"library\":{\"loaded\":%s,\"privatePath\":%s,\"canonicalPath\":%s,\"loadError\":%s},"
        "\"exports\":{\"expectedCount\":11,\"exportCount\":%u,\"allExports\":%s,\"ownerExact\":%s,\"ownerPath\":%s,\"missingSymbol\":%s,\"wrongOwnerSymbol\":%s},"
        "\"display\":{\"clientExtensions\":%s,\"clientExtensionBytes\":%u,\"displayExtensions\":%s,\"displayExtensionBytes\":%u,\"vendor\":%s,\"version\":%s,\"clientApis\":%s,\"displayCreated\":%s,\"displayInitialized\":%s,\"eglError\":%d},"
         "\"config\":{\"boundApi\":%s,\"chosen\":%s,\"count\":%d,\"attributesValid\":%s,\"bindError\":%d,\"chooseError\":%d,\"EGL_RENDERABLE_TYPE\":%d,\"EGL_CONFORMANT\":%d,\"EGL_SURFACE_TYPE\":%d,\"EGL_CONFIG_ID\":%d,\"attributeErrors\":{\"EGL_RENDERABLE_TYPE\":%d,\"EGL_CONFORMANT\":%d,\"EGL_SURFACE_TYPE\":%d,\"EGL_CONFIG_ID\":%d}},"
        "\"attempts\":[",
        PROFILES_SCHEMA, runId, (long)identity->pid, (unsigned long)identity->uid,
        (long)identity->pgid, (long)identity->sid, (unsigned long long)state->startedNs,
        (unsigned long long)state->finishedNs,
        (unsigned long long)(state->finishedNs >= state->startedNs ? state->finishedNs - state->startedNs : 0U),
        measured ? "PROFILE_MATRIX_MEASURED" : "UNKNOWN", "false",
        state->scopeValid ? "true" : "false", state->environmentValid ? "true" : "false",
        state->selectionValid ? "true" : "false", cwd, library, ldLibrary, vendorJson, (long)state->server.pid,
        (unsigned long long)state->server.startTicks, state->identityLoaded ? "true" : "false",
        state->identityValid ? "true" : "false", state->serverLiveBeforeTerminate ? "true" : "false",
        state->libraryLoaded ? "true" : "false", library, loader, error,
        state->exportsValid && state->configAttribExportValid ? 11U : 0U,
        state->exportsValid && state->configAttribExportValid ? "true" : "false",
        state->exportsValid && state->configAttribExportValid ? "true" : "false", owner, missing, wrongOwner,
        clientExt, (unsigned)state->clientExtensionBytes, displayExt, (unsigned)state->displayExtensionBytes,
        displayVendor, displayVersion, displayApis, state->displayCreated ? "true" : "false",
        state->displayInitialized ? "true" : "false", (int)state->displayEglError,
        state->boundApi ? "true" : "false", state->configChosen ? "true" : "false",
         state->configCount, state->configAttributesValid ? "true" : "false", state->bindEglError, state->chooseEglError,
        state->configRenderableType, state->configConformant, state->configSurfaceType, state->configId,
        (int)state->configRenderableError, (int)state->configConformantError,
        (int)state->configSurfaceError, (int)state->configIdError)) return 0;
    for (index = 0U; index < PROFILES_ATTEMPT_COUNT; ++index)
        if (!profiles_append_attempt(output, capacity, &used, &state->attempts[index], index == 0U)) return 0;
    if (!profiles_append(output, capacity, &used,
        "],\"maps\":{\"complete\":%s,\"truncated\":%s,\"bytes\":%u,\"lines\":%u,\"privateLoader\":%s,\"privateVendor\":%s,\"privateGallium\":%s,\"privateLlvm\":%s,\"androidEgl\":%s,\"androidGles\":%s,\"loaderPath\":%s,\"vendorPath\":%s,\"error\":%s},"
        "\"cleanup\":{\"terminateAttempted\":%s,\"terminateOk\":%s,\"terminateError\":%d,\"handleClosed\":%s,\"failures\":%d,\"serverLiveBeforeTerminate\":%s},"
        "\"nativeExit\":%d}\n",
        state->mapsComplete ? "true" : "false", state->mapsTruncated ? "true" : "false",
        (unsigned)state->mapBytes, (unsigned)state->mapLines, state->privateLoaderMapped ? "true" : "false",
        state->privateVendorMapped ? "true" : "false", state->privateGalliumMapped ? "true" : "false",
        state->privateLlvmMapped ? "true" : "false", state->androidEglMapped ? "true" : "false",
        state->androidGlesMapped ? "true" : "false", loader, vendor, mapError, state->terminateAttempted ? "true" : "false",
        state->terminateOk ? "true" : "false", state->terminateError, state->handleClosed ? "true" : "false",
        state->cleanupFailures, state->serverLiveBeforeTerminate ? "true" : "false", measured ? 0 : 1)) return 0;
    return used <= PROFILES_OUTPUT_MAX;
}

static int profiles_run(const char *runId, Identity *identity) {
    ProfilesState state;
    EGLint major = 0;
    EGLint minor = 0;
    EGLint configCount = 0;
    const EGLint configAttributes[] = {
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_SURFACE_TYPE, 0, EGL_NONE
    };
    size_t index;
    char output[PROFILES_OUTPUT_MAX + 1U];
    int clockOk;
    memset(&state, 0, sizeof(state));
    state.self = *identity;
    state.display = EGL_NO_DISPLAY;
    state.config = NULL;
    state.startedNs = profiles_clock_ns(&clockOk);
    state.deadlineValid = clockOk && state.startedNs != 0U;
    state.scopeValid = validate_scope(runId, identity, &state.paths);
    state.environmentValid = state.scopeValid;
    state.selectionValid = state.scopeValid;
    state.identityLoaded = profiles_load_server_identity(&state.server);
    state.identityValid = state.identityLoaded && state.scopeValid && profiles_server_live(&state.server, state.paths.cwd);
    if (state.scopeValid && state.identityValid) {
        state.handle = dlopen("guest/lib/libEGL.so.1.1.0", RTLD_NOW | RTLD_LOCAL);
        state.libraryLoaded = state.handle != NULL;
        if (!state.libraryLoaded) {
            const char *error = dlerror();
            (void)copy_bounded(state.loadError, sizeof(state.loadError), error == NULL ? "dlopen failed" : error, MAX_STRING - 1U);
        } else if (!canonical_path(state.paths.loader, state.loaderCanonical, sizeof(state.loaderCanonical))) {
            (void)copy_bounded(state.loadError, sizeof(state.loadError), "private loader canonicalization failed", MAX_STRING - 1U);
        }
    }
    if (state.libraryLoaded && state.loaderCanonical[0] != '\0') {
        state.exportsValid = profiles_resolve_base_exports(&state);
        state.configAttribExportValid = profiles_resolve_extra(&state);
        if (!state.exportsValid || !state.configAttribExportValid) state.exportsValid = 0;
    }
    if (state.exportsValid && state.configAttribExportValid) {
        const char *extensions = state.api.queryString(EGL_NO_DISPLAY, EGL_EXTENSIONS);
        state.clientEglError = state.api.getError();
        state.clientExtensionsValid = profiles_scan_extensions(extensions, state.clientExtensions,
            sizeof(state.clientExtensions), &state.clientExtensionBytes, NULL, NULL) &&
            state.clientEglError == EGL_SUCCESS;
    }
    if (state.clientExtensionsValid && state.identityValid && profiles_before_deadline(&state)) {
        state.display = state.api.getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, NULL, NULL);
        state.displayCreated = state.display != EGL_NO_DISPLAY;
        state.displayEglError = state.api.getError();
        if (state.displayCreated && state.displayEglError == EGL_SUCCESS)
            state.displayInitialized = state.api.initialize(state.display, &major, &minor) != 0U;
        state.displayEglError = state.api.getError();
        if (state.displayInitialized) {
            const char *extensions = state.api.queryString(state.display, EGL_EXTENSIONS);
            state.displayEglError = state.api.getError();
            state.displayExtensionsValid = profiles_scan_extensions(extensions, state.displayExtensions,
                sizeof(state.displayExtensions), &state.displayExtensionBytes,
                &state.displayCreateContextExtension, &state.displaySurfacelessExtension) &&
                state.displayEglError == EGL_SUCCESS && state.displayCreateContextExtension && state.displaySurfacelessExtension;
            if (!copy_bounded(state.displayVendor, sizeof(state.displayVendor), state.api.queryString(state.display, EGL_VENDOR), MAX_STRING - 1U) ||
                state.api.getError() != EGL_SUCCESS ||
                !copy_bounded(state.displayVersion, sizeof(state.displayVersion), state.api.queryString(state.display, EGL_VERSION), MAX_STRING - 1U) ||
                state.api.getError() != EGL_SUCCESS ||
                !copy_bounded(state.displayClientApis, sizeof(state.displayClientApis), state.api.queryString(state.display, EGL_CLIENT_APIS), MAX_STRING - 1U) ||
                state.api.getError() != EGL_SUCCESS) state.displayExtensionsValid = 0;
        }
    } else state.deadlineValid = 0;
    if (state.displayInitialized && state.displayExtensionsValid && state.identityValid) {
        state.boundApi = state.api.bindApi(EGL_OPENGL_API) != 0U;
        state.bindEglError = state.api.getError();
        if (state.boundApi && state.bindEglError == EGL_SUCCESS)
            state.configChosen = state.api.chooseConfig(state.display, configAttributes, &state.config, 1, &configCount) != 0U && configCount == 1;
        state.configCount = configCount;
        state.chooseEglError = state.api.getError();
        state.configChosen = state.configChosen && state.chooseEglError == EGL_SUCCESS;
        if (state.configChosen) state.configAttributesValid = profiles_measure_config(&state);
        (void)profiles_resolve_destroy(&state);
    }
    for (index = 0U; index < PROFILES_ATTEMPT_COUNT; ++index) {
        profiles_init_attempt(&state.attempts[index], index);
        if (state.configAttributesValid && state.destroyContext != NULL) (void)profiles_run_attempt(&state, index);
    }
    if (state.libraryLoaded && state.loaderCanonical[0] != '\0') {
        (void)canonical_path(state.paths.vendor, state.vendorCanonical, sizeof(state.vendorCanonical));
        (void)profiles_read_maps(&state);
    }
    state.serverLiveBeforeTerminate = state.identityValid && profiles_server_live(&state.server, state.paths.cwd);
    if (!state.serverLiveBeforeTerminate) ++state.cleanupFailures;
    if (state.display != EGL_NO_DISPLAY && state.api.terminate != NULL) {
        state.terminateAttempted = 1;
        state.terminateOk = state.api.terminate(state.display) != 0U;
        state.terminateError = state.api.getError();
        if (!state.terminateOk || state.terminateError != EGL_SUCCESS) ++state.cleanupFailures;
    }
    if (state.handle != NULL) {
        state.handleClosed = dlclose(state.handle) == 0;
        if (!state.handleClosed) ++state.cleanupFailures;
    }
    clockOk = 0;
    state.finishedNs = profiles_clock_ns(&clockOk);
    if (!clockOk || state.finishedNs < state.startedNs || state.finishedNs - state.startedNs > PROFILES_MAX_TIME_NS)
        state.deadlineValid = 0;
    if (!profiles_build_output(&state, runId, identity, output, sizeof(output))) {
        state.outputFailure = 1;
        (void)snprintf(output, sizeof(output), "{\"schema\":\"%s\",\"runId\":\"%s\",\"status\":\"UNKNOWN\",\"nativeExit\":1}\n", PROFILES_SCHEMA, runId);
    }
    if (fputs(output, stdout) == EOF || fflush(stdout) == EOF || ferror(stdout) != 0) state.outputFailure = 1;
    return !state.outputFailure && profiles_matrix_measured(1, 1, 1, 1, 1, state.attempts) &&
           state.scopeValid && state.identityValid && state.exportsValid && state.configAttributesValid &&
           state.mapsComplete && state.privateLoaderMapped && state.privateVendorMapped &&
           state.privateGalliumMapped && state.privateLlvmMapped && !state.androidEglMapped &&
           !state.androidGlesMapped && state.cleanupFailures == 0 && state.deadlineValid ? 0 : 1;
}

#ifndef GUEST_PROFILES_TESTING
int main(int argc, char **argv) {
    Identity identity;
    if (argc != 3 || argv == NULL || argv[1] == NULL || argv[2] == NULL ||
        strcmp(argv[1], "--run-id") != 0 || !valid_run_id(argv[2])) return 2;
    memset(&identity, 0, sizeof(identity));
    identity.pid = getpid();
    identity.uid = getuid();
    identity.pgid = getpgid(0);
    identity.sid = getsid(0);
    return profiles_run(argv[2], &identity);
}
#endif
