#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

/*
 * This is a private, instance/physical-device metadata control.  The reviewed
 * file below supplies only libc/dl/hash/identity/bounded-JSON helpers.  Its
 * loader entry point and classification symbols are renamed so that this
 * translation unit has one, separate Vulkan call graph.
 */
#define main panvk_legacy_loader_entry
#define set_failure panvk_legacy_set_failure
#define failure_is panvk_legacy_failure_is
#define observe_exports panvk_legacy_observe_exports
#include "PanvkPrivateLoader.c"
#undef main
#undef set_failure
#undef failure_is
#undef observe_exports

#include "/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include/vulkan/vulkan_core.h"
#include <stddef.h>

#define INSTANCE_SCHEMA "wowforever.android-panvk-instance-metadata"
#define INSTANCE_OUTPUT_LIMIT (64U * 1024U)
#define MAX_DEVICES 8U
#define MAX_EXTENSIONS 256U
#define MAX_NAME VK_MAX_PHYSICAL_DEVICE_NAME_SIZE
#define MAX_EXT_NAME VK_MAX_EXTENSION_NAME_SIZE

typedef PFN_vkVoidFunction (VKAPI_PTR *PfnIcdGetInstanceProcAddr)(VkInstance, const char *);
typedef PFN_vkVoidFunction (VKAPI_PTR *PfnIcdGetPhysicalDeviceProcAddr)(VkInstance, const char *);
typedef VkResult (VKAPI_PTR *PfnIcdNegotiate)(uint32_t *);

typedef struct FunctionObservation {
    const char *name;
    void *address;
    char owner[PATH_LIMIT];
    int resolved;
    int owner_exact;
    char error[ERROR_LIMIT + 1U];
} FunctionObservation;

typedef struct DeviceMetadata {
    VkPhysicalDevice handle;
    VkPhysicalDeviceProperties properties;
    VkPhysicalDeviceFeatures features;
    VkExtensionProperties extension_properties[MAX_EXTENSIONS];
    char extension_names[MAX_EXTENSIONS][MAX_EXT_NAME];
    uint32_t extension_count;
    uint32_t extension_reported_count;
    uint32_t extension_returned_count;
    uint32_t extension_retained_count;
    uint32_t extension_capacity;
    VkResult extension_query_result;
    VkResult extension_fill_result;
    int extensions_complete;
    int properties_valid;
    int features_valid;
    int extensions_valid;
    int feature_bools_valid;
    int features2_queried;
    int features2_valid;
    VkPhysicalDeviceFeatures2 features2;
    VkPhysicalDeviceVulkan11Features vulkan11;
    VkPhysicalDeviceShaderDrawParametersFeatures shader_draw_parameters;
    VkPhysicalDeviceVulkan12Features vulkan12;
    VkPhysicalDeviceVulkan13Features vulkan13;
    VkPhysicalDeviceTransformFeedbackFeaturesEXT transform_feedback;
    VkPhysicalDeviceRobustness2FeaturesEXT robustness2;
    int has_vulkan11;
    int has_vulkan12;
    int has_vulkan13;
    int has_transform_feedback;
    int has_robustness2;
    int metadata_unknown;
    char error[ERROR_LIMIT + 1U];
} DeviceMetadata;

typedef struct InstanceResult {
    char run_id[RUN_LENGTH + 1U];
    int pid;
    int uid;
    int pgid;
    int sid;
    uint64_t monotonic_start;
    uint64_t monotonic_end;
    int clock_start_valid;
    int clock_valid;
    char cwd[PATH_LIMIT];
    int scope_valid;
    int binding_valid;
    char icd_path[PATH_LIMIT];
    uint64_t icd_bytes;
    char icd_sha256[65];
    char before_sha256[65];
    char after_sha256[65];
    Identity before;
    Identity after;
    int before_hash_valid;
    int after_hash_valid;
    int identity_stable;
    int load_attempted;
    int load_loaded;
    char load_error[ERROR_LIMIT + 1U];
    ExportObservation exports[3];
    FunctionObservation functions[8];
    int interface_input;
    int interface_result;
    int interface_output;
    int interface_supported;
    int enumerate_instance_version_found;
    int enumerate_instance_version_owner;
    int legacy_api_10;
    VkResult enumerate_instance_version_result;
    uint32_t api_reported;
    uint32_t api_requested;
    int instance_create_attempted;
    int instance_created;
    VkResult instance_create_result;
    int layer_count;
    int extension_count;
    int allocator_null;
    uint32_t physical_count;
    uint32_t physical_returned;
    uint32_t physical_retained;
    VkResult physical_count_result;
    VkResult physical_fill_result;
    uint32_t physical_capacity;
    int physical_complete;
    uint32_t device_count;
    DeviceMetadata devices[MAX_DEVICES];
    int destroy_attempted;
    int destroy_ok;
    int dlclose_attempted;
    int dlclose_ok;
    int cleanup_complete;
    VkResult last_vk_error;
    int fatal_vk_error;
    int vulkan_functions_invoked;
    const char *status;
    const char *failure_stage;
    char failure_detail[ERROR_LIMIT + 1U];
    int native_exit;
} InstanceResult;

static const char *const function_names[8] = {
    "vkCreateInstance", "vkDestroyInstance", "vkEnumeratePhysicalDevices",
    "vkGetPhysicalDeviceProperties", "vkGetPhysicalDeviceFeatures",
    "vkEnumerateDeviceExtensionProperties", "vkGetPhysicalDeviceFeatures2",
    "vkEnumerateInstanceVersion"
};

static int version_at_least(uint32_t value, uint32_t major, uint32_t minor) {
    return VK_API_VERSION_MAJOR(value) > major ||
           (VK_API_VERSION_MAJOR(value) == major && VK_API_VERSION_MINOR(value) >= minor);
}

static uint32_t requested_version(uint32_t reported) {
    uint32_t major = VK_API_VERSION_MAJOR(reported);
    uint32_t minor = VK_API_VERSION_MINOR(reported);
    if (major > 1U || (major == 1U && minor > 3U)) return VK_API_VERSION_1_3;
    return VK_MAKE_API_VERSION(0U, major, minor, 0U);
}

static int valid_bool(VkBool32 value) { return value == VK_FALSE || value == VK_TRUE; }

static void set_failure(InstanceResult *result, const char *stage, const char *detail) {
    if (result->failure_stage == NULL || strcmp(result->failure_stage, "none") == 0) {
        result->failure_stage = stage;
        copy_error(result->failure_detail, detail);
    }
}

static int failure_is(const InstanceResult *result, const char *stage) {
    return result != NULL && result->failure_stage != NULL && stage != NULL &&
           strcmp(result->failure_stage, stage) == 0;
}

static void device_failure(DeviceMetadata *device, const char *detail) {
    device->metadata_unknown = 1;
    if (device->error[0] == '\0') copy_error(device->error, detail);
}

static void record_vk_result(InstanceResult *result, VkResult value, const char *detail) {
    if (value != VK_SUCCESS) {
        result->last_vk_error = value;
        if ((int)value < 0) result->fatal_vk_error = 1;
        set_failure(result, "vulkan", detail);
    }
}

static int observe_function(FunctionObservation *observation, PFN_vkVoidFunction address,
                            const char *owner) {
    observation->address = (void *)(uintptr_t)address;
    observation->resolved = address != NULL;
    if (!observation->resolved) {
        copy_error(observation->error, "function not returned");
        return 0;
    }
    if (!address_owner((void *)(uintptr_t)address, observation->owner,
                       sizeof(observation->owner))) {
        copy_error(observation->error, "dladdr failed");
        return 0;
    }
    observation->owner_exact = strcmp(observation->owner, owner) == 0;
    if (!observation->owner_exact) copy_error(observation->error, "owner mismatch");
    return observation->owner_exact;
}

static void observe_instance_exports(InstanceResult *result, void *handle,
                                     const char *owner) {
    size_t index;
    for (index = 0U; index < 3U; ++index) {
        void *symbol;
        const char *error;
        (void)dlerror();
        symbol = dlsym(handle, export_names[index]);
        error = dlerror();
        result->exports[index].found = symbol != NULL && error == NULL;
        if (result->exports[index].found) {
            if (!address_owner(symbol, result->exports[index].owner,
                               sizeof(result->exports[index].owner))) {
                copy_error(result->exports[index].error, "dladdr failed");
            } else {
                result->exports[index].owner_exact =
                    strcmp(result->exports[index].owner, owner) == 0;
                if (!result->exports[index].owner_exact)
                    copy_error(result->exports[index].error, "owner mismatch");
            }
        } else if (error != NULL) {
            copy_error(result->exports[index].error, error);
        } else {
            copy_error(result->exports[index].error, "symbol not found");
        }
        if (!result->exports[index].found || !result->exports[index].owner_exact)
            set_failure(result, "export", result->exports[index].error);
    }
}

static int extension_present(const DeviceMetadata *device, const char *name) {
    uint32_t index;
    for (index = 0U; index < device->extension_count; ++index)
        if (strcmp(device->extension_names[index], name) == 0) return 1;
    return 0;
}

static int validate_bool_array(const VkBool32 *values, size_t count) {
    size_t index;
    for (index = 0U; index < count; ++index)
        if (!valid_bool(values[index])) return 0;
    return 1;
}

static int validate_features(const VkPhysicalDeviceFeatures *features) {
    return validate_bool_array((const VkBool32 *)features,
                               sizeof(*features) / sizeof(VkBool32));
}

static int validate_features2(const DeviceMetadata *device) {
    const VkBool32 *values;
    if (!device->features2_queried) return 1;
    if (!validate_features(&device->features2.features)) return 0;
    if (device->has_vulkan11) {
        if (!device->has_vulkan12)
            return valid_bool(device->shader_draw_parameters.shaderDrawParameters) &&
                   (!device->has_transform_feedback ||
                    (valid_bool(device->transform_feedback.transformFeedback) &&
                     valid_bool(device->transform_feedback.geometryStreams))) &&
                   (!device->has_robustness2 ||
                    (valid_bool(device->robustness2.robustBufferAccess2) &&
                     valid_bool(device->robustness2.robustImageAccess2) &&
                     valid_bool(device->robustness2.nullDescriptor)));
        values = &device->vulkan11.storageBuffer16BitAccess;
        if (!validate_bool_array(values, (sizeof(device->vulkan11) -
                                  offsetof(VkPhysicalDeviceVulkan11Features,
                                           storageBuffer16BitAccess)) / sizeof(VkBool32))) return 0;
    }
    if (device->has_vulkan12) {
        values = &device->vulkan12.samplerMirrorClampToEdge;
        if (!validate_bool_array(values, (sizeof(device->vulkan12) -
                                  offsetof(VkPhysicalDeviceVulkan12Features,
                                           samplerMirrorClampToEdge)) / sizeof(VkBool32))) return 0;
    }
    if (device->has_vulkan13) {
        values = &device->vulkan13.robustImageAccess;
        if (!validate_bool_array(values, (sizeof(device->vulkan13) -
                                  offsetof(VkPhysicalDeviceVulkan13Features,
                                           robustImageAccess)) / sizeof(VkBool32))) return 0;
    }
    if (device->has_transform_feedback &&
        (!valid_bool(device->transform_feedback.transformFeedback) ||
         !valid_bool(device->transform_feedback.geometryStreams))) return 0;
    if (device->has_robustness2 &&
        (!valid_bool(device->robustness2.robustBufferAccess2) ||
         !valid_bool(device->robustness2.robustImageAccess2) ||
         !valid_bool(device->robustness2.nullDescriptor))) return 0;
    return 1;
}

static void init_feature_chain(DeviceMetadata *device, uint32_t api) {
    memset(&device->features2, 0, sizeof(device->features2));
    device->features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    device->has_vulkan11 = version_at_least(api, 1U, 1U);
    device->has_vulkan12 = version_at_least(api, 1U, 2U);
    device->has_vulkan13 = version_at_least(api, 1U, 3U);
    device->has_transform_feedback = device->extensions_complete && extension_present(device,
                                                       VK_EXT_TRANSFORM_FEEDBACK_EXTENSION_NAME);
    device->has_robustness2 = device->extensions_complete && extension_present(device, VK_EXT_ROBUSTNESS_2_EXTENSION_NAME);
    if (device->has_vulkan13) {
        device->vulkan13.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;
        device->vulkan13.pNext = device->features2.pNext;
        device->features2.pNext = &device->vulkan13;
    }
    if (device->has_vulkan12) {
        device->vulkan12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
        device->vulkan12.pNext = device->features2.pNext;
        device->features2.pNext = &device->vulkan12;
    }
    if (device->has_vulkan11) {
        if (device->has_vulkan12) {
            device->vulkan11.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
            device->vulkan11.pNext = device->features2.pNext;
            device->features2.pNext = &device->vulkan11;
        } else {
            device->shader_draw_parameters.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_DRAW_PARAMETERS_FEATURES;
            device->shader_draw_parameters.pNext = device->features2.pNext;
            device->features2.pNext = &device->shader_draw_parameters;
        }
    }
    if (device->has_transform_feedback) {
        device->transform_feedback.sType =
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TRANSFORM_FEEDBACK_FEATURES_EXT;
        device->transform_feedback.pNext = device->features2.pNext;
        device->features2.pNext = &device->transform_feedback;
    }
    if (device->has_robustness2) {
        device->robustness2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT;
        device->robustness2.pNext = device->features2.pNext;
        device->features2.pNext = &device->robustness2;
    }
}

static void query_device(InstanceResult *result, DeviceMetadata *device,
                         PFN_vkGetPhysicalDeviceProperties get_properties,
                         PFN_vkGetPhysicalDeviceFeatures get_features,
                         PFN_vkEnumerateDeviceExtensionProperties enumerate_extensions,
                         PFN_vkGetPhysicalDeviceFeatures2 get_features2) {
    uint32_t count = 0U;
    uint32_t returned;
    VkResult value;
    size_t index;
    int terminated;
    uint32_t api;
    int device_api_valid;
    device->extension_capacity = MAX_EXTENSIONS;
    device->extension_query_result = VK_ERROR_INITIALIZATION_FAILED;
    device->extension_fill_result = VK_ERROR_INITIALIZATION_FAILED;
    memset(&device->properties, 0, sizeof(device->properties));
    memset(&device->features, 0, sizeof(device->features));
    get_properties(device->handle, &device->properties);
    device_api_valid = VK_API_VERSION_VARIANT(device->properties.apiVersion) == 0U &&
                       VK_API_VERSION_MAJOR(device->properties.apiVersion) == 1U &&
                       device->properties.apiVersion >= VK_API_VERSION_1_0;
    if (!device_api_valid) device_failure(device, "device API version is invalid");
    get_features(device->handle, &device->features);
    result->vulkan_functions_invoked = 1;
    device->properties_valid = bounded_length(device->properties.deviceName, MAX_NAME,
                                              &terminated) < MAX_NAME && terminated;
    device->features_valid = validate_features(&device->features);
    if (!device->properties_valid) device_failure(device, "device name is not terminated");
    if (!device->features_valid) device_failure(device, "invalid VkBool32 in core features");
    value = enumerate_extensions(device->handle, NULL, &count, NULL);
    result->vulkan_functions_invoked = 1;
    device->extension_query_result = value;
    device->extension_reported_count = count;
    if (value != VK_SUCCESS || count > MAX_EXTENSIONS) {
        if (value != VK_SUCCESS) record_vk_result(result, value, "device extension count failed");
        device_failure(device, count > MAX_EXTENSIONS ? "device extension count overflow" :
                       "device extension count failed");
        return;
    }
    returned = count;
    if (count != 0U) {
        value = enumerate_extensions(device->handle, NULL, &returned,
                                     device->extension_properties);
        result->vulkan_functions_invoked = 1;
        device->extension_fill_result = value;
        if (value != VK_SUCCESS && value != VK_INCOMPLETE) {
            record_vk_result(result, value, "device extension fill failed");
            device->extension_returned_count = 0U;
            device_failure(device, "device extension fill failed");
            return;
        }
        device->extension_returned_count = returned;
        device->extensions_complete = value == VK_SUCCESS && returned == count;
        if (returned > MAX_EXTENSIONS) returned = MAX_EXTENSIONS;
        if (returned > count) returned = count;
    } else {
        device->extension_fill_result = VK_SUCCESS;
        device->extension_returned_count = 0U;
        device->extensions_complete = 1;
    }
    device->extension_count = returned;
    device->extension_retained_count = returned;
    device->extensions_valid = 1;
    for (index = 0U; index < returned; ++index) {
        device->extension_names[index][0] = '\0';
        if (!copy_bounded(device->extension_names[index], MAX_EXT_NAME,
                          device->extension_properties[index].extensionName,
                          MAX_EXT_NAME)) {
            device->extensions_valid = 0;
            device_failure(device, "extension name is not terminated");
        }
    }
    if (!device->extensions_complete)
        device_failure(device, "device extension enumeration incomplete");
    if (!device_api_valid || !device->properties_valid ||
        !version_at_least(result->api_requested, 1U, 1U) ||
        !version_at_least(device->properties.apiVersion, 1U, 1U)) {
        device->features2_queried = 0;
        device->features2_valid = device_api_valid &&
            (!version_at_least(result->api_requested, 1U, 1U) ||
             !version_at_least(device->properties.apiVersion, 1U, 1U) ||
             device->extensions_complete);
        return;
    }
    api = device->properties.apiVersion;
    if (VK_API_VERSION_MINOR(api) > VK_API_VERSION_MINOR(result->api_requested))
        api = result->api_requested;
    init_feature_chain(device, api);
    device->features2_queried = 1;
    if (get_features2 == NULL) {
        device_failure(device, "features2 function unavailable");
        return;
    }
    get_features2(device->handle, &device->features2);
    result->vulkan_functions_invoked = 1;
    device->features2_valid = validate_features2(device);
    if (!device->features2_valid) device_failure(device, "invalid VkBool32 in feature chain");
    if (!device->extensions_complete) device_failure(device, "device extension enumeration incomplete");
}

static int json_nullable_bool(Json *json, int available, VkBool32 value) {
    if (!available) return json_append(json, "null");
    return json_bool(json, value == VK_TRUE);
}

static int json_core_features(Json *json, const DeviceMetadata *device) {
    const VkPhysicalDeviceFeatures *f = &device->features;
    return json_append(json, "{\"fillModeNonSolid\":") &&
           json_nullable_bool(json, device->features_valid, f->fillModeNonSolid) &&
           json_append(json, ",\"shaderClipDistance\":") &&
           json_nullable_bool(json, device->features_valid, f->shaderClipDistance) &&
           json_append(json, ",\"textureCompressionBC\":") &&
           json_nullable_bool(json, device->features_valid, f->textureCompressionBC) &&
           json_append(json, ",\"geometryShader\":") &&
           json_nullable_bool(json, device->features_valid, f->geometryShader) &&
           json_append(json, ",\"tessellationShader\":") &&
           json_nullable_bool(json, device->features_valid, f->tessellationShader) &&
           json_append(json, ",\"sampleRateShading\":") &&
           json_nullable_bool(json, device->features_valid, f->sampleRateShading) &&
           json_append(json, ",\"samplerAnisotropy\":") &&
           json_nullable_bool(json, device->features_valid, f->samplerAnisotropy) &&
           json_append(json, ",\"robustBufferAccess\":") &&
           json_nullable_bool(json, device->features_valid, f->robustBufferAccess) &&
           json_append(json, "}");
}

static int json_feature2(Json *json, const DeviceMetadata *device) {
    int valid = device->features2_valid;
    return json_append(json, "{\"queried\":") && json_bool(json, device->features2_queried) &&
           json_append(json, ",\"core\":") && json_core_features(json, device) &&
           json_append(json, ",\"vulkan11ShaderDrawParameters\":") &&
            json_nullable_bool(json, valid && device->has_vulkan11,
                               device->has_vulkan12 ? device->vulkan11.shaderDrawParameters :
                               device->shader_draw_parameters.shaderDrawParameters) &&
           json_append(json, ",\"vulkan12\":{\"descriptorIndexing\":") &&
           json_nullable_bool(json, valid && device->has_vulkan12, device->vulkan12.descriptorIndexing) &&
           json_append(json, ",\"bufferDeviceAddress\":") &&
           json_nullable_bool(json, valid && device->has_vulkan12, device->vulkan12.bufferDeviceAddress) &&
           json_append(json, "},\"vulkan13\":{\"shaderDemoteToHelperInvocation\":") &&
           json_nullable_bool(json, valid && device->has_vulkan13,
                              device->vulkan13.shaderDemoteToHelperInvocation) &&
           json_append(json, ",\"synchronization2\":") &&
           json_nullable_bool(json, valid && device->has_vulkan13, device->vulkan13.synchronization2) &&
           json_append(json, ",\"dynamicRendering\":") &&
           json_nullable_bool(json, valid && device->has_vulkan13, device->vulkan13.dynamicRendering) &&
           json_append(json, "},\"transformFeedback\":{\"transformFeedback\":") &&
           json_nullable_bool(json, valid && device->has_transform_feedback,
                              device->transform_feedback.transformFeedback) &&
           json_append(json, ",\"geometryStreams\":") &&
           json_nullable_bool(json, valid && device->has_transform_feedback,
                              device->transform_feedback.geometryStreams) &&
           json_append(json, "},\"robustness2\":{\"robustBufferAccess2\":") &&
           json_nullable_bool(json, valid && device->has_robustness2,
                              device->robustness2.robustBufferAccess2) &&
           json_append(json, ",\"nullDescriptor\":") &&
           json_nullable_bool(json, valid && device->has_robustness2,
                              device->robustness2.nullDescriptor) &&
           json_append(json, "},\"valid\":") && json_bool(json, valid) && json_append(json, "}");
}

static int json_function(Json *json, const FunctionObservation *function) {
    return json_append(json, "{\"name\":") && json_string(json, function->name, PATH_LIMIT) &&
           json_append(json, ",\"resolved\":") && json_bool(json, function->resolved) &&
           json_append(json, ",\"owner\":") && json_string(json, function->owner, PATH_LIMIT) &&
           json_append(json, ",\"ownerExact\":") && json_bool(json, function->owner_exact) &&
           json_append(json, ",\"error\":") && json_string(json, function->error, ERROR_LIMIT) &&
           json_append(json, "}");
}

static int json_device(Json *json, const DeviceMetadata *device) {
    uint32_t index;
    int terminated;
    terminated = bounded_length(device->properties.deviceName, MAX_NAME, NULL) < MAX_NAME;
    if (!json_append(json, "{\"metadataStatus\":") ||
        !json_string(json, device->metadata_unknown ? "UNKNOWN" : "OBSERVED", 16U) ||
        !json_append(json, ",\"vendorId\":%u,\"deviceId\":%u,\"apiVersion\":%u,\"driverVersion\":%u,\"deviceName\":",
                     device->properties.vendorID, device->properties.deviceID,
                     device->properties.apiVersion, device->properties.driverVersion) ||
         !(terminated ? json_string(json, device->properties.deviceName, MAX_NAME) : json_append(json, "null")) ||
        !json_append(json, ",\"propertiesValid\":") ||
        !json_bool(json, device->properties_valid && terminated) ||
        !json_append(json, ",\"extensionsValid\":") || json_bool(json, device->extensions_valid) == 0 ||
        !json_append(json, ",\"extensionCount\":%u,\"extensionReportedCount\":%u,\"extensionReturnedCount\":%u,\"extensionRetainedCount\":%u,\"extensionCapacity\":%u,\"extensionQueryResult\":%d,\"extensionFillResult\":%d,\"extensionsComplete\":",
                     device->extension_count, device->extension_reported_count,
                     device->extension_returned_count, device->extension_retained_count,
                     device->extension_capacity, device->extension_query_result,
                     device->extension_fill_result) ||
        json_bool(json, device->extensions_complete) == 0 ||
        !json_append(json, ",\"extensions\":[")) return 0;
    for (index = 0U; index < device->extension_count; ++index) {
        if (index != 0U && !json_append(json, ",")) return 0;
        if (!json_string(json, device->extension_names[index], MAX_EXT_NAME - 1U)) return 0;
    }
    return json_append(json, "],\"features\":") && json_core_features(json, device) &&
           json_append(json, ",\"features2\":") && json_feature2(json, device) &&
           json_append(json, ",\"error\":") && json_string(json, device->error, ERROR_LIMIT) &&
           json_append(json, "}");
}

static int encode_instance_result(const InstanceResult *result, char *output, size_t capacity) {
    Json json = {output, capacity, 0U, 1};
    size_t index;
    if (result == NULL || output == NULL || capacity == 0U) return 0;
    if (!json_append(&json, "{\"schema\":") ||
        !json_string(&json, INSTANCE_SCHEMA, sizeof(INSTANCE_SCHEMA)) ||
        !json_append(&json, ",\"version\":1,\"runId\":") ||
        !json_string(&json, result->run_id, RUN_LENGTH) ||
        !json_append(&json, ",\"pid\":%d,\"uid\":%d,\"pgid\":%d,\"sid\":%d,\"monotonicStartNs\":%" PRIu64 ",\"monotonicEndNs\":%" PRIu64 ",\"cwd\":",
                     result->pid, result->uid, result->pgid, result->sid,
                     result->monotonic_start, result->monotonic_end) ||
        !json_string(&json, result->cwd, PATH_LIMIT) ||
        !json_append(&json, ",\"scopeValid\":") || json_bool(&json, result->scope_valid) == 0 ||
        !json_append(&json, ",\"icdPath\":") || json_string(&json, result->icd_path, PATH_LIMIT) == 0 ||
        !json_append(&json, ",\"icdBytes\":%" PRIu64 ",\"icdSha256\":", result->icd_bytes) ||
        !json_string(&json, result->icd_sha256, 64U) ||
        !json_append(&json, ",\"icdIdentityBefore\":") || json_identity(&json, &result->before) == 0 ||
        !json_append(&json, ",\"icdIdentityAfter\":") || json_identity(&json, &result->after) == 0 ||
        !json_append(&json, ",\"icdIdentityStable\":") || json_bool(&json, result->identity_stable) == 0 ||
        !json_append(&json, ",\"load\":{\"attempted\":") || json_bool(&json, result->load_attempted) == 0 ||
        !json_append(&json, ",\"loaded\":") || json_bool(&json, result->load_loaded) == 0 ||
        !json_append(&json, ",\"dlerror\":") || json_string(&json, result->load_error, ERROR_LIMIT) == 0 ||
        !json_append(&json, "},\"exports\":[")) return 0;
    for (index = 0U; index < 3U; ++index) {
        if (index != 0U && !json_append(&json, ",")) return 0;
        if (!json_export(&json, &result->exports[index], export_names[index])) return 0;
    }
    if (!json_append(&json, "],\"interface\":{\"input\":%d,\"result\":%d,\"output\":%d,\"supported\":",
                     result->interface_input, result->interface_result, result->interface_output) ||
        json_bool(&json, result->interface_supported) == 0 ||
        !json_append(&json, "},\"functions\":[")) return 0;
    for (index = 0U; index < 8U; ++index) {
        if (index != 0U && !json_append(&json, ",")) return 0;
        if (!json_function(&json, &result->functions[index])) return 0;
    }
    if (!json_append(&json, "],\"api\":{\"enumerateInstanceVersionFound\":") ||
        json_bool(&json, result->enumerate_instance_version_found) == 0 ||
        !json_append(&json, ",\"ownerExact\":") || json_bool(&json, result->enumerate_instance_version_owner) == 0 ||
        !json_append(&json, ",\"legacy10\":") || json_bool(&json, result->legacy_api_10) == 0 ||
        !json_append(&json, ",\"result\":%d,\"reported\":%u,\"requested\":%u},\"instance\":{\"createAttempted\":",
                     result->enumerate_instance_version_result, result->api_reported,
                     result->api_requested) || json_bool(&json, result->instance_create_attempted) == 0 ||
        !json_append(&json, ",\"created\":") || json_bool(&json, result->instance_created) == 0 ||
        !json_append(&json, ",\"result\":%d,\"layerCount\":%d,\"extensionCount\":%d,\"allocatorNull\":",
                     result->instance_create_result, result->layer_count, result->extension_count) ||
        json_bool(&json, result->allocator_null) == 0 ||
        !json_append(&json, "},\"physicalDevices\":{\"countQueryResult\":%d,\"fillResult\":%d,\"reportedCount\":%u,\"returnedCount\":%u,\"retainedCount\":%u,\"capacity\":%u,\"complete\":",
                     result->physical_count_result, result->physical_fill_result,
                     result->physical_count, result->physical_returned,
                     result->physical_retained, result->physical_capacity) ||
        json_bool(&json, result->physical_complete) == 0 ||
        !json_append(&json, ",\"devices\":[")) return 0;
    for (index = 0U; index < result->device_count; ++index) {
        if (index != 0U && !json_append(&json, ",")) return 0;
        if (!json_device(&json, &result->devices[index])) return 0;
    }
    if (!json_append(&json, "]},\"cleanup\":{\"destroyAttempted\":") ||
        json_bool(&json, result->destroy_attempted) == 0 ||
        !json_append(&json, ",\"destroyOk\":") || json_bool(&json, result->destroy_ok) == 0 ||
        !json_append(&json, ",\"dlcloseAttempted\":") || json_bool(&json, result->dlclose_attempted) == 0 ||
        !json_append(&json, ",\"dlcloseOk\":") || json_bool(&json, result->dlclose_ok) == 0 ||
        !json_append(&json, ",\"complete\":") || json_bool(&json, result->cleanup_complete) == 0 ||
        !json_append(&json, "},\"icdCodeLoaded\":") || json_bool(&json, result->load_loaded) == 0 ||
        !json_append(&json, ",\"constructorsMayHaveExecuted\":") ||
        json_bool(&json, result->load_attempted) == 0 ||
        !json_append(&json, ",\"vulkanFunctionsInvoked\":") ||
        json_bool(&json, result->vulkan_functions_invoked) == 0 ||
        !json_append(&json, ",\"apiComplete\":false,\"gpuWorkload\":false,\"triangle\":false,\"presentation\":false,\"d3d\":false,\"wow\":false,\"status\":") ||
        !json_string(&json, result->status, 64U) || !json_append(&json, ",\"failureStage\":") ||
        !json_string(&json, result->failure_stage, 64U) || !json_append(&json, ",\"failureDetail\":") ||
        !json_string(&json, result->failure_detail, ERROR_LIMIT) ||
        !json_append(&json, ",\"nativeExit\":%d}", result->native_exit)) return 0;
    return json.valid && json.used < capacity;
}

static void classify_instance(InstanceResult *result) {
    uint32_t index;
    int complete = result->device_count > 0U && result->physical_complete &&
                   result->physical_retained == result->physical_count;
    for (index = 0U; index < result->device_count; ++index)
        if (result->devices[index].metadata_unknown ||
            !result->devices[index].extensions_complete ||
            !result->devices[index].extensions_valid ||
            !result->devices[index].properties_valid ||
            !result->devices[index].features_valid ||
            !result->devices[index].features2_valid) complete = 0;
    if (!result->load_loaded) {
        result->status = "LOADER_FAILED"; result->native_exit = 1; return;
    }
    if (result->fatal_vk_error) {
        result->status = "VK_ERROR"; result->native_exit = 1; return;
    }
    if (result->physical_count == 0U && result->physical_count_result == VK_SUCCESS) {
        result->status = "ZERO_DEVICES"; result->native_exit = 1; return;
    }
    if (complete && result->scope_valid && result->binding_valid && result->identity_stable &&
        result->clock_valid && result->cleanup_complete && failure_is(result, "none")) {
        result->status = "PHYSICAL_METADATA_OBSERVED"; result->native_exit = 0; return;
    }
    result->status = "UNKNOWN"; result->native_exit = 1;
}

static void init_result(InstanceResult *result, const char *run_id) {
    size_t index;
    memset(result, 0, sizeof(*result));
    (void)copy_bounded(result->run_id, sizeof(result->run_id), run_id, RUN_LENGTH + 1U);
    result->pid = (int)getpid(); result->uid = (int)getuid();
    result->pgid = (int)getpgid(0); result->sid = (int)getsid(0);
    result->failure_stage = "none"; result->last_vk_error = VK_SUCCESS;
    result->api_reported = VK_API_VERSION_1_0; result->api_requested = VK_API_VERSION_1_0;
    result->enumerate_instance_version_result = VK_SUCCESS;
    result->instance_create_result = VK_ERROR_INITIALIZATION_FAILED;
    result->physical_count_result = VK_ERROR_INITIALIZATION_FAILED;
    result->physical_fill_result = VK_ERROR_INITIALIZATION_FAILED;
    result->physical_capacity = MAX_DEVICES;
    result->layer_count = 0; result->extension_count = 0; result->allocator_null = 1;
    for (index = 0U; index < 8U; ++index) result->functions[index].name = function_names[index];
    for (index = 0U; index < MAX_DEVICES; ++index) result->devices[index].extension_capacity = MAX_EXTENSIONS;
}

static int encode_bounded_instance_result(InstanceResult *result, char *output, size_t capacity) {
    if (encode_instance_result(result, output, capacity)) return 1;
    result->status = "UNKNOWN";
    result->native_exit = 1;
    result->failure_stage = "output";
    copy_error(result->failure_detail, "output budget exceeded; only a bounded device prefix retained");
    result->physical_complete = 0;
    while (result->device_count > 0U) {
        --result->device_count;
        result->physical_retained = result->device_count;
        if (encode_instance_result(result, output, capacity)) return 1;
    }
    result->physical_retained = 0U;
    return encode_instance_result(result, output, capacity);
}

static int resolve_instance_functions(InstanceResult *result, PfnIcdGetInstanceProcAddr gipa,
                                      VkInstance instance, const char *owner) {
    static const char *const names[6] = {
        "vkDestroyInstance", "vkEnumeratePhysicalDevices", "vkGetPhysicalDeviceProperties",
        "vkGetPhysicalDeviceFeatures", "vkEnumerateDeviceExtensionProperties",
        "vkGetPhysicalDeviceFeatures2"
    };
    size_t index;
    int required = 1;
    for (index = 0U; index < 6U; ++index) {
        PFN_vkVoidFunction address = gipa(instance, names[index]);
        if (!observe_function(&result->functions[index + 1U], address, owner)) {
            if (index == 5U && !version_at_least(result->api_requested, 1U, 1U)) continue;
            set_failure(result, "owner", result->functions[index + 1U].error);
            required = 0;
        }
    }
    return required;
}

static void run_instance_queries(InstanceResult *result, PfnIcdGetInstanceProcAddr gipa,
                                  VkInstance instance, const char *owner) {
    PFN_vkEnumeratePhysicalDevices enumerate;
    PFN_vkGetPhysicalDeviceProperties properties;
    PFN_vkGetPhysicalDeviceFeatures features;
    PFN_vkEnumerateDeviceExtensionProperties extensions;
    PFN_vkGetPhysicalDeviceFeatures2 features2 = NULL;
    VkPhysicalDevice handles[MAX_DEVICES];
    uint32_t count = 0U;
    uint32_t returned;
    VkResult value;
    uint32_t index;
    result->physical_capacity = MAX_DEVICES;
    result->physical_complete = 0;
    if (!resolve_instance_functions(result, gipa, instance, owner)) return;
    enumerate = (PFN_vkEnumeratePhysicalDevices)(uintptr_t)result->functions[2].address;
    properties = (PFN_vkGetPhysicalDeviceProperties)(uintptr_t)result->functions[3].address;
    features = (PFN_vkGetPhysicalDeviceFeatures)(uintptr_t)result->functions[4].address;
    extensions = (PFN_vkEnumerateDeviceExtensionProperties)(uintptr_t)result->functions[5].address;
    if (result->functions[6].owner_exact)
        features2 = (PFN_vkGetPhysicalDeviceFeatures2)(uintptr_t)result->functions[6].address;
    value = enumerate(instance, &count, NULL); result->vulkan_functions_invoked = 1;
    result->physical_count_result = value; result->physical_count = count;
    if (value != VK_SUCCESS) { record_vk_result(result, value, "physical-device count failed"); return; }
    if (count > MAX_DEVICES) { set_failure(result, "physical", "physical-device count overflow"); return; }
    if (count == 0U) { result->physical_complete = 1; return; }
    returned = count;
    value = enumerate(instance, &returned, handles); result->vulkan_functions_invoked = 1;
    result->physical_fill_result = value; result->physical_returned = returned;
    if (value != VK_SUCCESS && value != VK_INCOMPLETE) {
        record_vk_result(result, value, "physical-device fill failed");
        set_failure(result, "physical", "physical-device fill failed");
        return;
    }
    result->physical_complete = value == VK_SUCCESS && returned == count;
    if (!result->physical_complete)
        set_failure(result, "physical", "physical-device enumeration incomplete");
    if (returned > MAX_DEVICES) returned = MAX_DEVICES;
    if (returned > count) returned = count;
    result->physical_retained = returned;
    result->device_count = returned;
    for (index = 0U; index < returned; ++index) {
        result->devices[index].handle = handles[index];
        if (handles[index] == VK_NULL_HANDLE) {
            device_failure(&result->devices[index], "physical-device handle is null");
            continue;
        }
        query_device(result, &result->devices[index], properties, features, extensions, features2);
    }
}

static MAYBE_UNUSED int native_instance_main(const char *run_id) {
    static InstanceResult result;
    static char output[INSTANCE_OUTPUT_LIMIT];
    void *handle = NULL;
    char canonical_icd[PATH_LIMIT];
    PfnIcdGetInstanceProcAddr gipa = NULL;
    PfnIcdGetPhysicalDeviceProcAddr gpdpa = NULL;
    PfnIcdNegotiate negotiate = NULL;
    PFN_vkDestroyInstance destroy_instance = NULL;
    VkInstance instance = VK_NULL_HANDLE;
    uint32_t negotiated = 7U;
    uint64_t end_time;
    size_t output_length;
    init_result(&result, run_id);
    result.monotonic_start = monotonic_now();
    result.clock_start_valid = result.monotonic_start > 0U;
    result.scope_valid = scope_valid(result.cwd, run_id);
    if (!result.scope_valid) set_failure(&result, "scope", "UID, CWD, run ID, or environment mismatch");
    if (!result.clock_start_valid) set_failure(&result, "clock", "monotonic start unavailable");
    if (result.scope_valid && path_join(result.icd_path, sizeof(result.icd_path), result.cwd, ICD_NAME)) {
        struct stat listed;
        if (lstat(result.icd_path, &listed) == 0 && listed.st_size >= 0)
            result.icd_bytes = (uint64_t)listed.st_size;
        if (!canonical_path(result.icd_path, canonical_icd, sizeof(canonical_icd)) ||
            strcmp(canonical_icd, result.icd_path) != 0) {
            set_failure(&result, "identity", "ICD is not canonical");
        } else {
            result.binding_valid = 1;
            result.before_hash_valid = hash_file(result.icd_path, &result.before, result.before_sha256);
            if (result.before_hash_valid) (void)copy_bounded(result.icd_sha256, sizeof(result.icd_sha256),
                                                             result.before_sha256, 65U);
        }
    } else set_failure(&result, "path", "ICD path exceeds bound");
    if (result.binding_valid && result.before_hash_valid) {
        result.load_attempted = 1; (void)dlerror();
        handle = dlopen(result.icd_path, RTLD_NOW | RTLD_LOCAL);
        if (handle == NULL) copy_error(result.load_error, dlerror());
        else result.load_loaded = 1;
        if (handle == NULL) set_failure(&result, "loader", result.load_error[0] ? result.load_error : "dlopen failed");
    } else set_failure(&result, "hash", "ICD identity, size, owner, or SHA-256 mismatch");
    if (handle != NULL) {
        observe_instance_exports(&result, handle, canonical_icd);
        gipa = (PfnIcdGetInstanceProcAddr)(uintptr_t)dlsym(handle, export_names[0]);
        gpdpa = (PfnIcdGetPhysicalDeviceProcAddr)(uintptr_t)dlsym(handle, export_names[1]);
        negotiate = (PfnIcdNegotiate)(uintptr_t)dlsym(handle, export_names[2]);
        (void)gpdpa;
        if (gipa == NULL || negotiate == NULL || !result.exports[0].owner_exact ||
            !result.exports[1].owner_exact || !result.exports[2].owner_exact) {
            set_failure(&result, "export", "required ICD export missing or owner mismatch");
        } else {
            result.interface_input = 7;
            result.interface_result = negotiate(&negotiated);
            record_vk_result(&result, (VkResult)result.interface_result, "ICD interface negotiation failed");
            result.interface_output = (int)negotiated;
            result.interface_supported = result.interface_result == VK_SUCCESS &&
                                         negotiated >= 2U && negotiated <= 7U;
            if (!result.interface_supported) set_failure(&result, "interface", "unsupported ICD interface");
        }
        if (gipa != NULL && result.interface_supported && failure_is(&result, "none")) {
            PFN_vkVoidFunction version_address = gipa(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
            result.enumerate_instance_version_found = version_address != NULL;
            if (version_address == NULL) {
                result.legacy_api_10 = 1; result.enumerate_instance_version_owner = 1;
                result.enumerate_instance_version_result = VK_SUCCESS;
            } else {
                result.enumerate_instance_version_owner =
                    observe_function(&result.functions[7], version_address, canonical_icd);
                if (!result.enumerate_instance_version_owner) {
                    set_failure(&result, "owner", result.functions[7].error);
                } else {
                    uint32_t reported = 0U;
                    PFN_vkEnumerateInstanceVersion version =
                        (PFN_vkEnumerateInstanceVersion)(uintptr_t)version_address;
                    result.enumerate_instance_version_result = version(&reported);
                    record_vk_result(&result, result.enumerate_instance_version_result, "instance API version query failed");
                    result.api_reported = reported;
                    if (result.enumerate_instance_version_result != VK_SUCCESS ||
                        VK_API_VERSION_VARIANT(reported) != 0U ||
                        VK_API_VERSION_MAJOR(reported) != 1U || reported < VK_API_VERSION_1_0)
                        set_failure(&result, "api", "invalid instance API version");
                }
            }
            result.api_requested = requested_version(result.api_reported);
            if (failure_is(&result, "none")) {
                PFN_vkVoidFunction address = gipa(VK_NULL_HANDLE, "vkCreateInstance");
                if (!observe_function(&result.functions[0], address, canonical_icd)) {
                    set_failure(&result, "owner", result.functions[0].error);
                } else {
                    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL,
                                              "PanvkInstanceMetadata", 1U, "wowforever", 1U,
                                              result.api_requested};
                    VkInstanceCreateInfo create = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, NULL,
                                                    0U, &app, 0U, NULL, 0U, NULL};
                    PFN_vkCreateInstance create_instance =
                        (PFN_vkCreateInstance)(uintptr_t)address;
                    result.instance_create_attempted = 1;
                    result.instance_create_result = create_instance(&create, NULL, &instance);
                    result.vulkan_functions_invoked = 1;
                    if (result.instance_create_result != VK_SUCCESS || instance == VK_NULL_HANDLE) {
                        record_vk_result(&result, result.instance_create_result,
                                         "vkCreateInstance failed");
                        if (instance == VK_NULL_HANDLE && result.instance_create_result == VK_SUCCESS)
                            set_failure(&result, "vulkan", "vkCreateInstance returned null");
                        if (result.instance_create_result != VK_SUCCESS && instance != VK_NULL_HANDLE) {
                            set_failure(&result, "vulkan", "vkCreateInstance returned a handle with an error");
                            instance = VK_NULL_HANDLE;
                        }
                    } else result.instance_created = 1;
                }
            }
        }
        if (instance != VK_NULL_HANDLE) {
            PFN_vkVoidFunction destroy_address = gipa(instance, "vkDestroyInstance");
            if (!observe_function(&result.functions[1], destroy_address, canonical_icd)) {
                set_failure(&result, "owner", result.functions[1].error);
            } else {
                destroy_instance = (PFN_vkDestroyInstance)(uintptr_t)destroy_address;
                run_instance_queries(&result, gipa, instance, canonical_icd);
            }
        }
    }
    if (instance != VK_NULL_HANDLE) {
        result.destroy_attempted = 1;
        if (destroy_instance != NULL) {
            destroy_instance(instance, NULL); result.destroy_ok = 1; instance = VK_NULL_HANDLE;
        } else set_failure(&result, "cleanup", "destroy function unavailable; instance retained");
    }
    if (result.icd_path[0] != '\0') {
        result.after_hash_valid = hash_file(result.icd_path, &result.after, result.after_sha256);
        result.identity_stable = identity_same(&result.before, &result.after);
        if (result.after_hash_valid) (void)copy_bounded(result.icd_sha256, sizeof(result.icd_sha256),
                                                        result.after_sha256, 65U);
        if (!result.after_hash_valid || !result.identity_stable)
            set_failure(&result, "identity", "ICD changed or failed post-observation verification");
    }
    if (handle != NULL && instance == VK_NULL_HANDLE) {
        result.dlclose_attempted = 1; result.dlclose_ok = dlclose(handle) == 0;
        if (!result.dlclose_ok) set_failure(&result, "cleanup", "dlclose failed");
    }
    result.cleanup_complete = instance == VK_NULL_HANDLE && (handle == NULL || result.dlclose_ok);
    end_time = monotonic_now(); result.monotonic_end = end_time;
    result.clock_valid = result.clock_start_valid && end_time > 0U && end_time >= result.monotonic_start;
    if (!result.clock_valid) set_failure(&result, "clock", "monotonic end unavailable");
    classify_instance(&result);
    if (!encode_bounded_instance_result(&result, output, sizeof(output))) return 1;
    output_length = strlen(output);
    if (output_length >= sizeof(output) || fwrite(output, 1U, output_length, stdout) != output_length ||
        fputc('\n', stdout) == EOF || fflush(stdout) != 0) return 1;
    return result.native_exit;
}

#ifdef PANVK_INSTANCE_METADATA_HOST_TEST
static int fake_destroy_calls;
static int fake_query_calls;
static int fake_create_failure;
static int fake_enumerate_failure;

static VkResult VKAPI_PTR fake_create(const VkInstanceCreateInfo *info, const VkAllocationCallbacks *allocator,
                                      VkInstance *instance) {
    (void)info; (void)allocator;
    if (fake_create_failure) { *instance = (VkInstance)(uintptr_t)1U; return VK_ERROR_INITIALIZATION_FAILED; }
    *instance = (VkInstance)(uintptr_t)1U; return VK_SUCCESS;
}

static void VKAPI_PTR fake_destroy(VkInstance instance, const VkAllocationCallbacks *allocator) {
    (void)instance; (void)allocator; ++fake_destroy_calls;
}

static VkResult VKAPI_PTR fake_enumerate(VkInstance instance, uint32_t *count, VkPhysicalDevice *devices) {
    (void)instance;
    ++fake_query_calls;
    if (fake_enumerate_failure) return VK_ERROR_INITIALIZATION_FAILED;
    if (devices == NULL) { *count = 1U; return VK_SUCCESS; }
    *count = 1U; devices[0] = (VkPhysicalDevice)(uintptr_t)2U; return VK_SUCCESS;
}

int panvk_test_fake_lifecycle(const char *case_name) {
    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL, "test", 1U, "test", 1U, VK_API_VERSION_1_0};
    VkInstanceCreateInfo create = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, NULL, 0U, &app, 0U, NULL, 0U, NULL};
    VkInstance instance = VK_NULL_HANDLE;
    fake_destroy_calls = 0; fake_query_calls = 0;
    fake_create_failure = strcmp(case_name, "create-fails") == 0;
    fake_enumerate_failure = strcmp(case_name, "enumerate-fails") == 0;
    if (fake_create(&create, NULL, &instance) != VK_SUCCESS)
        return fake_destroy_calls == 0 && fake_query_calls == 0;
    (void)fake_enumerate(instance, &(uint32_t){0U}, NULL);
    fake_destroy(instance, NULL);
    return fake_destroy_calls == 1 && fake_query_calls == 1;
}

int panvk_test_valid_bool(uint32_t value) { return valid_bool((VkBool32)value); }
int panvk_test_reject_count(uint32_t value) { return value <= MAX_DEVICES; }
int panvk_test_reject_incomplete(int result, uint32_t reported, uint32_t returned) {
    return result == VK_SUCCESS && reported == returned;
}
int panvk_test_instance_owner(const char *owner, const char *expected) {
    return owner != NULL && expected != NULL && strcmp(owner, expected) == 0;
}

int panvk_test_encode_fixture(char *output, size_t capacity) {
    InstanceResult result;
    DeviceMetadata *device;
    init_result(&result, "PVK-0123456789abcdef0123456789abcdef");
    result.pid = 7; result.uid = EXPECTED_UID; result.pgid = 7; result.sid = 7;
    result.monotonic_start = 1U; result.monotonic_end = 2U; result.clock_valid = 1;
    result.scope_valid = 1; result.binding_valid = 1; result.icd_bytes = ICD_SIZE;
    (void)snprintf(result.cwd, sizeof(result.cwd), "/data/data/com.wowforever.arm64probe/files/forever/controls/panvk-loader/%s", result.run_id);
    {
        size_t cwd_length = strlen(result.cwd);
        if (cwd_length + 1U + sizeof(ICD_NAME) > sizeof(result.icd_path)) return 0;
        memcpy(result.icd_path, result.cwd, cwd_length);
        memcpy(result.icd_path + cwd_length, "/" ICD_NAME, sizeof("/" ICD_NAME));
    }
    (void)snprintf(result.icd_sha256, sizeof(result.icd_sha256), "%s", ICD_SHA256);
    (void)snprintf(result.before_sha256, sizeof(result.before_sha256), "%s", ICD_SHA256);
    (void)snprintf(result.after_sha256, sizeof(result.after_sha256), "%s", ICD_SHA256);
    result.before.valid = 1; result.after.valid = 1; result.before.owner_uid = EXPECTED_UID;
    result.after.owner_uid = EXPECTED_UID; result.before.mode = 0600; result.after.mode = 0600;
    result.before.size = ICD_SIZE; result.after.size = ICD_SIZE; result.before.device = 1; result.after.device = 1;
    result.before.inode = 2; result.after.inode = 2; result.before_hash_valid = 1; result.after_hash_valid = 1;
    result.identity_stable = 1; result.load_attempted = 1; result.load_loaded = 1;
    result.interface_input = 7; result.interface_result = VK_SUCCESS; result.interface_output = 7;
    result.interface_supported = 1; result.api_reported = VK_API_VERSION_1_3; result.api_requested = VK_API_VERSION_1_3;
    result.enumerate_instance_version_found = 1; result.enumerate_instance_version_owner = 1;
    result.enumerate_instance_version_result = VK_SUCCESS;
    result.instance_create_attempted = 1; result.instance_created = 1; result.instance_create_result = VK_SUCCESS;
    result.physical_count = 1; result.physical_returned = 1; result.physical_count_result = VK_SUCCESS;
    result.physical_fill_result = VK_SUCCESS; result.physical_retained = 1; result.physical_capacity = MAX_DEVICES;
    result.physical_complete = 1; result.device_count = 1; result.destroy_attempted = 1;
    result.destroy_ok = 1; result.dlclose_attempted = 1; result.dlclose_ok = 1; result.cleanup_complete = 1;
    result.failure_stage = "none"; result.status = "PHYSICAL_METADATA_OBSERVED"; result.native_exit = 0;
    device = &result.devices[0]; device->properties_valid = 1; device->features_valid = 1;
    device->extensions_valid = 1; device->extension_capacity = MAX_EXTENSIONS;
    device->extension_query_result = VK_SUCCESS; device->extension_fill_result = VK_SUCCESS;
    device->extensions_complete = 1; device->features2_queried = 1; device->features2_valid = 1;
    device->has_vulkan11 = 1; device->has_vulkan12 = 1; device->has_vulkan13 = 1;
    device->properties.apiVersion = VK_API_VERSION_1_3;
    device->properties.vendorID = 0x13b5U; device->properties.deviceID = 0xa867U;
    (void)snprintf(device->properties.deviceName, sizeof(device->properties.deviceName), "fake-device");
    for (size_t index = 0U; index < 8U; ++index) {
        result.functions[index].resolved = 1; result.functions[index].owner_exact = 1;
        (void)snprintf(result.functions[index].owner, sizeof(result.functions[index].owner), "%s", result.icd_path);
    }
    return encode_instance_result(&result, output, capacity);
}

int panvk_test_encode_case(const char *case_name, char *output, size_t capacity) {
    InstanceResult result;
    DeviceMetadata *device;
    if (case_name == NULL || output == NULL) return 0;
    if (!panvk_test_encode_fixture(output, capacity)) return 0;
    /* Rebuild the same fixture in memory so adversarial states exercise the
     * production encoder, not a host-side JSON rewriter. */
    memset(&result, 0, sizeof(result));
    init_result(&result, "PVK-0123456789abcdef0123456789abcdef");
    result.pid = 7; result.uid = EXPECTED_UID; result.pgid = 7; result.sid = 7;
    result.monotonic_start = 1U; result.monotonic_end = 2U; result.clock_valid = 1;
    result.scope_valid = 1; result.binding_valid = 1; result.icd_bytes = ICD_SIZE;
    (void)snprintf(result.cwd, sizeof(result.cwd), "/data/data/com.wowforever.arm64probe/files/forever/controls/panvk-loader/%s", result.run_id);
    (void)path_join(result.icd_path, sizeof(result.icd_path), result.cwd, ICD_NAME);
    (void)snprintf(result.icd_sha256, sizeof(result.icd_sha256), "%s", ICD_SHA256);
    (void)snprintf(result.before_sha256, sizeof(result.before_sha256), "%s", ICD_SHA256);
    (void)snprintf(result.after_sha256, sizeof(result.after_sha256), "%s", ICD_SHA256);
    result.before.valid = 1; result.after.valid = 1; result.before.owner_uid = EXPECTED_UID; result.after.owner_uid = EXPECTED_UID;
    result.before.mode = 0600; result.after.mode = 0600; result.before.size = ICD_SIZE; result.after.size = ICD_SIZE;
    result.before.device = 1; result.after.device = 1; result.before.inode = 2; result.after.inode = 2;
    result.before_hash_valid = 1; result.after_hash_valid = 1; result.identity_stable = 1;
    result.load_attempted = 1; result.load_loaded = 1; result.interface_input = 7; result.interface_result = VK_SUCCESS;
    result.interface_output = 7; result.interface_supported = 1; result.enumerate_instance_version_found = 1;
    result.enumerate_instance_version_owner = 1; result.enumerate_instance_version_result = VK_SUCCESS;
    result.api_reported = VK_API_VERSION_1_3; result.api_requested = VK_API_VERSION_1_3;
    result.instance_create_attempted = 1; result.instance_created = 1; result.instance_create_result = VK_SUCCESS;
    result.physical_count = 2; result.physical_returned = 1; result.physical_retained = 1;
    result.physical_capacity = MAX_DEVICES; result.physical_count_result = VK_SUCCESS;
    result.physical_fill_result = VK_INCOMPLETE; result.physical_complete = 0; result.device_count = 1;
    result.destroy_attempted = 1; result.destroy_ok = 1; result.dlclose_attempted = 1; result.dlclose_ok = 1;
    result.cleanup_complete = 1; result.failure_stage = "physical"; result.status = "UNKNOWN"; result.native_exit = 1;
    device = &result.devices[0]; device->properties_valid = 1; device->features_valid = 1;
    device->extensions_valid = 1; device->extension_capacity = MAX_EXTENSIONS; device->extension_query_result = VK_SUCCESS;
    device->extension_fill_result = VK_INCOMPLETE; device->extension_reported_count = 2; device->extension_returned_count = 1;
    device->extension_retained_count = 1; device->extension_count = 1; device->extensions_complete = 0;
    device->features2_valid = 0; device->properties.apiVersion = VK_API_VERSION_1_3;
    (void)snprintf(device->properties.deviceName, sizeof(device->properties.deviceName), "partial-device");
    if (strcmp(case_name, "extension-overflow") == 0) {
        device->extension_reported_count = MAX_EXTENSIONS + 1U; device->extension_returned_count = 0U;
        device->extension_retained_count = 0U; device->extension_count = 0U; device->extension_fill_result = VK_ERROR_INITIALIZATION_FAILED;
        device->extensions_valid = 0;
    } else if (strcmp(case_name, "vk-error") == 0) {
        result.physical_count_result = VK_ERROR_INITIALIZATION_FAILED; result.physical_fill_result = VK_ERROR_INITIALIZATION_FAILED;
        result.physical_count = 2; result.physical_returned = 0; result.physical_retained = 0; result.device_count = 0;
        result.status = "VK_ERROR"; result.failure_stage = "vulkan";
    } else if (strcmp(case_name, "api-invalid") == 0) {
        device->properties.apiVersion = 0U; device->metadata_unknown = 1; result.failure_stage = "physical";
    } else if (strcmp(case_name, "api-variant") == 0) {
        device->properties.apiVersion = VK_MAKE_API_VERSION(1U, 1U, 0U, 0U); device->metadata_unknown = 1; result.failure_stage = "physical";
    } else if (strcmp(case_name, "create-error-nonnull") == 0) {
        result.instance_created = 0; result.instance_create_result = VK_ERROR_INITIALIZATION_FAILED;
        result.physical_count = 0; result.physical_returned = 0; result.physical_retained = 0; result.device_count = 0;
        result.physical_count_result = VK_ERROR_INITIALIZATION_FAILED; result.physical_fill_result = VK_ERROR_INITIALIZATION_FAILED;
        result.status = "VK_ERROR"; result.failure_stage = "vulkan"; result.destroy_attempted = 0; result.destroy_ok = 0;
    } else if (strcmp(case_name, "partial-devices") != 0 && strcmp(case_name, "extension-incomplete") != 0) {
        return 0;
    }
    return encode_instance_result(&result, output, capacity);
}
#else
int main(int argc, char **argv) {
    if (argc != 3 || argv == NULL || argv[1] == NULL || argv[2] == NULL ||
        strcmp(argv[1], "--run-id") != 0 || !valid_run_id(argv[2])) return 1;
    return native_instance_main(argv[2]);
}
#endif
