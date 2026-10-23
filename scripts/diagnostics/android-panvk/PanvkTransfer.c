#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

/*
 * PanVK control B.  The private loader helper is included verbatim: it owns
 * the immutable scope, file-identity, SHA-256, JSON escaping and dladdr
 * primitives.  This translation unit adds the Gate A one-device/one-queue
 * lifecycle (byte-faithful in behaviour) followed by exactly one bounded
 * 4 KiB transfer with fence and readback.  No shader, image, renderpass,
 * framebuffer, surface, swapchain, idle wait, reset, retry, fallback,
 * second candidate, feature change, process tracing, handlers, node mapping,
 * permission is present here.
 */
#define main panvk_transfer_legacy_loader_main
#define set_failure panvk_transfer_loader_set_failure
#define failure_is panvk_transfer_loader_failure_is
#define observe_exports panvk_transfer_loader_observe_exports
#include "PanvkPrivateLoader.c"
#undef main
#undef set_failure
#undef failure_is
#undef observe_exports

#include "/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include/vulkan/vulkan_core.h"

#include <stddef.h>

#define TRANSFER_SCHEMA "wowforever.android-panvk-transfer"
#define TRANSFER_OUTPUT_LIMIT (64U * 1024U)
#define MAX_PHYSICAL_DEVICES 8U
#define MAX_QUEUE_FAMILIES 16U
#define MAX_FUNCTIONS 33U
#define EXPECTED_VENDOR UINT32_C(0x13b5)
#define EXPECTED_DEVICE UINT32_C(0xa8670000)
#define EXPECTED_NAME "Mali-G610 MC6"
#define TRANSFER_SIZE UINT64_C(4096)
#define TRANSFER_SIZE_U32 UINT32_C(4096)
#define TRANSFER_DWORDS UINT32_C(1024)
#define TRANSFER_CAPACITY UINT64_C(262144)
#define TRANSFER_TIMEOUT_NS UINT64_C(1000000000)
#define TRANSFER_PATTERN UINT32_C(0xa1b2c3d4)
#define TRANSFER_POOL_FLAGS UINT32_C(2)

typedef PFN_vkVoidFunction (VKAPI_PTR *TransferGetInstanceProcAddr)(VkInstance, const char *);
typedef PFN_vkVoidFunction (VKAPI_PTR *TransferGetDeviceProcAddr)(VkDevice, const char *);
typedef VkResult (VKAPI_PTR *TransferNegotiate)(uint32_t *);

typedef struct TransferFunction {
    const char *name;
    PFN_vkVoidFunction address;
    char owner[PATH_LIMIT];
    int resolved;
    int owner_exact;
    char error[ERROR_LIMIT + 1U];
} TransferFunction;

typedef struct TransferFamilyObservation {
    uint32_t index;
    VkQueueFlags flags;
    uint32_t queue_count;
} TransferFamilyObservation;

typedef struct TransferResult {
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
    TransferFunction functions[MAX_FUNCTIONS];

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
    TransferFamilyObservation queue_families[MAX_QUEUE_FAMILIES];
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

    int buf_create_attempted;
    int buf_created;
    int buf_handle_on_error;
    VkResult buf_result;
    uint32_t buf_size;
    uint32_t buf_usage;
    uint32_t buf_sharing;

    int req_queried;
    uint64_t req_size;
    uint64_t req_align;
    uint32_t req_bits;

    int mem_selected;
    uint32_t mem_index;
    uint32_t mem_flags;
    int mem_visible;
    int mem_coherent;
    int mem_device_local;

    int alloc_attempted;
    int alloc_done;
    int alloc_handle_on_error;
    VkResult alloc_result;
    uint64_t alloc_requested;
    uint64_t alloc_capacity;

    int bind_attempted;
    int bind_ok;

    int map_attempted;
    int map_done;
    int map_non_null;

    int host_performed;
    uint32_t host_bytes;
    int host_coherent;
    int flush_attempted;
    int flush_ok;
    uint64_t flush_offset;
    uint64_t flush_size;

    int pool_attempted;
    int pool_done;
    VkResult pool_result;
    uint32_t pool_flags;
    uint32_t pool_family;

    int cmd_attempted;
    int cmd_done;
    VkResult cmd_result;
    uint32_t cmd_level;

    int begin_attempted;
    int begun;
    VkResult begin_result;
    int fill_attempted;
    int fill_ok;
    uint64_t fill_offset;
    uint64_t fill_size;
    uint32_t fill_data;
    int barrier_attempted;
    int barrier_ok;
    int end_attempted;
    int ended;
    VkResult end_result;

    int fence_attempted;
    int fence_done;
    VkResult fence_result;
    uint32_t fence_flags;

    int submit_attempted;
    VkResult submit_result;
    int wait_attempted;
    VkResult wait_result;
    int waited;
    uint64_t wait_timeout;
    int signaled;
    int submit_count;
    int wait_count;

    int inv_required;
    int inv_attempted;
    int inv_ok;
    uint64_t inv_offset;
    uint64_t inv_size;

    int read_attempted;
    int read_verified;
    uint32_t read_dwords;
    uint32_t read_mismatches;
    int32_t read_first;
    uint32_t read_pattern;
    char read_hash[17];

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
} TransferResult;

#ifdef PANVK_TRANSFER_HOST_TEST
static int fake_owner_mode;
static int fake_clock_fail_after_create;
#endif

static const char *const transfer_function_names[MAX_FUNCTIONS] = {
    "vkEnumerateInstanceVersion", "vkCreateInstance", "vkDestroyInstance",
    "vkEnumeratePhysicalDevices", "vkGetPhysicalDeviceProperties",
    "vkGetPhysicalDeviceQueueFamilyProperties", "vkGetPhysicalDeviceMemoryProperties",
    "vkCreateDevice", "vkDestroyDevice", "vkGetDeviceProcAddr", "vkGetDeviceQueue",
    "vkCreateBuffer", "vkDestroyBuffer", "vkGetBufferMemoryRequirements",
    "vkAllocateMemory", "vkFreeMemory", "vkBindBufferMemory", "vkMapMemory",
    "vkUnmapMemory", "vkFlushMappedMemoryRanges", "vkInvalidateMappedMemoryRanges",
    "vkCreateCommandPool", "vkDestroyCommandPool", "vkAllocateCommandBuffers",
    "vkFreeCommandBuffers", "vkBeginCommandBuffer", "vkEndCommandBuffer",
    "vkCmdFillBuffer", "vkCmdPipelineBarrier", "vkCreateFence", "vkDestroyFence",
    "vkWaitForFences", "vkQueueSubmit"
};

static void transfer_set_failure(TransferResult *result, const char *stage,
                                 const char *detail) {
    if (result->failure_stage == NULL || strcmp(result->failure_stage, "none") == 0) {
        result->failure_stage = stage;
        copy_error(result->failure_detail, detail);
    }
}

static int transfer_failure_is(const TransferResult *result, const char *stage) {
    return result != NULL && result->failure_stage != NULL && stage != NULL &&
           strcmp(result->failure_stage, stage) == 0;
}

static void transfer_record_result(TransferResult *result, VkResult value,
                                   const char *detail) {
    if (value < 0) {
        result->vk_error = 1;
        transfer_set_failure(result, "vulkan", detail);
    } else if (value != VK_SUCCESS) {
        transfer_set_failure(result, "result", detail);
    }
}

static int transfer_call_begin(TransferResult *result, uint64_t *before) {
#ifdef PANVK_TRANSFER_HOST_TEST
    if (fake_clock_fail_after_create && result->device_live) {
        result->timing_valid = 0;
        transfer_set_failure(result, "clock", "fake monotonic failure after device creation");
        return 0;
    }
#endif
    uint64_t value = monotonic_now();
    if (before == NULL || value == 0U ||
        (result->last_call_ns != 0U && value < result->last_call_ns)) {
        result->timing_valid = 0;
        transfer_set_failure(result, "clock", "monotonic check before Vulkan call failed");
        return 0;
    }
    *before = value;
    result->last_call_ns = value;
    ++result->call_count;
    ++result->monotonic_checks;
    return 1;
}

static int transfer_call_end(TransferResult *result, uint64_t before) {
    uint64_t value = monotonic_now();
    if (value == 0U || value < before ||
        (result->last_call_ns != 0U && value < result->last_call_ns)) {
        result->timing_valid = 0;
        transfer_set_failure(result, "clock", "monotonic check after Vulkan call failed");
        return 0;
    }
    result->last_call_ns = value;
    ++result->monotonic_checks;
    return 1;
}

static int transfer_observe_function(TransferFunction *observation,
                                     PFN_vkVoidFunction address,
                                     const char *owner) {
    observation->address = address;
    observation->resolved = address != NULL;
    observation->owner_exact = 0;
    if (!observation->resolved) {
        copy_error(observation->error, "function not returned");
        return 0;
    }
#ifdef PANVK_TRANSFER_HOST_TEST
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

static MAYBE_UNUSED void transfer_observe_exports(TransferResult *result, void *handle,
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

static int transfer_resolve_instance(TransferResult *result,
                                     TransferGetInstanceProcAddr gipa,
                                     VkInstance instance, const char *owner,
                                     size_t index, const char *name) {
    PFN_vkVoidFunction address;
    if (gipa == NULL || index >= MAX_FUNCTIONS) return 0;
    address = gipa(instance, name);
    return transfer_observe_function(&result->functions[index], address, owner);
}

static int transfer_select_queue(const VkQueueFamilyProperties *families,
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

static uint32_t transfer_select_memory_index(const VkPhysicalDeviceMemoryProperties *props,
                                             uint32_t allowed, int *coherent) {
    uint32_t index;
    if (props == NULL || coherent == NULL) return UINT32_MAX;
    for (index = 0U; index < props->memoryTypeCount; ++index) {
        if ((allowed & (1U << index)) == 0U) continue;
        if ((props->memoryTypes[index].propertyFlags &
             VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0U) continue;
        if ((props->memoryTypes[index].propertyFlags &
             VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0U) {
            *coherent = 1;
            return index;
        }
    }
    for (index = 0U; index < props->memoryTypeCount; ++index) {
        if ((allowed & (1U << index)) == 0U) continue;
        if ((props->memoryTypes[index].propertyFlags &
             VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0U) continue;
        *coherent = 0;
        return index;
    }
    return UINT32_MAX;
}

static void transfer_fnv1a(const unsigned char *data, size_t length, char out[17]) {
    static const char digits[] = "0123456789abcdef";
    uint64_t hash = UINT64_C(14695981039346656037);
    size_t index;
    int shift;
    for (index = 0U; index < length; ++index) {
        hash ^= (uint64_t)data[index];
        hash *= UINT64_C(1099511628211);
    }
    for (shift = 0; shift < 16; ++shift) {
        unsigned value = (unsigned)((hash >> (uint64_t)(60 - 4 * shift)) & 15U);
        out[shift] = digits[value];
    }
    out[16] = '\0';
}

static void transfer_init_result(TransferResult *result, const char *run_id) {
    size_t index;
    memset(result, 0, sizeof(*result));
    (void)copy_bounded(result->run_id, sizeof(result->run_id), run_id, RUN_LENGTH + 1U);
    result->pid = (int)getpid();
    result->uid = (int)getuid();
    result->pgid = (int)getpgid(0);
    result->sid = (int)getsid(0);
    result->failure_stage = "none";
    result->timing_valid = 1;
    result->read_first = -1;
    result->read_pattern = TRANSFER_PATTERN;
    result->read_dwords = TRANSFER_DWORDS;
    result->alloc_capacity = TRANSFER_CAPACITY;
    result->wait_timeout = 0U;
    result->enumerate_instance_version_result = VK_SUCCESS;
    result->instance_create_result = VK_ERROR_INITIALIZATION_FAILED;
    result->physical_count_result = VK_ERROR_INITIALIZATION_FAILED;
    result->physical_fill_result = VK_ERROR_INITIALIZATION_FAILED;
    result->device_create_result = VK_ERROR_INITIALIZATION_FAILED;
    result->buf_result = VK_ERROR_INITIALIZATION_FAILED;
    result->alloc_result = VK_ERROR_INITIALIZATION_FAILED;
    result->pool_result = VK_ERROR_INITIALIZATION_FAILED;
    result->cmd_result = VK_ERROR_INITIALIZATION_FAILED;
    result->begin_result = VK_ERROR_INITIALIZATION_FAILED;
    result->end_result = VK_ERROR_INITIALIZATION_FAILED;
    result->fence_result = VK_ERROR_INITIALIZATION_FAILED;
    result->submit_result = VK_ERROR_INITIALIZATION_FAILED;
    result->wait_result = VK_ERROR_INITIALIZATION_FAILED;
    result->api_reported = VK_API_VERSION_1_0;
    result->api_requested = VK_API_VERSION_1_0;
    result->physical_capacity = MAX_PHYSICAL_DEVICES;
    result->queue_capacity = MAX_QUEUE_FAMILIES;
    result->allocator_null = 1;
    result->device_features_null = 1;
    result->device_allocator_null = 1;
    for (index = 0U; index < MAX_FUNCTIONS; ++index)
        result->functions[index].name = transfer_function_names[index];
}

static int transfer_json_function(Json *json, const TransferFunction *function) {
    return json_append(json, "{\"name\":") && json_string(json, function->name, PATH_LIMIT) &&
           json_append(json, ",\"resolved\":") && json_bool(json, function->resolved) &&
           json_append(json, ",\"owner\":") && json_string(json, function->owner, PATH_LIMIT) &&
           json_append(json, ",\"ownerExact\":") && json_bool(json, function->owner_exact) &&
           json_append(json, ",\"error\":") && json_string(json, function->error, ERROR_LIMIT) &&
           json_append(json, "}");
}

static int transfer_json_queues(Json *json, const TransferResult *result) {
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

static int transfer_json_transfer(Json *json, const TransferResult *result) {
    if (!json_append(json, "{\"buffer\":{\"createAttempted\":")) return 0;
    if (!json_bool(json, result->buf_create_attempted)) return 0;
    if (!json_append(json, ",\"created\":")) return 0;
    if (!json_bool(json, result->buf_created)) return 0;
    if (!json_append(json, ",\"handleReturnedOnError\":")) return 0;
    if (!json_bool(json, result->buf_handle_on_error)) return 0;
    if (!json_append(json, ",\"result\":%d,\"size\":%u,\"usage\":%u,\"sharingMode\":%u},\"memoryRequirements\":{\"queried\":",
                     result->buf_result, result->buf_size, result->buf_usage,
                     result->buf_sharing)) return 0;
    if (!json_bool(json, result->req_queried)) return 0;
    if (!json_append(json, ",\"size\":%" PRIu64 ",\"alignment\":%" PRIu64 ",\"memoryTypeBits\":%u},\"memoryType\":{\"selected\":",
                     result->req_size, result->req_align, result->req_bits)) return 0;
    if (!json_bool(json, result->mem_selected)) return 0;
    if (!json_append(json, ",\"typeIndex\":%u,\"propertyFlags\":%u,\"hostVisible\":",
                     result->mem_index, result->mem_flags)) return 0;
    if (!json_bool(json, result->mem_visible)) return 0;
    if (!json_append(json, ",\"hostCoherent\":")) return 0;
    if (!json_bool(json, result->mem_coherent)) return 0;
    if (!json_append(json, ",\"deviceLocal\":")) return 0;
    if (!json_bool(json, result->mem_device_local)) return 0;
    if (!json_append(json, "},\"allocation\":{\"attempted\":")) return 0;
    if (!json_bool(json, result->alloc_attempted)) return 0;
    if (!json_append(json, ",\"allocated\":")) return 0;
    if (!json_bool(json, result->alloc_done)) return 0;
    if (!json_append(json, ",\"handleReturnedOnError\":")) return 0;
    if (!json_bool(json, result->alloc_handle_on_error)) return 0;
    if (!json_append(json, ",\"result\":%d,\"requestedBytes\":%" PRIu64 ",\"capacityBytes\":%" PRIu64 "},\"bind\":{\"attempted\":",
                     result->alloc_result, result->alloc_requested,
                     result->alloc_capacity)) return 0;
    if (!json_bool(json, result->bind_attempted)) return 0;
    if (!json_append(json, ",\"ok\":")) return 0;
    if (!json_bool(json, result->bind_ok)) return 0;
    if (!json_append(json, "},\"map\":{\"attempted\":")) return 0;
    if (!json_bool(json, result->map_attempted)) return 0;
    if (!json_append(json, ",\"mapped\":")) return 0;
    if (!json_bool(json, result->map_done)) return 0;
    if (!json_append(json, ",\"nonNull\":")) return 0;
    if (!json_bool(json, result->map_non_null)) return 0;
    if (!json_append(json, "},\"hostWrite\":{\"performed\":")) return 0;
    if (!json_bool(json, result->host_performed)) return 0;
    if (!json_append(json, ",\"bytes\":%u,\"coherent\":", result->host_bytes)) return 0;
    if (!json_bool(json, result->host_coherent)) return 0;
    if (!json_append(json, ",\"flushAttempted\":")) return 0;
    if (!json_bool(json, result->flush_attempted)) return 0;
    if (!json_append(json, ",\"flushOk\":")) return 0;
    if (!json_bool(json, result->flush_ok)) return 0;
    if (!json_append(json, ",\"flushOffset\":%" PRIu64 ",\"flushSize\":%" PRIu64 "},\"commandPool\":{\"createAttempted\":",
                     result->flush_offset, result->flush_size)) return 0;
    if (!json_bool(json, result->pool_attempted)) return 0;
    if (!json_append(json, ",\"created\":")) return 0;
    if (!json_bool(json, result->pool_done)) return 0;
    if (!json_append(json, ",\"result\":%d,\"flags\":%u,\"queueFamilyIndex\":%u},\"commandBuffer\":{\"allocateAttempted\":",
                     result->pool_result, result->pool_flags, result->pool_family)) return 0;
    if (!json_bool(json, result->cmd_attempted)) return 0;
    if (!json_append(json, ",\"allocated\":")) return 0;
    if (!json_bool(json, result->cmd_done)) return 0;
    if (!json_append(json, ",\"result\":%d,\"level\":%u},\"record\":{\"beginAttempted\":",
                     result->cmd_result, result->cmd_level)) return 0;
    if (!json_bool(json, result->begin_attempted)) return 0;
    if (!json_append(json, ",\"begun\":")) return 0;
    if (!json_bool(json, result->begun)) return 0;
    if (!json_append(json, ",\"beginResult\":%d,\"fillAttempted\":",
                     result->begin_result)) return 0;
    if (!json_bool(json, result->fill_attempted)) return 0;
    if (!json_append(json, ",\"fillOk\":")) return 0;
    if (!json_bool(json, result->fill_ok)) return 0;
    if (!json_append(json, ",\"fillOffset\":%" PRIu64 ",\"fillSize\":%" PRIu64 ",\"fillData\":%u,\"barrierAttempted\":",
                     result->fill_offset, result->fill_size, result->fill_data)) return 0;
    if (!json_bool(json, result->barrier_attempted)) return 0;
    if (!json_append(json, ",\"barrierOk\":")) return 0;
    if (!json_bool(json, result->barrier_ok)) return 0;
    if (!json_append(json, ",\"endAttempted\":")) return 0;
    if (!json_bool(json, result->end_attempted)) return 0;
    if (!json_append(json, ",\"ended\":")) return 0;
    if (!json_bool(json, result->ended)) return 0;
    if (!json_append(json, ",\"endResult\":%d},\"fence\":{\"createAttempted\":",
                     result->end_result)) return 0;
    if (!json_bool(json, result->fence_attempted)) return 0;
    if (!json_append(json, ",\"created\":")) return 0;
    if (!json_bool(json, result->fence_done)) return 0;
    if (!json_append(json, ",\"result\":%d,\"flags\":%u},\"submit\":{\"attempted\":",
                     result->fence_result, result->fence_flags)) return 0;
    if (!json_bool(json, result->submit_attempted)) return 0;
    if (!json_append(json, ",\"result\":%d,\"waitAttempted\":",
                     result->submit_result)) return 0;
    if (!json_bool(json, result->wait_attempted)) return 0;
    if (!json_append(json, ",\"waitResult\":%d,\"waited\":",
                     result->wait_result)) return 0;
    if (!json_bool(json, result->waited)) return 0;
    if (!json_append(json, ",\"timeoutNs\":%" PRIu64 ",\"signaled\":",
                     result->wait_timeout)) return 0;
    if (!json_bool(json, result->signaled)) return 0;
    if (!json_append(json, "},\"invalidate\":{\"required\":")) return 0;
    if (!json_bool(json, result->inv_required)) return 0;
    if (!json_append(json, ",\"attempted\":")) return 0;
    if (!json_bool(json, result->inv_attempted)) return 0;
    if (!json_append(json, ",\"ok\":")) return 0;
    if (!json_bool(json, result->inv_ok)) return 0;
    if (!json_append(json, ",\"offset\":%" PRIu64 ",\"size\":%" PRIu64 "},\"readback\":{\"attempted\":",
                     result->inv_offset, result->inv_size)) return 0;
    if (!json_bool(json, result->read_attempted)) return 0;
    if (!json_append(json, ",\"verified\":")) return 0;
    if (!json_bool(json, result->read_verified)) return 0;
    if (!json_append(json, ",\"dwordCount\":%u,\"mismatches\":%u,\"firstMismatchIndex\":%d,\"pattern\":%u,\"hashHex\":",
                     result->read_dwords, result->read_mismatches,
                     result->read_first, result->read_pattern)) return 0;
    if (!json_string(json, result->read_hash, 16U)) return 0;
    return json_append(json, "}}");
}

static int transfer_encode_result(const TransferResult *result, char *output,
                                  size_t capacity) {
    Json json = {output, capacity, 0U, 1};
    size_t index;
    if (result == NULL || output == NULL || capacity == 0U) return 0;
    if (!json_append(&json, "{\"schema\":") ||
        !json_string(&json, TRANSFER_SCHEMA, sizeof(TRANSFER_SCHEMA)) ||
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
            !transfer_json_function(&json, &result->functions[index])) return 0;
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
        !transfer_json_queues(&json, result) ||
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
        !json_append(&json, "},\"transfer\":") || !transfer_json_transfer(&json, result) ||
        !json_append(&json, ",\"timing\":{\"callCount\":%u,\"monotonicChecks\":%u,\"valid\":",
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
        !json_bool(&json, result->call_count != 0U)) return 0;
    if (!json_append(&json, ",\"apiComplete\":false,\"gpuWorkload\":")) return 0;
    if (!json_bool(&json, result->status != NULL &&
                   strcmp(result->status, "TRANSFER_READBACK_OBSERVED") == 0)) return 0;
    if (!json_append(&json, ",\"triangle\":false,\"presentation\":false,\"d3d\":false,\"wow\":false,\"status\":")) return 0;
    if (!json_string(&json, result->status, 64U) || !json_append(&json, ",\"failureStage\":") ||
        !json_string(&json, result->failure_stage, 64U) || !json_append(&json, ",\"failureDetail\":") ||
        !json_string(&json, result->failure_detail, ERROR_LIMIT) ||
        !json_append(&json, ",\"nativeExit\":%d}", result->native_exit)) return 0;
    return json.valid && json.used < capacity;
}

static void transfer_make_unknown_fallback(TransferResult *fallback,
                                           const TransferResult *source) {
    memset(fallback, 0, sizeof(*fallback));
    if (source == NULL) return;
    (void)copy_bounded(fallback->run_id, sizeof(fallback->run_id), source->run_id,
                       RUN_LENGTH + 1U);
    fallback->pid = source->pid;
    fallback->uid = source->uid;
    fallback->pgid = source->pgid;
    fallback->sid = source->sid;
    fallback->monotonic_start = source->monotonic_start;
    fallback->monotonic_end = source->monotonic_end;
    fallback->scope_valid = 0;
    fallback->load_attempted = source->load_attempted;
    fallback->load_loaded = source->load_loaded;
    fallback->read_first = -1;
    fallback->alloc_capacity = TRANSFER_CAPACITY;
    fallback->read_dwords = TRANSFER_DWORDS;
    fallback->read_pattern = TRANSFER_PATTERN;
    fallback->dlclose_attempted = source->dlclose_attempted;
    fallback->dlclose_ok = source->dlclose_ok;
    fallback->cleanup_complete = source->cleanup_complete;
    fallback->status = "UNKNOWN";
    fallback->failure_stage = "output";
    fallback->failure_detail[0] = '\0';
    fallback->native_exit = 1;
}

static int transfer_encode_unknown_fallback(const TransferResult *source, char *output,
                                            size_t capacity) {
    TransferResult fallback;
    transfer_make_unknown_fallback(&fallback, source);
    return transfer_encode_result(&fallback, output, capacity);
}

static int transfer_in_flight(const TransferResult *result) {
    return result != NULL && result->submit_attempted &&
           result->submit_result == VK_SUCCESS && !result->signaled;
}

static void transfer_classify(TransferResult *result) {
    int success = result->scope_valid && result->binding_valid && result->identity_stable &&
                  result->load_loaded && result->interface_supported &&
                  result->instance_created && result->physical_complete &&
                  result->physical_returned_count == 1U && result->physical_target_exact &&
                  result->queue_complete && result->selected && result->device_created &&
                  result->queue_observed && result->buf_created &&
                  result->req_queried && result->mem_selected && result->alloc_done &&
                  result->bind_ok && result->map_done && result->map_non_null &&
                  result->host_performed && (!result->flush_attempted || result->flush_ok) &&
                  result->pool_done && result->cmd_done && result->begun &&
                  result->fill_ok && result->barrier_ok && result->ended &&
                  result->fence_done && result->submit_attempted &&
                  result->submit_result == VK_SUCCESS && result->submit_count == 1 &&
                  result->wait_attempted && result->wait_result == VK_SUCCESS &&
                  result->wait_count == 1 && result->waited && result->signaled &&
                  (!result->inv_required || (result->inv_attempted && result->inv_ok)) &&
                  result->read_attempted && result->read_verified &&
                  result->read_mismatches == 0U &&
                  result->device_destroy_ok && result->instance_destroy_ok &&
                  result->dlclose_ok && result->cleanup_complete &&
                  result->clock_valid && result->timing_valid &&
                  transfer_failure_is(result, "none");
    if (!result->load_loaded && result->load_attempted && result->load_error[0] != '\0' &&
        result->cleanup_complete && transfer_failure_is(result, "loader")) {
        result->status = "LOADER_FAILED";
    } else if (success) {
        result->status = "TRANSFER_READBACK_OBSERVED";
    } else if (!result->vk_error && transfer_failure_is(result, "no_queue")) {
        result->status = "NO_QUEUE";
    } else if (!result->vk_error && transfer_failure_is(result, "memory")) {
        result->status = "NO_MEMORY_TYPE";
    } else if (!result->vk_error && transfer_failure_is(result, "timeout")) {
        result->status = "TIMEOUT";
    } else if (result->vk_error) {
        result->status = "VK_ERROR";
    } else {
        result->status = "UNKNOWN";
    }
    result->native_exit = strcmp(result->status, "TRANSFER_READBACK_OBSERVED") == 0 ? 0 : 1;
}

static int transfer_resolve_device_function(TransferResult *result,
                                            TransferGetDeviceProcAddr gdpa,
                                            VkDevice device, const char *owner,
                                            size_t index, const char *name) {
    PFN_vkVoidFunction address;
    if (gdpa == NULL || index >= MAX_FUNCTIONS) return 0;
    address = gdpa(device, name);
    return transfer_observe_function(&result->functions[index], address, owner);
}

typedef struct TransferCalls {
    PFN_vkGetPhysicalDeviceMemoryProperties get_mem_props;
    PFN_vkCreateBuffer create_buffer;
    PFN_vkDestroyBuffer destroy_buffer;
    PFN_vkGetBufferMemoryRequirements get_mem_req;
    PFN_vkAllocateMemory alloc_memory;
    PFN_vkFreeMemory free_memory;
    PFN_vkBindBufferMemory bind_memory;
    PFN_vkMapMemory map_memory;
    PFN_vkUnmapMemory unmap_memory;
    PFN_vkFlushMappedMemoryRanges flush_ranges;
    PFN_vkInvalidateMappedMemoryRanges invalidate_ranges;
    PFN_vkCreateCommandPool create_pool;
    PFN_vkDestroyCommandPool destroy_pool;
    PFN_vkAllocateCommandBuffers alloc_commands;
    PFN_vkFreeCommandBuffers free_commands;
    PFN_vkBeginCommandBuffer begin_commands;
    PFN_vkEndCommandBuffer end_commands;
    PFN_vkCmdFillBuffer cmd_fill;
    PFN_vkCmdPipelineBarrier cmd_barrier;
    PFN_vkCreateFence create_fence;
    PFN_vkDestroyFence destroy_fence;
    PFN_vkWaitForFences wait_fences;
    PFN_vkQueueSubmit queue_submit;
} TransferCalls;

static void transfer_free_partials(TransferResult *result, const TransferCalls *calls,
                                   VkDevice device, VkBuffer *buffer, int *has_buffer,
                                   VkDeviceMemory *memory, int *has_memory,
                                   void **mapped, int *has_mapped,
                                   VkCommandPool *pool, int *has_pool,
                                   VkCommandBuffer *cmd, int *has_cmd,
                                   VkFence *fence, int *has_fence) {
    uint64_t before;
    int started;
    if (result == NULL || calls == NULL) return;
    if (has_mapped != NULL && *has_mapped && mapped != NULL && *mapped != NULL &&
        calls->unmap_memory != NULL && device != VK_NULL_HANDLE && memory != NULL &&
        *has_memory) {
        started = transfer_call_begin(result, &before);
        calls->unmap_memory(device, *memory);
        if (started) (void)transfer_call_end(result, before);
        *mapped = NULL;
        *has_mapped = 0;
    }
    if (has_cmd != NULL && *has_cmd && has_pool != NULL && *has_pool &&
        calls->free_commands != NULL && device != VK_NULL_HANDLE && cmd != NULL &&
        pool != NULL) {
        started = transfer_call_begin(result, &before);
        calls->free_commands(device, *pool, 1U, cmd);
        if (started) (void)transfer_call_end(result, before);
        *has_cmd = 0;
    }
    if (has_pool != NULL && *has_pool && calls->destroy_pool != NULL &&
        device != VK_NULL_HANDLE && pool != NULL) {
        started = transfer_call_begin(result, &before);
        calls->destroy_pool(device, *pool, NULL);
        if (started) (void)transfer_call_end(result, before);
        *has_pool = 0;
    }
    if (has_fence != NULL && *has_fence && calls->destroy_fence != NULL &&
        device != VK_NULL_HANDLE && fence != NULL) {
        started = transfer_call_begin(result, &before);
        calls->destroy_fence(device, *fence, NULL);
        if (started) (void)transfer_call_end(result, before);
        *has_fence = 0;
    }
    if (has_buffer != NULL && *has_buffer && calls->destroy_buffer != NULL &&
        device != VK_NULL_HANDLE && buffer != NULL) {
        started = transfer_call_begin(result, &before);
        calls->destroy_buffer(device, *buffer, NULL);
        if (started) (void)transfer_call_end(result, before);
        *has_buffer = 0;
    }
    if (has_memory != NULL && *has_memory && calls->free_memory != NULL &&
        device != VK_NULL_HANDLE && memory != NULL) {
        started = transfer_call_begin(result, &before);
        calls->free_memory(device, *memory, NULL);
        if (started) (void)transfer_call_end(result, before);
        *has_memory = 0;
    }
}

static int transfer_workload(TransferResult *result, TransferGetDeviceProcAddr gdpa,
                             VkPhysicalDevice physical, VkDevice device, VkQueue queue,
                             uint32_t family, const char *owner) {
    TransferCalls calls;
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    void *mapped = NULL;
    int has_buffer = 0;
    int has_memory = 0;
    int has_mapped = 0;
    int has_pool = 0;
    int has_cmd = 0;
    int has_fence = 0;
    uint64_t before;
    int timing_ok;
    memset(&calls, 0, sizeof(calls));

    if (gdpa == NULL) {
        transfer_set_failure(result, "owner", "device proc addr unavailable for transfer");
        return 0;
    }
    {
        static const size_t device_indices[] = {11U, 12U, 13U, 14U, 15U, 16U, 17U,
                                                18U, 19U, 20U, 21U, 22U, 23U, 24U,
                                                25U, 26U, 27U, 28U, 29U, 30U, 31U, 32U};
        size_t slot;
        for (slot = 0U; slot < sizeof(device_indices) / sizeof(device_indices[0]); ++slot) {
            size_t target = device_indices[slot];
            if (!transfer_resolve_device_function(result, gdpa, device, owner, target,
                                                  transfer_function_names[target])) {
                transfer_set_failure(result, "owner", "required transfer PFN missing or owner mismatch");
                return 0;
            }
        }
    }
    calls.get_mem_props = NULL;
    calls.create_buffer = (PFN_vkCreateBuffer)(uintptr_t)result->functions[11U].address;
    calls.destroy_buffer = (PFN_vkDestroyBuffer)(uintptr_t)result->functions[12U].address;
    calls.get_mem_req = (PFN_vkGetBufferMemoryRequirements)(uintptr_t)result->functions[13U].address;
    calls.alloc_memory = (PFN_vkAllocateMemory)(uintptr_t)result->functions[14U].address;
    calls.free_memory = (PFN_vkFreeMemory)(uintptr_t)result->functions[15U].address;
    calls.bind_memory = (PFN_vkBindBufferMemory)(uintptr_t)result->functions[16U].address;
    calls.map_memory = (PFN_vkMapMemory)(uintptr_t)result->functions[17U].address;
    calls.unmap_memory = (PFN_vkUnmapMemory)(uintptr_t)result->functions[18U].address;
    calls.flush_ranges = (PFN_vkFlushMappedMemoryRanges)(uintptr_t)result->functions[19U].address;
    calls.invalidate_ranges = (PFN_vkInvalidateMappedMemoryRanges)(uintptr_t)result->functions[20U].address;
    calls.create_pool = (PFN_vkCreateCommandPool)(uintptr_t)result->functions[21U].address;
    calls.destroy_pool = (PFN_vkDestroyCommandPool)(uintptr_t)result->functions[22U].address;
    calls.alloc_commands = (PFN_vkAllocateCommandBuffers)(uintptr_t)result->functions[23U].address;
    calls.free_commands = (PFN_vkFreeCommandBuffers)(uintptr_t)result->functions[24U].address;
    calls.begin_commands = (PFN_vkBeginCommandBuffer)(uintptr_t)result->functions[25U].address;
    calls.end_commands = (PFN_vkEndCommandBuffer)(uintptr_t)result->functions[26U].address;
    calls.cmd_fill = (PFN_vkCmdFillBuffer)(uintptr_t)result->functions[27U].address;
    calls.cmd_barrier = (PFN_vkCmdPipelineBarrier)(uintptr_t)result->functions[28U].address;
    calls.create_fence = (PFN_vkCreateFence)(uintptr_t)result->functions[29U].address;
    calls.destroy_fence = (PFN_vkDestroyFence)(uintptr_t)result->functions[30U].address;
    calls.wait_fences = (PFN_vkWaitForFences)(uintptr_t)result->functions[31U].address;
    calls.queue_submit = (PFN_vkQueueSubmit)(uintptr_t)result->functions[32U].address;

    {
        VkBufferCreateInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        info.size = (VkDeviceSize)TRANSFER_SIZE;
        info.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        result->buf_size = TRANSFER_SIZE_U32;
        result->buf_usage = (uint32_t)VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        result->buf_sharing = (uint32_t)VK_SHARING_MODE_EXCLUSIVE;
        result->buf_create_attempted = 1;
        if (!transfer_call_begin(result, &before)) goto transfer_done;
        result->buf_result = calls.create_buffer(device, &info, NULL, &buffer);
        timing_ok = transfer_call_end(result, before);
        transfer_record_result(result, result->buf_result, "vkCreateBuffer failed");
        if (result->buf_result != VK_SUCCESS || buffer == VK_NULL_HANDLE) {
            if (result->buf_result != VK_SUCCESS && buffer != VK_NULL_HANDLE) {
                result->buf_handle_on_error = 1;
                transfer_set_failure(result, "vulkan", "vkCreateBuffer returned a handle with an error");
                buffer = VK_NULL_HANDLE;
            } else if (result->buf_result == VK_SUCCESS) {
                transfer_set_failure(result, "vulkan", "vkCreateBuffer returned null");
            }
            goto transfer_done;
        }
        result->buf_created = 1;
        has_buffer = 1;
        if (!timing_ok) goto transfer_done;
    }

    {
        VkMemoryRequirements req;
        memset(&req, 0, sizeof(req));
        if (!transfer_call_begin(result, &before)) goto transfer_done;
        calls.get_mem_req(device, buffer, &req);
        if (!transfer_call_end(result, before)) goto transfer_done;
        result->req_queried = 1;
        result->req_size = (uint64_t)req.size;
        result->req_align = (uint64_t)req.alignment;
        result->req_bits = req.memoryTypeBits;
    }

    {
        PFN_vkGetPhysicalDeviceMemoryProperties get_props;
        VkPhysicalDeviceMemoryProperties props;
        uint32_t chosen;
        int coherent = 0;
        get_props = (PFN_vkGetPhysicalDeviceMemoryProperties)(uintptr_t)
            result->functions[6U].address;
        if (get_props == NULL) {
            transfer_set_failure(result, "owner", "memory-properties PFN unavailable");
            goto transfer_done;
        }
        calls.get_mem_props = get_props;
        memset(&props, 0, sizeof(props));
        if (!transfer_call_begin(result, &before)) goto transfer_done;
        get_props(physical, &props);
        if (!transfer_call_end(result, before)) goto transfer_done;
        chosen = transfer_select_memory_index(&props, result->req_bits, &coherent);
        if (chosen == UINT32_MAX) {
            transfer_set_failure(result, "memory", "no memory type with HOST_VISIBLE for requirements");
            goto transfer_done;
        }
        result->mem_selected = 1;
        result->mem_index = chosen;
        result->mem_flags = (uint32_t)props.memoryTypes[chosen].propertyFlags;
        result->mem_visible = (props.memoryTypes[chosen].propertyFlags &
                               VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0U;
        result->mem_coherent = (props.memoryTypes[chosen].propertyFlags &
                                VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0U;
        result->mem_device_local = (props.memoryTypes[chosen].propertyFlags &
                                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0U;
    }

    {
        VkMemoryAllocateInfo info;
        uint64_t aligned = result->req_size;
        if (result->req_size > TRANSFER_CAPACITY) {
            transfer_set_failure(result, "requirements", "buffer requirements exceed 256 KiB cap");
            goto transfer_done;
        }
        if (result->req_align > 1U &&
            (result->req_align & (result->req_align - 1U)) == 0U) {
            uint64_t mask = result->req_align - 1U;
            aligned = (result->req_size + mask) & ~mask;
        }
        if (aligned > TRANSFER_CAPACITY) {
            transfer_set_failure(result, "requirements", "aligned allocation exceeds 256 KiB cap");
            goto transfer_done;
        }
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        info.allocationSize = (VkDeviceSize)aligned;
        info.memoryTypeIndex = result->mem_index;
        result->alloc_requested = aligned;
        result->alloc_attempted = 1;
        if (!transfer_call_begin(result, &before)) goto transfer_done;
        result->alloc_result = calls.alloc_memory(device, &info, NULL, &memory);
        timing_ok = transfer_call_end(result, before);
        transfer_record_result(result, result->alloc_result, "vkAllocateMemory failed");
        if (result->alloc_result != VK_SUCCESS || memory == VK_NULL_HANDLE) {
            if (result->alloc_result != VK_SUCCESS && memory != VK_NULL_HANDLE) {
                result->alloc_handle_on_error = 1;
                transfer_set_failure(result, "vulkan", "vkAllocateMemory returned a handle with an error");
                memory = VK_NULL_HANDLE;
            } else if (result->alloc_result == VK_SUCCESS) {
                transfer_set_failure(result, "vulkan", "vkAllocateMemory returned null");
            }
            goto transfer_done;
        }
        result->alloc_done = 1;
        has_memory = 1;
        if (!timing_ok) goto transfer_done;
    }

    {
        VkResult bind_result;
        result->bind_attempted = 1;
        if (!transfer_call_begin(result, &before)) goto transfer_done;
        bind_result = calls.bind_memory(device, buffer, memory, 0U);
        timing_ok = transfer_call_end(result, before);
        transfer_record_result(result, bind_result, "vkBindBufferMemory failed");
        if (bind_result != VK_SUCCESS) goto transfer_done;
        result->bind_ok = 1;
        if (!timing_ok) goto transfer_done;
    }

    {
        VkResult map_result;
        result->map_attempted = 1;
        if (!transfer_call_begin(result, &before)) goto transfer_done;
        map_result = calls.map_memory(device, memory, 0U, (VkDeviceSize)TRANSFER_SIZE,
                                      0U, &mapped);
        timing_ok = transfer_call_end(result, before);
        transfer_record_result(result, map_result, "vkMapMemory failed");
        if (map_result != VK_SUCCESS || mapped == NULL) {
            mapped = NULL;
            if (map_result == VK_SUCCESS)
                transfer_set_failure(result, "map", "vkMapMemory returned null pointer");
            goto transfer_done;
        }
        result->map_done = 1;
        result->map_non_null = 1;
        has_mapped = 1;
        if (!timing_ok) goto transfer_done;
        memset(mapped, 0, (size_t)TRANSFER_SIZE);
        result->host_performed = 1;
        result->host_bytes = TRANSFER_SIZE_U32;
        result->host_coherent = result->mem_coherent;
        if (!result->mem_coherent) {
            VkMappedMemoryRange range;
            VkResult flush_result;
            memset(&range, 0, sizeof(range));
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
            range.memory = memory;
            range.offset = 0U;
            range.size = (VkDeviceSize)TRANSFER_SIZE;
            result->flush_attempted = 1;
            result->flush_offset = 0U;
            result->flush_size = TRANSFER_SIZE;
            if (!transfer_call_begin(result, &before)) goto transfer_done;
            flush_result = calls.flush_ranges(device, 1U, &range);
            timing_ok = transfer_call_end(result, before);
            transfer_record_result(result, flush_result, "vkFlushMappedMemoryRanges failed");
            if (flush_result != VK_SUCCESS) goto transfer_done;
            result->flush_ok = 1;
            if (!timing_ok) goto transfer_done;
        }
    }

    {
        VkCommandPoolCreateInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        info.queueFamilyIndex = family;
        result->pool_flags = TRANSFER_POOL_FLAGS;
        result->pool_family = family;
        result->pool_attempted = 1;
        if (!transfer_call_begin(result, &before)) goto transfer_done;
        result->pool_result = calls.create_pool(device, &info, NULL, &pool);
        timing_ok = transfer_call_end(result, before);
        transfer_record_result(result, result->pool_result, "vkCreateCommandPool failed");
        if (result->pool_result != VK_SUCCESS || pool == VK_NULL_HANDLE) {
            pool = VK_NULL_HANDLE;
            if (result->pool_result == VK_SUCCESS)
                transfer_set_failure(result, "vulkan", "vkCreateCommandPool returned null");
            goto transfer_done;
        }
        result->pool_done = 1;
        has_pool = 1;
        if (!timing_ok) goto transfer_done;
    }

    {
        VkCommandBufferAllocateInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        info.commandPool = pool;
        info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        info.commandBufferCount = 1U;
        result->cmd_level = (uint32_t)VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        result->cmd_attempted = 1;
        if (!transfer_call_begin(result, &before)) goto transfer_done;
        result->cmd_result = calls.alloc_commands(device, &info, &cmd);
        timing_ok = transfer_call_end(result, before);
        transfer_record_result(result, result->cmd_result, "vkAllocateCommandBuffers failed");
        if (result->cmd_result != VK_SUCCESS || cmd == VK_NULL_HANDLE) {
            cmd = VK_NULL_HANDLE;
            if (result->cmd_result == VK_SUCCESS)
                transfer_set_failure(result, "vulkan", "vkAllocateCommandBuffers returned null");
            goto transfer_done;
        }
        result->cmd_done = 1;
        has_cmd = 1;
        if (!timing_ok) goto transfer_done;
    }

    {
        VkCommandBufferBeginInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        info.flags = 0U;
        result->begin_attempted = 1;
        if (!transfer_call_begin(result, &before)) goto transfer_done;
        result->begin_result = calls.begin_commands(cmd, &info);
        timing_ok = transfer_call_end(result, before);
        transfer_record_result(result, result->begin_result, "vkBeginCommandBuffer failed");
        if (result->begin_result != VK_SUCCESS) goto transfer_done;
        result->begun = 1;
        if (!timing_ok) goto transfer_done;
    }

    {
        VkMemoryBarrier barrier;
        if (!transfer_call_begin(result, &before)) goto transfer_done;
        calls.cmd_fill(cmd, buffer, 0U, (VkDeviceSize)TRANSFER_SIZE, TRANSFER_PATTERN);
        if (!transfer_call_end(result, before)) goto transfer_done;
        result->fill_attempted = 1;
        result->fill_ok = 1;
        result->fill_offset = 0U;
        result->fill_size = TRANSFER_SIZE;
        result->fill_data = TRANSFER_PATTERN;
        memset(&barrier, 0, sizeof(barrier));
        barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        if (!transfer_call_begin(result, &before)) goto transfer_done;
        calls.cmd_barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                          VK_PIPELINE_STAGE_HOST_BIT, 0U, 1U, &barrier, 0U, NULL,
                          0U, NULL);
        if (!transfer_call_end(result, before)) goto transfer_done;
        result->barrier_attempted = 1;
        result->barrier_ok = 1;
    }

    {
        result->end_attempted = 1;
        if (!transfer_call_begin(result, &before)) goto transfer_done;
        result->end_result = calls.end_commands(cmd);
        timing_ok = transfer_call_end(result, before);
        transfer_record_result(result, result->end_result, "vkEndCommandBuffer failed");
        if (result->end_result != VK_SUCCESS) goto transfer_done;
        result->ended = 1;
        if (!timing_ok) goto transfer_done;
    }

    {
        VkFenceCreateInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        info.flags = 0U;
        result->fence_flags = 0U;
        result->fence_attempted = 1;
        if (!transfer_call_begin(result, &before)) goto transfer_done;
        result->fence_result = calls.create_fence(device, &info, NULL, &fence);
        timing_ok = transfer_call_end(result, before);
        transfer_record_result(result, result->fence_result, "vkCreateFence failed");
        if (result->fence_result != VK_SUCCESS || fence == VK_NULL_HANDLE) {
            fence = VK_NULL_HANDLE;
            if (result->fence_result == VK_SUCCESS)
                transfer_set_failure(result, "vulkan", "vkCreateFence returned null");
            goto transfer_done;
        }
        result->fence_done = 1;
        has_fence = 1;
        if (!timing_ok) goto transfer_done;
    }

    {
        VkSubmitInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        info.commandBufferCount = 1U;
        info.pCommandBuffers = &cmd;
        result->submit_attempted = 1;
        if (!transfer_call_begin(result, &before)) goto transfer_done;
        result->submit_result = calls.queue_submit(queue, 1U, &info, fence);
        timing_ok = transfer_call_end(result, before);
        ++result->submit_count;
        transfer_record_result(result, result->submit_result, "vkQueueSubmit failed");
        if (result->submit_result != VK_SUCCESS) goto transfer_done;
        if (!timing_ok) goto transfer_done;
        result->wait_attempted = 1;
        result->wait_timeout = TRANSFER_TIMEOUT_NS;
        if (!transfer_call_begin(result, &before)) {
            transfer_set_failure(result, "timeout", "monotonic check before fence wait failed");
            goto transfer_done;
        }
        result->wait_result = calls.wait_fences(device, 1U, &fence, VK_TRUE,
                                                (uint64_t)TRANSFER_TIMEOUT_NS);
        timing_ok = transfer_call_end(result, before);
        ++result->wait_count;
        if (result->wait_result == VK_SUCCESS) {
            result->waited = 1;
            result->signaled = 1;
        } else if (result->wait_result == VK_TIMEOUT) {
            transfer_set_failure(result, "timeout", "vkWaitForFences timed out after 1s");
            goto transfer_done;
        } else {
            transfer_record_result(result, result->wait_result, "vkWaitForFences failed");
            if (!transfer_failure_is(result, "none"))
                transfer_set_failure(result, "vulkan", "vkWaitForFences failed");
            goto transfer_done;
        }
        if (!timing_ok) goto transfer_done;
    }

    if (!result->mem_coherent) {
        VkMappedMemoryRange range;
        VkResult inv_result;
        memset(&range, 0, sizeof(range));
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = memory;
        range.offset = 0U;
        range.size = (VkDeviceSize)TRANSFER_SIZE;
        result->inv_required = 1;
        result->inv_attempted = 1;
        result->inv_offset = 0U;
        result->inv_size = TRANSFER_SIZE;
        if (!transfer_call_begin(result, &before)) goto transfer_done;
        inv_result = calls.invalidate_ranges(device, 1U, &range);
        timing_ok = transfer_call_end(result, before);
        transfer_record_result(result, inv_result, "vkInvalidateMappedMemoryRanges failed");
        if (inv_result != VK_SUCCESS) goto transfer_done;
        result->inv_ok = 1;
        if (!timing_ok) goto transfer_done;
    }

    {
        const uint32_t *words;
        uint32_t mismatches = 0U;
        int32_t first = -1;
        uint32_t index;
        result->read_attempted = 1;
        words = (const uint32_t *)mapped;
        for (index = 0U; index < TRANSFER_DWORDS; ++index) {
            if (words[index] != TRANSFER_PATTERN) {
                if (first < 0) first = (int32_t)index;
                ++mismatches;
            }
        }
        result->read_mismatches = mismatches;
        result->read_first = first;
        transfer_fnv1a((const unsigned char *)mapped, (size_t)TRANSFER_SIZE,
                       result->read_hash);
        result->read_verified = mismatches == 0U;
        if (!result->read_verified)
            transfer_set_failure(result, "readback", "transfer readback mismatch");
    }

transfer_done:
    if (result->signaled && transfer_failure_is(result, "none")) {
        transfer_free_partials(result, &calls, device, &buffer, &has_buffer, &memory,
                               &has_memory, &mapped, &has_mapped, &pool, &has_pool,
                               &cmd, &has_cmd, &fence, &has_fence);
        return result->read_verified;
    }
    if (transfer_in_flight(result)) {
        return 0;
    }
    transfer_free_partials(result, &calls, device, &buffer, &has_buffer, &memory,
                           &has_memory, &mapped, &has_mapped, &pool, &has_pool,
                           &cmd, &has_cmd, &fence, &has_fence);
    return 0;
}

static void transfer_control(TransferResult *result, TransferGetInstanceProcAddr gipa,
                             VkInstance instance, VkPhysicalDevice physical,
                             const char *owner) {
    PFN_vkGetPhysicalDeviceQueueFamilyProperties get_families;
    PFN_vkGetPhysicalDeviceMemoryProperties get_mem_props = NULL;
    PFN_vkCreateDevice create_device;
    PFN_vkDestroyDevice destroy_device = NULL;
    TransferGetDeviceProcAddr gdpa = NULL;
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

    if (!transfer_resolve_instance(result, gipa, instance, owner, 5U,
                                   "vkGetPhysicalDeviceQueueFamilyProperties") ||
        !transfer_resolve_instance(result, gipa, instance, owner, 6U,
                                   "vkGetPhysicalDeviceMemoryProperties") ||
        !transfer_resolve_instance(result, gipa, instance, owner, 7U, "vkCreateDevice") ||
        !transfer_resolve_instance(result, gipa, instance, owner, 8U, "vkDestroyDevice") ||
        !transfer_resolve_instance(result, gipa, instance, owner, 9U, "vkGetDeviceProcAddr")) {
        transfer_set_failure(result, "owner", "required device PFN missing or owner mismatch");
        return;
    }
    get_families = (PFN_vkGetPhysicalDeviceQueueFamilyProperties)(uintptr_t)
        result->functions[5U].address;
    get_mem_props = (PFN_vkGetPhysicalDeviceMemoryProperties)(uintptr_t)
        result->functions[6U].address;
    (void)get_mem_props;
    create_device = (PFN_vkCreateDevice)(uintptr_t)result->functions[7U].address;
    destroy_device = (PFN_vkDestroyDevice)(uintptr_t)result->functions[8U].address;
    gdpa = (TransferGetDeviceProcAddr)(uintptr_t)result->functions[9U].address;

    if (!transfer_call_begin(result, &before)) return;
    get_families(physical, &reported, NULL);
    if (!transfer_call_end(result, before)) return;
    result->queue_count_queried = 1;
    result->queue_reported_count = reported;
    if (reported > MAX_QUEUE_FAMILIES) {
        transfer_set_failure(result, "queues", "queue-family count exceeds 16");
        return;
    }
    returned = reported;
    if (reported != 0U) {
        memset(families, 0, sizeof(families));
        if (!transfer_call_begin(result, &before)) return;
        get_families(physical, &returned, families);
        if (!transfer_call_end(result, before)) return;
        result->queue_fill_queried = 1;
        result->queue_returned_count = returned;
        result->queue_retained_count = returned > MAX_QUEUE_FAMILIES ? MAX_QUEUE_FAMILIES : returned;
        if (returned > MAX_QUEUE_FAMILIES || returned != reported) {
            transfer_set_failure(result, "queues", "queue-family count is incomplete or oversized");
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
    if (returned == 0U || !transfer_select_queue(families, returned, &index, &kind)) {
        transfer_set_failure(result, "no_queue", "no complete eligible queue family");
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
    if (!transfer_call_begin(result, &before)) return;
    result->device_create_result = create_device(physical, &device_create, NULL, &device);
    {
        int create_timing_valid = transfer_call_end(result, before);
        transfer_record_result(result, result->device_create_result, "vkCreateDevice failed");
        if (result->device_create_result != VK_SUCCESS || device == VK_NULL_HANDLE) {
            if (result->device_create_result != VK_SUCCESS && device != VK_NULL_HANDLE) {
                result->device_handle_returned_on_error = 1;
                transfer_set_failure(result, "vulkan", "vkCreateDevice returned a handle with an error");
                device = VK_NULL_HANDLE;
            } else if (result->device_create_result == VK_SUCCESS) {
                transfer_set_failure(result, "vulkan", "vkCreateDevice returned null");
            }
            return;
        }
        result->device_created = 1;
        result->device_live = 1;
        if (!create_timing_valid) goto transfer_cleanup;
    }

    {
        TransferFunction gdpa_destroy;
        memset(&gdpa_destroy, 0, sizeof(gdpa_destroy));
        gdpa_destroy.name = "vkDestroyDevice";
        if (!transfer_observe_function(&gdpa_destroy, gdpa(device, "vkDestroyDevice"), owner)) {
            transfer_set_failure(result, "owner", "device vkDestroyDevice owner mismatch");
            goto transfer_cleanup;
        }
        destroy_device = (PFN_vkDestroyDevice)(uintptr_t)gdpa_destroy.address;
    }
    if (!transfer_resolve_device_function(result, gdpa, device, owner, 10U, "vkGetDeviceQueue")) {
        transfer_set_failure(result, "owner", "device vkGetDeviceQueue owner mismatch");
        goto transfer_cleanup;
    }
    if (result->functions[10U].owner_exact) {
        get_queue = (PFN_vkGetDeviceQueue)(uintptr_t)result->functions[10U].address;
        result->queue_get_attempted = 1;
        result->queue_index = 0U;
        if (!transfer_call_begin(result, &before)) goto transfer_cleanup;
        get_queue(device, index, 0U, &queue);
        if (!transfer_call_end(result, before)) goto transfer_cleanup;
        result->queue_non_null = queue != VK_NULL_HANDLE;
        result->queue_observed = result->queue_non_null;
        if (!result->queue_non_null) {
            transfer_set_failure(result, "queue", "vkGetDeviceQueue returned null");
            goto transfer_cleanup;
        }
    }
    if (transfer_failure_is(result, "none") && queue != VK_NULL_HANDLE) {
        (void)transfer_workload(result, gdpa, physical, device, queue, index, owner);
    }
    goto transfer_cleanup;

transfer_cleanup:
    if (transfer_in_flight(result)) {
        return;
    }
    if (destroy_device != NULL && result->device_live) {
        int cleanup_timing_started;
        result->device_destroy_attempted = 1;
        cleanup_timing_started = transfer_call_begin(result, &before);
        destroy_device(device, NULL);
        if (cleanup_timing_started) (void)transfer_call_end(result, before);
        result->device_destroy_ok = 1;
        result->device_live = 0;
    }
    return;
}

static MAYBE_UNUSED int transfer_target_properties(TransferResult *result,
                                                   PFN_vkGetPhysicalDeviceProperties get_properties,
                                                   VkPhysicalDevice physical) {
    VkPhysicalDeviceProperties properties;
    uint64_t before;
    memset(&properties, 0, sizeof(properties));
    if (!transfer_call_begin(result, &before)) return 0;
    get_properties(physical, &properties);
    if (!transfer_call_end(result, before)) return 0;
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
        transfer_set_failure(result, "physical", "physical device is not the measured Mali-G610 target");
    return result->physical_target_exact;
}

static MAYBE_UNUSED int transfer_emit(TransferResult *result, char *output, size_t capacity) {
    size_t length;
    transfer_classify(result);
    if (!transfer_encode_result(result, output, capacity)) {
        if (!transfer_encode_unknown_fallback(result, output, capacity)) return 0;
        transfer_classify(result);
        result->status = "UNKNOWN";
        result->native_exit = 1;
        if (!transfer_encode_unknown_fallback(result, output, capacity)) return 0;
    }
    length = strlen(output);
    if (length >= capacity || fwrite(output, 1U, length, stdout) != length ||
        fputc('\n', stdout) == EOF || fflush(stdout) != 0) return 0;
    return 1;
}

#ifndef PANVK_TRANSFER_HOST_TEST
static int transfer_invalid_invocation(void) {
    TransferResult result;
    char output[TRANSFER_OUTPUT_LIMIT];
    transfer_init_result(&result, "");
    result.monotonic_start = monotonic_now();
    result.monotonic_end = monotonic_now();
    result.status = "UNKNOWN";
    result.failure_stage = "arguments";
    copy_error(result.failure_detail, "expected --run-id PVK-<32 lowercase hexadecimal characters>");
    result.native_exit = 1;
    return transfer_encode_result(&result, output, sizeof(output)) &&
           fwrite(output, 1U, strlen(output), stdout) == strlen(output) &&
           fputc('\n', stdout) != EOF && fflush(stdout) == 0 ? 1 : 1;
}

static int transfer_native_main(const char *run_id) {
    TransferResult result;
    char canonical_icd[PATH_LIMIT];
    void *handle = NULL;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    TransferGetInstanceProcAddr gipa = NULL;
    TransferNegotiate negotiate = NULL;
    PFN_vkDestroyInstance destroy_instance = NULL;
    PFN_vkEnumeratePhysicalDevices enumerate_physical = NULL;
    PFN_vkGetPhysicalDeviceProperties get_properties = NULL;
    uint32_t negotiated = 7U;
    uint32_t reported = 0U;
    uint32_t returned;
    VkPhysicalDevice physicals[MAX_PHYSICAL_DEVICES];
    uint64_t before;
    uint64_t end_time;
    char output[TRANSFER_OUTPUT_LIMIT];
    size_t index;

    transfer_init_result(&result, run_id);
    result.monotonic_start = monotonic_now();
    result.clock_start_valid = result.monotonic_start > 0U;
    result.scope_valid = scope_valid(result.cwd, run_id);
    if (!result.scope_valid) transfer_set_failure(&result, "scope", "UID, CWD, run ID, or environment mismatch");
    if (!result.clock_start_valid) transfer_set_failure(&result, "clock", "monotonic start unavailable");
    if (result.scope_valid && path_join(result.icd_path, sizeof(result.icd_path), result.cwd, ICD_NAME)) {
        if (!canonical_path(result.icd_path, canonical_icd, sizeof(canonical_icd)) ||
            strcmp(canonical_icd, result.icd_path) != 0) {
            transfer_set_failure(&result, "identity", "ICD is not canonical");
        } else {
            result.binding_valid = 1;
            result.before_hash_valid = hash_file(result.icd_path, &result.before, result.before_sha256);
            if (result.before_hash_valid) {
                (void)copy_bounded(result.icd_sha256, sizeof(result.icd_sha256), result.before_sha256, 65U);
                result.icd_bytes = result.before.size;
            }
        }
    } else {
        transfer_set_failure(&result, "path", "ICD path exceeds bound");
    }
    if (!result.binding_valid || !result.before_hash_valid) {
        transfer_set_failure(&result, "hash", "ICD identity, size, owner, or SHA-256 mismatch");
    } else if (result.clock_start_valid) {
        (void)dlerror();
        result.load_attempted = 1;
        handle = dlopen(result.icd_path, RTLD_NOW | RTLD_LOCAL);
        if (handle == NULL) {
            copy_error(result.load_error, dlerror());
            transfer_set_failure(&result, "loader", result.load_error[0] != '\0' ? result.load_error : "dlopen failed");
        } else {
            result.load_loaded = 1;
            transfer_observe_exports(&result, handle, canonical_icd);
            for (index = 0U; index < 3U; ++index) {
                if (!result.exports[index].found || !result.exports[index].owner_exact)
                    transfer_set_failure(&result, "export", "required ICD export missing or owner mismatch");
            }
            gipa = (TransferGetInstanceProcAddr)(uintptr_t)dlsym(handle, export_names[0]);
            negotiate = (TransferNegotiate)(uintptr_t)dlsym(handle, export_names[2]);
            if (gipa == NULL || negotiate == NULL || !result.exports[0].owner_exact ||
                !result.exports[2].owner_exact) {
                transfer_set_failure(&result, "export", "required ICD entrypoint missing or owner mismatch");
            } else {
                uint64_t call_before;
                result.interface_input = 7;
                if (transfer_call_begin(&result, &call_before)) {
                    result.interface_result = negotiate(&negotiated);
                    (void)transfer_call_end(&result, call_before);
                } else result.interface_result = VK_ERROR_INITIALIZATION_FAILED;
                result.interface_output = (int)negotiated;
                transfer_record_result(&result, (VkResult)result.interface_result, "ICD interface negotiation failed");
                result.interface_supported = result.interface_result == VK_SUCCESS && negotiated >= 2U && negotiated <= 7U;
                if (!result.interface_supported) transfer_set_failure(&result, "interface", "unsupported ICD interface");
            }
            if (gipa != NULL && result.interface_supported && transfer_failure_is(&result, "none")) {
                PFN_vkVoidFunction version_address = gipa(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
                result.enumerate_instance_version_found = version_address != NULL;
                if (version_address == NULL) {
                    result.legacy_api_10 = 1;
                    result.enumerate_instance_version_owner = 1;
                    result.enumerate_instance_version_result = VK_SUCCESS;
                } else {
                    uint64_t call_before;
                    result.enumerate_instance_version_owner = transfer_observe_function(&result.functions[0], version_address, canonical_icd);
                    if (!result.enumerate_instance_version_owner) {
                        transfer_set_failure(&result, "owner", "instance-version PFN owner mismatch");
                    } else if (transfer_call_begin(&result, &call_before)) {
                        uint32_t version = 0U;
                        PFN_vkEnumerateInstanceVersion version_fn = (PFN_vkEnumerateInstanceVersion)(uintptr_t)version_address;
                        result.enumerate_instance_version_result = version_fn(&version);
                        (void)transfer_call_end(&result, call_before);
                        result.api_reported = version;
                        transfer_record_result(&result, result.enumerate_instance_version_result, "instance API version query failed");
                        if (result.enumerate_instance_version_result != VK_SUCCESS || VK_API_VERSION_VARIANT(version) != 0U ||
                            VK_API_VERSION_MAJOR(version) != 1U || version < VK_API_VERSION_1_0)
                            transfer_set_failure(&result, "api", "invalid instance API version");
                    }
                }
                result.api_requested = result.api_reported;
                if (VK_API_VERSION_MAJOR(result.api_requested) > 1U ||
                    (VK_API_VERSION_MAJOR(result.api_requested) == 1U && VK_API_VERSION_MINOR(result.api_requested) > 3U))
                    result.api_requested = VK_API_VERSION_1_3;
                if (transfer_failure_is(&result, "none")) {
                    PFN_vkVoidFunction create_address = gipa(VK_NULL_HANDLE, "vkCreateInstance");
                    VkApplicationInfo app;
                    VkInstanceCreateInfo create;
                    if (!transfer_observe_function(&result.functions[1], create_address, canonical_icd)) {
                        transfer_set_failure(&result, "owner", "vkCreateInstance PFN owner mismatch");
                    } else {
                        memset(&app, 0, sizeof(app));
                        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
                        app.pApplicationName = "PanvkTransfer";
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
                        if (transfer_call_begin(&result, &before)) {
                            PFN_vkCreateInstance create_instance = (PFN_vkCreateInstance)(uintptr_t)create_address;
                            result.instance_create_result = create_instance(&create, NULL, &instance);
                            (void)transfer_call_end(&result, before);
                        } else result.instance_create_result = VK_ERROR_INITIALIZATION_FAILED;
                        transfer_record_result(&result, result.instance_create_result, "vkCreateInstance failed");
                        if (result.instance_create_result != VK_SUCCESS && instance != VK_NULL_HANDLE) {
                            result.instance_handle_returned_on_error = 1;
                            instance = VK_NULL_HANDLE;
                            transfer_set_failure(&result, "vulkan", "vkCreateInstance returned a handle with an error");
                        } else if (result.instance_create_result == VK_SUCCESS && instance == VK_NULL_HANDLE) {
                            transfer_set_failure(&result, "vulkan", "vkCreateInstance returned null");
                        } else if (result.instance_create_result == VK_SUCCESS) {
                            result.instance_created = 1;
                        }
                    }
                }
            }
            if (instance != VK_NULL_HANDLE && result.instance_created) {
                PFN_vkVoidFunction destroy_address = gipa(instance, "vkDestroyInstance");
                result.functions[2].name = "vkDestroyInstance";
                if (!transfer_observe_function(&result.functions[2], destroy_address, canonical_icd)) {
                    transfer_set_failure(&result, "owner", "vkDestroyInstance PFN owner mismatch");
                } else {
                    destroy_instance = (PFN_vkDestroyInstance)(uintptr_t)destroy_address;
                }
                if (destroy_instance != NULL && result.functions[2].owner_exact &&
                    transfer_failure_is(&result, "none")) {
                    PFN_vkVoidFunction enumerate_address = gipa(instance, "vkEnumeratePhysicalDevices");
                    PFN_vkVoidFunction properties_address = gipa(instance, "vkGetPhysicalDeviceProperties");
                    if (!transfer_observe_function(&result.functions[3], enumerate_address, canonical_icd) ||
                        !transfer_observe_function(&result.functions[4], properties_address, canonical_icd)) {
                        transfer_set_failure(&result, "owner", "required physical-device PFN owner mismatch");
                    } else {
                        enumerate_physical = (PFN_vkEnumeratePhysicalDevices)(uintptr_t)enumerate_address;
                        get_properties = (PFN_vkGetPhysicalDeviceProperties)(uintptr_t)properties_address;
                        if (transfer_call_begin(&result, &before)) {
                            result.physical_count_result = enumerate_physical(instance, &reported, NULL);
                            (void)transfer_call_end(&result, before);
                        } else result.physical_count_result = VK_ERROR_INITIALIZATION_FAILED;
                        result.physical_reported_count = reported;
                        transfer_record_result(&result, result.physical_count_result, "physical-device count failed");
                        if (result.physical_count_result == VK_SUCCESS && transfer_failure_is(&result, "none")) {
                            if (reported > MAX_PHYSICAL_DEVICES) {
                                transfer_set_failure(&result, "physical", "physical-device count exceeds 8");
                            } else if (reported != 0U) {
                                returned = reported;
                                memset(physicals, 0, sizeof(physicals));
                                if (transfer_call_begin(&result, &before)) {
                                    result.physical_fill_result = enumerate_physical(instance, &returned, physicals);
                                    (void)transfer_call_end(&result, before);
                                } else result.physical_fill_result = VK_ERROR_INITIALIZATION_FAILED;
                                result.physical_returned_count = returned;
                                transfer_record_result(&result, result.physical_fill_result, "physical-device fill failed");
                                result.physical_complete = result.physical_fill_result == VK_SUCCESS && returned == reported;
                                if (!result.physical_complete)
                                    transfer_set_failure(&result, "physical", "physical-device count is incomplete");
                                if (returned > MAX_PHYSICAL_DEVICES) returned = MAX_PHYSICAL_DEVICES;
                                result.physical_retained_count = returned;
                                if (result.physical_complete && returned == 1U && physicals[0] != VK_NULL_HANDLE &&
                                    transfer_failure_is(&result, "none")) {
                                    physical = physicals[0];
                                    (void)transfer_target_properties(&result, get_properties, physical);
                                    if (result.physical_target_exact)
                                        transfer_control(&result, gipa, instance, physical, canonical_icd);
                                } else if (returned != 1U) {
                                    transfer_set_failure(&result, "physical", "exactly one physical device required");
                                } else {
                                    transfer_set_failure(&result, "physical", "physical-device handle is null");
                                }
                            } else {
                                result.physical_complete = 1;
                                transfer_set_failure(&result, "physical", "zero physical devices");
                            }
                        }
                    }
                }
            }
        }
    }
    if (instance != VK_NULL_HANDLE && result.instance_created && !result.device_live &&
        !transfer_in_flight(&result) && destroy_instance != NULL &&
        result.functions[2].owner_exact) {
        result.instance_destroy_attempted = 1;
        {
            int cleanup_timing_started = transfer_call_begin(&result, &before);
            destroy_instance(instance, NULL);
            if (cleanup_timing_started) (void)transfer_call_end(&result, before);
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
            transfer_set_failure(&result, "identity", "ICD changed or failed post-observation verification");
    }
    if (handle != NULL && instance == VK_NULL_HANDLE && !result.device_live &&
        !transfer_in_flight(&result)) {
        result.dlclose_attempted = 1;
        result.dlclose_ok = dlclose(handle) == 0;
        if (!result.dlclose_ok) transfer_set_failure(&result, "cleanup", "dlclose failed");
    } else if (handle == NULL) {
        result.dlclose_ok = 0;
    }
    result.cleanup_complete = instance == VK_NULL_HANDLE && !result.device_live &&
        !transfer_in_flight(&result) && (handle == NULL || result.dlclose_ok);
    end_time = monotonic_now();
    result.monotonic_end = end_time;
    result.clock_valid = result.clock_start_valid && end_time > 0U && end_time >= result.monotonic_start;
    result.timing_valid = result.timing_valid && result.clock_valid;
    if (!result.clock_valid) transfer_set_failure(&result, "clock", "monotonic end unavailable or reversed");
    if (!transfer_emit(&result, output, sizeof(output))) return 1;
    return result.native_exit;
}

int main(int argc, char **argv) {
    if (argc != 3 || argv == NULL || argv[1] == NULL || argv[2] == NULL ||
        strcmp(argv[1], "--run-id") != 0 || !valid_run_id(argv[2])) return transfer_invalid_invocation();
    return transfer_native_main(argv[2]);
}
#endif

#ifdef PANVK_TRANSFER_HOST_TEST
static int fake_case;
static int fake_create_called;
static int fake_destroy_called;
static int fake_submit_count;
static int fake_wait_count;
static uint32_t fake_selected_family;
static unsigned char fake_mapped[4096];
static int fake_mapped_valid;
#define MAX_FAKE_CALLS 96U
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
    fake_record_call("CREATE");
    if (info == NULL || device == NULL || info->queueCreateInfoCount != 1U ||
        info->pQueueCreateInfos == NULL || info->pQueueCreateInfos[0].queueCount != 1U ||
        info->pQueueCreateInfos[0].pQueuePriorities == NULL ||
        info->pQueueCreateInfos[0].pQueuePriorities[0] != 1.0F ||
        info->pEnabledFeatures != NULL || info->enabledLayerCount != 0U ||
        info->enabledExtensionCount != 0U || info->pNext != NULL) return VK_ERROR_INITIALIZATION_FAILED;
    if (fake_case == 1) { *device = (VkDevice)(uintptr_t)0x33U; return VK_ERROR_DEVICE_LOST; }
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
        *count = 2U;
        return;
    }
    fake_record_call("QF:fill");
    *count = 2U;
    memset(families, 0, sizeof(VkQueueFamilyProperties) * 2U);
    families[0].queueFlags = VK_QUEUE_GRAPHICS_BIT;
    families[0].queueCount = 1U;
    families[1].queueFlags = VK_QUEUE_TRANSFER_BIT;
    families[1].queueCount = 1U;
}

static void VKAPI_PTR fake_get_mem_props(VkPhysicalDevice physical,
                                         VkPhysicalDeviceMemoryProperties *props) {
    (void)physical;
    fake_record_call("MEMPROPS");
    memset(props, 0, sizeof(*props));
    if (fake_case == 12) {
        props->memoryTypeCount = 1U;
        props->memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
        return;
    }
    props->memoryTypeCount = 2U;
    if (fake_case == 16 || fake_case == 17) {
        props->memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
    } else {
        props->memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                              VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    }
    props->memoryTypes[1].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
}

static void VKAPI_PTR fake_get_queue(VkDevice device, uint32_t family, uint32_t queue,
                                     VkQueue *output) {
    (void)device; fake_selected_family = family;
    fake_record_call("GETQ");
    if (output != NULL) *output = (queue == 0U ? (VkQueue)(uintptr_t)0x44U : VK_NULL_HANDLE);
}

static VkResult VKAPI_PTR fake_create_buffer(VkDevice device, const VkBufferCreateInfo *info,
                                             const VkAllocationCallbacks *allocator,
                                             VkBuffer *buffer) {
    (void)device; (void)allocator;
    fake_record_call("BUF_CREATE");
    if (fake_case == 10) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    if (info == NULL || buffer == NULL || info->size != (VkDeviceSize)TRANSFER_SIZE ||
        info->usage != VK_BUFFER_USAGE_TRANSFER_DST_BIT ||
        info->sharingMode != VK_SHARING_MODE_EXCLUSIVE) return VK_ERROR_INITIALIZATION_FAILED;
    *buffer = (VkBuffer)(uintptr_t)0x51U;
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_destroy_buffer(VkDevice device, VkBuffer buffer,
                                          const VkAllocationCallbacks *allocator) {
    (void)device; (void)buffer; (void)allocator; fake_record_call("BUF_DESTROY");
}

static void VKAPI_PTR fake_get_mem_req(VkDevice device, VkBuffer buffer,
                                       VkMemoryRequirements *req) {
    (void)device; (void)buffer;
    fake_record_call("MEMREQ");
    memset(req, 0, sizeof(*req));
    if (fake_case == 11) {
        req->size = (VkDeviceSize)300000U;
        req->alignment = 256U;
        req->memoryTypeBits = 1U;
        return;
    }
    req->size = (VkDeviceSize)TRANSFER_SIZE;
    req->alignment = 64U;
    req->memoryTypeBits = 1U;
}

static VkResult VKAPI_PTR fake_alloc_memory(VkDevice device, const VkMemoryAllocateInfo *info,
                                            const VkAllocationCallbacks *allocator,
                                            VkDeviceMemory *memory) {
    (void)device; (void)allocator;
    fake_record_call("ALLOC");
    if (fake_case == 13) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    if (info == NULL || memory == NULL) return VK_ERROR_INITIALIZATION_FAILED;
    *memory = (VkDeviceMemory)(uintptr_t)0x52U;
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_free_memory(VkDevice device, VkDeviceMemory memory,
                                       const VkAllocationCallbacks *allocator) {
    (void)device; (void)memory; (void)allocator; fake_record_call("MEM_FREE");
}

static VkResult VKAPI_PTR fake_bind_memory(VkDevice device, VkBuffer buffer,
                                           VkDeviceMemory memory, VkDeviceSize offset) {
    (void)device; (void)buffer; (void)memory; (void)offset;
    fake_record_call("BIND");
    if (fake_case == 14) return VK_ERROR_MEMORY_MAP_FAILED;
    return VK_SUCCESS;
}

static VkResult VKAPI_PTR fake_map_memory(VkDevice device, VkDeviceMemory memory,
                                          VkDeviceSize offset, VkDeviceSize size,
                                          VkMemoryMapFlags flags, void **data) {
    (void)device; (void)memory; (void)offset; (void)size; (void)flags;
    fake_record_call("MAP");
    if (fake_case == 15) {
        if (data != NULL) *data = NULL;
        return VK_SUCCESS;
    }
    memset(fake_mapped, 0x00, sizeof(fake_mapped));
    fake_mapped_valid = 1;
    if (data != NULL) *data = fake_mapped;
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_unmap_memory(VkDevice device, VkDeviceMemory memory) {
    (void)device; (void)memory; fake_record_call("UNMAP");
}

static VkResult VKAPI_PTR fake_flush_ranges(VkDevice device, uint32_t count,
                                            const VkMappedMemoryRange *ranges) {
    (void)device; (void)count; (void)ranges;
    fake_record_call("FLUSH");
    if (fake_case == 16) return VK_ERROR_MEMORY_MAP_FAILED;
    return VK_SUCCESS;
}

static VkResult VKAPI_PTR fake_invalidate_ranges(VkDevice device, uint32_t count,
                                                 const VkMappedMemoryRange *ranges) {
    (void)device; (void)count; (void)ranges;
    fake_record_call("INVALIDATE");
    return VK_SUCCESS;
}

static VkResult VKAPI_PTR fake_create_pool(VkDevice device, const VkCommandPoolCreateInfo *info,
                                           const VkAllocationCallbacks *allocator,
                                           VkCommandPool *pool) {
    (void)device; (void)allocator;
    fake_record_call("POOL_CREATE");
    if (info == NULL || pool == NULL ||
        info->flags != VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    *pool = (VkCommandPool)(uintptr_t)0x53U;
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_destroy_pool(VkDevice device, VkCommandPool pool,
                                        const VkAllocationCallbacks *allocator) {
    (void)device; (void)pool; (void)allocator; fake_record_call("POOL_DESTROY");
}

static VkResult VKAPI_PTR fake_alloc_commands(VkDevice device,
                                              const VkCommandBufferAllocateInfo *info,
                                              VkCommandBuffer *buffers) {
    (void)device;
    fake_record_call("CMD_ALLOC");
    if (info == NULL || buffers == NULL || info->commandBufferCount != 1U ||
        info->level != VK_COMMAND_BUFFER_LEVEL_PRIMARY) return VK_ERROR_INITIALIZATION_FAILED;
    *buffers = (VkCommandBuffer)(uintptr_t)0x54U;
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_free_commands(VkDevice device, VkCommandPool pool,
                                         uint32_t count, const VkCommandBuffer *buffers) {
    (void)device; (void)pool; (void)count; (void)buffers; fake_record_call("CMD_FREE");
}

static VkResult VKAPI_PTR fake_begin_commands(VkCommandBuffer buffer,
                                              const VkCommandBufferBeginInfo *info) {
    (void)buffer; (void)info;
    fake_record_call("BEGIN");
    return VK_SUCCESS;
}

static VkResult VKAPI_PTR fake_end_commands(VkCommandBuffer buffer) {
    (void)buffer;
    fake_record_call("END");
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_cmd_fill(VkCommandBuffer buffer, VkBuffer dst, VkDeviceSize offset,
                                    VkDeviceSize size, uint32_t data) {
    (void)buffer; (void)dst; (void)offset; (void)size; (void)data;
    fake_record_call("FILL");
}

static void VKAPI_PTR fake_cmd_barrier(VkCommandBuffer buffer, VkPipelineStageFlags src,
                                       VkPipelineStageFlags dst, VkDependencyFlags dep,
                                       uint32_t mem_count, const VkMemoryBarrier *mem,
                                       uint32_t buf_count, const VkBufferMemoryBarrier *buf,
                                       uint32_t img_count, const VkImageMemoryBarrier *img) {
    (void)buffer; (void)src; (void)dst; (void)dep;
    (void)mem_count; (void)mem; (void)buf_count; (void)buf; (void)img_count; (void)img;
    fake_record_call("BARRIER");
}

static VkResult VKAPI_PTR fake_create_fence(VkDevice device, const VkFenceCreateInfo *info,
                                            const VkAllocationCallbacks *allocator,
                                            VkFence *fence) {
    (void)device; (void)allocator;
    fake_record_call("FENCE_CREATE");
    if (info == NULL || fence == NULL || info->flags != 0U) return VK_ERROR_INITIALIZATION_FAILED;
    *fence = (VkFence)(uintptr_t)0x55U;
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_destroy_fence(VkDevice device, VkFence fence,
                                         const VkAllocationCallbacks *allocator) {
    (void)device; (void)fence; (void)allocator; fake_record_call("FENCE_DESTROY");
}

static VkResult VKAPI_PTR fake_wait_fences(VkDevice device, uint32_t count,
                                           const VkFence *fences, VkBool32 wait_all,
                                           uint64_t timeout) {
    (void)device; (void)count; (void)fences; (void)wait_all; (void)timeout;
    fake_record_call("WAIT");
    ++fake_wait_count;
    if (fake_case == 18) return VK_TIMEOUT;
    return VK_SUCCESS;
}

static VkResult VKAPI_PTR fake_queue_submit(VkQueue queue, uint32_t count,
                                            const VkSubmitInfo *infos, VkFence fence) {
    uint32_t index;
    (void)queue; (void)fence;
    fake_record_call("SUBMIT");
    ++fake_submit_count;
    if (infos == NULL || count != 1U || infos->commandBufferCount != 1U ||
        infos->waitSemaphoreCount != 0U || infos->signalSemaphoreCount != 0U) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    if (fake_mapped_valid) {
        uint32_t *words = (uint32_t *)fake_mapped;
        for (index = 0U; index < TRANSFER_DWORDS; ++index) words[index] = TRANSFER_PATTERN;
        if (fake_case == 19) words[7] = UINT32_C(0xdeadbeef);
    }
    return VK_SUCCESS;
}

static PFN_vkVoidFunction VKAPI_PTR fake_gdpa(VkDevice device, const char *name) {
    char label[64];
    (void)device;
    if (name == NULL) return NULL;
    (void)copy_bounded(label, sizeof(label), "D:", 63U);
    (void)copy_bounded(label + 2U, sizeof(label) - 2U, name, 61U);
    label[sizeof(label) - 1U] = '\0';
    fake_record_call(label);
    if (fake_case == 20) {
        if (strcmp(name, "vkCreateBuffer") == 0) return NULL;
    }
    if (strcmp(name, "vkDestroyDevice") == 0) return (PFN_vkVoidFunction)fake_destroy_device;
    if (strcmp(name, "vkGetDeviceQueue") == 0) return (PFN_vkVoidFunction)fake_get_queue;
    if (strcmp(name, "vkCreateBuffer") == 0) return (PFN_vkVoidFunction)fake_create_buffer;
    if (strcmp(name, "vkDestroyBuffer") == 0) return (PFN_vkVoidFunction)fake_destroy_buffer;
    if (strcmp(name, "vkGetBufferMemoryRequirements") == 0) return (PFN_vkVoidFunction)fake_get_mem_req;
    if (strcmp(name, "vkAllocateMemory") == 0) return (PFN_vkVoidFunction)fake_alloc_memory;
    if (strcmp(name, "vkFreeMemory") == 0) return (PFN_vkVoidFunction)fake_free_memory;
    if (strcmp(name, "vkBindBufferMemory") == 0) return (PFN_vkVoidFunction)fake_bind_memory;
    if (strcmp(name, "vkMapMemory") == 0) return (PFN_vkVoidFunction)fake_map_memory;
    if (strcmp(name, "vkUnmapMemory") == 0) return (PFN_vkVoidFunction)fake_unmap_memory;
    if (strcmp(name, "vkFlushMappedMemoryRanges") == 0) return (PFN_vkVoidFunction)fake_flush_ranges;
    if (strcmp(name, "vkInvalidateMappedMemoryRanges") == 0) return (PFN_vkVoidFunction)fake_invalidate_ranges;
    if (strcmp(name, "vkCreateCommandPool") == 0) return (PFN_vkVoidFunction)fake_create_pool;
    if (strcmp(name, "vkDestroyCommandPool") == 0) return (PFN_vkVoidFunction)fake_destroy_pool;
    if (strcmp(name, "vkAllocateCommandBuffers") == 0) return (PFN_vkVoidFunction)fake_alloc_commands;
    if (strcmp(name, "vkFreeCommandBuffers") == 0) return (PFN_vkVoidFunction)fake_free_commands;
    if (strcmp(name, "vkBeginCommandBuffer") == 0) return (PFN_vkVoidFunction)fake_begin_commands;
    if (strcmp(name, "vkEndCommandBuffer") == 0) return (PFN_vkVoidFunction)fake_end_commands;
    if (strcmp(name, "vkCmdFillBuffer") == 0) return (PFN_vkVoidFunction)fake_cmd_fill;
    if (strcmp(name, "vkCmdPipelineBarrier") == 0) return (PFN_vkVoidFunction)fake_cmd_barrier;
    if (strcmp(name, "vkCreateFence") == 0) return (PFN_vkVoidFunction)fake_create_fence;
    if (strcmp(name, "vkDestroyFence") == 0) return (PFN_vkVoidFunction)fake_destroy_fence;
    if (strcmp(name, "vkWaitForFences") == 0) return (PFN_vkVoidFunction)fake_wait_fences;
    if (strcmp(name, "vkQueueSubmit") == 0) return (PFN_vkVoidFunction)fake_queue_submit;
    return NULL;
}

static PFN_vkVoidFunction VKAPI_PTR fake_gipa(VkInstance instance, const char *name) {
    char label[64];
    (void)instance;
    if (name == NULL) return NULL;
    (void)copy_bounded(label, sizeof(label), "I:", 63U);
    (void)copy_bounded(label + 2U, sizeof(label) - 2U, name, 61U);
    label[sizeof(label) - 1U] = '\0';
    fake_record_call(label);
    if (strcmp(name, "vkGetPhysicalDeviceQueueFamilyProperties") == 0) return (PFN_vkVoidFunction)fake_get_families;
    if (strcmp(name, "vkGetPhysicalDeviceMemoryProperties") == 0) return (PFN_vkVoidFunction)fake_get_mem_props;
    if (strcmp(name, "vkCreateDevice") == 0) return (PFN_vkVoidFunction)fake_create_device;
    if (strcmp(name, "vkDestroyDevice") == 0) return (PFN_vkVoidFunction)fake_destroy_device;
    if (strcmp(name, "vkGetDeviceProcAddr") == 0) return (PFN_vkVoidFunction)fake_gdpa;
    return NULL;
}

int panvk_transfer_test_fake(const char *case_name, char *output, size_t capacity) {
    TransferResult result;
    const char *owner = "/fake/icd/libvulkan_panfrost.so";
    if (case_name == NULL || output == NULL) return 0;
    fake_case = 0;
    if (strcmp(case_name, "pass") == 0) fake_case = 0;
    else if (strcmp(case_name, "pass-nocoherent") == 0) fake_case = 17;
    else if (strcmp(case_name, "create-error") == 0) fake_case = 1;
    else if (strcmp(case_name, "buffer-error") == 0) fake_case = 10;
    else if (strcmp(case_name, "requirements-cap") == 0) fake_case = 11;
    else if (strcmp(case_name, "no-memory-type") == 0) fake_case = 12;
    else if (strcmp(case_name, "alloc-error") == 0) fake_case = 13;
    else if (strcmp(case_name, "bind-error") == 0) fake_case = 14;
    else if (strcmp(case_name, "map-null") == 0) fake_case = 15;
    else if (strcmp(case_name, "flush-error") == 0) fake_case = 16;
    else if (strcmp(case_name, "fence-timeout") == 0) fake_case = 18;
    else if (strcmp(case_name, "readback-mismatch") == 0) fake_case = 19;
    else if (strcmp(case_name, "owner") == 0) fake_case = 20;
    else if (strcmp(case_name, "clock-after-create") == 0) fake_case = 21;
    else return 0;
    fake_owner_mode = 1;
    fake_clock_fail_after_create = fake_case == 21;
    fake_create_called = 0; fake_destroy_called = 0;
    fake_submit_count = 0; fake_wait_count = 0;
    fake_mapped_valid = 0;
    memset(fake_mapped, 0, sizeof(fake_mapped));
    fake_call_count = 0U; memset(fake_calls, 0, sizeof(fake_calls));
    transfer_init_result(&result, "PVK-0123456789abcdef0123456789abcdef");
    result.scope_valid = 1; result.binding_valid = 1; result.identity_stable = 1;
    result.load_loaded = 1; result.interface_supported = 1; result.instance_created = 1;
    result.clock_start_valid = 1; result.clock_valid = 1; result.timing_valid = 1;
    result.physical_complete = 1; result.physical_returned_count = 1;
    result.physical_target_exact = 1; result.physical_properties_valid = 1;
    result.physical_vendor_id = EXPECTED_VENDOR; result.physical_device_id = EXPECTED_DEVICE;
    (void)copy_bounded(result.physical_name, sizeof(result.physical_name), EXPECTED_NAME, sizeof(EXPECTED_NAME));
    transfer_control(&result, fake_gipa, (VkInstance)(uintptr_t)1U,
                     (VkPhysicalDevice)(uintptr_t)2U, owner);
    if (!transfer_in_flight(&result)) {
        result.instance_destroy_ok = 1; result.dlclose_ok = 1;
        result.cleanup_complete = !result.device_live;
    } else {
        result.cleanup_complete = 0;
    }
    result.after_hash_valid = result.before_hash_valid = result.identity_stable;
    if (fake_case == 1) {
        result.failure_stage = "vulkan"; result.vk_error = 1; result.status = "VK_ERROR";
    }
    transfer_classify(&result);
    return transfer_encode_result(&result, output, capacity);
}

int panvk_transfer_test_submit_counts(int *submit, int *wait) {
    if (submit != NULL) *submit = fake_submit_count;
    if (wait != NULL) *wait = fake_wait_count;
    return fake_submit_count == 1 && fake_wait_count == 1;
}

int panvk_transfer_test_call_sequence(char *output, size_t capacity) {
    size_t index;
    size_t used = 0U;
    for (index = 0U; index < fake_call_count; ++index) {
        size_t length = strlen(fake_calls[index]);
        if (used + length + 2U > capacity) return 0;
        memcpy(output + used, fake_calls[index], length);
        used += length;
        output[used++] = (index + 1U < fake_call_count) ? ',' : '\0';
        if (index + 1U < fake_call_count) output[used - 1U] = ',';
        else output[used - 1U] = '\0';
    }
    if (fake_call_count == 0U && capacity > 0U) output[0] = '\0';
    return 1;
}

int panvk_transfer_test_encode_oversized_fallback(char *output, size_t capacity) {
    TransferResult result;
    size_t index;
    transfer_init_result(&result, "PVK-0123456789abcdef0123456789abcdef");
    result.scope_valid = 1; result.binding_valid = 1; result.identity_stable = 1;
    result.load_loaded = 1; result.load_attempted = 1;
    result.status = "TRANSFER_READBACK_OBSERVED";
    result.failure_stage = "none";
    memset(result.cwd, 'x', sizeof(result.cwd) - 1U);
    result.cwd[sizeof(result.cwd) - 1U] = '\0';
    memset(result.icd_path, 'y', sizeof(result.icd_path) - 1U);
    result.icd_path[sizeof(result.icd_path) - 1U] = '\0';
    memset(result.failure_detail, 'z', sizeof(result.failure_detail) - 1U);
    result.failure_detail[sizeof(result.failure_detail) - 1U] = '\0';
    for (index = 0U; index < 3U; ++index) {
        memset(result.exports[index].owner, 'o', sizeof(result.exports[index].owner) - 1U);
        result.exports[index].owner[sizeof(result.exports[index].owner) - 1U] = '\0';
        memset(result.exports[index].error, 'e', sizeof(result.exports[index].error) - 1U);
        result.exports[index].error[sizeof(result.exports[index].error) - 1U] = '\0';
    }
    for (index = 0U; index < MAX_FUNCTIONS; ++index) {
        memset(result.functions[index].owner, 'f', sizeof(result.functions[index].owner) - 1U);
        result.functions[index].owner[sizeof(result.functions[index].owner) - 1U] = '\0';
        memset(result.functions[index].error, 'g', sizeof(result.functions[index].error) - 1U);
        result.functions[index].error[sizeof(result.functions[index].error) - 1U] = '\0';
    }
    transfer_classify(&result);
    if (transfer_encode_result(&result, output, capacity)) return 0;
    return transfer_encode_unknown_fallback(&result, output, capacity);
}
#endif
