#define WIN32_LEAN_AND_MEAN
#define COBJMACROS
#define INITGUID
#include <windows.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <dxgi.h>
#include <dxgi1_2.h>
#include <d3d11.h>
#include <d3dcompiler.h>
#include <vulkan/vulkan.h>

#ifndef READBACK_VARIANT
#define READBACK_VARIANT 0
#endif

/*
 * This is intentionally a C-only, dynamically linked diagnostic.  In
 * particular, it does not link against d3d11, dxgi, d3dcompiler, or Vulkan:
 * those DLLs are the Windows/Wine interface being measured, not part of the
 * probe.  The Vulkan declarations below are therefore the declarations from
 * the pinned Khronos header, including the complete pNext structures.
 */

#define PROBE_SCHEMA 1
#define MAX_LINE 8192
#define MAX_RUN 100
#define MAX_PATH_TEXT 260
#define MAX_MODULES 4
#define MAX_FRAMES 30
#define MAX_ADAPTERS 16
#define MAX_EXTENSIONS 512
#define MAX_PROBE_MS 30000

typedef struct ProbeLog {
    HANDLE file;
    HANDLE output;
    char run_id[MAX_RUN + 1];
    int file_ok;
    int output_ok;
    int io_failed;
} ProbeLog;

typedef struct ModuleInfo {
    const wchar_t *requested;
    HMODULE handle;
    char name[64];
    char version[48];
    FARPROC captured_proc;
    DWORD load_error;
    DWORD proc_error;
    int load_error_valid;
    int proc_error_valid;
} ModuleInfo;

typedef struct VulkanState {
    HMODULE loader;
    const char *loader_name;
    VkInstance instance;
    VkPhysicalDevice physical_device;
    VkResult create_result;
    uint32_t api_version;
    uint32_t vendor_id;
    uint32_t device_id;
    uint32_t driver_version;
    char device_name[256];
    int available;
    int feature_query_supported;
    int extension_transform_feedback;
    int extension_robustness2;
    int extension_properties2;
    int extensions_complete;
    VkPhysicalDeviceFeatures base_features;
    VkPhysicalDeviceVulkan11Features features11;
    VkPhysicalDeviceShaderDrawParametersFeatures draw_parameters;
    VkPhysicalDeviceVulkan12Features features12;
    VkPhysicalDeviceVulkan13Features features13;
    VkPhysicalDeviceTransformFeedbackFeaturesEXT features_transform_feedback;
    VkPhysicalDeviceRobustness2FeaturesEXT features_robustness2;
} VulkanState;

typedef VkResult (VKAPI_PTR *ProbeVkCreateInstance)(const VkInstanceCreateInfo *, const VkAllocationCallbacks *, VkInstance *);
typedef VkResult (VKAPI_PTR *ProbeVkEnumerateInstanceVersion)(uint32_t *);
typedef VkResult (VKAPI_PTR *ProbeVkEnumeratePhysicalDevices)(VkInstance, uint32_t *, VkPhysicalDevice *);
typedef void (VKAPI_PTR *ProbeVkGetPhysicalDeviceProperties)(VkPhysicalDevice, VkPhysicalDeviceProperties *);
typedef void (VKAPI_PTR *ProbeVkGetPhysicalDeviceFeatures)(VkPhysicalDevice, VkPhysicalDeviceFeatures *);
typedef void (VKAPI_PTR *ProbeVkGetPhysicalDeviceFeatures2)(VkPhysicalDevice, VkPhysicalDeviceFeatures2 *);
typedef VkResult (VKAPI_PTR *ProbeVkEnumerateDeviceExtensionProperties)(VkPhysicalDevice, const char *, uint32_t *, VkExtensionProperties *);
typedef void (VKAPI_PTR *ProbeVkDestroyInstance)(VkInstance, const VkAllocationCallbacks *);

typedef HRESULT (WINAPI *ProbeD3D11CreateDevice)(IDXGIAdapter *, D3D_DRIVER_TYPE, HMODULE, UINT, const D3D_FEATURE_LEVEL *, UINT, UINT, ID3D11Device **, D3D_FEATURE_LEVEL *, ID3D11DeviceContext **);
typedef HRESULT (WINAPI *ProbeCreateDXGIFactory1)(REFIID, void **);
typedef HRESULT (WINAPI *ProbeD3DCompile)(const void *, SIZE_T, const char *, const D3D_SHADER_MACRO *, ID3DInclude *, const char *, const char *, UINT, UINT, ID3DBlob **, ID3DBlob **);

static ULONGLONG probe_started;
static const char *d3d_status = "UNKNOWN";
static int within_budget(void) { return GetTickCount64() - probe_started < MAX_PROBE_MS; }

static int ascii_token(const char *value) {
    size_t i;
    if (value == NULL || value[0] == '\0' || strlen(value) > MAX_RUN) return 0;
    if (!((value[0] >= 'A' && value[0] <= 'Z') || (value[0] >= 'a' && value[0] <= 'z') || (value[0] >= '0' && value[0] <= '9'))) return 0;
    for (i = 1; value[i] != '\0'; ++i) {
        char c = value[i];
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.')) return 0;
    }
    return 1;
}

static int valid_log_path(const char *path, const char *run_id) {
    char expected[MAX_PATH_TEXT];
    size_t i;
    int n = snprintf(expected, sizeof(expected), "A:\\Logs\\%s-graphics-probe.jsonl", run_id);
    if (n <= 0 || (size_t)n >= sizeof(expected) || strcmp(path, expected) != 0) return 0;
    if (strlen(path) >= MAX_PATH_TEXT) return 0;
    for (i = 0; path[i] != '\0'; ++i) {
        unsigned char c = (unsigned char)path[i];
        if (c < 0x20 || c >= 0x7f || c == '"' || c == '\'') return 0;
    }
    return 1;
}

static void json_quote_ascii(char *out, size_t capacity, const char *value) {
    size_t at = 0;
    size_t i;
    if (capacity == 0) return;
    out[at++] = '"';
    for (i = 0; value != NULL && value[i] != '\0' && at + 6 < capacity; ++i) {
        unsigned char c = (unsigned char)value[i];
        if (c == '"' || c == '\\') {
            out[at++] = '\\';
            out[at++] = (char)c;
        } else if (c == '\n') {
            out[at++] = '\\'; out[at++] = 'n';
        } else if (c == '\r') {
            out[at++] = '\\'; out[at++] = 'r';
        } else if (c == '\t') {
            out[at++] = '\\'; out[at++] = 't';
        } else if (c < 0x20 || c >= 0x7f) {
            int written = snprintf(out + at, capacity - at, "\\u%04X", (unsigned)c);
            if (written < 0 || (size_t)written >= capacity - at) break;
            at += (size_t)written;
        } else {
            out[at++] = (char)c;
        }
    }
    if (at + 2 <= capacity) { out[at++] = '"'; out[at] = '\0'; }
    else out[capacity - 1] = '\0';
}

static void json_quote_wide(char *out, size_t capacity, const wchar_t *value) {
    size_t at = 0;
    size_t i;
    if (capacity == 0) return;
    out[at++] = '"';
    for (i = 0; value != NULL && value[i] != L'\0' && at + 8 < capacity; ++i) {
        uint32_t c = (uint32_t)value[i];
        if (c == '"' || c == '\\') { out[at++] = '\\'; out[at++] = (char)c; }
        else if (c == '\n') { out[at++] = '\\'; out[at++] = 'n'; }
        else if (c == '\r') { out[at++] = '\\'; out[at++] = 'r'; }
        else if (c < 0x20 || c > 0x7e) {
            int written = snprintf(out + at, capacity - at, "\\u%04X", (unsigned)(c & 0xffffu));
            if (written < 0 || (size_t)written >= capacity - at) break;
            at += (size_t)written;
        } else out[at++] = (char)c;
    }
    if (at + 2 <= capacity) { out[at++] = '"'; out[at] = '\0'; }
    else out[capacity - 1] = '\0';
}

static int write_all(HANDLE handle, const char *data, DWORD length) {
    DWORD written = 0;
    return handle != INVALID_HANDLE_VALUE && handle != NULL && WriteFile(handle, data, length, &written, NULL) && written == length;
}

static int log_event(ProbeLog *log, const char *event, const char *fields) {
    char line[MAX_LINE];
    char run_json[MAX_RUN + 8];
    int length;
    json_quote_ascii(run_json, sizeof(run_json), log->run_id);
    length = snprintf(line, sizeof(line), "{\"schema\":%d,\"runId\":%s,\"event\":\"%s\"%s}\n", PROBE_SCHEMA, run_json, event, fields != NULL ? fields : "");
    if (length <= 0 || (size_t)length >= sizeof(line)) {
        log->io_failed = 1;
        return 0;
    }
    if (!write_all(log->file, line, (DWORD)length) || !FlushFileBuffers(log->file)) log->file_ok = 0;
    if (!write_all(log->output, line, (DWORD)length)) log->output_ok = 0;
    if (!log->file_ok) log->io_failed = 1;
    return log->file_ok;
}

static const char *bool_text(int known, VkBool32 value) {
    if (!known) return "\"unknown\"";
    return value == VK_TRUE ? "true" : "false";
}

static void hresult_text(char *out, size_t capacity, HRESULT value) {
    snprintf(out, capacity, "0x%08lX", (unsigned long)(uint32_t)value);
}

static void log_present_timeout(ProbeLog *log, unsigned index) {
    char fields[256];
    snprintf(fields, sizeof(fields), ",\"index\":%u,\"result\":\"TIMEOUT\",\"successful\":false,\"deviceRemovedReason\":\"NOT_QUERIED_TIMEOUT\",\"elapsedMs\":%llu", index, (unsigned long long)(GetTickCount64() - probe_started));
    log_event(log, "present", fields);
}

static void vkresult_text(char *out, size_t capacity, VkResult value) {
    snprintf(out, capacity, "%ld", (long)value);
}

static void module_basename(char *out, size_t capacity, const wchar_t *path) {
    const wchar_t *start = path;
    size_t i;
    if (path != NULL && path[0] != L'\0') {
        start = path;
        for (i = 0; path[i] != L'\0'; ++i) if (path[i] == L'\\' || path[i] == L'/') start = path + i + 1;
    }
    if (start == NULL) start = L"unknown.dll";
    json_quote_wide(out, capacity, start);
}

static void module_version(char *out, size_t capacity, HMODULE module) {
    typedef DWORD (WINAPI *GetFileVersionInfoSizeWFn)(LPCWSTR, LPDWORD);
    typedef BOOL (WINAPI *GetFileVersionInfoWFn)(LPCWSTR, DWORD, DWORD, LPVOID);
    typedef BOOL (WINAPI *VerQueryValueWFn)(LPCVOID, LPCWSTR, LPVOID *, PUINT);
    static const wchar_t root[] = L"\\";
    HMODULE version_dll;
    GetFileVersionInfoSizeWFn size_fn;
    GetFileVersionInfoWFn info_fn;
    VerQueryValueWFn query_fn;
    wchar_t path[MAX_PATH_TEXT];
    BYTE buffer[65536];
    VS_FIXEDFILEINFO *fixed = NULL;
    UINT fixed_size = 0;
    DWORD size;
    DWORD path_size;
    if (module == NULL) { strcpy(out, "\"unavailable\""); return; }
    path_size = GetModuleFileNameW(module, path, (DWORD)(sizeof(path) / sizeof(path[0])));
    if (path_size == 0 || path_size >= sizeof(path) / sizeof(path[0])) { strcpy(out, "\"unavailable\""); return; }
    version_dll = LoadLibraryW(L"version.dll");
    if (version_dll == NULL) { strcpy(out, "\"unavailable\""); return; }
    size_fn = (GetFileVersionInfoSizeWFn)GetProcAddress(version_dll, "GetFileVersionInfoSizeW");
    info_fn = (GetFileVersionInfoWFn)GetProcAddress(version_dll, "GetFileVersionInfoW");
    query_fn = (VerQueryValueWFn)GetProcAddress(version_dll, "VerQueryValueW");
    size = size_fn != NULL ? size_fn(path, NULL) : 0;
    if (size == 0 || size > sizeof(buffer) || info_fn == NULL || query_fn == NULL || !info_fn(path, 0, sizeof(buffer), buffer) || !query_fn(buffer, root, (LPVOID *)&fixed, &fixed_size) || fixed == NULL || fixed_size < sizeof(*fixed)) {
        FreeLibrary(version_dll);
        strcpy(out, "\"unavailable\"");
        return;
    }
    snprintf(out, capacity, "\"%u.%u.%u.%u\"", HIWORD(fixed->dwFileVersionMS), LOWORD(fixed->dwFileVersionMS), HIWORD(fixed->dwFileVersionLS), LOWORD(fixed->dwFileVersionLS));
    FreeLibrary(version_dll);
}

static void record_module(char *out, size_t capacity, ModuleInfo *module) {
    char name[192], path_json[700];
    wchar_t path[MAX_PATH_TEXT];
    DWORD length = module->handle != NULL ? GetModuleFileNameW(module->handle, path, ARRAYSIZE(path)) : 0;
    if (length != 0 && length < ARRAYSIZE(path)) {
        module_basename(name, sizeof(name), path);
        json_quote_wide(path_json, sizeof(path_json), path);
    } else {
        strcpy(path_json, "null");
        if (module->handle != NULL) module_basename(name, sizeof(name), module->requested);
        else strcpy(name, "null");
    }
    if (module->handle != NULL) module_version(module->version, sizeof(module->version), module->handle);
    else strcpy(module->version, "\"unavailable\"");
    snprintf(out, capacity, "{\"name\":%s,\"path\":%s,\"loaded\":%s,\"version\":%s,\"hash\":\"unavailable\"}", name, path_json, module->handle != NULL ? "true" : "false", module->version);
}

static void win32_error_json(char *out, size_t capacity, int valid, DWORD value) {
    if (!valid) strcpy(out, "null");
    else snprintf(out, capacity, "%lu", (unsigned long)value);
}

static void compiler_load_fields(char *out, size_t capacity, const ModuleInfo *module) {
    char load_error[24];
    char proc_error[24];
    win32_error_json(load_error, sizeof(load_error), module->load_error_valid, module->load_error);
    win32_error_json(proc_error, sizeof(proc_error), module->proc_error_valid, module->proc_error);
    snprintf(out, capacity, ",\"moduleName\":\"d3dcompiler_47.dll\",\"loaded\":%s,\"getProcFound\":%s,\"loadError\":%s,\"procError\":%s", module->handle != NULL ? "true" : "false", module->captured_proc != NULL ? "true" : "false", load_error, proc_error);
}

static void *load_proc(HMODULE module, const char *name) {
    return module == NULL ? NULL : (void *)GetProcAddress(module, name);
}

static int has_extension(const VkExtensionProperties *extensions, uint32_t count, const char *name) {
    uint32_t i;
    for (i = 0; i < count; ++i) if (strcmp(extensions[i].extensionName, name) == 0) return 1;
    return 0;
}

static int api_at_least(uint32_t version, uint32_t major, uint32_t minor) {
    return VK_VERSION_MAJOR(version) > major || (VK_VERSION_MAJOR(version) == major && VK_VERSION_MINOR(version) >= minor);
}

static void run_vulkan(ProbeLog *log, VulkanState *state, ModuleInfo *winevulkan, ModuleInfo *fallback) {
    ProbeVkCreateInstance create_instance;
    ProbeVkEnumerateInstanceVersion enumerate_version;
    ProbeVkEnumeratePhysicalDevices enumerate_devices;
    ProbeVkGetPhysicalDeviceProperties get_properties;
    ProbeVkGetPhysicalDeviceFeatures get_features;
    ProbeVkGetPhysicalDeviceFeatures2 get_features2;
    ProbeVkEnumerateDeviceExtensionProperties enumerate_extensions;
    ProbeVkDestroyInstance destroy_instance;
    PFN_vkGetInstanceProcAddr get_instance_proc;
    VkApplicationInfo application_info;
    VkInstanceCreateInfo instance_info;
    VkPhysicalDevice devices[4];
    VkExtensionProperties extensions[MAX_EXTENSIONS];
    VkPhysicalDeviceProperties properties;
    uint32_t instance_api = VK_API_VERSION_1_0;
    uint32_t device_count = 4;
    uint32_t extension_count = MAX_EXTENSIONS;
    uint32_t reported_extension_count = 0;
    VkResult result;
    char result_text[32];
    char name_json[520];
    char fields[7000];
    char *tail;
    int extension_truncated = 0;

    memset(state, 0, sizeof(*state));
    state->loader = winevulkan->handle;
    state->loader_name = winevulkan->handle != NULL ? "winevulkan.dll" : "vulkan-1.dll";
    if (state->loader == NULL) state->loader = fallback->handle;
    get_instance_proc = (PFN_vkGetInstanceProcAddr)load_proc(state->loader, "vkGetInstanceProcAddr");
    if (get_instance_proc == NULL) {
        log_event(log, "vulkan_caps", ",\"status\":\"UNKNOWN\",\"loader\":\"unavailable\",\"featureQueryFunction\":\"unsupported\",\"reason\":\"vkGetInstanceProcAddr unavailable\"");
        return;
    }
    enumerate_version = (ProbeVkEnumerateInstanceVersion)get_instance_proc(NULL, "vkEnumerateInstanceVersion");
    if (enumerate_version != NULL && enumerate_version(&instance_api) != VK_SUCCESS) instance_api = VK_API_VERSION_1_0;
    create_instance = (ProbeVkCreateInstance)get_instance_proc(NULL, "vkCreateInstance");
    if (create_instance == NULL) {
        snprintf(fields, sizeof(fields), ",\"status\":\"UNKNOWN\",\"loader\":\"%s\",\"featureQueryFunction\":\"unsupported\",\"reason\":\"vkCreateInstance unavailable\"", state->loader_name);
        log_event(log, "vulkan_caps", fields);
        return;
    }
    memset(&application_info, 0, sizeof(application_info));
    application_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application_info.pApplicationName = "GraphicsProbe-ARM64";
    application_info.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    application_info.pEngineName = "GraphicsProbe";
    application_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    /* Match DXVK's instance API request, not the Android native inventory.
     * Failed creation is explicit UNKNOWN; never advertise a fake API version. */
    application_info.apiVersion = VK_API_VERSION_1_3;
    memset(&instance_info, 0, sizeof(instance_info));
    instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.pApplicationInfo = &application_info;
    result = create_instance(&instance_info, NULL, &state->instance);
    state->create_result = result;
    vkresult_text(result_text, sizeof(result_text), result);
    if (result != VK_SUCCESS || state->instance == VK_NULL_HANDLE) {
        snprintf(fields, sizeof(fields), ",\"status\":\"UNKNOWN\",\"loader\":\"%s\",\"instanceApiVersion\":%u,\"requestedApiVersion\":%u,\"vkCreateInstanceResult\":%s,\"featureQueryFunction\":\"unknown\"", state->loader_name, instance_api, VK_API_VERSION_1_3, result_text);
        log_event(log, "vulkan_caps", fields);
        return;
    }
    enumerate_devices = (ProbeVkEnumeratePhysicalDevices)get_instance_proc(state->instance, "vkEnumeratePhysicalDevices");
    get_properties = (ProbeVkGetPhysicalDeviceProperties)get_instance_proc(state->instance, "vkGetPhysicalDeviceProperties");
    get_features = (ProbeVkGetPhysicalDeviceFeatures)get_instance_proc(state->instance, "vkGetPhysicalDeviceFeatures");
    get_features2 = (ProbeVkGetPhysicalDeviceFeatures2)get_instance_proc(state->instance, "vkGetPhysicalDeviceFeatures2");
    enumerate_extensions = (ProbeVkEnumerateDeviceExtensionProperties)get_instance_proc(state->instance, "vkEnumerateDeviceExtensionProperties");
    destroy_instance = (ProbeVkDestroyInstance)get_instance_proc(state->instance, "vkDestroyInstance");
    result = enumerate_devices != NULL ? enumerate_devices(state->instance, &device_count, devices) : VK_ERROR_INITIALIZATION_FAILED;
    if (enumerate_devices == NULL || get_properties == NULL || get_features == NULL || enumerate_extensions == NULL || (result != VK_SUCCESS && result != VK_INCOMPLETE) || device_count == 0) {
        snprintf(fields, sizeof(fields), ",\"status\":\"UNKNOWN\",\"loader\":\"%s\",\"instanceApiVersion\":%u,\"featureQueryFunction\":\"%s\",\"reason\":\"physical device enumeration unavailable\"", state->loader_name, instance_api, get_features2 != NULL ? "supported" : "unsupported");
        log_event(log, "vulkan_caps", fields);
        if (destroy_instance != NULL) destroy_instance(state->instance, NULL);
        state->instance = VK_NULL_HANDLE;
        return;
    }
    state->physical_device = devices[0];
    state->available = 1;
    if (get_properties != NULL) get_properties(state->physical_device, &properties);
    state->api_version = properties.apiVersion;
    state->vendor_id = properties.vendorID;
    state->device_id = properties.deviceID;
    state->driver_version = properties.driverVersion;
    json_quote_ascii(name_json, sizeof(name_json), properties.deviceName);
    snprintf(state->device_name, sizeof(state->device_name), "%s", name_json);
    if (get_features != NULL) get_features(state->physical_device, &state->base_features);
    result = enumerate_extensions(state->physical_device, NULL, &reported_extension_count, NULL);
    if (result == VK_SUCCESS && reported_extension_count > 0) {
        extension_count = reported_extension_count < MAX_EXTENSIONS ? reported_extension_count : MAX_EXTENSIONS;
        result = enumerate_extensions(state->physical_device, NULL, &extension_count, extensions);
        state->extensions_complete = result == VK_SUCCESS && reported_extension_count <= MAX_EXTENSIONS;
        extension_truncated = !state->extensions_complete;
        if (result != VK_SUCCESS && result != VK_INCOMPLETE) extension_count = 0;
        if (extension_count > MAX_EXTENSIONS) extension_count = MAX_EXTENSIONS;
    } else {
        extension_count = 0;
        state->extensions_complete = result == VK_SUCCESS;
        extension_truncated = !state->extensions_complete;
    }
    state->extension_transform_feedback = has_extension(extensions, extension_count, VK_EXT_TRANSFORM_FEEDBACK_EXTENSION_NAME);
    state->extension_robustness2 = has_extension(extensions, extension_count, VK_EXT_ROBUSTNESS_2_EXTENSION_NAME);
    /* properties2 is core for our successfully created 1.3 instance, not a
     * device extension. Extended device structs must also match device API. */
    state->extension_properties2 = 1;
    state->feature_query_supported = get_features2 != NULL;
    memset(&state->features11, 0, sizeof(state->features11)); state->features11.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
    memset(&state->draw_parameters, 0, sizeof(state->draw_parameters)); state->draw_parameters.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_DRAW_PARAMETERS_FEATURES;
    memset(&state->features12, 0, sizeof(state->features12)); state->features12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
    memset(&state->features13, 0, sizeof(state->features13)); state->features13.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;
    memset(&state->features_transform_feedback, 0, sizeof(state->features_transform_feedback)); state->features_transform_feedback.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TRANSFORM_FEEDBACK_FEATURES_EXT;
    memset(&state->features_robustness2, 0, sizeof(state->features_robustness2)); state->features_robustness2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT;
    if (state->feature_query_supported) {
        VkBaseOutStructure *last = NULL;
        VkPhysicalDeviceFeatures2 features2;
        memset(&features2, 0, sizeof(features2)); features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        last = (VkBaseOutStructure *)&features2;
        /* Vulkan11Features was introduced in API 1.2. Query the standalone
         * shaderDrawParameters structure for a 1.1 device. */
        if (api_at_least(state->api_version, 1, 2)) { last->pNext = (VkBaseOutStructure *)&state->features11; last = (VkBaseOutStructure *)&state->features11; }
        else if (api_at_least(state->api_version, 1, 1)) { last->pNext = (VkBaseOutStructure *)&state->draw_parameters; last = (VkBaseOutStructure *)&state->draw_parameters; }
        if (api_at_least(state->api_version, 1, 2)) { last->pNext = (VkBaseOutStructure *)&state->features12; last = (VkBaseOutStructure *)&state->features12; }
        if (api_at_least(state->api_version, 1, 3)) { last->pNext = (VkBaseOutStructure *)&state->features13; last = (VkBaseOutStructure *)&state->features13; }
        if (state->extension_transform_feedback) { last->pNext = (VkBaseOutStructure *)&state->features_transform_feedback; last = (VkBaseOutStructure *)&state->features_transform_feedback; }
        if (state->extension_robustness2) { last->pNext = (VkBaseOutStructure *)&state->features_robustness2; last = (VkBaseOutStructure *)&state->features_robustness2; }
        last->pNext = NULL;
        get_features2(state->physical_device, &features2);
        state->base_features = features2.features;
        if (!api_at_least(state->api_version, 1, 2)) state->features11.shaderDrawParameters = state->draw_parameters.shaderDrawParameters;
    }
    tail = fields;
    tail += snprintf(tail, sizeof(fields), ",\"status\":\"MEASURED\",\"loader\":\"%s\",\"instanceApiVersion\":%u,\"deviceApiVersion\":%u,\"vendorId\":%u,\"deviceId\":%u,\"driverVersion\":%u,\"deviceName\":%s,\"extensionCount\":%u,\"extensionListTruncated\":%s,\"featureQueryFunction\":\"%s\",\"featureQueryApi\":\"%s\",\"structAbi\":\"exact-header-definitions\",\"sizes\":{\"vulkan11\":%zu,\"vulkan12\":%zu,\"vulkan13\":%zu,\"transformFeedback\":%zu,\"robustness2\":%zu}", state->loader_name, instance_api, state->api_version, state->vendor_id, state->device_id, state->driver_version, name_json, reported_extension_count, extension_truncated ? "true" : "false", get_features2 != NULL ? "supported" : "unsupported", state->feature_query_supported ? "supported" : "unsupported", sizeof(state->features11), sizeof(state->features12), sizeof(state->features13), sizeof(state->features_transform_feedback), sizeof(state->features_robustness2));
    tail += snprintf(tail, sizeof(fields) - (size_t)(tail - fields), ",\"requestedApiVersion\":%u,\"extensions\":{\"transformFeedback\":%s,\"robustness2\":%s,\"properties2Core\":true}", VK_API_VERSION_1_3, bool_text(state->extensions_complete, state->extension_transform_feedback), bool_text(state->extensions_complete, state->extension_robustness2));
    tail += snprintf(tail, sizeof(fields) - (size_t)(tail - fields), ",\"features\":{\"fillModeNonSolid\":%s,\"shaderClipDistance\":%s,\"textureCompressionBC\":%s,\"geometryShader\":%s,\"tessellationShader\":%s,\"sampleRateShading\":%s,\"robustBufferAccess\":%s,\"shaderDrawParameters\":%s,\"samplerMirrorClampToEdge\":%s,\"shaderDemoteToHelperInvocation\":%s,\"synchronization2\":%s,\"dynamicRendering\":%s,\"transformFeedback\":%s,\"geometryStreams\":%s,\"robustBufferAccess2\":%s,\"nullDescriptor\":%s}", bool_text(1, state->base_features.fillModeNonSolid), bool_text(1, state->base_features.shaderClipDistance), bool_text(1, state->base_features.textureCompressionBC), bool_text(1, state->base_features.geometryShader), bool_text(1, state->base_features.tessellationShader), bool_text(1, state->base_features.sampleRateShading), bool_text(1, state->base_features.robustBufferAccess), bool_text(api_at_least(state->api_version, 1, 1) && state->feature_query_supported, state->features11.shaderDrawParameters), bool_text(api_at_least(state->api_version, 1, 2) && state->feature_query_supported, state->features12.samplerMirrorClampToEdge), bool_text(api_at_least(state->api_version, 1, 3) && state->feature_query_supported, state->features13.shaderDemoteToHelperInvocation), bool_text(api_at_least(state->api_version, 1, 3) && state->feature_query_supported, state->features13.synchronization2), bool_text(api_at_least(state->api_version, 1, 3) && state->feature_query_supported, state->features13.dynamicRendering), bool_text(state->extension_transform_feedback && state->feature_query_supported, state->features_transform_feedback.transformFeedback), bool_text(state->extension_transform_feedback && state->feature_query_supported, state->features_transform_feedback.geometryStreams), bool_text(state->extension_robustness2 && state->feature_query_supported, state->features_robustness2.robustBufferAccess2), bool_text(state->extension_robustness2 && state->feature_query_supported, state->features_robustness2.nullDescriptor));
    log_event(log, "vulkan_caps", fields);
    if (destroy_instance != NULL) destroy_instance(state->instance, NULL);
    state->instance = VK_NULL_HANDLE;
}

static LRESULT CALLBACK probe_window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    (void)wparam; (void)lparam;
    return DefWindowProcW(window, message, wparam, lparam);
}

static const char *feature_level_text(D3D_FEATURE_LEVEL level) {
    switch (level) {
        case D3D_FEATURE_LEVEL_11_1: return "11_1";
        case D3D_FEATURE_LEVEL_11_0: return "11_0";
        case D3D_FEATURE_LEVEL_10_1: return "10_1";
        case D3D_FEATURE_LEVEL_10_0: return "10_0";
        case D3D_FEATURE_LEVEL_9_3: return "9_3";
        case D3D_FEATURE_LEVEL_9_2: return "9_2";
        case D3D_FEATURE_LEVEL_9_1: return "9_1";
        default: return "0";
    }
}

static const char *shader_profile(D3D_FEATURE_LEVEL level, int vertex) {
    if (level >= D3D_FEATURE_LEVEL_11_0) return vertex ? "vs_5_0" : "ps_5_0";
    if (level == D3D_FEATURE_LEVEL_10_1) return vertex ? "vs_4_1" : "ps_4_1";
    if (level == D3D_FEATURE_LEVEL_10_0) return vertex ? "vs_4_0" : "ps_4_0";
    return level == D3D_FEATURE_LEVEL_9_3 ? (vertex ? "vs_4_0_level_9_3" : "ps_4_0_level_9_3") : (vertex ? "vs_4_0_level_9_1" : "ps_4_0_level_9_1");
}

static void blob_error(char *out, size_t capacity, ID3DBlob *blob) {
    const unsigned char *data;
    SIZE_T size;
    size_t at = 0;
    SIZE_T i;
    if (blob == NULL) { strcpy(out, "\"none\""); return; }
    data = (const unsigned char *)ID3D10Blob_GetBufferPointer(blob);
    size = ID3D10Blob_GetBufferSize(blob);
    if (capacity < 3) { if (capacity > 0) out[0] = '\0'; return; }
    out[at++] = '"';
    for (i = 0; i < size && i < 768 && at + 8 < capacity; ++i) {
        unsigned char c = data[i];
        if (c == '"' || c == '\\') { out[at++] = '\\'; out[at++] = (char)c; }
        else if (c == '\n') { out[at++] = '\\'; out[at++] = 'n'; }
        else if (c >= 0x20 && c < 0x7f) out[at++] = (char)c;
        else out[at++] = '.';
    }
    out[at++] = '"'; out[at] = '\0';
}

static int make_window(HWND *window, HINSTANCE instance) {
    WNDCLASSW klass;
    memset(&klass, 0, sizeof(klass));
    klass.lpfnWndProc = probe_window_proc;
    klass.hInstance = instance;
    klass.lpszClassName = L"GraphicsProbeWindow";
    klass.hCursor = LoadCursorW(NULL, MAKEINTRESOURCEW(32512));
    if (RegisterClassW(&klass) == 0 && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) return 0;
    *window = CreateWindowExW(WS_EX_APPWINDOW, klass.lpszClassName, L"GraphicsProbe", WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 320, 240, NULL, NULL, instance, NULL);
    if (*window == NULL) return 0;
    ShowWindow(*window, SW_SHOW);
    UpdateWindow(*window);
    return 1;
}

typedef struct ReadbackSummary {
    int offscreen_clear;
    int offscreen_draw;
    int backbuffer_clear;
    int backbuffer_draw;
} ReadbackSummary;

#if !READBACK_VARIANT
/* Verify real rendered pixels rather than treating the void Draw call as proof.
 * The staging copy is tiny (320x240 RGBA), never a game-memory capture. */
static int verify_triangle(ProbeLog *log, ID3D11Device *device, ID3D11DeviceContext *context, ID3D11Texture2D *back_buffer) {
    D3D11_TEXTURE2D_DESC desc;
    D3D11_MAPPED_SUBRESOURCE mapped;
    ID3D11Texture2D *staging = NULL;
    HRESULT hr;
    unsigned colored = 0, x, y;
    uint32_t hash = 2166136261u;
    char fields[512], result[32];
    ID3D11Texture2D_GetDesc(back_buffer, &desc);
    if (desc.Width != 320 || desc.Height != 240 || desc.Format != DXGI_FORMAT_R8G8B8A8_UNORM || desc.SampleDesc.Count != 1) return 0;
    desc.Usage = D3D11_USAGE_STAGING; desc.BindFlags = 0; desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ; desc.MiscFlags = 0;
    hr = ID3D11Device_CreateTexture2D(device, &desc, NULL, &staging);
    if (SUCCEEDED(hr) && staging != NULL) {
        ID3D11DeviceContext_CopyResource(context, (ID3D11Resource *)staging, (ID3D11Resource *)back_buffer);
        hr = ID3D11DeviceContext_Map(context, (ID3D11Resource *)staging, 0, D3D11_MAP_READ, 0, &mapped);
        if (hr == S_OK) {
            if (mapped.pData != NULL && mapped.RowPitch >= desc.Width * 4) {
                for (y = 0; y < desc.Height; ++y) {
                    const unsigned char *row = (const unsigned char *)mapped.pData + y * mapped.RowPitch;
                    for (x = 0; x < desc.Width * 4; ++x) { hash ^= row[x]; hash *= 16777619u; }
                    for (x = 0; x < desc.Width; ++x) {
                        const unsigned char *p = row + x * 4;
                        if (p[3] >= 200 && (p[0] >= 40 || p[1] >= 40 || p[2] >= 40)) ++colored;
                    }
                }
            }
            ID3D11DeviceContext_Unmap(context, (ID3D11Resource *)staging, 0);
        }
    }
    if (staging != NULL) ID3D11Texture2D_Release(staging);
    hresult_text(result, sizeof(result), hr);
    snprintf(fields, sizeof(fields), ",\"status\":\"%s\",\"result\":\"%s\",\"coloredPixels\":%u,\"rgbaFnv1a\":%u,\"width\":320,\"height\":240", hr == S_OK && colored >= 128 ? "VERIFIED" : "FAILED", result, colored, hash);
    log_event(log, "triangle_readback", fields);
    return hr == S_OK && colored >= 128;
}
#endif

#if READBACK_VARIANT
static void json_sample(char *out, size_t capacity, const unsigned char *data, UINT row_pitch, UINT width, UINT height) {
    static const UINT points[][2] = {{0, 0}, {160, 120}, {319, 239}, {80, 60}, {240, 180}};
    size_t at = 0;
    unsigned i;
    at += (size_t)snprintf(out + at, capacity - at, "[");
    for (i = 0; i < ARRAYSIZE(points); ++i) {
        UINT x = points[i][0] < width ? points[i][0] : width - 1;
        UINT y = points[i][1] < height ? points[i][1] : height - 1;
        const unsigned char *pixel = data + y * row_pitch + x * 4;
        at += (size_t)snprintf(out + at, capacity - at,
            "%s{\"x\":%u,\"y\":%u,\"rgba\":[%u,%u,%u,%u]}",
            i == 0 ? "" : ",", x, y, pixel[0], pixel[1], pixel[2], pixel[3]);
        if (at >= capacity) { out[capacity - 1] = '\0'; return; }
    }
    snprintf(out + at, capacity - at, "]");
    (void)height;
}

static void log_readback_timeout(ProbeLog *log, const char *phase) {
    char fields[1024];
    snprintf(fields, sizeof(fields),
        ",\"phase\":\"%s\",\"status\":\"TIMEOUT\",\"textureResult\":\"NOT_ATTEMPTED\",\"rtvResult\":\"NOT_ATTEMPTED\",\"stagingResult\":\"NOT_ATTEMPTED\",\"mapResult\":\"NOT_ATTEMPTED\",\"copyResult\":\"NOT_ATTEMPTED\",\"pDataValid\":false,\"rowPitch\":0,\"width\":0,\"height\":0,\"clearPixels\":0,\"coloredPixels\":0,\"rgbaFnv1a\":0,\"samples\":[],\"deviceRemovedReason\":\"NOT_QUERIED_TIMEOUT\",\"elapsedMs\":%llu",
        phase, (unsigned long long)(GetTickCount64() - probe_started));
    log_event(log, strcmp(phase, "backbuffer_draw") == 0 ? "triangle_readback" : "readback", fields);
}

static int readback_resource(
    ProbeLog *log,
    ID3D11Device *device,
    ID3D11DeviceContext *context,
    ID3D11Resource *resource,
    const char *phase,
    HRESULT texture_result,
    HRESULT rtv_result,
    int expect_clear,
    ReadbackSummary *summary
) {
    D3D11_TEXTURE2D_DESC desc;
    D3D11_TEXTURE2D_DESC staging_desc;
    D3D11_MAPPED_SUBRESOURCE mapped;
    ID3D11Texture2D *staging = NULL;
    HRESULT staging_result = E_FAIL;
    HRESULT map_result = E_FAIL;
    HRESULT removed_reason = E_FAIL;
    unsigned clear_pixels = 0, colored = 0, x, y;
    uint32_t hash = 2166136261u;
    int data_valid = 0;
    int copy_submitted = 0;
    int removed_reason_valid = 0;
    int verified = 0;
    char texture_text[32], rtv_text[32], staging_text[32], map_text[32], removed_text[32];
    char samples[768];
    char fields[4096];

    if (!within_budget()) {
        log_readback_timeout(log, phase);
        return 0;
    }
    removed_reason = ID3D11Device_GetDeviceRemovedReason(device);
    removed_reason_valid = 1;

    memset(&desc, 0, sizeof(desc));
    memset(&staging_desc, 0, sizeof(staging_desc));
    memset(&mapped, 0, sizeof(mapped));
    if (resource != NULL) {
        ID3D11Resource_QueryInterface(resource, &IID_ID3D11Texture2D, (void **)&staging);
        if (staging != NULL) {
            ID3D11Texture2D_GetDesc(staging, &desc);
            ID3D11Texture2D_Release(staging);
            staging = NULL;
        }
    }
    if (resource != NULL && desc.Width == 320 && desc.Height == 240 &&
        desc.Format == DXGI_FORMAT_R8G8B8A8_UNORM && desc.SampleDesc.Count == 1) {
        staging_desc = desc;
        staging_desc.Usage = D3D11_USAGE_STAGING;
        staging_desc.BindFlags = 0;
        staging_desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        staging_desc.MiscFlags = 0;
        if (!within_budget()) {
            log_readback_timeout(log, phase);
            return 0;
        }
        staging_result = ID3D11Device_CreateTexture2D(device, &staging_desc, NULL, &staging);
        if (SUCCEEDED(staging_result) && staging != NULL) {
            /* CopyResource has no HRESULT in D3D11. Record the API's void
             * submission explicitly; absence of this marker is never success. */
            if (within_budget()) {
                ID3D11DeviceContext_CopyResource(context, (ID3D11Resource *)staging, resource);
                copy_submitted = 1;
                if (within_budget()) {
                    map_result = ID3D11DeviceContext_Map(context, (ID3D11Resource *)staging, 0, D3D11_MAP_READ, 0, &mapped);
                }
            }
            if (map_result == S_OK && mapped.pData != NULL && mapped.RowPitch >= 320 * 4) {
                data_valid = 1;
                for (y = 0; y < 240; ++y) {
                    const unsigned char *row = (const unsigned char *)mapped.pData + y * mapped.RowPitch;
                    for (x = 0; x < 320 * 4; ++x) { hash ^= row[x]; hash *= 16777619u; }
                    for (x = 0; x < 320; ++x) {
                        const unsigned char *pixel = row + x * 4;
                        if (pixel[0] >= 8 && pixel[0] <= 12 && pixel[1] >= 8 && pixel[1] <= 12 &&
                            pixel[2] >= 18 && pixel[2] <= 22 && pixel[3] >= 250) ++clear_pixels;
                        if (pixel[3] >= 200 && (pixel[0] >= 40 || pixel[1] >= 40 || pixel[2] >= 40)) ++colored;
                    }
                }
                json_sample(samples, sizeof(samples), (const unsigned char *)mapped.pData, mapped.RowPitch, 320, 240);
                verified = expect_clear ? (clear_pixels == 320 * 240 && colored == 0) : colored >= 128;
            }
            if (map_result == S_OK) ID3D11DeviceContext_Unmap(context, (ID3D11Resource *)staging, 0);
        }
    }
    if (within_budget()) {
        removed_reason = ID3D11Device_GetDeviceRemovedReason(device);
        removed_reason_valid = 1;
    }
    if (!within_budget()) verified = 0;
    hresult_text(texture_text, sizeof(texture_text), texture_result);
    hresult_text(rtv_text, sizeof(rtv_text), rtv_result);
    hresult_text(staging_text, sizeof(staging_text), staging_result);
    hresult_text(map_text, sizeof(map_text), map_result);
    if (removed_reason_valid) hresult_text(removed_text, sizeof(removed_text), removed_reason);
    else strcpy(removed_text, "NOT_QUERIED_TIMEOUT");
    if (!data_valid) strcpy(samples, "[]");
    snprintf(fields, sizeof(fields),
        ",\"phase\":\"%s\",\"status\":\"%s\",\"textureResult\":\"%s\",\"rtvResult\":\"%s\",\"stagingResult\":\"%s\",\"mapResult\":\"%s\",\"copyResult\":\"%s\",\"pDataValid\":%s,\"rowPitch\":%u,\"width\":%u,\"height\":%u,\"clearPixels\":%u,\"coloredPixels\":%u,\"rgbaFnv1a\":%u,\"samples\":%s,\"deviceRemovedReason\":\"%s\",\"elapsedMs\":%llu",
        phase, !within_budget() ? "TIMEOUT" : (verified ? "VERIFIED" : "FAILED"), texture_text, rtv_text, staging_text, map_text,
        copy_submitted ? "VOID_SUBMITTED" : "NOT_ATTEMPTED", data_valid ? "true" : "false",
        data_valid ? mapped.RowPitch : 0, data_valid ? 320 : 0, data_valid ? 240 : 0,
        clear_pixels, colored, hash, samples, removed_text,
        (unsigned long long)(GetTickCount64() - probe_started));
    log_event(log, strcmp(phase, "backbuffer_draw") == 0 ? "triangle_readback" : "readback", fields);
    if (summary != NULL) {
        if (strcmp(phase, "offscreen_clear") == 0) summary->offscreen_clear = verified;
        else if (strcmp(phase, "offscreen_draw") == 0) summary->offscreen_draw = verified;
        else if (strcmp(phase, "backbuffer_clear") == 0) summary->backbuffer_clear = verified;
        else if (strcmp(phase, "backbuffer_draw") == 0) summary->backbuffer_draw = verified;
    }
    if (staging != NULL) ID3D11Texture2D_Release(staging);
    return verified;
}
#endif

static int run_d3d(ProbeLog *log, VulkanState *vulkan, ModuleInfo *dxgi_module, ModuleInfo *d3d11_module, ModuleInfo *compiler_module, unsigned *present_count, ReadbackSummary *readback_summary) {
    static const D3D_FEATURE_LEVEL requested[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0, D3D_FEATURE_LEVEL_9_3, D3D_FEATURE_LEVEL_9_2, D3D_FEATURE_LEVEL_9_1};
    static const D3D_FEATURE_LEVEL retry_requested[] = {D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0, D3D_FEATURE_LEVEL_9_3, D3D_FEATURE_LEVEL_9_2, D3D_FEATURE_LEVEL_9_1};
    static const char vertex_source[] = "struct VSIn { float3 p : POSITION; float4 c : COLOR; }; struct VSOut { float4 p : SV_POSITION; float4 c : COLOR; }; VSOut main(VSIn i) { VSOut o; o.p=float4(i.p,1); o.c=i.c; return o; }";
    static const char pixel_source[] = "struct PSIn { float4 p : SV_POSITION; float4 c : COLOR; }; float4 main(PSIn i) : SV_TARGET { return i.c; }";
    static const D3D11_INPUT_ELEMENT_DESC input_desc[] = {
        {"POSITION", 0, DXGI_FORMAT_R32G32B32_FLOAT, 0, 0, D3D11_INPUT_PER_VERTEX_DATA, 0},
        {"COLOR", 0, DXGI_FORMAT_R32G32B32A32_FLOAT, 0, 12, D3D11_INPUT_PER_VERTEX_DATA, 0},
    };
    static const float vertices[] = {
        0.0f, 0.7f, 0.0f, 1.0f, 0.1f, 0.1f, 1.0f,
        0.7f, -0.7f, 0.0f, 0.1f, 1.0f, 0.1f, 1.0f,
        -0.7f, -0.7f, 0.0f, 0.1f, 0.1f, 1.0f, 1.0f,
    };
    ProbeD3D11CreateDevice create_device;
    ProbeCreateDXGIFactory1 create_factory;
    ProbeD3DCompile compile_shader;
    IDXGIFactory1 *factory = NULL;
    IDXGIFactory2 *factory2 = NULL;
    IDXGIAdapter1 *adapter = NULL;
    DXGI_ADAPTER_DESC1 adapter_desc;
    ID3D11Device *device = NULL;
    ID3D11DeviceContext *context = NULL;
    ID3D11Texture2D *back_buffer = NULL;
#if READBACK_VARIANT
    ID3D11Texture2D *offscreen = NULL;
    ID3D11RenderTargetView *offscreen_rtv = NULL;
#endif
    ID3D11RenderTargetView *rtv = NULL;
    ID3D11InputLayout *layout = NULL;
    ID3D11Buffer *vertex_buffer = NULL;
    ID3D11VertexShader *vertex_shader = NULL;
    ID3D11PixelShader *pixel_shader = NULL;
    IDXGISwapChain1 *swap_chain = NULL;
    ID3DBlob *vs_blob = NULL;
    ID3DBlob *ps_blob = NULL;
    ID3DBlob *errors = NULL;
    HWND window = NULL;
    HINSTANCE instance = GetModuleHandleW(NULL);
    HRESULT hr;
    HRESULT removed_reason = S_OK;
    D3D_FEATURE_LEVEL actual_level = 0;
    D3D11_BUFFER_DESC buffer_desc;
    D3D11_SUBRESOURCE_DATA buffer_data;
    DXGI_SWAP_CHAIN_DESC1 swap_desc;
    D3D11_VIEWPORT viewport;
    const D3D_FEATURE_LEVEL *levels = requested;
    UINT level_count = ARRAYSIZE(requested);
    char hr_text[32];
    char fields[4096];
    char error_text[1200];
    unsigned presents = 0;
    int class_registered = 0;
    int created = 0;
    int triangle_drawn = 0;
    int shader_ok = 0;
    int swapchain_ok = 0;
    int present_failed = 0;
    int adapter_explicit = 0;
    DXGI_ADAPTER_FLAG adapter_flags = DXGI_ADAPTER_FLAG_NONE;
    HRESULT enum_hr = DXGI_ERROR_NOT_FOUND;

    (void)vulkan;
    *present_count = 0;
#if READBACK_VARIANT
    memset(readback_summary, 0, sizeof(*readback_summary));
#else
    (void)readback_summary;
#endif
    create_device = (ProbeD3D11CreateDevice)load_proc(d3d11_module->handle, "D3D11CreateDevice");
    create_factory = (ProbeCreateDXGIFactory1)load_proc(dxgi_module->handle, "CreateDXGIFactory1");
    compile_shader = (ProbeD3DCompile)compiler_module->captured_proc;
    if (!within_budget() || create_device == NULL || create_factory == NULL) {
        log_event(log, "d3d_attempt", ",\"status\":\"UNAVAILABLE\",\"driverType\":\"HARDWARE\",\"adapterExplicit\":false,\"requestedFeatureLevels\":\"11_1,11_0,10_1,10_0,9_3,9_2,9_1\",\"result\":\"0x8007007E\"");
        return 0;
    }
    hr = create_factory(&IID_IDXGIFactory1, (void **)&factory);
    if (SUCCEEDED(hr) && factory != NULL) {
        UINT index = 0;
        while (index < MAX_ADAPTERS && within_budget()) {
            enum_hr = IDXGIFactory1_EnumAdapters1(factory, index++, &adapter);
            if (FAILED(enum_hr)) break;
            if (adapter == NULL) { enum_hr = E_FAIL; break; }
            if (SUCCEEDED(IDXGIAdapter1_GetDesc1(adapter, &adapter_desc))) {
                adapter_flags = adapter_desc.Flags;
                if ((adapter_flags & DXGI_ADAPTER_FLAG_SOFTWARE) == 0) { adapter_explicit = 1; break; }
            }
            IDXGIAdapter1_Release(adapter); adapter = NULL;
        }
        if (enum_hr != DXGI_ERROR_NOT_FOUND && FAILED(enum_hr)) {
            hresult_text(hr_text, sizeof(hr_text), enum_hr);
            snprintf(fields, sizeof(fields), ",\"status\":\"ENUMERATION_FAILED\",\"adapterDescription\":\"none\",\"adapterFlags\":0,\"driverType\":\"HARDWARE\",\"adapterExplicit\":false,\"requestedFeatureLevels\":\"11_1,11_0,10_1,10_0,9_3,9_2,9_1\",\"result\":\"%s\"", hr_text);
        } else if (adapter_explicit) {
            char adapter_json[700];
            json_quote_wide(adapter_json, sizeof(adapter_json), adapter_desc.Description);
            snprintf(fields, sizeof(fields), ",\"status\":\"ENUMERATED\",\"adapterDescription\":%s,\"adapterFlags\":%u,\"driverType\":\"UNKNOWN\",\"adapterExplicit\":true,\"requestedFeatureLevels\":\"11_1,11_0,10_1,10_0,9_3,9_2,9_1\",\"result\":\"0x00000000\"", adapter_json, (unsigned)adapter_flags);
        } else snprintf(fields, sizeof(fields), ",\"status\":\"ENUMERATED\",\"adapterDescription\":\"none\",\"adapterFlags\":0,\"driverType\":\"HARDWARE\",\"adapterExplicit\":false,\"requestedFeatureLevels\":\"11_1,11_0,10_1,10_0,9_3,9_2,9_1\",\"result\":\"0x00000000\"");
        log_event(log, "d3d_attempt", fields);
        if (SUCCEEDED(IDXGIFactory1_QueryInterface(factory, &IID_IDXGIFactory2, (void **)&factory2)) && factory2 != NULL) {
            /* Keep factory2 for the swap-chain path. */
        }
    } else {
        hresult_text(hr_text, sizeof(hr_text), hr);
        snprintf(fields, sizeof(fields), ",\"status\":\"ENUMERATION_FAILED\",\"driverType\":\"HARDWARE\",\"adapterExplicit\":false,\"requestedFeatureLevels\":\"11_1,11_0,10_1,10_0,9_3,9_2,9_1\",\"result\":\"%s\"", hr_text);
        log_event(log, "d3d_attempt", fields);
    }
    if (!within_budget()) goto cleanup;
    hr = create_device(adapter_explicit ? (IDXGIAdapter *)adapter : NULL, adapter_explicit ? D3D_DRIVER_TYPE_UNKNOWN : D3D_DRIVER_TYPE_HARDWARE, NULL, 0, levels, level_count, D3D11_SDK_VERSION, &device, &actual_level, &context);
    hresult_text(hr_text, sizeof(hr_text), hr);
    snprintf(fields, sizeof(fields), ",\"status\":\"CREATE\",\"driverType\":\"%s\",\"adapterExplicit\":%s,\"requestedFeatureLevels\":\"11_1,11_0,10_1,10_0,9_3,9_2,9_1\",\"result\":\"%s\",\"resultFeatureLevel\":\"%s\"", adapter_explicit ? "UNKNOWN" : "HARDWARE", adapter_explicit ? "true" : "false", hr_text, feature_level_text(actual_level));
    log_event(log, "d3d_attempt", fields);
    if (hr == E_INVALIDARG) {
        levels = retry_requested; level_count = ARRAYSIZE(retry_requested);
        actual_level = 0;
        hr = create_device(adapter_explicit ? (IDXGIAdapter *)adapter : NULL, adapter_explicit ? D3D_DRIVER_TYPE_UNKNOWN : D3D_DRIVER_TYPE_HARDWARE, NULL, 0, levels, level_count, D3D11_SDK_VERSION, &device, &actual_level, &context);
        hresult_text(hr_text, sizeof(hr_text), hr);
        snprintf(fields, sizeof(fields), ",\"status\":\"CREATE_RETRY\",\"driverType\":\"%s\",\"adapterExplicit\":%s,\"requestedFeatureLevels\":\"11_0,10_1,10_0,9_3,9_2,9_1\",\"result\":\"%s\",\"resultFeatureLevel\":\"%s\"", adapter_explicit ? "UNKNOWN" : "HARDWARE", adapter_explicit ? "true" : "false", hr_text, feature_level_text(actual_level));
        log_event(log, "d3d_attempt", fields);
    }
    if (FAILED(hr) || device == NULL || context == NULL) { d3d_status = "DEVICE_REJECTED"; goto cleanup; }
    created = 1;
    d3d_status = "FAILED"; /* A later window/compiler/present failure is not a GPU support verdict. */
    removed_reason = ID3D11Device_GetDeviceRemovedReason(device);
    hresult_text(hr_text, sizeof(hr_text), removed_reason);
    snprintf(fields, sizeof(fields), ",\"result\":\"0x%08lX\",\"featureLevel\":\"%s\",\"deviceRemovedReason\":\"%s\"", (unsigned long)(uint32_t)hr, feature_level_text(actual_level), hr_text);
    log_event(log, "device_created", fields);
    if (compile_shader == NULL) { log_event(log, "shader", ",\"status\":\"COMPILER_UNAVAILABLE\""); goto cleanup; }
    if (!make_window(&window, instance)) { log_event(log, "swapchain", ",\"status\":\"WINDOW_FAILED\",\"windowCreated\":false,\"result\":\"0x80004005\""); goto cleanup; }
    class_registered = 1;
    if (factory2 == NULL) { log_event(log, "swapchain", ",\"status\":\"FACTORY2_UNAVAILABLE\",\"windowCreated\":true,\"result\":\"0x80004005\""); goto cleanup; }
    memset(&swap_desc, 0, sizeof(swap_desc));
    swap_desc.Width = 320; swap_desc.Height = 240; swap_desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM; swap_desc.Stereo = FALSE;
    swap_desc.SampleDesc.Count = 1; swap_desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT; swap_desc.BufferCount = 2; swap_desc.Scaling = DXGI_SCALING_STRETCH; swap_desc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD; swap_desc.AlphaMode = DXGI_ALPHA_MODE_IGNORE;
    hr = IDXGIFactory2_CreateSwapChainForHwnd(factory2, (IUnknown *)device, window, &swap_desc, NULL, NULL, &swap_chain);
    hresult_text(hr_text, sizeof(hr_text), hr);
    snprintf(fields, sizeof(fields), ",\"status\":\"%s\",\"windowCreated\":true,\"result\":\"%s\",\"width\":320,\"height\":240,\"buffers\":2", SUCCEEDED(hr) ? "CREATED" : "FAILED", hr_text);
    log_event(log, "swapchain", fields);
    if (FAILED(hr) || swap_chain == NULL) goto cleanup;
    swapchain_ok = 1;
    hr = IDXGISwapChain1_GetBuffer(swap_chain, 0, &IID_ID3D11Texture2D, (void **)&back_buffer);
    if (SUCCEEDED(hr)) hr = ID3D11Device_CreateRenderTargetView(device, (ID3D11Resource *)back_buffer, NULL, &rtv);
    if (FAILED(hr) || rtv == NULL) { hresult_text(hr_text, sizeof(hr_text), hr); snprintf(fields, sizeof(fields), ",\"status\":\"RTV_FAILED\",\"windowCreated\":true,\"result\":\"%s\"", hr_text); log_event(log, "swapchain", fields); goto cleanup; }
    hr = compile_shader(vertex_source, sizeof(vertex_source) - 1, "GraphicsProbeVS", NULL, NULL, "main", shader_profile(actual_level, 1), D3DCOMPILE_ENABLE_STRICTNESS, 0, &vs_blob, &errors);
    blob_error(error_text, sizeof(error_text), errors);
    if (errors != NULL) ID3D10Blob_Release(errors); errors = NULL;
    if (FAILED(hr)) { hresult_text(hr_text, sizeof(hr_text), hr); snprintf(fields, sizeof(fields), ",\"status\":\"FAILED\",\"stage\":\"vertex\",\"profile\":\"%s\",\"result\":\"%s\",\"error\":%s", shader_profile(actual_level, 1), hr_text, error_text); log_event(log, "shader", fields); goto cleanup; }
    hr = compile_shader(pixel_source, sizeof(pixel_source) - 1, "GraphicsProbePS", NULL, NULL, "main", shader_profile(actual_level, 0), D3DCOMPILE_ENABLE_STRICTNESS, 0, &ps_blob, &errors);
    blob_error(error_text, sizeof(error_text), errors);
    if (errors != NULL) ID3D10Blob_Release(errors); errors = NULL;
    if (FAILED(hr)) { hresult_text(hr_text, sizeof(hr_text), hr); snprintf(fields, sizeof(fields), ",\"status\":\"FAILED\",\"stage\":\"pixel\",\"profile\":\"%s\",\"result\":\"%s\",\"error\":%s", shader_profile(actual_level, 0), hr_text, error_text); log_event(log, "shader", fields); goto cleanup; }
    hr = ID3D11Device_CreateVertexShader(device, ID3D10Blob_GetBufferPointer(vs_blob), ID3D10Blob_GetBufferSize(vs_blob), NULL, &vertex_shader);
    if (SUCCEEDED(hr)) hr = ID3D11Device_CreatePixelShader(device, ID3D10Blob_GetBufferPointer(ps_blob), ID3D10Blob_GetBufferSize(ps_blob), NULL, &pixel_shader);
    if (SUCCEEDED(hr)) hr = ID3D11Device_CreateInputLayout(device, input_desc, ARRAYSIZE(input_desc), ID3D10Blob_GetBufferPointer(vs_blob), ID3D10Blob_GetBufferSize(vs_blob), &layout);
    hresult_text(hr_text, sizeof(hr_text), hr);
    snprintf(fields, sizeof(fields), ",\"status\":\"%s\",\"vsProfile\":\"%s\",\"psProfile\":\"%s\",\"result\":\"%s\",\"error\":\"none\"", SUCCEEDED(hr) ? "COMPILED" : "CREATE_FAILED", shader_profile(actual_level, 1), shader_profile(actual_level, 0), hr_text);
    log_event(log, "shader", fields);
    if (FAILED(hr)) goto cleanup;
    shader_ok = 1;
    memset(&buffer_desc, 0, sizeof(buffer_desc)); buffer_desc.Usage = D3D11_USAGE_DEFAULT; buffer_desc.ByteWidth = sizeof(vertices); buffer_desc.BindFlags = D3D11_BIND_VERTEX_BUFFER;
    memset(&buffer_data, 0, sizeof(buffer_data)); buffer_data.pSysMem = vertices;
    hr = ID3D11Device_CreateBuffer(device, &buffer_desc, &buffer_data, &vertex_buffer);
    if (FAILED(hr)) goto cleanup;
    memset(&viewport, 0, sizeof(viewport)); viewport.Width = 320.0f; viewport.Height = 240.0f; viewport.MaxDepth = 1.0f;
#if READBACK_VARIANT
    {
        ULONGLONG pause_start;
        ULONGLONG pause_budget;
        ULONGLONG pause_elapsed;
        DWORD pause_ms;
        log_event(log, "readback_window", ",\"status\":\"READY\",\"marker\":\"modules-d3d-shaders-ready\",\"maxPauseMs\":5000");
        pause_start = GetTickCount64();
        pause_budget = pause_start - probe_started < MAX_PROBE_MS ? MAX_PROBE_MS - (pause_start - probe_started) : 0;
        pause_ms = (DWORD)(pause_budget < 5000 ? pause_budget : 5000);
        Sleep(pause_ms);
        pause_elapsed = GetTickCount64() - pause_start;
        snprintf(fields, sizeof(fields), ",\"status\":\"RESUMED\",\"marker\":\"modules-d3d-shaders-ready\",\"pauseRequestedMs\":%lu,\"pauseElapsedMs\":%llu,\"pauseOverrun\":%s,\"elapsedMs\":%llu", (unsigned long)pause_ms, (unsigned long long)pause_elapsed, pause_elapsed > pause_ms ? "true" : "false", (unsigned long long)(GetTickCount64() - probe_started));
        log_event(log, "readback_window", fields);
        if (!within_budget()) {
            snprintf(fields, sizeof(fields), ",\"status\":\"TIMEOUT\",\"marker\":\"modules-d3d-shaders-ready\",\"phase\":\"post-pause\",\"elapsedMs\":%llu", (unsigned long long)(GetTickCount64() - probe_started));
            log_event(log, "readback_window", fields);
            goto cleanup;
        }
    }
#endif
#if READBACK_VARIANT
    if (!within_budget()) { log_readback_timeout(log, "pipeline_setup"); goto cleanup; }
#endif
    ID3D11DeviceContext_OMSetRenderTargets(context, 1, &rtv, NULL);
    ID3D11DeviceContext_RSSetViewports(context, 1, &viewport);
    ID3D11DeviceContext_IASetInputLayout(context, layout);
    ID3D11DeviceContext_IASetPrimitiveTopology(context, D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    ID3D11DeviceContext_VSSetShader(context, vertex_shader, NULL, 0);
    ID3D11DeviceContext_PSSetShader(context, pixel_shader, NULL, 0);
    { UINT stride = 28; UINT offset = 0; ID3D11DeviceContext_IASetVertexBuffers(context, 0, 1, &vertex_buffer, &stride, &offset); }
#if READBACK_VARIANT
    {
        D3D11_TEXTURE2D_DESC offscreen_desc;
        HRESULT texture_result;
        HRESULT offscreen_rtv_result = E_FAIL;
        float clear[4] = {0.04f, 0.04f, 0.08f, 1.0f};
        memset(&offscreen_desc, 0, sizeof(offscreen_desc));
        offscreen_desc.Width = 320; offscreen_desc.Height = 240; offscreen_desc.MipLevels = 1; offscreen_desc.ArraySize = 1;
        offscreen_desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM; offscreen_desc.SampleDesc.Count = 1;
        offscreen_desc.Usage = D3D11_USAGE_DEFAULT; offscreen_desc.BindFlags = D3D11_BIND_RENDER_TARGET;
        if (!within_budget()) { log_readback_timeout(log, "offscreen_resources"); goto cleanup; }
        texture_result = ID3D11Device_CreateTexture2D(device, &offscreen_desc, NULL, &offscreen);
        if (SUCCEEDED(texture_result) && offscreen != NULL) {
            if (!within_budget()) { log_readback_timeout(log, "offscreen_resources"); goto cleanup; }
            offscreen_rtv_result = ID3D11Device_CreateRenderTargetView(device, (ID3D11Resource *)offscreen, NULL, &offscreen_rtv);
        }
        hresult_text(hr_text, sizeof(hr_text), texture_result);
        {
            char offscreen_rtv_text[32];
            hresult_text(offscreen_rtv_text, sizeof(offscreen_rtv_text), offscreen_rtv_result);
            snprintf(fields, sizeof(fields), ",\"status\":\"%s\",\"textureResult\":\"%s\",\"rtvResult\":\"%s\",\"width\":320,\"height\":240", SUCCEEDED(texture_result) && offscreen != NULL && SUCCEEDED(offscreen_rtv_result) && offscreen_rtv != NULL ? "CREATED" : "FAILED", hr_text, offscreen_rtv_text);
            log_event(log, "offscreen_resources", fields);
        }
        if (!within_budget()) { log_readback_timeout(log, "offscreen_clear"); goto cleanup; }
        if (offscreen_rtv != NULL) {
            ID3D11DeviceContext_OMSetRenderTargets(context, 1, &offscreen_rtv, NULL);
            if (!within_budget()) { log_readback_timeout(log, "offscreen_clear"); goto cleanup; }
            ID3D11DeviceContext_ClearRenderTargetView(context, offscreen_rtv, clear);
        }
        ID3D11DeviceContext_OMSetRenderTargets(context, 0, NULL, NULL);
        readback_resource(log, device, context, offscreen != NULL && offscreen_rtv != NULL ? (ID3D11Resource *)offscreen : NULL,
            "offscreen_clear", texture_result, offscreen_rtv_result, 1, readback_summary);
        if (!within_budget()) goto cleanup;
        if (offscreen_rtv != NULL) {
            if (!within_budget()) { log_readback_timeout(log, "offscreen_draw"); goto cleanup; }
            ID3D11DeviceContext_OMSetRenderTargets(context, 1, &offscreen_rtv, NULL);
            if (!within_budget()) { log_readback_timeout(log, "offscreen_draw"); goto cleanup; }
            ID3D11DeviceContext_Draw(context, 3, 0);
        }
        ID3D11DeviceContext_OMSetRenderTargets(context, 0, NULL, NULL);
        readback_resource(log, device, context, offscreen != NULL && offscreen_rtv != NULL ? (ID3D11Resource *)offscreen : NULL,
            "offscreen_draw", texture_result, offscreen_rtv_result, 0, readback_summary);
        if (!within_budget()) goto cleanup;

        if (!within_budget()) { log_readback_timeout(log, "backbuffer_clear"); goto cleanup; }
        ID3D11DeviceContext_OMSetRenderTargets(context, 1, &rtv, NULL);
        if (!within_budget()) { log_readback_timeout(log, "backbuffer_clear"); goto cleanup; }
        ID3D11DeviceContext_ClearRenderTargetView(context, rtv, clear);
        ID3D11DeviceContext_OMSetRenderTargets(context, 0, NULL, NULL);
        readback_resource(log, device, context, (ID3D11Resource *)back_buffer, "backbuffer_clear", S_OK, S_OK, 1, readback_summary);
        if (!within_budget()) goto cleanup;
        if (!within_budget()) { log_readback_timeout(log, "backbuffer_draw"); goto cleanup; }
        ID3D11DeviceContext_OMSetRenderTargets(context, 1, &rtv, NULL);
        if (!within_budget()) { log_readback_timeout(log, "backbuffer_draw"); goto cleanup; }
        ID3D11DeviceContext_Draw(context, 3, 0);
        ID3D11DeviceContext_OMSetRenderTargets(context, 0, NULL, NULL);
        triangle_drawn = readback_resource(log, device, context, (ID3D11Resource *)back_buffer, "backbuffer_draw", S_OK, S_OK, 0, readback_summary);
        if (!within_budget()) goto cleanup;
        {
            HRESULT diagnostic_removed = ID3D11Device_GetDeviceRemovedReason(device);
            hresult_text(hr_text, sizeof(hr_text), diagnostic_removed);
            snprintf(fields, sizeof(fields), ",\"result\":\"%s\",\"phase\":\"post-readback\"", hr_text);
            log_event(log, "device_removed_reason", fields);
        }
        if (triangle_drawn) {
            unsigned frame;
            for (frame = 0; frame < MAX_FRAMES && within_budget(); ++frame) {
                MSG message;
                if (!within_budget()) { log_present_timeout(log, frame + 1); present_failed = 1; break; }
                while (within_budget() && PeekMessageW(&message, NULL, 0, 0, PM_REMOVE)) { TranslateMessage(&message); DispatchMessageW(&message); }
                if (!within_budget()) { log_present_timeout(log, frame + 1); present_failed = 1; break; }
                ID3D11DeviceContext_OMSetRenderTargets(context, 1, &rtv, NULL);
                if (!within_budget()) { log_present_timeout(log, frame + 1); present_failed = 1; break; }
                ID3D11DeviceContext_ClearRenderTargetView(context, rtv, clear);
                if (!within_budget()) { log_present_timeout(log, frame + 1); present_failed = 1; break; }
                ID3D11DeviceContext_Draw(context, 3, 0);
                if (!within_budget()) { log_present_timeout(log, frame + 1); present_failed = 1; break; }
                hr = IDXGISwapChain1_Present(swap_chain, 1, 0);
                hresult_text(hr_text, sizeof(hr_text), hr);
                if (hr != S_OK) {
                    char present_removed_text[32];
                    if (within_budget()) {
                        HRESULT present_removed_reason = ID3D11Device_GetDeviceRemovedReason(device);
                        hresult_text(present_removed_text, sizeof(present_removed_text), present_removed_reason);
                    } else strcpy(present_removed_text, "NOT_QUERIED_TIMEOUT");
                    snprintf(fields, sizeof(fields), ",\"index\":%u,\"result\":\"%s\",\"successful\":false,\"deviceRemovedReason\":\"%s\"", frame + 1, hr_text, present_removed_text);
                } else snprintf(fields, sizeof(fields), ",\"index\":%u,\"result\":\"%s\",\"successful\":true", frame + 1, hr_text);
                log_event(log, "present", fields);
                if (hr != S_OK) { present_failed = 1; break; }
                ++presents;
                Sleep(67);
            }
        }
    }
#else
    { unsigned frame;
      for (frame = 0; frame < MAX_FRAMES && within_budget(); ++frame) {
          MSG message; float clear[4] = {0.04f, 0.04f, 0.08f, 1.0f};
          if (!within_budget()) { log_present_timeout(log, frame + 1); present_failed = 1; break; }
          while (within_budget() && PeekMessageW(&message, NULL, 0, 0, PM_REMOVE)) { TranslateMessage(&message); DispatchMessageW(&message); }
          if (!within_budget()) { log_present_timeout(log, frame + 1); present_failed = 1; break; }
          ID3D11DeviceContext_ClearRenderTargetView(context, rtv, clear);
          if (!within_budget()) { log_present_timeout(log, frame + 1); present_failed = 1; break; }
          ID3D11DeviceContext_Draw(context, 3, 0);
          if (!within_budget()) { log_present_timeout(log, frame + 1); present_failed = 1; break; }
          if (frame == 0) {
              triangle_drawn = verify_triangle(log, device, context, back_buffer);
              if (!triangle_drawn) break;
          }
          hr = IDXGISwapChain1_Present(swap_chain, 1, 0);
           hresult_text(hr_text, sizeof(hr_text), hr);
           if (hr != S_OK) {
               char present_removed_text[32];
               if (within_budget()) {
                   HRESULT present_removed_reason = ID3D11Device_GetDeviceRemovedReason(device);
                   hresult_text(present_removed_text, sizeof(present_removed_text), present_removed_reason);
               } else strcpy(present_removed_text, "NOT_QUERIED_TIMEOUT");
              snprintf(fields, sizeof(fields), ",\"index\":%u,\"result\":\"%s\",\"successful\":false,\"deviceRemovedReason\":\"%s\"", frame + 1, hr_text, present_removed_text);
          } else snprintf(fields, sizeof(fields), ",\"index\":%u,\"result\":\"%s\",\"successful\":true", frame + 1, hr_text);
          log_event(log, "present", fields);
          /* DXGI_STATUS_OCCLUDED is SUCCEEDED but is not a presented frame. */
          if (hr != S_OK) { present_failed = 1; break; }
          ++presents;
          Sleep(67);
      }
    }
#endif
cleanup:
    *present_count = presents;
#if READBACK_VARIANT
    if (!within_budget()) {
        log_event(log, "probe_budget", ",\"status\":\"TIMEOUT\",\"maxDurationMs\":30000");
        removed_reason = E_FAIL; /* Not queried: never claim device removal from a timeout. */
    } else if (device != NULL) {
        removed_reason = ID3D11Device_GetDeviceRemovedReason(device);
        hresult_text(hr_text, sizeof(hr_text), removed_reason);
    }
#else
    if (device != NULL) { removed_reason = ID3D11Device_GetDeviceRemovedReason(device); hresult_text(hr_text, sizeof(hr_text), removed_reason); }
#endif
    if (errors != NULL) ID3D10Blob_Release(errors);
    if (vertex_buffer != NULL) ID3D11Buffer_Release(vertex_buffer);
    if (pixel_shader != NULL) ID3D11PixelShader_Release(pixel_shader);
    if (vertex_shader != NULL) ID3D11VertexShader_Release(vertex_shader);
    if (layout != NULL) ID3D11InputLayout_Release(layout);
    if (ps_blob != NULL) ID3D10Blob_Release(ps_blob);
    if (vs_blob != NULL) ID3D10Blob_Release(vs_blob);
    if (rtv != NULL) ID3D11RenderTargetView_Release(rtv);
#if READBACK_VARIANT
    if (offscreen_rtv != NULL) ID3D11RenderTargetView_Release(offscreen_rtv);
    if (offscreen != NULL) ID3D11Texture2D_Release(offscreen);
#endif
    if (back_buffer != NULL) ID3D11Texture2D_Release(back_buffer);
    if (swap_chain != NULL) IDXGISwapChain1_Release(swap_chain);
    if (window != NULL) DestroyWindow(window);
    if (class_registered) UnregisterClassW(L"GraphicsProbeWindow", instance);
    if (context != NULL) ID3D11DeviceContext_Release(context);
    if (device != NULL) ID3D11Device_Release(device);
    if (factory2 != NULL) IDXGIFactory2_Release(factory2);
    if (adapter != NULL) IDXGIAdapter1_Release(adapter);
    if (factory != NULL) IDXGIFactory1_Release(factory);
#if READBACK_VARIANT
    return created && shader_ok && swapchain_ok && triangle_drawn && readback_summary->offscreen_clear &&
        readback_summary->offscreen_draw && readback_summary->backbuffer_clear && readback_summary->backbuffer_draw &&
        !present_failed && presents == MAX_FRAMES && removed_reason == S_OK && within_budget();
#else
    return created && shader_ok && swapchain_ok && triangle_drawn && !present_failed && presents == MAX_FRAMES && removed_reason == S_OK;
#endif
}

int main(int argc, char **argv) {
    ProbeLog log;
    ModuleInfo winevulkan = {L"winevulkan.dll", NULL, {0}, {0}, NULL, 0, 0, 0, 0};
    ModuleInfo fallback = {L"vulkan-1.dll", NULL, {0}, {0}, NULL, 0, 0, 0, 0};
    ModuleInfo dxgi = {L"dxgi.dll", NULL, {0}, {0}, NULL, 0, 0, 0, 0};
    ModuleInfo d3d11 = {L"d3d11.dll", NULL, {0}, {0}, NULL, 0, 0, 0, 0};
    ModuleInfo compiler = {L"d3dcompiler_47.dll", NULL, {0}, {0}, NULL, 0, 0, 0, 0};
    VulkanState vulkan;
    char fields[MAX_LINE - 512];
    char module0[1300], module1[1300], module2[1300], module3[1300], module4[1300];
    int graphics_ok;
    int terminal_ok;
    int terminal_log_ok;
    unsigned successful_presents = 0;
    ReadbackSummary readback_summary;

    memset(&log, 0, sizeof(log));
    log.file = INVALID_HANDLE_VALUE;
    log.output = GetStdHandle(STD_OUTPUT_HANDLE);
    log.file_ok = 1;
    log.output_ok = 1;
    if (argc != 5 || strcmp(argv[1], "--log") != 0 || strcmp(argv[3], "--run-id") != 0 || !ascii_token(argv[4]) || !valid_log_path(argv[2], argv[4])) return 2;
    snprintf(log.run_id, sizeof(log.run_id), "%s", argv[4]);
    {
        wchar_t path[MAX_PATH_TEXT];
        int converted = MultiByteToWideChar(CP_ACP, 0, argv[2], -1, path, ARRAYSIZE(path));
        if (converted <= 0) return 2;
        log.file = CreateFileW(path, GENERIC_WRITE, FILE_SHARE_READ, NULL, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
    }
    if (log.file == INVALID_HANDLE_VALUE) return 3;
    probe_started = GetTickCount64();
#if READBACK_VARIANT
    log_event(&log, "probe_begin", ",\"status\":\"STARTED\",\"variant\":\"readback-v1\",\"maxFrames\":30,\"maxDurationMs\":30000,\"logIo\":\"durable-file-plus-stdout\"");
#else
    log_event(&log, "probe_begin", ",\"status\":\"STARTED\",\"maxFrames\":30,\"maxDurationMs\":30000,\"logIo\":\"durable-file-plus-stdout\"");
#endif
    winevulkan.handle = LoadLibraryW(winevulkan.requested);
    if (winevulkan.handle == NULL) fallback.handle = LoadLibraryW(fallback.requested);
    dxgi.handle = LoadLibraryW(dxgi.requested);
    d3d11.handle = LoadLibraryW(d3d11.requested);
    compiler.handle = LoadLibraryW(compiler.requested);
    /* Capture each loader error before the next Win32 API can replace it.  Do
     * not query a procedure from a failed module: procError is null when the
     * GetProcAddress call was not made. */
    if (compiler.handle == NULL) {
        compiler.load_error = GetLastError();
        compiler.load_error_valid = 1;
    } else {
        compiler.captured_proc = GetProcAddress(compiler.handle, "D3DCompile");
        if (compiler.captured_proc == NULL) {
            compiler.proc_error = GetLastError();
            compiler.proc_error_valid = 1;
        }
    }
    {
        char compiler_fields[256];
        compiler_load_fields(compiler_fields, sizeof(compiler_fields), &compiler);
        log_event(&log, "compiler_load", compiler_fields);
    }
    record_module(module0, sizeof(module0), &winevulkan);
    record_module(module1, sizeof(module1), &fallback);
    record_module(module2, sizeof(module2), &dxgi);
    record_module(module3, sizeof(module3), &compiler);
    record_module(module4, sizeof(module4), &d3d11);
    snprintf(fields, sizeof(fields), ",\"items\":[%s,%s,%s,%s,%s]", module0, module1, module2, module3, module4);
    log_event(&log, "modules", fields);
    run_vulkan(&log, &vulkan, &winevulkan, &fallback);
    graphics_ok = run_d3d(&log, &vulkan, &dxgi, &d3d11, &compiler, &successful_presents, &readback_summary);
    terminal_ok = graphics_ok && !log.io_failed;
#if READBACK_VARIANT
    snprintf(fields, sizeof(fields), ",\"status\":\"%s\",\"variant\":\"readback-v1\",\"successfulPresents\":%u,\"d3dTriangleResult\":%s,\"offscreenClear\":%s,\"offscreenDraw\":%s,\"backbufferClear\":%s,\"backbufferDraw\":%s,\"apiQualificationObserved\":%s,\"visualPresentationObserved\":false,\"wow\":false,\"logDurable\":%s,\"stdoutMirrored\":%s,\"vulkanStatus\":\"%s\"", terminal_ok ? "PASS" : (graphics_ok ? "FAIL_LOG_IO" : d3d_status), successful_presents, graphics_ok ? "true" : "false", readback_summary.offscreen_clear ? "true" : "false", readback_summary.offscreen_draw ? "true" : "false", readback_summary.backbuffer_clear ? "true" : "false", readback_summary.backbuffer_draw ? "true" : "false", graphics_ok ? "true" : "false", log.file_ok ? "true" : "false", log.output_ok ? "true" : "false", vulkan.available ? "MEASURED" : "UNKNOWN");
#else
    snprintf(fields, sizeof(fields), ",\"status\":\"%s\",\"successfulPresents\":%u,\"d3dTriangleResult\":%s,\"logDurable\":%s,\"stdoutMirrored\":%s,\"vulkanStatus\":\"%s\"", terminal_ok ? "PASS" : (graphics_ok ? "FAIL_LOG_IO" : d3d_status), successful_presents, graphics_ok ? "true" : "false", log.file_ok ? "true" : "false", log.output_ok ? "true" : "false", vulkan.available ? "MEASURED" : "UNKNOWN");
#endif
    terminal_log_ok = log_event(&log, "probe_end", fields);
    if (winevulkan.handle != NULL) FreeLibrary(winevulkan.handle);
    if (fallback.handle != NULL) FreeLibrary(fallback.handle);
    if (dxgi.handle != NULL) FreeLibrary(dxgi.handle);
    if (d3d11.handle != NULL) FreeLibrary(d3d11.handle);
    if (compiler.handle != NULL) FreeLibrary(compiler.handle);
    if (!CloseHandle(log.file)) log.io_failed = 1;
    return terminal_ok && terminal_log_ok && !log.io_failed && log.output_ok ? 0 : 1;
}
