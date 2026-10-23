#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

/*
 * PanVK control A.  The private loader helper is included verbatim: it owns
 * the immutable scope, file-identity, SHA-256, JSON escaping and dladdr
 * primitives.  This translation unit adds only the one-device/one-queue
 * lifecycle; it deliberately has no resource or submission path.
 */
#define main panvk_device_queue_legacy_loader_main
#define set_failure panvk_device_queue_loader_set_failure
#define failure_is panvk_device_queue_loader_failure_is
#define observe_exports panvk_device_queue_loader_observe_exports
#include "PanvkPrivateLoader.c"
#undef main
#undef set_failure
#undef failure_is
#undef observe_exports

#include "/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include/vulkan/vulkan_core.h"

#include <stddef.h>

#define DEVICE_QUEUE_SCHEMA "wowforever.android-panvk-device-queue"
#define DEVICE_QUEUE_OUTPUT_LIMIT (64U * 1024U)
#define MAX_PHYSICAL_DEVICES 8U
#define MAX_QUEUE_FAMILIES 16U
#define MAX_FUNCTIONS 10U
#define EXPECTED_VENDOR UINT32_C(0x13b5)
#define EXPECTED_DEVICE UINT32_C(0xa8670000)
#define EXPECTED_NAME "Mali-G610 MC6"

typedef PFN_vkVoidFunction (VKAPI_PTR *DeviceGetInstanceProcAddr)(VkInstance, const char *);
typedef PFN_vkVoidFunction (VKAPI_PTR *DeviceGetDeviceProcAddr)(VkDevice, const char *);
typedef VkResult (VKAPI_PTR *DeviceNegotiate)(uint32_t *);

typedef struct DeviceFunction {
    const char *name;
    PFN_vkVoidFunction address;
    char owner[PATH_LIMIT];
    int resolved;
    int owner_exact;
    char error[ERROR_LIMIT + 1U];
} DeviceFunction;

typedef struct QueueFamilyObservation {
    uint32_t index;
    VkQueueFlags flags;
    uint32_t queue_count;
} QueueFamilyObservation;

typedef struct DeviceQueueResult {
    char run_id[RUN_LENGTH + 1U];
    int pid;
    int uid;
    int pgid;
    int sid;
    uint64_t monotonic_start;
    uint64_t monotonic_end;
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
    DeviceFunction functions[MAX_FUNCTIONS];

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
    int instance_handle_returned_on_error;
    VkResult instance_create_result;
    int layer_count;
    int extension_count;
    int allocator_null;

    uint32_t physical_reported_count;
    uint32_t physical_returned_count;
    uint32_t physical_retained_count;
    VkResult physical_count_result;
    VkResult physical_fill_result;
    uint32_t physical_capacity;
    int physical_complete;
    uint32_t physical_vendor_id;
    uint32_t physical_device_id;
    char physical_name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE];
    int physical_properties_valid;
    int physical_target_exact;

    uint32_t queue_reported_count;
    uint32_t queue_returned_count;
    uint32_t queue_retained_count;
    uint32_t queue_capacity;
    int queue_count_queried;
    int queue_fill_queried;
    int queue_complete;
    QueueFamilyObservation queue_families[MAX_QUEUE_FAMILIES];
    uint32_t selected_family;
    VkQueueFlags selected_flags;
    uint32_t selected_queue_count;
    char selected_kind[16];
    int selected;

    int device_create_attempted;
    int device_created;
    int device_live;
    int device_handle_returned_on_error;
    VkResult device_create_result;
    int device_queue_create_count;
    uint32_t device_queue_family;
    uint32_t device_queue_count;
    float device_queue_priority;
    int device_features_null;
    int device_layers_count;
    int device_extensions_count;
    int device_allocator_null;

    int queue_get_attempted;
    uint32_t queue_index;
    int queue_non_null;
    int queue_observed;

    int device_destroy_attempted;
    int device_destroy_ok;
    int instance_destroy_attempted;
    int instance_destroy_ok;
    int dlclose_attempted;
    int dlclose_ok;
    int cleanup_complete;

    uint32_t call_count;
    uint32_t monotonic_checks;
    uint64_t last_call_ns;
    int timing_valid;
    int clock_start_valid;
    int clock_valid;
    int vk_error;
    const char *status;
    const char *failure_stage;
    char failure_detail[ERROR_LIMIT + 1U];
    int native_exit;
} DeviceQueueResult;

#ifdef PANVK_DEVICE_QUEUE_HOST_TEST
static int fake_owner_mode;
static int fake_clock_fail_after_create;
#endif

static const char *const device_function_names[MAX_FUNCTIONS] = {
    "vkEnumerateInstanceVersion", "vkCreateInstance", "vkDestroyInstance",
    "vkEnumeratePhysicalDevices", "vkGetPhysicalDeviceProperties",
    "vkGetPhysicalDeviceQueueFamilyProperties", "vkCreateDevice", "vkDestroyDevice",
    "vkGetDeviceProcAddr", "vkGetDeviceQueue"
};

static void device_set_failure(DeviceQueueResult *result, const char *stage,
                               const char *detail) {
    if (result->failure_stage == NULL || strcmp(result->failure_stage, "none") == 0) {
        result->failure_stage = stage;
        copy_error(result->failure_detail, detail);
    }
}

static int device_failure_is(const DeviceQueueResult *result, const char *stage) {
    return result != NULL && result->failure_stage != NULL && stage != NULL &&
           strcmp(result->failure_stage, stage) == 0;
}

static void device_record_result(DeviceQueueResult *result, VkResult value,
                                 const char *detail) {
    if (value < 0) {
        result->vk_error = 1;
        device_set_failure(result, "vulkan", detail);
    } else if (value != VK_SUCCESS) {
        device_set_failure(result, "result", detail);
    }
}

static int device_call_begin(DeviceQueueResult *result, uint64_t *before) {
#ifdef PANVK_DEVICE_QUEUE_HOST_TEST
    if (fake_clock_fail_after_create && result->device_live) {
        result->timing_valid = 0;
        device_set_failure(result, "clock", "fake monotonic failure after device creation");
        return 0;
    }
#endif
    uint64_t value = monotonic_now();
    if (before == NULL || value == 0U ||
        (result->last_call_ns != 0U && value < result->last_call_ns)) {
        result->timing_valid = 0;
        device_set_failure(result, "clock", "monotonic check before Vulkan call failed");
        return 0;
    }
    *before = value;
    result->last_call_ns = value;
    ++result->call_count;
    ++result->monotonic_checks;
    return 1;
}

static int device_call_end(DeviceQueueResult *result, uint64_t before) {
    uint64_t value = monotonic_now();
    if (value == 0U || value < before ||
        (result->last_call_ns != 0U && value < result->last_call_ns)) {
        result->timing_valid = 0;
        device_set_failure(result, "clock", "monotonic check after Vulkan call failed");
        return 0;
    }
    result->last_call_ns = value;
    ++result->monotonic_checks;
    return 1;
}

static int device_observe_function(DeviceFunction *observation,
                                   PFN_vkVoidFunction address,
                                   const char *owner) {
    observation->address = address;
    observation->resolved = address != NULL;
    observation->owner_exact = 0;
    if (!observation->resolved) {
        copy_error(observation->error, "function not returned");
        return 0;
    }
#ifdef PANVK_DEVICE_QUEUE_HOST_TEST
    if (fake_owner_mode) {
        observation->owner_exact = 1;
        (void)copy_bounded(observation->owner, sizeof(observation->owner), owner,
                           PATH_LIMIT - 1U);
        return 1;
    }
#endif
    if (!address_owner((void *)(uintptr_t)address, observation->owner,
                       sizeof(observation->owner))) {
        copy_error(observation->error, "dladdr failed");
        return 0;
    }
    observation->owner_exact = owner != NULL && strcmp(observation->owner, owner) == 0;
    if (!observation->owner_exact) copy_error(observation->error, "owner mismatch");
    return observation->owner_exact;
}

static MAYBE_UNUSED void device_observe_exports(DeviceQueueResult *result, void *handle,
                                                const char *owner) {
    size_t index;
    for (index = 0U; index < 3U; ++index) {
        void *symbol;
        const char *error;
        (void)dlerror();
        symbol = dlsym(handle, export_names[index]);
        error = dlerror();
        result->exports[index].found = symbol != NULL && error == NULL;
        result->exports[index].owner_exact = 0;
        if (!result->exports[index].found) {
            copy_error(result->exports[index].error, error != NULL ? error : "symbol not found");
        } else if (!address_owner(symbol, result->exports[index].owner,
                                  sizeof(result->exports[index].owner))) {
            copy_error(result->exports[index].error, "dladdr failed");
        } else {
            result->exports[index].owner_exact = owner != NULL &&
                strcmp(result->exports[index].owner, owner) == 0;
            if (!result->exports[index].owner_exact)
                copy_error(result->exports[index].error, "owner mismatch");
        }
    }
}

static int device_resolve_instance(DeviceQueueResult *result,
                                    DeviceGetInstanceProcAddr gipa,
                                    VkInstance instance, const char *owner,
                                    size_t index, const char *name) {
    PFN_vkVoidFunction address;
    if (gipa == NULL || index >= MAX_FUNCTIONS) return 0;
    address = gipa(instance, name);
    return device_observe_function(&result->functions[index], address, owner);
}

static int device_select_queue(const VkQueueFamilyProperties *families,
                               uint32_t count, uint32_t *selected,
                               const char **kind) {
    uint32_t index;
    if (families == NULL || selected == NULL || kind == NULL || count > MAX_QUEUE_FAMILIES)
        return 0;
    for (index = 0U; index < count; ++index) {
        if (families[index].queueCount == 0U) continue;
        if ((families[index].queueFlags & VK_QUEUE_TRANSFER_BIT) != 0U) {
            *selected = index; *kind = "transfer"; return 1;
        }
        if ((families[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0U) {
            *selected = index; *kind = "graphics"; return 1;
        }
        if ((families[index].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0U) {
            *selected = index; *kind = "compute"; return 1;
        }
    }
    return 0;
}

static void device_init_result(DeviceQueueResult *result, const char *run_id) {
    size_t index;
    memset(result, 0, sizeof(*result));
    (void)copy_bounded(result->run_id, sizeof(result->run_id), run_id, RUN_LENGTH + 1U);
    result->pid = (int)getpid();
    result->uid = (int)getuid();
    result->pgid = (int)getpgid(0);
    result->sid = (int)getsid(0);
    result->failure_stage = "none";
    result->timing_valid = 1;
    result->enumerate_instance_version_result = VK_SUCCESS;
    result->instance_create_result = VK_ERROR_INITIALIZATION_FAILED;
    result->physical_count_result = VK_ERROR_INITIALIZATION_FAILED;
    result->physical_fill_result = VK_ERROR_INITIALIZATION_FAILED;
    result->device_create_result = VK_ERROR_INITIALIZATION_FAILED;
    result->api_reported = VK_API_VERSION_1_0;
    result->api_requested = VK_API_VERSION_1_0;
    result->physical_capacity = MAX_PHYSICAL_DEVICES;
    result->queue_capacity = MAX_QUEUE_FAMILIES;
    result->allocator_null = 1;
    result->device_features_null = 1;
    result->device_allocator_null = 1;
    for (index = 0U; index < MAX_FUNCTIONS; ++index)
        result->functions[index].name = device_function_names[index];
}

static int device_json_function(Json *json, const DeviceFunction *function) {
    return json_append(json, "{\"name\":") && json_string(json, function->name, PATH_LIMIT) &&
           json_append(json, ",\"resolved\":") && json_bool(json, function->resolved) &&
           json_append(json, ",\"owner\":") && json_string(json, function->owner, PATH_LIMIT) &&
           json_append(json, ",\"ownerExact\":") && json_bool(json, function->owner_exact) &&
           json_append(json, ",\"error\":") && json_string(json, function->error, ERROR_LIMIT) &&
           json_append(json, "}");
}

static int device_json_queues(Json *json, const DeviceQueueResult *result) {
    uint32_t index;
    if (!json_append(json, "{\"reportedCount\":%u,\"returnedCount\":%u,\"retainedCount\":%u,\"capacity\":%u,\"complete\":",
                     result->queue_reported_count, result->queue_returned_count,
                     result->queue_retained_count, result->queue_capacity) ||
        !json_bool(json, result->queue_complete) ||
        !json_append(json, ",\"families\":[")) return 0;
    for (index = 0U; index < result->queue_retained_count; ++index) {
        if ((index != 0U && !json_append(json, ",")) ||
            !json_append(json, "{\"index\":%u,\"flags\":%u,\"queueCount\":%u}",
                         result->queue_families[index].index,
                         (uint32_t)result->queue_families[index].flags,
                         result->queue_families[index].queue_count)) return 0;
    }
    return json_append(json, "]}");
}

static int device_encode_result(const DeviceQueueResult *result, char *output,
                                size_t capacity) {
    Json json = {output, capacity, 0U, 1};
    size_t index;
    if (result == NULL || output == NULL || capacity == 0U) return 0;
    if (!json_append(&json, "{\"schema\":") ||
        !json_string(&json, DEVICE_QUEUE_SCHEMA, sizeof(DEVICE_QUEUE_SCHEMA)) ||
        !json_append(&json, ",\"version\":1,\"runId\":") ||
        !json_string(&json, result->run_id, RUN_LENGTH) ||
        !json_append(&json, ",\"pid\":%d,\"uid\":%d,\"pgid\":%d,\"sid\":%d,\"monotonicStartNs\":%" PRIu64 ",\"monotonicEndNs\":%" PRIu64 ",\"cwd\":",
                     result->pid, result->uid, result->pgid, result->sid,
                     result->monotonic_start, result->monotonic_end) ||
        !json_string(&json, result->cwd, PATH_LIMIT) ||
        !json_append(&json, ",\"scopeValid\":") || !json_bool(&json, result->scope_valid) ||
        !json_append(&json, ",\"icdPath\":") || !json_string(&json, result->icd_path, PATH_LIMIT) ||
        !json_append(&json, ",\"icdBytes\":%" PRIu64 ",\"icdSha256\":", result->icd_bytes) ||
        !json_string(&json, result->icd_sha256, 64U) ||
        !json_append(&json, ",\"icdIdentityBefore\":") || !json_identity(&json, &result->before) ||
        !json_append(&json, ",\"icdIdentityAfter\":") || !json_identity(&json, &result->after) ||
        !json_append(&json, ",\"icdIdentityStable\":") || !json_bool(&json, result->identity_stable) ||
        !json_append(&json, ",\"load\":{\"attempted\":") || !json_bool(&json, result->load_attempted) ||
        !json_append(&json, ",\"loaded\":") || !json_bool(&json, result->load_loaded) ||
        !json_append(&json, ",\"dlerror\":") || !json_string(&json, result->load_error, ERROR_LIMIT) ||
        !json_append(&json, "},\"exports\":[")) return 0;
    for (index = 0U; index < 3U; ++index) {
        if ((index != 0U && !json_append(&json, ",")) ||
            !json_export(&json, &result->exports[index], export_names[index])) return 0;
    }
    if (!json_append(&json, "],\"interface\":{\"input\":%d,\"result\":%d,\"output\":%d,\"supported\":",
                     result->interface_input, result->interface_result, result->interface_output) ||
        !json_bool(&json, result->interface_supported) ||
        !json_append(&json, "},\"functions\":[")) return 0;
    for (index = 0U; index < MAX_FUNCTIONS; ++index) {
        if ((index != 0U && !json_append(&json, ",")) ||
            !device_json_function(&json, &result->functions[index])) return 0;
    }
    if (!json_append(&json, "],\"api\":{\"enumerateInstanceVersionFound\":") ||
        !json_bool(&json, result->enumerate_instance_version_found) ||
        !json_append(&json, ",\"ownerExact\":") ||
        !json_bool(&json, result->enumerate_instance_version_owner) ||
        !json_append(&json, ",\"legacy10\":") || json_bool(&json, result->legacy_api_10) == 0 ||
        !json_append(&json, ",\"result\":%d,\"reported\":%u,\"requested\":%u},\"instance\":{\"createAttempted\":",
                     result->enumerate_instance_version_result, result->api_reported,
                     result->api_requested) || !json_bool(&json, result->instance_create_attempted) ||
        !json_append(&json, ",\"created\":") || !json_bool(&json, result->instance_created) ||
        !json_append(&json, ",\"handleReturnedOnError\":") ||
        !json_bool(&json, result->instance_handle_returned_on_error) ||
        !json_append(&json, ",\"result\":%d,\"layerCount\":%d,\"extensionCount\":%d,\"allocatorNull\":",
                     result->instance_create_result, result->layer_count, result->extension_count) ||
        !json_bool(&json, result->allocator_null) ||
        !json_append(&json, "},\"physical\":{\"reportedCount\":%u,\"returnedCount\":%u,\"retainedCount\":%u,\"capacity\":%u,\"countResult\":%d,\"fillResult\":%d,\"complete\":",
                     result->physical_reported_count, result->physical_returned_count,
                     result->physical_retained_count, result->physical_capacity,
                     result->physical_count_result, result->physical_fill_result) ||
        !json_bool(&json, result->physical_complete) ||
        !json_append(&json, ",\"vendorId\":%u,\"deviceId\":%u,\"name\":",
                     result->physical_vendor_id, result->physical_device_id) ||
        !json_string(&json, result->physical_name, VK_MAX_PHYSICAL_DEVICE_NAME_SIZE) ||
        !json_append(&json, ",\"propertiesValid\":") ||
        !json_bool(&json, result->physical_properties_valid) ||
        !json_append(&json, ",\"targetExact\":") ||
        !json_bool(&json, result->physical_target_exact) || !json_append(&json, "},\"queues\":") ||
        !device_json_queues(&json, result) ||
        !json_append(&json, ",\"selection\":{\"selected\":") ||
        !json_bool(&json, result->selected) ||
        !json_append(&json, ",\"familyIndex\":%u,\"flags\":%u,\"queueCount\":%u,\"kind\":",
                     result->selected_family, (uint32_t)result->selected_flags,
                     result->selected_queue_count) ||
        !json_string(&json, result->selected_kind, sizeof(result->selected_kind)) ||
        !json_append(&json, "},\"device\":{\"createAttempted\":") ||
        !json_bool(&json, result->device_create_attempted) ||
        !json_append(&json, ",\"created\":") || !json_bool(&json, result->device_created) ||
        !json_append(&json, ",\"handleReturnedOnError\":") ||
        !json_bool(&json, result->device_handle_returned_on_error) ||
        !json_append(&json, ",\"result\":%d,\"queueCreateCount\":%d,\"queueFamilyIndex\":%u,\"queueCount\":%u,\"priority\":%.1f,\"featuresNull\":",
                     result->device_create_result, result->device_queue_create_count,
                     result->device_queue_family, result->device_queue_count,
                     (double)result->device_queue_priority) ||
        !json_bool(&json, result->device_features_null) ||
        !json_append(&json, ",\"layerCount\":%d,\"extensionCount\":%d,\"allocatorNull\":",
                     result->device_layers_count, result->device_extensions_count) ||
        !json_bool(&json, result->device_allocator_null) ||
        !json_append(&json, "},\"queue\":{\"getAttempted\":") ||
        !json_bool(&json, result->queue_get_attempted) ||
        !json_append(&json, ",\"index\":%u,\"nonNull\":", result->queue_index) ||
        !json_bool(&json, result->queue_non_null) ||
        !json_append(&json, ",\"observed\":") || !json_bool(&json, result->queue_observed) ||
        !json_append(&json, "},\"timing\":{\"callCount\":%u,\"monotonicChecks\":%u,\"valid\":",
                     result->call_count, result->monotonic_checks) ||
        !json_bool(&json, result->timing_valid) ||
        !json_append(&json, "},\"cleanup\":{\"deviceDestroyAttempted\":") ||
        !json_bool(&json, result->device_destroy_attempted) ||
        !json_append(&json, ",\"deviceDestroyOk\":") || !json_bool(&json, result->device_destroy_ok) ||
        !json_append(&json, ",\"instanceDestroyAttempted\":") ||
        !json_bool(&json, result->instance_destroy_attempted) ||
        !json_append(&json, ",\"instanceDestroyOk\":") || !json_bool(&json, result->instance_destroy_ok) ||
        !json_append(&json, ",\"dlcloseAttempted\":") || !json_bool(&json, result->dlclose_attempted) ||
        !json_append(&json, ",\"dlcloseOk\":") || !json_bool(&json, result->dlclose_ok) ||
        !json_append(&json, ",\"complete\":") || !json_bool(&json, result->cleanup_complete) ||
        !json_append(&json, "},\"icdCodeLoaded\":") || !json_bool(&json, result->load_loaded) ||
        !json_append(&json, ",\"constructorsMayHaveExecuted\":") ||
        !json_bool(&json, result->load_attempted) ||
        !json_append(&json, ",\"vulkanFunctionsInvoked\":") ||
        !json_bool(&json, result->call_count != 0U) ||
        !json_append(&json, ",\"apiComplete\":false,\"gpuWorkload\":false,\"triangle\":false,\"presentation\":false,\"d3d\":false,\"wow\":false,\"status\":") ||
        !json_string(&json, result->status, 64U) || !json_append(&json, ",\"failureStage\":") ||
        !json_string(&json, result->failure_stage, 64U) || !json_append(&json, ",\"failureDetail\":") ||
        !json_string(&json, result->failure_detail, ERROR_LIMIT) ||
        !json_append(&json, ",\"nativeExit\":%d}", result->native_exit)) return 0;
    return json.valid && json.used < capacity;
}

static void device_classify(DeviceQueueResult *result) {
    int success = result->scope_valid && result->binding_valid && result->identity_stable &&
                  result->load_loaded && result->interface_supported &&
                  result->instance_created && result->physical_complete &&
                  result->physical_returned_count == 1U && result->physical_target_exact &&
                  result->queue_complete && result->selected && result->device_created &&
                  result->queue_observed && result->device_destroy_ok &&
                  result->instance_destroy_ok && result->dlclose_ok &&
                  result->cleanup_complete && result->clock_valid && result->timing_valid &&
                  device_failure_is(result, "none");
    if (!result->load_loaded && result->load_attempted && result->load_error[0] != '\0' &&
        result->cleanup_complete && device_failure_is(result, "loader")) {
        result->status = "LOADER_FAILED";
    } else if (success) {
        result->status = "DEVICE_QUEUE_OBSERVED";
    } else if (!result->vk_error && device_failure_is(result, "no_queue")) {
        result->status = "NO_QUEUE";
    } else if (result->vk_error) {
        result->status = "VK_ERROR";
    } else {
        result->status = "UNKNOWN";
    }
    result->native_exit = strcmp(result->status, "DEVICE_QUEUE_OBSERVED") == 0 ? 0 : 1;
}

static int device_resolve_device_function(DeviceQueueResult *result,
                                          DeviceGetDeviceProcAddr gdpa,
                                          VkDevice device, const char *owner,
                                          size_t index, const char *name) {
    PFN_vkVoidFunction address;
    if (gdpa == NULL || index >= MAX_FUNCTIONS) return 0;
    address = gdpa(device, name);
    return device_observe_function(&result->functions[index], address, owner);
}

static void device_control(DeviceQueueResult *result, DeviceGetInstanceProcAddr gipa,
                           VkInstance instance, VkPhysicalDevice physical,
                           const char *owner) {
    PFN_vkGetPhysicalDeviceQueueFamilyProperties get_families;
    PFN_vkCreateDevice create_device;
    PFN_vkDestroyDevice destroy_device = NULL;
    DeviceGetDeviceProcAddr gdpa = NULL;
    PFN_vkGetDeviceQueue get_queue = NULL;
    VkQueueFamilyProperties families[MAX_QUEUE_FAMILIES];
    VkDeviceQueueCreateInfo queue_create;
    VkDeviceCreateInfo device_create;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    float priority = 1.0F;
    uint64_t before;
    uint32_t reported = 0U;
    uint32_t returned = 0U;
    uint32_t index;
    const char *kind;

    if (!device_resolve_instance(result, gipa, instance, owner, 5U,
                                 "vkGetPhysicalDeviceQueueFamilyProperties") ||
        !device_resolve_instance(result, gipa, instance, owner, 6U, "vkCreateDevice") ||
        !device_resolve_instance(result, gipa, instance, owner, 7U, "vkDestroyDevice") ||
        !device_resolve_instance(result, gipa, instance, owner, 8U, "vkGetDeviceProcAddr")) {
        device_set_failure(result, "owner", "required device PFN missing or owner mismatch");
        return;
    }
    get_families = (PFN_vkGetPhysicalDeviceQueueFamilyProperties)(uintptr_t)
        result->functions[5U].address;
    create_device = (PFN_vkCreateDevice)(uintptr_t)result->functions[6U].address;
    destroy_device = (PFN_vkDestroyDevice)(uintptr_t)result->functions[7U].address;
    gdpa = (DeviceGetDeviceProcAddr)(uintptr_t)result->functions[8U].address;

    if (!device_call_begin(result, &before)) return;
    get_families(physical, &reported, NULL);
    if (!device_call_end(result, before)) return;
    result->queue_count_queried = 1;
    result->queue_reported_count = reported;
    if (reported > MAX_QUEUE_FAMILIES) {
        device_set_failure(result, "queues", "queue-family count exceeds 16");
        return;
    }
    returned = reported;
    if (reported != 0U) {
        memset(families, 0, sizeof(families));
        if (!device_call_begin(result, &before)) return;
        get_families(physical, &returned, families);
        if (!device_call_end(result, before)) return;
        result->queue_fill_queried = 1;
        result->queue_returned_count = returned;
        result->queue_retained_count = returned > MAX_QUEUE_FAMILIES ? MAX_QUEUE_FAMILIES : returned;
        if (returned > MAX_QUEUE_FAMILIES || returned != reported) {
            device_set_failure(result, "queues", "queue-family count is incomplete or oversized");
            return;
        }
        result->queue_complete = 1;
        for (index = 0U; index < returned; ++index) {
            result->queue_families[index].index = index;
            result->queue_families[index].flags = families[index].queueFlags;
            result->queue_families[index].queue_count = families[index].queueCount;
        }
    } else {
        result->queue_returned_count = 0U;
        result->queue_retained_count = 0U;
        result->queue_complete = 1;
    }
    if (returned == 0U || !device_select_queue(families, returned, &index, &kind)) {
        device_set_failure(result, "no_queue", "no complete eligible queue family");
        return;
    }
    result->selected = 1;
    result->selected_family = index;
    result->selected_flags = families[index].queueFlags;
    result->selected_queue_count = families[index].queueCount;
    (void)copy_bounded(result->selected_kind, sizeof(result->selected_kind), kind, 15U);

    memset(&queue_create, 0, sizeof(queue_create));
    queue_create.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queue_create.queueFamilyIndex = index;
    queue_create.queueCount = 1U;
    queue_create.pQueuePriorities = &priority;
    memset(&device_create, 0, sizeof(device_create));
    device_create.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    device_create.queueCreateInfoCount = 1U;
    device_create.pQueueCreateInfos = &queue_create;
    device_create.enabledLayerCount = 0U;
    device_create.ppEnabledLayerNames = NULL;
    device_create.enabledExtensionCount = 0U;
    device_create.ppEnabledExtensionNames = NULL;
    device_create.pEnabledFeatures = NULL;
    result->device_queue_create_count = 1;
    result->device_queue_family = index;
    result->device_queue_count = 1U;
    result->device_queue_priority = priority;
    result->device_layers_count = 0;
    result->device_extensions_count = 0;
    result->device_features_null = 1;
    result->device_allocator_null = 1;
    result->device_create_attempted = 1;
    if (!device_call_begin(result, &before)) return;
    result->device_create_result = create_device(physical, &device_create, NULL, &device);
    {
        int create_timing_valid = device_call_end(result, before);
    device_record_result(result, result->device_create_result, "vkCreateDevice failed");
    if (result->device_create_result != VK_SUCCESS || device == VK_NULL_HANDLE) {
        if (result->device_create_result != VK_SUCCESS && device != VK_NULL_HANDLE) {
            result->device_handle_returned_on_error = 1;
            device_set_failure(result, "vulkan", "vkCreateDevice returned a handle with an error");
            device = VK_NULL_HANDLE;
        } else if (result->device_create_result == VK_SUCCESS) {
            device_set_failure(result, "vulkan", "vkCreateDevice returned null");
        }
        return;
    }
    result->device_created = 1;
    result->device_live = 1;
    if (!create_timing_valid) goto device_cleanup;
    }

    {
        DeviceFunction gdpa_destroy;
        memset(&gdpa_destroy, 0, sizeof(gdpa_destroy));
        gdpa_destroy.name = "vkDestroyDevice";
        if (!device_observe_function(&gdpa_destroy, gdpa(device, "vkDestroyDevice"), owner)) {
            device_set_failure(result, "owner", "device vkDestroyDevice owner mismatch");
            goto device_cleanup;
        }
        destroy_device = (PFN_vkDestroyDevice)(uintptr_t)gdpa_destroy.address;
    }
    if (!device_resolve_device_function(result, gdpa, device, owner, 9U, "vkGetDeviceQueue")) {
        device_set_failure(result, "owner", "device vkGetDeviceQueue owner mismatch");
        goto device_cleanup;
    }
    if (result->functions[9U].owner_exact) {
        get_queue = (PFN_vkGetDeviceQueue)(uintptr_t)result->functions[9U].address;
        result->queue_get_attempted = 1;
        result->queue_index = 0U;
        if (!device_call_begin(result, &before)) goto device_cleanup;
        get_queue(device, index, 0U, &queue);
        if (!device_call_end(result, before)) goto device_cleanup;
        result->queue_non_null = queue != VK_NULL_HANDLE;
        result->queue_observed = result->queue_non_null;
        if (!result->queue_non_null)
            device_set_failure(result, "queue", "vkGetDeviceQueue returned null");
    }
    goto device_cleanup;

device_cleanup:
    if (destroy_device != NULL && result->device_live) {
        int cleanup_timing_started;
        result->device_destroy_attempted = 1;
        cleanup_timing_started = device_call_begin(result, &before);
        /* Cleanup of an already-owned valid handle is never gated by clocks. */
        destroy_device(device, NULL);
        if (cleanup_timing_started) (void)device_call_end(result, before);
        result->device_destroy_ok = 1;
        result->device_live = 0;
    }
    return;
}

static MAYBE_UNUSED int device_target_properties(DeviceQueueResult *result,
                                                 PFN_vkGetPhysicalDeviceProperties get_properties,
                                                 VkPhysicalDevice physical) {
    VkPhysicalDeviceProperties properties;
    uint64_t before;
    memset(&properties, 0, sizeof(properties));
    if (!device_call_begin(result, &before)) return 0;
    get_properties(physical, &properties);
    if (!device_call_end(result, before)) return 0;
    result->physical_vendor_id = properties.vendorID;
    result->physical_device_id = properties.deviceID;
    result->physical_properties_valid = bounded_length(properties.deviceName,
                                                       VK_MAX_PHYSICAL_DEVICE_NAME_SIZE,
                                                       NULL) < VK_MAX_PHYSICAL_DEVICE_NAME_SIZE;
    if (result->physical_properties_valid)
        (void)copy_bounded(result->physical_name, sizeof(result->physical_name),
                           properties.deviceName, VK_MAX_PHYSICAL_DEVICE_NAME_SIZE);
    result->physical_target_exact = result->physical_properties_valid &&
        properties.vendorID == EXPECTED_VENDOR && properties.deviceID == EXPECTED_DEVICE &&
        strcmp(properties.deviceName, EXPECTED_NAME) == 0;
    if (!result->physical_target_exact)
        device_set_failure(result, "physical", "physical device is not the measured Mali-G610 target");
    return result->physical_target_exact;
}

static MAYBE_UNUSED int device_emit(DeviceQueueResult *result, char *output, size_t capacity) {
    size_t length;
    device_classify(result);
    if (!device_encode_result(result, output, capacity)) return 0;
    length = strlen(output);
    if (length >= capacity || fwrite(output, 1U, length, stdout) != length ||
        fputc('\n', stdout) == EOF || fflush(stdout) != 0) return 0;
    return 1;
}

#ifndef PANVK_DEVICE_QUEUE_HOST_TEST
static int device_invalid_invocation(void) {
    DeviceQueueResult result;
    char output[DEVICE_QUEUE_OUTPUT_LIMIT];
    device_init_result(&result, "");
    result.monotonic_start = monotonic_now();
    result.monotonic_end = monotonic_now();
    result.status = "UNKNOWN";
    result.failure_stage = "arguments";
    copy_error(result.failure_detail, "expected --run-id PVK-<32 lowercase hexadecimal characters>");
    result.native_exit = 1;
    return device_encode_result(&result, output, sizeof(output)) &&
           fwrite(output, 1U, strlen(output), stdout) == strlen(output) &&
           fputc('\n', stdout) != EOF && fflush(stdout) == 0 ? 1 : 1;
}

static int device_native_main(const char *run_id) {
    DeviceQueueResult result;
    char canonical_icd[PATH_LIMIT];
    void *handle = NULL;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    DeviceGetInstanceProcAddr gipa = NULL;
    DeviceNegotiate negotiate = NULL;
    PFN_vkDestroyInstance destroy_instance = NULL;
    PFN_vkEnumeratePhysicalDevices enumerate_physical = NULL;
    PFN_vkGetPhysicalDeviceProperties get_properties = NULL;
    uint32_t negotiated = 7U;
    uint32_t reported = 0U;
    uint32_t returned;
    VkPhysicalDevice physicals[MAX_PHYSICAL_DEVICES];
    uint64_t before;
    uint64_t end_time;
    char output[DEVICE_QUEUE_OUTPUT_LIMIT];
    size_t index;

    device_init_result(&result, run_id);
    result.monotonic_start = monotonic_now();
    result.clock_start_valid = result.monotonic_start > 0U;
    result.scope_valid = scope_valid(result.cwd, run_id);
    if (!result.scope_valid) device_set_failure(&result, "scope", "UID, CWD, run ID, or environment mismatch");
    if (!result.clock_start_valid) device_set_failure(&result, "clock", "monotonic start unavailable");
    if (result.scope_valid && path_join(result.icd_path, sizeof(result.icd_path), result.cwd, ICD_NAME)) {
        if (!canonical_path(result.icd_path, canonical_icd, sizeof(canonical_icd)) ||
            strcmp(canonical_icd, result.icd_path) != 0) {
            device_set_failure(&result, "identity", "ICD is not canonical");
        } else {
            result.binding_valid = 1;
            result.before_hash_valid = hash_file(result.icd_path, &result.before, result.before_sha256);
            if (result.before_hash_valid) {
                (void)copy_bounded(result.icd_sha256, sizeof(result.icd_sha256), result.before_sha256, 65U);
                result.icd_bytes = result.before.size;
            }
        }
    } else {
        device_set_failure(&result, "path", "ICD path exceeds bound");
    }
    if (!result.binding_valid || !result.before_hash_valid) {
        device_set_failure(&result, "hash", "ICD identity, size, owner, or SHA-256 mismatch");
    } else if (result.clock_start_valid) {
        (void)dlerror();
        result.load_attempted = 1;
        handle = dlopen(result.icd_path, RTLD_NOW | RTLD_LOCAL);
        if (handle == NULL) {
            copy_error(result.load_error, dlerror());
            device_set_failure(&result, "loader", result.load_error[0] != '\0' ? result.load_error : "dlopen failed");
        } else {
            result.load_loaded = 1;
            device_observe_exports(&result, handle, canonical_icd);
            for (index = 0U; index < 3U; ++index) {
                if (!result.exports[index].found || !result.exports[index].owner_exact)
                    device_set_failure(&result, "export", "required ICD export missing or owner mismatch");
            }
            gipa = (DeviceGetInstanceProcAddr)(uintptr_t)dlsym(handle, export_names[0]);
            negotiate = (DeviceNegotiate)(uintptr_t)dlsym(handle, export_names[2]);
            if (gipa == NULL || negotiate == NULL || !result.exports[0].owner_exact ||
                !result.exports[2].owner_exact) {
                device_set_failure(&result, "export", "required ICD entrypoint missing or owner mismatch");
            } else {
                uint64_t call_before;
                result.interface_input = 7;
                if (device_call_begin(&result, &call_before)) {
                    result.interface_result = negotiate(&negotiated);
                    (void)device_call_end(&result, call_before);
                } else result.interface_result = VK_ERROR_INITIALIZATION_FAILED;
                result.interface_output = (int)negotiated;
                device_record_result(&result, (VkResult)result.interface_result, "ICD interface negotiation failed");
                result.interface_supported = result.interface_result == VK_SUCCESS && negotiated >= 2U && negotiated <= 7U;
                if (!result.interface_supported) device_set_failure(&result, "interface", "unsupported ICD interface");
            }
            if (gipa != NULL && result.interface_supported && device_failure_is(&result, "none")) {
                PFN_vkVoidFunction version_address = gipa(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
                result.enumerate_instance_version_found = version_address != NULL;
                if (version_address == NULL) {
                    result.legacy_api_10 = 1;
                    result.enumerate_instance_version_owner = 1;
                    result.enumerate_instance_version_result = VK_SUCCESS;
                } else {
                    uint64_t call_before;
                    result.enumerate_instance_version_owner = device_observe_function(&result.functions[0], version_address, canonical_icd);
                    if (!result.enumerate_instance_version_owner) {
                        device_set_failure(&result, "owner", "instance-version PFN owner mismatch");
                    } else if (device_call_begin(&result, &call_before)) {
                        uint32_t version = 0U;
                        PFN_vkEnumerateInstanceVersion version_fn = (PFN_vkEnumerateInstanceVersion)(uintptr_t)version_address;
                        result.enumerate_instance_version_result = version_fn(&version);
                        (void)device_call_end(&result, call_before);
                        result.api_reported = version;
                        device_record_result(&result, result.enumerate_instance_version_result, "instance API version query failed");
                        if (result.enumerate_instance_version_result != VK_SUCCESS || VK_API_VERSION_VARIANT(version) != 0U ||
                            VK_API_VERSION_MAJOR(version) != 1U || version < VK_API_VERSION_1_0)
                            device_set_failure(&result, "api", "invalid instance API version");
                    }
                }
                result.api_requested = result.api_reported;
                if (VK_API_VERSION_MAJOR(result.api_requested) > 1U ||
                    (VK_API_VERSION_MAJOR(result.api_requested) == 1U && VK_API_VERSION_MINOR(result.api_requested) > 3U))
                    result.api_requested = VK_API_VERSION_1_3;
                if (device_failure_is(&result, "none")) {
                    PFN_vkVoidFunction create_address = gipa(VK_NULL_HANDLE, "vkCreateInstance");
                    VkApplicationInfo app;
                    VkInstanceCreateInfo create;
                    if (!device_observe_function(&result.functions[1], create_address, canonical_icd)) {
                        device_set_failure(&result, "owner", "vkCreateInstance PFN owner mismatch");
                    } else {
                        memset(&app, 0, sizeof(app));
                        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
                        app.pApplicationName = "PanvkDeviceQueue";
                        app.applicationVersion = 1U;
                        app.pEngineName = "wowforever";
                        app.engineVersion = 1U;
                        app.apiVersion = result.api_requested;
                        memset(&create, 0, sizeof(create));
                        create.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
                        create.pApplicationInfo = &app;
                        result.layer_count = 0;
                        result.extension_count = 0;
                        result.allocator_null = 1;
                        result.instance_create_attempted = 1;
                        if (device_call_begin(&result, &before)) {
                            PFN_vkCreateInstance create_instance = (PFN_vkCreateInstance)(uintptr_t)create_address;
                            result.instance_create_result = create_instance(&create, NULL, &instance);
                            (void)device_call_end(&result, before);
                        } else result.instance_create_result = VK_ERROR_INITIALIZATION_FAILED;
                        device_record_result(&result, result.instance_create_result, "vkCreateInstance failed");
                        if (result.instance_create_result != VK_SUCCESS && instance != VK_NULL_HANDLE) {
                            result.instance_handle_returned_on_error = 1;
                            instance = VK_NULL_HANDLE;
                            device_set_failure(&result, "vulkan", "vkCreateInstance returned a handle with an error");
                        } else if (result.instance_create_result == VK_SUCCESS && instance == VK_NULL_HANDLE) {
                            device_set_failure(&result, "vulkan", "vkCreateInstance returned null");
                        } else if (result.instance_create_result == VK_SUCCESS) {
                            result.instance_created = 1;
                        }
                    }
                }
            }
            if (instance != VK_NULL_HANDLE && result.instance_created) {
                PFN_vkVoidFunction destroy_address = gipa(instance, "vkDestroyInstance");
                result.functions[2].name = "vkDestroyInstance";
                if (!device_observe_function(&result.functions[2], destroy_address, canonical_icd)) {
                    device_set_failure(&result, "owner", "vkDestroyInstance PFN owner mismatch");
                } else {
                    destroy_instance = (PFN_vkDestroyInstance)(uintptr_t)destroy_address;
                }
                if (destroy_instance != NULL && result.functions[2].owner_exact &&
                    device_failure_is(&result, "none")) {
                    PFN_vkVoidFunction enumerate_address = gipa(instance, "vkEnumeratePhysicalDevices");
                    PFN_vkVoidFunction properties_address = gipa(instance, "vkGetPhysicalDeviceProperties");
                    if (!device_observe_function(&result.functions[3], enumerate_address, canonical_icd) ||
                        !device_observe_function(&result.functions[4], properties_address, canonical_icd)) {
                        device_set_failure(&result, "owner", "required physical-device PFN owner mismatch");
                    } else {
                        enumerate_physical = (PFN_vkEnumeratePhysicalDevices)(uintptr_t)enumerate_address;
                        get_properties = (PFN_vkGetPhysicalDeviceProperties)(uintptr_t)properties_address;
                        if (device_call_begin(&result, &before)) {
                            result.physical_count_result = enumerate_physical(instance, &reported, NULL);
                            (void)device_call_end(&result, before);
                        } else result.physical_count_result = VK_ERROR_INITIALIZATION_FAILED;
                        result.physical_reported_count = reported;
                        device_record_result(&result, result.physical_count_result, "physical-device count failed");
                        if (result.physical_count_result == VK_SUCCESS && device_failure_is(&result, "none")) {
                            if (reported > MAX_PHYSICAL_DEVICES) {
                                device_set_failure(&result, "physical", "physical-device count exceeds 8");
                            } else if (reported != 0U) {
                                returned = reported;
                                memset(physicals, 0, sizeof(physicals));
                                if (device_call_begin(&result, &before)) {
                                    result.physical_fill_result = enumerate_physical(instance, &returned, physicals);
                                    (void)device_call_end(&result, before);
                                } else result.physical_fill_result = VK_ERROR_INITIALIZATION_FAILED;
                                result.physical_returned_count = returned;
                                device_record_result(&result, result.physical_fill_result, "physical-device fill failed");
                                result.physical_complete = result.physical_fill_result == VK_SUCCESS && returned == reported;
                                if (!result.physical_complete)
                                    device_set_failure(&result, "physical", "physical-device count is incomplete");
                                if (returned > MAX_PHYSICAL_DEVICES) returned = MAX_PHYSICAL_DEVICES;
                                result.physical_retained_count = returned;
                                if (result.physical_complete && returned == 1U && physicals[0] != VK_NULL_HANDLE &&
                                    device_failure_is(&result, "none")) {
                                    physical = physicals[0];
                                    (void)device_target_properties(&result, get_properties, physical);
                                    if (result.physical_target_exact)
                                        device_control(&result, gipa, instance, physical, canonical_icd);
                                } else if (returned != 1U) {
                                    device_set_failure(&result, "physical", "exactly one physical device required");
                                } else {
                                    device_set_failure(&result, "physical", "physical-device handle is null");
                                }
                            } else {
                                result.physical_complete = 1;
                                device_set_failure(&result, "physical", "zero physical devices");
                            }
                        }
                    }
                }
            }
        }
    }
    if (instance != VK_NULL_HANDLE && result.instance_created && !result.device_live && destroy_instance != NULL &&
        result.functions[2].owner_exact) {
        result.instance_destroy_attempted = 1;
        {
            int cleanup_timing_started = device_call_begin(&result, &before);
            /* Cleanup of an already-owned valid handle is never gated by clocks. */
            destroy_instance(instance, NULL);
            if (cleanup_timing_started) (void)device_call_end(&result, before);
            result.instance_destroy_ok = 1;
            instance = VK_NULL_HANDLE;
        }
    }
    if (result.icd_path[0] != '\0') {
        result.after_hash_valid = hash_file(result.icd_path, &result.after, result.after_sha256);
        result.identity_stable = identity_same(&result.before, &result.after);
        if (result.after_hash_valid)
            (void)copy_bounded(result.icd_sha256, sizeof(result.icd_sha256), result.after_sha256, 65U);
        if (!result.after_hash_valid || !result.identity_stable)
            device_set_failure(&result, "identity", "ICD changed or failed post-observation verification");
    }
    if (handle != NULL && instance == VK_NULL_HANDLE && !result.device_live) {
        result.dlclose_attempted = 1;
        result.dlclose_ok = dlclose(handle) == 0;
        if (!result.dlclose_ok) device_set_failure(&result, "cleanup", "dlclose failed");
    } else if (handle == NULL) {
        result.dlclose_ok = 0;
    }
    result.cleanup_complete = instance == VK_NULL_HANDLE && !result.device_live &&
        (handle == NULL || result.dlclose_ok);
    end_time = monotonic_now();
    result.monotonic_end = end_time;
    result.clock_valid = result.clock_start_valid && end_time > 0U && end_time >= result.monotonic_start;
    result.timing_valid = result.timing_valid && result.clock_valid;
    if (!result.clock_valid) device_set_failure(&result, "clock", "monotonic end unavailable or reversed");
    if (!device_emit(&result, output, sizeof(output))) return 1;
    return result.native_exit;
}

int main(int argc, char **argv) {
    if (argc != 3 || argv == NULL || argv[1] == NULL || argv[2] == NULL ||
        strcmp(argv[1], "--run-id") != 0 || !valid_run_id(argv[2])) return device_invalid_invocation();
    return device_native_main(argv[2]);
}
#endif

#ifdef PANVK_DEVICE_QUEUE_HOST_TEST
static int fake_case;
static int fake_create_called;
static int fake_destroy_called;
static int fake_queue_called;
static uint32_t fake_selected_family;
#define MAX_FAKE_CALLS 16U
static char fake_calls[MAX_FAKE_CALLS][64];
static size_t fake_call_count;

static void fake_record_call(const char *value) {
    if (fake_call_count < MAX_FAKE_CALLS) {
        (void)copy_bounded(fake_calls[fake_call_count], sizeof(fake_calls[0]), value, 63U);
        ++fake_call_count;
    }
}

static VkResult VKAPI_PTR fake_create_device(const VkPhysicalDevice physical,
                                             const VkDeviceCreateInfo *info,
                                             const VkAllocationCallbacks *allocator,
                                             VkDevice *device) {
    (void)physical; (void)allocator;
    fake_create_called = 1;
    fake_record_call("CREATE");
    if (info == NULL || device == NULL || info->queueCreateInfoCount != 1U ||
        info->pQueueCreateInfos == NULL || info->pQueueCreateInfos[0].queueCount != 1U ||
        info->pQueueCreateInfos[0].pQueuePriorities == NULL ||
        info->pQueueCreateInfos[0].pQueuePriorities[0] != 1.0F ||
        info->pEnabledFeatures != NULL || info->enabledLayerCount != 0U ||
        info->enabledExtensionCount != 0U || info->pNext != NULL) return VK_ERROR_INITIALIZATION_FAILED;
    if (fake_case == 1) { *device = (VkDevice)(uintptr_t)0x33U; return VK_ERROR_DEVICE_LOST; }
    if (fake_case == 2) { *device = (VkDevice)(uintptr_t)0x33U; return VK_ERROR_INITIALIZATION_FAILED; }
    *device = (VkDevice)(uintptr_t)0x33U;
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_destroy_device(VkDevice device, const VkAllocationCallbacks *allocator) {
    (void)device; (void)allocator; ++fake_destroy_called; fake_record_call("DESTROY");
}

static void VKAPI_PTR fake_get_families(VkPhysicalDevice physical, uint32_t *count,
                                        VkQueueFamilyProperties *families) {
    (void)physical;
    if (count == NULL) return;
    if (families == NULL) {
        fake_record_call("QF:count");
        *count = fake_case == 3 ? MAX_QUEUE_FAMILIES + 1U : fake_case == 9 ? 0U : 2U;
        return;
    }
    fake_record_call("QF:fill");
    if (fake_case == 4) { *count = 1U; return; }
    if (fake_case == 8) { *count = MAX_QUEUE_FAMILIES + 1U; return; }
    *count = 2U;
    memset(families, 0, sizeof(VkQueueFamilyProperties) * 2U);
    families[0].queueFlags = VK_QUEUE_GRAPHICS_BIT;
    families[0].queueCount = 1U;
    families[1].queueFlags = VK_QUEUE_TRANSFER_BIT;
    families[1].queueCount = 1U;
}

static void VKAPI_PTR fake_get_queue(VkDevice device, uint32_t family, uint32_t queue,
                                     VkQueue *output) {
    (void)device; fake_queue_called = 1; fake_selected_family = family;
    fake_record_call("GETQ");
    if (output != NULL) *output = fake_case == 5 ? VK_NULL_HANDLE :
        (queue == 0U ? (VkQueue)(uintptr_t)0x44U : VK_NULL_HANDLE);
}

static PFN_vkVoidFunction VKAPI_PTR fake_gdpa(VkDevice device, const char *name) {
    (void)device;
    if (strcmp(name, "vkDestroyDevice") == 0) fake_record_call("D:vkDestroyDevice");
    if (strcmp(name, "vkGetDeviceQueue") == 0) fake_record_call("D:vkGetDeviceQueue");
    if (fake_case == 6 && strcmp(name, "vkDestroyDevice") == 0) return NULL;
    if (strcmp(name, "vkDestroyDevice") == 0) return (PFN_vkVoidFunction)fake_destroy_device;
    if (strcmp(name, "vkGetDeviceQueue") == 0) return (PFN_vkVoidFunction)fake_get_queue;
    return NULL;
}

static PFN_vkVoidFunction VKAPI_PTR fake_gipa(VkInstance instance, const char *name) {
    (void)instance;
    if (strcmp(name, "vkGetPhysicalDeviceQueueFamilyProperties") == 0) fake_record_call("I:vkGetPhysicalDeviceQueueFamilyProperties");
    if (strcmp(name, "vkCreateDevice") == 0) fake_record_call("I:vkCreateDevice");
    if (strcmp(name, "vkDestroyDevice") == 0) fake_record_call("I:vkDestroyDevice");
    if (strcmp(name, "vkGetDeviceProcAddr") == 0) fake_record_call("I:vkGetDeviceProcAddr");
    if (strcmp(name, "vkGetPhysicalDeviceQueueFamilyProperties") == 0) return (PFN_vkVoidFunction)fake_get_families;
    if (strcmp(name, "vkCreateDevice") == 0) return (PFN_vkVoidFunction)fake_create_device;
    if (strcmp(name, "vkDestroyDevice") == 0) return (PFN_vkVoidFunction)fake_destroy_device;
    if (strcmp(name, "vkGetDeviceProcAddr") == 0) return (PFN_vkVoidFunction)fake_gdpa;
    return NULL;
}

int panvk_test_fake_device(const char *case_name, char *output, size_t capacity) {
    DeviceQueueResult result;
    const char *owner = "/fake/icd/libvulkan_panfrost.so";
    if (case_name == NULL || output == NULL) return 0;
    fake_case = 0;
    if (strcmp(case_name, "create-error") == 0) fake_case = 1;
    else if (strcmp(case_name, "create-error-nonnull") == 0) fake_case = 2;
    else if (strcmp(case_name, "count-overflow") == 0) fake_case = 3;
    else if (strcmp(case_name, "count-partial") == 0) fake_case = 4;
    else if (strcmp(case_name, "queue-null") == 0) fake_case = 5;
    else if (strcmp(case_name, "owner") == 0) fake_case = 6;
    else if (strcmp(case_name, "clock-after-create") == 0) fake_case = 7;
    else if (strcmp(case_name, "fill-overflow") == 0) fake_case = 8;
    else if (strcmp(case_name, "zero-queues") == 0) fake_case = 9;
    else if (strcmp(case_name, "pass") != 0) return 0;
    fake_owner_mode = 1; fake_clock_fail_after_create = fake_case == 7;
    fake_create_called = 0; fake_destroy_called = 0; fake_queue_called = 0;
    fake_call_count = 0U; memset(fake_calls, 0, sizeof(fake_calls));
    device_init_result(&result, "PVK-0123456789abcdef0123456789abcdef");
    result.scope_valid = 1; result.binding_valid = 1; result.identity_stable = 1;
    result.load_loaded = 1; result.interface_supported = 1; result.instance_created = 1;
    result.clock_start_valid = 1; result.clock_valid = 1; result.timing_valid = 1;
    result.physical_complete = 1; result.physical_returned_count = 1;
    result.physical_target_exact = 1; result.physical_properties_valid = 1;
    result.physical_vendor_id = EXPECTED_VENDOR; result.physical_device_id = EXPECTED_DEVICE;
    (void)copy_bounded(result.physical_name, sizeof(result.physical_name), EXPECTED_NAME, sizeof(EXPECTED_NAME));
    device_control(&result, fake_gipa, (VkInstance)(uintptr_t)1U,
                   (VkPhysicalDevice)(uintptr_t)2U, owner);
    result.instance_destroy_ok = 1; result.dlclose_ok = 1; result.cleanup_complete = !result.device_live;
    result.after_hash_valid = result.before_hash_valid = result.identity_stable;
    if (fake_case == 3 || fake_case == 4 || fake_case == 8) result.failure_stage = "queues";
    if (fake_case == 9) result.failure_stage = "no_queue";
    if (fake_case == 5) result.failure_stage = "queue";
    if (fake_case == 6) result.failure_stage = "owner";
    if (fake_case == 1 || fake_case == 2) {
        result.failure_stage = "vulkan"; result.vk_error = 1; result.status = "VK_ERROR";
    }
    device_classify(&result);
    return device_encode_result(&result, output, capacity);
}

int panvk_test_fake_destroy_calls(void) { return fake_destroy_called; }

int panvk_test_select_queue(uint32_t flags, uint32_t queue_count, uint32_t *index,
                            char *kind, size_t kind_capacity) {
    VkQueueFamilyProperties family;
    const VkQueueFamilyProperties *families = &family;
    const char *selected_kind = NULL;
    memset(&family, 0, sizeof(family));
    family.queueFlags = (VkQueueFlags)flags;
    family.queueCount = queue_count;
    if (!device_select_queue(families, 1U, index, &selected_kind)) return 0;
    return kind != NULL && copy_bounded(kind, kind_capacity, selected_kind, 15U);
}

int panvk_test_fake_call_order(void) {
    static const char *const expected[] = {
        "I:vkGetPhysicalDeviceQueueFamilyProperties", "I:vkCreateDevice",
        "I:vkDestroyDevice", "I:vkGetDeviceProcAddr", "QF:count", "QF:fill",
        "CREATE", "D:vkDestroyDevice", "D:vkGetDeviceQueue", "GETQ", "DESTROY"
    };
    size_t index;
    if (fake_call_count != sizeof(expected) / sizeof(expected[0])) return 0;
    for (index = 0U; index < fake_call_count; ++index)
        if (strcmp(fake_calls[index], expected[index]) != 0) return 0;
    return 1;
}

int panvk_test_encode_fixture(char *output, size_t capacity) {
    return panvk_test_fake_device("pass", output, capacity);
}
#endif
