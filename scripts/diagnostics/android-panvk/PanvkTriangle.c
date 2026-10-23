#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

/*
 * PanVK gate C: offscreen triangle.  The private loader helper is included
 * verbatim: it owns the immutable scope, file-identity, SHA-256, JSON
 * escaping and dladdr primitives.  This translation unit adds the Gate A
 * one-device/one-queue lifecycle (byte-faithful in behaviour) followed by
 * exactly one bounded 64x64 offscreen triangle: image, render pass,
 * framebuffer, two SPIR-V shader modules, graphics pipeline, draw, copy to
 * a host-visible staging buffer, one fenced submit and readback.  No
 * surface, swapchain, presentation, idle wait, reset, retry, fallback,
 * second candidate, feature change, process tracing, handlers, node mapping,
 * permission is present here.
 */
#define main panvk_triangle_legacy_loader_main
#define set_failure panvk_triangle_loader_set_failure
#define failure_is panvk_triangle_loader_failure_is
#define observe_exports panvk_triangle_loader_observe_exports
#include "PanvkPrivateLoader.c"
#undef main
#undef set_failure
#undef failure_is
#undef observe_exports

#include "/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include/vulkan/vulkan_core.h"

#include <stddef.h>

#include "triangle_spirv.h"

#define TRIANGLE_SCHEMA "wowforever.android-panvk-triangle"
#define TRIANGLE_OUTPUT_LIMIT (64U * 1024U)
#define MAX_PHYSICAL_DEVICES 8U
#define MAX_QUEUE_FAMILIES 16U
#define MAX_FUNCTIONS 51U
#define EXPECTED_VENDOR UINT32_C(0x13b5)
#define EXPECTED_DEVICE UINT32_C(0xa8670000)
#define EXPECTED_NAME "Mali-G610 MC6"
#define TRIANGLE_EXTENT UINT32_C(64)
#define TRIANGLE_PIXELS UINT32_C(4096)
#define TRIANGLE_STAGING_SIZE UINT64_C(16384)
#define TRIANGLE_STAGING_SIZE_U32 UINT32_C(16384)
#define TRIANGLE_CAPACITY UINT64_C(16777216)
#define TRIANGLE_TIMEOUT_NS UINT64_C(1000000000)
#define TRIANGLE_POOL_FLAGS UINT32_C(2)
#define TRIANGLE_FORMAT_VALUE UINT32_C(37)
#define TRIANGLE_USAGE_VALUE UINT32_C(0x21)

typedef PFN_vkVoidFunction (VKAPI_PTR *TriangleGetInstanceProcAddr)(VkInstance, const char *);
typedef PFN_vkVoidFunction (VKAPI_PTR *TriangleGetDeviceProcAddr)(VkDevice, const char *);
typedef VkResult (VKAPI_PTR *TriangleNegotiate)(uint32_t *);

typedef struct TriangleFunction {
    const char *name;
    PFN_vkVoidFunction address;
    char owner[PATH_LIMIT];
    int resolved;
    int owner_exact;
    char error[ERROR_LIMIT + 1U];
} TriangleFunction;

typedef struct TriangleFamilyObservation {
    uint32_t index;
    VkQueueFlags flags;
    uint32_t queue_count;
} TriangleFamilyObservation;

typedef struct TriangleResult {
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
    TriangleFunction functions[MAX_FUNCTIONS];

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
    TriangleFamilyObservation queue_families[MAX_QUEUE_FAMILIES];
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

    int img_create_attempted;
    int img_created;
    int img_handle_on_error;
    VkResult img_result;
    uint32_t img_format;
    uint32_t img_extent_w;
    uint32_t img_extent_h;
    uint32_t img_usage;
    uint32_t img_tiling;
    uint32_t img_samples;
    uint32_t img_mip_levels;
    uint32_t img_array_layers;
    uint32_t img_sharing;

    int img_req_queried;
    uint64_t img_req_size;
    uint64_t img_req_align;
    uint32_t img_req_bits;

    int img_mem_selected;
    uint32_t img_mem_index;
    uint32_t img_mem_flags;
    int img_mem_device_local;

    int img_alloc_attempted;
    int img_alloc_done;
    int img_alloc_handle_on_error;
    VkResult img_alloc_result;
    uint64_t img_alloc_requested;
    uint64_t img_alloc_capacity;

    int img_bind_attempted;
    int img_bind_ok;

    int view_create_attempted;
    int view_created;
    int view_handle_on_error;
    VkResult view_result;

    int rp_create_attempted;
    int rp_created;
    int rp_handle_on_error;
    VkResult rp_result;

    int fb_create_attempted;
    int fb_created;
    int fb_handle_on_error;
    VkResult fb_result;

    uint32_t vert_bytes;
    uint32_t frag_bytes;
    int vert_create_attempted;
    int vert_created;
    VkResult vert_result;
    int frag_create_attempted;
    int frag_created;
    VkResult frag_result;

    int layout_create_attempted;
    int layout_created;
    VkResult layout_result;

    int pipe_create_attempted;
    int pipe_created;
    int pipe_handle_on_error;
    VkResult pipe_result;

    int stg_create_attempted;
    int stg_created;
    int stg_handle_on_error;
    VkResult stg_result;
    uint32_t stg_size;
    uint32_t stg_usage;

    int stg_req_queried;
    uint64_t stg_req_size;
    uint64_t stg_req_align;
    uint32_t stg_req_bits;

    int stg_mem_selected;
    uint32_t stg_mem_index;
    uint32_t stg_mem_flags;
    int stg_mem_visible;
    int stg_mem_coherent;

    int stg_alloc_attempted;
    int stg_alloc_done;
    VkResult stg_alloc_result;
    uint64_t stg_alloc_requested;
    uint64_t stg_alloc_capacity;

    int stg_bind_attempted;
    int stg_bind_ok;

    int stg_map_attempted;
    int stg_map_done;
    int stg_map_non_null;

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
    int draw_attempted;
    int draw_ok;
    uint32_t draw_count;
    int copy_attempted;
    int copy_ok;
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
    uint32_t read_bytes;
    uint32_t read_pixels;
    uint32_t read_clear;
    uint32_t read_triangle;
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
} TriangleResult;

#ifdef PANVK_TRIANGLE_HOST_TEST
static int fake_owner_mode;
static int fake_clock_fail_after_create;
#endif

static const char *const triangle_function_names[MAX_FUNCTIONS] = {
    "vkEnumerateInstanceVersion", "vkCreateInstance", "vkDestroyInstance",
    "vkEnumeratePhysicalDevices", "vkGetPhysicalDeviceProperties",
    "vkGetPhysicalDeviceQueueFamilyProperties", "vkGetPhysicalDeviceMemoryProperties",
    "vkCreateDevice", "vkDestroyDevice", "vkGetDeviceProcAddr", "vkGetDeviceQueue",
    "vkCreateImage", "vkGetImageMemoryRequirements",
    "vkAllocateMemory", "vkFreeMemory", "vkBindImageMemory",
    "vkCreateImageView", "vkDestroyImageView", "vkDestroyImage",
    "vkCreateRenderPass", "vkDestroyRenderPass",
    "vkCreateFramebuffer", "vkDestroyFramebuffer",
    "vkCreateShaderModule", "vkDestroyShaderModule",
    "vkCreatePipelineLayout", "vkDestroyPipelineLayout",
    "vkCreateGraphicsPipelines", "vkDestroyPipeline",
    "vkCreateBuffer", "vkDestroyBuffer", "vkGetBufferMemoryRequirements",
    "vkBindBufferMemory", "vkMapMemory", "vkUnmapMemory",
    "vkInvalidateMappedMemoryRanges",
    "vkCreateCommandPool", "vkDestroyCommandPool", "vkAllocateCommandBuffers",
    "vkFreeCommandBuffers", "vkBeginCommandBuffer", "vkEndCommandBuffer",
    "vkCmdBeginRenderPass", "vkCmdBindPipeline", "vkCmdDraw",
    "vkCmdEndRenderPass", "vkCmdCopyImageToBuffer",
    "vkCreateFence", "vkDestroyFence", "vkWaitForFences", "vkQueueSubmit"
};

static void triangle_set_failure(TriangleResult *result, const char *stage,
                                 const char *detail) {
    if (result->failure_stage == NULL || strcmp(result->failure_stage, "none") == 0) {
        result->failure_stage = stage;
        copy_error(result->failure_detail, detail);
    }
}

static int triangle_failure_is(const TriangleResult *result, const char *stage) {
    return result != NULL && result->failure_stage != NULL && stage != NULL &&
           strcmp(result->failure_stage, stage) == 0;
}

static void triangle_record_result(TriangleResult *result, VkResult value,
                                   const char *detail) {
    if (value < 0) {
        result->vk_error = 1;
        triangle_set_failure(result, "vulkan", detail);
    } else if (value != VK_SUCCESS) {
        triangle_set_failure(result, "result", detail);
    }
}

static int triangle_call_begin(TriangleResult *result, uint64_t *before) {
#ifdef PANVK_TRIANGLE_HOST_TEST
    if (fake_clock_fail_after_create && result->device_live) {
        result->timing_valid = 0;
        triangle_set_failure(result, "clock", "fake monotonic failure after device creation");
        return 0;
    }
#endif
    uint64_t value = monotonic_now();
    if (before == NULL || value == 0U ||
        (result->last_call_ns != 0U && value < result->last_call_ns)) {
        result->timing_valid = 0;
        triangle_set_failure(result, "clock", "monotonic check before Vulkan call failed");
        return 0;
    }
    *before = value;
    result->last_call_ns = value;
    ++result->call_count;
    ++result->monotonic_checks;
    return 1;
}

static int triangle_call_end(TriangleResult *result, uint64_t before) {
    uint64_t value = monotonic_now();
    if (value == 0U || value < before ||
        (result->last_call_ns != 0U && value < result->last_call_ns)) {
        result->timing_valid = 0;
        triangle_set_failure(result, "clock", "monotonic check after Vulkan call failed");
        return 0;
    }
    result->last_call_ns = value;
    ++result->monotonic_checks;
    return 1;
}

static int triangle_observe_function(TriangleFunction *observation,
                                     PFN_vkVoidFunction address,
                                     const char *owner) {
    observation->address = address;
    observation->resolved = address != NULL;
    observation->owner_exact = 0;
    if (!observation->resolved) {
        copy_error(observation->error, "function not returned");
        return 0;
    }
#ifdef PANVK_TRIANGLE_HOST_TEST
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

static MAYBE_UNUSED void triangle_observe_exports(TriangleResult *result, void *handle,
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

static int triangle_resolve_instance(TriangleResult *result,
                                     TriangleGetInstanceProcAddr gipa,
                                     VkInstance instance, const char *owner,
                                     size_t index, const char *name) {
    PFN_vkVoidFunction address;
    if (gipa == NULL || index >= MAX_FUNCTIONS) return 0;
    address = gipa(instance, name);
    return triangle_observe_function(&result->functions[index], address, owner);
}

static int triangle_select_queue(const VkQueueFamilyProperties *families,
                                 uint32_t count, uint32_t *selected,
                                 const char **kind) {
    uint32_t index;
    if (families == NULL || selected == NULL || kind == NULL || count > MAX_QUEUE_FAMILIES)
        return 0;
    for (index = 0U; index < count; ++index) {
        if (families[index].queueCount == 0U) continue;
        if ((families[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) == 0U) continue;
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

static uint32_t triangle_select_staging_memory_index(const VkPhysicalDeviceMemoryProperties *props,
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

static uint32_t triangle_select_image_memory_index(const VkPhysicalDeviceMemoryProperties *props,
                                                     uint32_t allowed) {
    uint32_t index;
    if (props == NULL) return UINT32_MAX;
    for (index = 0U; index < props->memoryTypeCount; ++index) {
        if ((allowed & (1U << index)) == 0U) continue;
        if (props->memoryTypes[index].propertyFlags == VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
            return index;
    }
    for (index = 0U; index < props->memoryTypeCount; ++index) {
        if ((allowed & (1U << index)) == 0U) continue;
        if ((props->memoryTypes[index].propertyFlags &
             VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0U)
            return index;
    }
    for (index = 0U; index < props->memoryTypeCount; ++index) {
        if ((allowed & (1U << index)) == 0U) continue;
        return index;
    }
    return UINT32_MAX;
}

static void triangle_fnv1a(const unsigned char *data, size_t length, char out[17]) {
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

static void triangle_init_result(TriangleResult *result, const char *run_id) {
    size_t index;
    memset(result, 0, sizeof(*result));
    (void)copy_bounded(result->run_id, sizeof(result->run_id), run_id, RUN_LENGTH + 1U);
    result->pid = (int)getpid();
    result->uid = (int)getuid();
    result->pgid = (int)getpgid(0);
    result->sid = (int)getsid(0);
    result->failure_stage = "none";
    result->timing_valid = 1;
    result->read_hash[0] = '\0';
    result->img_alloc_capacity = TRIANGLE_CAPACITY;
    result->stg_alloc_capacity = TRIANGLE_CAPACITY;
    result->wait_timeout = 0U;
    result->enumerate_instance_version_result = VK_SUCCESS;
    result->instance_create_result = VK_ERROR_INITIALIZATION_FAILED;
    result->physical_count_result = VK_ERROR_INITIALIZATION_FAILED;
    result->physical_fill_result = VK_ERROR_INITIALIZATION_FAILED;
    result->device_create_result = VK_ERROR_INITIALIZATION_FAILED;
    result->img_result = VK_ERROR_INITIALIZATION_FAILED;
    result->img_alloc_result = VK_ERROR_INITIALIZATION_FAILED;
    result->view_result = VK_ERROR_INITIALIZATION_FAILED;
    result->rp_result = VK_ERROR_INITIALIZATION_FAILED;
    result->fb_result = VK_ERROR_INITIALIZATION_FAILED;
    result->vert_result = VK_ERROR_INITIALIZATION_FAILED;
    result->frag_result = VK_ERROR_INITIALIZATION_FAILED;
    result->layout_result = VK_ERROR_INITIALIZATION_FAILED;
    result->pipe_result = VK_ERROR_INITIALIZATION_FAILED;
    result->stg_result = VK_ERROR_INITIALIZATION_FAILED;
    result->stg_alloc_result = VK_ERROR_INITIALIZATION_FAILED;
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
        result->functions[index].name = triangle_function_names[index];
}

static int triangle_json_function(Json *json, const TriangleFunction *function) {
    return json_append(json, "{\"name\":") && json_string(json, function->name, PATH_LIMIT) &&
           json_append(json, ",\"resolved\":") && json_bool(json, function->resolved) &&
           json_append(json, ",\"owner\":") && json_string(json, function->owner, PATH_LIMIT) &&
           json_append(json, ",\"ownerExact\":") && json_bool(json, function->owner_exact) &&
           json_append(json, ",\"error\":") && json_string(json, function->error, ERROR_LIMIT) &&
           json_append(json, "}");
}

static int triangle_json_queues(Json *json, const TriangleResult *result) {
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

static int triangle_json_triangle(Json *json, const TriangleResult *result) {
    if (!json_append(json, "{\"image\":{\"createAttempted\":")) return 0;
    if (!json_bool(json, result->img_create_attempted)) return 0;
    if (!json_append(json, ",\"created\":")) return 0;
    if (!json_bool(json, result->img_created)) return 0;
    if (!json_append(json, ",\"handleReturnedOnError\":")) return 0;
    if (!json_bool(json, result->img_handle_on_error)) return 0;
    if (!json_append(json, ",\"result\":%d,\"format\":%u,\"extentWidth\":%u,\"extentHeight\":%u,\"usage\":%u,\"tiling\":%u,\"samples\":%u,\"mipLevels\":%u,\"arrayLayers\":%u,\"sharingMode\":%u},\"imageMemory\":{\"requirementsQueried\":",
                     result->img_result, result->img_format, result->img_extent_w,
                     result->img_extent_h, result->img_usage, result->img_tiling,
                     result->img_samples, result->img_mip_levels,
                     result->img_array_layers, result->img_sharing)) return 0;
    if (!json_bool(json, result->img_req_queried)) return 0;
    if (!json_append(json, ",\"requirementsSize\":%" PRIu64 ",\"alignment\":%" PRIu64 ",\"memoryTypeBits\":%u,\"typeSelected\":",
                     result->img_req_size, result->img_req_align, result->img_req_bits)) return 0;
    if (!json_bool(json, result->img_mem_selected)) return 0;
    if (!json_append(json, ",\"typeIndex\":%u,\"propertyFlags\":%u,\"deviceLocal\":",
                     result->img_mem_index, result->img_mem_flags)) return 0;
    if (!json_bool(json, result->img_mem_device_local)) return 0;
    if (!json_append(json, ",\"allocateAttempted\":")) return 0;
    if (!json_bool(json, result->img_alloc_attempted)) return 0;
    if (!json_append(json, ",\"allocated\":")) return 0;
    if (!json_bool(json, result->img_alloc_done)) return 0;
    if (!json_append(json, ",\"handleReturnedOnError\":")) return 0;
    if (!json_bool(json, result->img_alloc_handle_on_error)) return 0;
    if (!json_append(json, ",\"allocateResult\":%d,\"requestedBytes\":%" PRIu64 ",\"capacityBytes\":%" PRIu64 "},\"bind\":{\"attempted\":",
                     result->img_alloc_result, result->img_alloc_requested,
                     result->img_alloc_capacity)) return 0;
    if (!json_bool(json, result->img_bind_attempted)) return 0;
    if (!json_append(json, ",\"ok\":")) return 0;
    if (!json_bool(json, result->img_bind_ok)) return 0;
    if (!json_append(json, "},\"view\":{\"createAttempted\":")) return 0;
    if (!json_bool(json, result->view_create_attempted)) return 0;
    if (!json_append(json, ",\"created\":")) return 0;
    if (!json_bool(json, result->view_created)) return 0;
    if (!json_append(json, ",\"handleReturnedOnError\":")) return 0;
    if (!json_bool(json, result->view_handle_on_error)) return 0;
    if (!json_append(json, ",\"result\":%d},\"renderPass\":{\"createAttempted\":",
                     result->view_result)) return 0;
    if (!json_bool(json, result->rp_create_attempted)) return 0;
    if (!json_append(json, ",\"created\":")) return 0;
    if (!json_bool(json, result->rp_created)) return 0;
    if (!json_append(json, ",\"handleReturnedOnError\":")) return 0;
    if (!json_bool(json, result->rp_handle_on_error)) return 0;
    if (!json_append(json, ",\"result\":%d},\"framebuffer\":{\"createAttempted\":",
                     result->rp_result)) return 0;
    if (!json_bool(json, result->fb_create_attempted)) return 0;
    if (!json_append(json, ",\"created\":")) return 0;
    if (!json_bool(json, result->fb_created)) return 0;
    if (!json_append(json, ",\"handleReturnedOnError\":")) return 0;
    if (!json_bool(json, result->fb_handle_on_error)) return 0;
    if (!json_append(json, ",\"result\":%d},\"shaders\":{\"vertBytes\":%u,\"fragBytes\":%u,\"vertCreateAttempted\":",
                     result->fb_result, result->vert_bytes, result->frag_bytes)) return 0;
    if (!json_bool(json, result->vert_create_attempted)) return 0;
    if (!json_append(json, ",\"vertCreated\":")) return 0;
    if (!json_bool(json, result->vert_created)) return 0;
    if (!json_append(json, ",\"vertResult\":%d,\"fragCreateAttempted\":",
                     result->vert_result)) return 0;
    if (!json_bool(json, result->frag_create_attempted)) return 0;
    if (!json_append(json, ",\"fragCreated\":")) return 0;
    if (!json_bool(json, result->frag_created)) return 0;
    if (!json_append(json, ",\"fragResult\":%d},\"pipelineLayout\":{\"createAttempted\":",
                     result->frag_result)) return 0;
    if (!json_bool(json, result->layout_create_attempted)) return 0;
    if (!json_append(json, ",\"created\":")) return 0;
    if (!json_bool(json, result->layout_created)) return 0;
    if (!json_append(json, ",\"result\":%d},\"pipeline\":{\"createAttempted\":",
                     result->layout_result)) return 0;
    if (!json_bool(json, result->pipe_create_attempted)) return 0;
    if (!json_append(json, ",\"created\":")) return 0;
    if (!json_bool(json, result->pipe_created)) return 0;
    if (!json_append(json, ",\"handleReturnedOnError\":")) return 0;
    if (!json_bool(json, result->pipe_handle_on_error)) return 0;
    if (!json_append(json, ",\"result\":%d},\"staging\":{\"bufferCreateAttempted\":",
                     result->pipe_result)) return 0;
    if (!json_bool(json, result->stg_create_attempted)) return 0;
    if (!json_append(json, ",\"bufferCreated\":")) return 0;
    if (!json_bool(json, result->stg_created)) return 0;
    if (!json_append(json, ",\"handleReturnedOnError\":")) return 0;
    if (!json_bool(json, result->stg_handle_on_error)) return 0;
    if (!json_append(json, ",\"bufferResult\":%d,\"bufferSize\":%u,\"bufferUsage\":%u,\"memoryQueried\":",
                     result->stg_result, result->stg_size, result->stg_usage)) return 0;
    if (!json_bool(json, result->stg_req_queried)) return 0;
    if (!json_append(json, ",\"memorySize\":%" PRIu64 ",\"alignment\":%" PRIu64 ",\"memoryTypeBits\":%u,\"memoryTypeSelected\":",
                     result->stg_req_size, result->stg_req_align, result->stg_req_bits)) return 0;
    if (!json_bool(json, result->stg_mem_selected)) return 0;
    if (!json_append(json, ",\"typeIndex\":%u,\"hostVisible\":",
                     result->stg_mem_index)) return 0;
    if (!json_bool(json, result->stg_mem_visible)) return 0;
    if (!json_append(json, ",\"hostCoherent\":")) return 0;
    if (!json_bool(json, result->stg_mem_coherent)) return 0;
    if (!json_append(json, ",\"allocateAttempted\":")) return 0;
    if (!json_bool(json, result->stg_alloc_attempted)) return 0;
    if (!json_append(json, ",\"allocated\":")) return 0;
    if (!json_bool(json, result->stg_alloc_done)) return 0;
    if (!json_append(json, ",\"allocateResult\":%d,\"requestedBytes\":%" PRIu64 ",\"capacityBytes\":%" PRIu64 ",\"bindAttempted\":",
                     result->stg_alloc_result, result->stg_alloc_requested,
                     result->stg_alloc_capacity)) return 0;
    if (!json_bool(json, result->stg_bind_attempted)) return 0;
    if (!json_append(json, ",\"bindOk\":")) return 0;
    if (!json_bool(json, result->stg_bind_ok)) return 0;
    if (!json_append(json, ",\"mapAttempted\":")) return 0;
    if (!json_bool(json, result->stg_map_attempted)) return 0;
    if (!json_append(json, ",\"mapped\":")) return 0;
    if (!json_bool(json, result->stg_map_done)) return 0;
    if (!json_append(json, ",\"nonNull\":")) return 0;
    if (!json_bool(json, result->stg_map_non_null)) return 0;
    if (!json_append(json, "},\"commandPool\":{\"createAttempted\":")) return 0;
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
    if (!json_append(json, ",\"beginResult\":%d,\"drawAttempted\":",
                     result->begin_result)) return 0;
    if (!json_bool(json, result->draw_attempted)) return 0;
    if (!json_append(json, ",\"drawOk\":")) return 0;
    if (!json_bool(json, result->draw_ok)) return 0;
    if (!json_append(json, ",\"drawVertexCount\":%u,\"copyAttempted\":",
                     result->draw_count)) return 0;
    if (!json_bool(json, result->copy_attempted)) return 0;
    if (!json_append(json, ",\"copyOk\":")) return 0;
    if (!json_bool(json, result->copy_ok)) return 0;
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
    if (!json_append(json, ",\"result\":%d,\"submitCount\":%d,\"waitAttempted\":",
                     result->submit_result, result->submit_count)) return 0;
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
    if (!json_append(json, ",\"byteSize\":%u,\"pixelCount\":%u,\"clearPixels\":%u,\"trianglePixels\":%u,\"hashHex\":",
                     result->read_bytes, result->read_pixels,
                     result->read_clear, result->read_triangle)) return 0;
    if (!json_string(json, result->read_verified ? result->read_hash : "", 16U)) return 0;
    return json_append(json, "}}");
}

static int triangle_encode_result(const TriangleResult *result, char *output,
                                  size_t capacity) {
    Json json = {output, capacity, 0U, 1};
    size_t index;
    if (result == NULL || output == NULL || capacity == 0U) return 0;
    if (!json_append(&json, "{\"schema\":") ||
        !json_string(&json, TRIANGLE_SCHEMA, sizeof(TRIANGLE_SCHEMA)) ||
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
            !triangle_json_function(&json, &result->functions[index])) return 0;
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
        !triangle_json_queues(&json, result) ||
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
        !json_append(&json, "},\"triangle\":") || !triangle_json_triangle(&json, result) ||
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
                   strcmp(result->status, "TRIANGLE_OFFSCREEN_OBSERVED") == 0)) return 0;
    if (!json_append(&json, ",\"triangleRendered\":")) return 0;
    if (!json_bool(&json, result->status != NULL &&
                   strcmp(result->status, "TRIANGLE_OFFSCREEN_OBSERVED") == 0)) return 0;
    if (!json_append(&json, ",\"presentation\":false,\"d3d\":false,\"wow\":false,\"status\":")) return 0;
    if (!json_string(&json, result->status, 64U) || !json_append(&json, ",\"failureStage\":") ||
        !json_string(&json, result->failure_stage, 64U) || !json_append(&json, ",\"failureDetail\":") ||
        !json_string(&json, result->failure_detail, ERROR_LIMIT) ||
        !json_append(&json, ",\"nativeExit\":%d}", result->native_exit)) return 0;
    return json.valid && json.used < capacity;
}

static void triangle_make_unknown_fallback(TriangleResult *fallback,
                                           const TriangleResult *source) {
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
    fallback->read_hash[0] = '\0';
    fallback->img_alloc_capacity = TRIANGLE_CAPACITY;
    fallback->stg_alloc_capacity = TRIANGLE_CAPACITY;
    fallback->dlclose_attempted = source->dlclose_attempted;
    fallback->dlclose_ok = source->dlclose_ok;
    fallback->cleanup_complete = source->cleanup_complete;
    fallback->status = "UNKNOWN";
    fallback->failure_stage = "output";
    fallback->failure_detail[0] = '\0';
    fallback->native_exit = 1;
}

static int triangle_encode_unknown_fallback(const TriangleResult *source, char *output,
                                            size_t capacity) {
    TriangleResult fallback;
    triangle_make_unknown_fallback(&fallback, source);
    return triangle_encode_result(&fallback, output, capacity);
}

static int triangle_in_flight(const TriangleResult *result) {
    return result != NULL && result->submit_attempted &&
           result->submit_result == VK_SUCCESS && !result->signaled;
}

static void triangle_classify(TriangleResult *result) {
    int success = result->scope_valid && result->binding_valid && result->identity_stable &&
                  result->load_loaded && result->interface_supported &&
                  result->instance_created && result->physical_complete &&
                  result->physical_returned_count == 1U && result->physical_target_exact &&
                  result->queue_complete && result->selected && result->device_created &&
                  result->queue_observed && result->img_created &&
                  result->img_req_queried && result->img_mem_selected &&
                  result->img_mem_device_local && result->img_alloc_done &&
                  result->img_bind_ok && result->view_created &&
                  result->rp_created && result->fb_created &&
                  result->vert_created && result->frag_created &&
                  result->layout_created && result->pipe_created &&
                  result->stg_created && result->stg_req_queried &&
                  result->stg_mem_selected && result->stg_mem_visible &&
                  result->stg_alloc_done && result->stg_bind_ok &&
                  result->stg_map_done && result->stg_map_non_null &&
                  result->pool_done && result->cmd_done && result->begun &&
                  result->draw_ok && result->draw_count == 3U &&
                  result->copy_ok && result->ended &&
                  result->fence_done && result->submit_attempted &&
                  result->submit_result == VK_SUCCESS && result->submit_count == 1 &&
                  result->wait_attempted && result->wait_result == VK_SUCCESS &&
                  result->wait_count == 1 && result->waited && result->signaled &&
                  (!result->inv_required || (result->inv_attempted && result->inv_ok)) &&
                  result->read_attempted && result->read_verified &&
                  result->read_bytes == TRIANGLE_STAGING_SIZE_U32 &&
                  result->read_pixels == TRIANGLE_PIXELS &&
                  result->read_clear > 0U && result->read_triangle > 0U &&
                  result->read_clear + result->read_triangle == TRIANGLE_PIXELS &&
                  result->device_destroy_ok && result->instance_destroy_ok &&
                  result->dlclose_ok && result->cleanup_complete &&
                  result->clock_valid && result->timing_valid &&
                  triangle_failure_is(result, "none");
    if (!result->load_loaded && result->load_attempted && result->load_error[0] != '\0' &&
        result->cleanup_complete && triangle_failure_is(result, "loader")) {
        result->status = "LOADER_FAILED";
    } else if (success) {
        result->status = "TRIANGLE_OFFSCREEN_OBSERVED";
    } else if (!result->vk_error && triangle_failure_is(result, "memory_type")) {
        result->status = "NO_MEMORY_TYPE";
    } else if (!result->vk_error && triangle_failure_is(result, "timeout")) {
        result->status = "TIMEOUT";
    } else if (result->vk_error) {
        result->status = "VK_ERROR";
    } else {
        result->status = "UNKNOWN";
    }
    result->native_exit = strcmp(result->status, "TRIANGLE_OFFSCREEN_OBSERVED") == 0 ? 0 : 1;
}

static int triangle_resolve_device_function(TriangleResult *result,
                                            TriangleGetDeviceProcAddr gdpa,
                                            VkDevice device, const char *owner,
                                            size_t index, const char *name) {
    PFN_vkVoidFunction address;
    if (gdpa == NULL || index >= MAX_FUNCTIONS) return 0;
    address = gdpa(device, name);
    return triangle_observe_function(&result->functions[index], address, owner);
}

typedef struct TriangleCalls {
    PFN_vkGetPhysicalDeviceMemoryProperties get_mem_props;
    PFN_vkCreateImage create_image;
    PFN_vkGetImageMemoryRequirements get_image_mem_req;
    PFN_vkAllocateMemory alloc_memory;
    PFN_vkFreeMemory free_memory;
    PFN_vkBindImageMemory bind_image_memory;
    PFN_vkCreateImageView create_view;
    PFN_vkDestroyImageView destroy_view;
    PFN_vkDestroyImage destroy_image;
    PFN_vkCreateRenderPass create_render_pass;
    PFN_vkDestroyRenderPass destroy_render_pass;
    PFN_vkCreateFramebuffer create_framebuffer;
    PFN_vkDestroyFramebuffer destroy_framebuffer;
    PFN_vkCreateShaderModule create_shader;
    PFN_vkDestroyShaderModule destroy_shader;
    PFN_vkCreatePipelineLayout create_layout;
    PFN_vkDestroyPipelineLayout destroy_layout;
    PFN_vkCreateGraphicsPipelines create_pipelines;
    PFN_vkDestroyPipeline destroy_pipeline;
    PFN_vkCreateBuffer create_buffer;
    PFN_vkDestroyBuffer destroy_buffer;
    PFN_vkGetBufferMemoryRequirements get_buffer_mem_req;
    PFN_vkBindBufferMemory bind_buffer_memory;
    PFN_vkMapMemory map_memory;
    PFN_vkUnmapMemory unmap_memory;
    PFN_vkInvalidateMappedMemoryRanges invalidate_ranges;
    PFN_vkCreateCommandPool create_pool;
    PFN_vkDestroyCommandPool destroy_pool;
    PFN_vkAllocateCommandBuffers alloc_commands;
    PFN_vkFreeCommandBuffers free_commands;
    PFN_vkBeginCommandBuffer begin_commands;
    PFN_vkEndCommandBuffer end_commands;
    PFN_vkCmdBeginRenderPass cmd_begin_rp;
    PFN_vkCmdBindPipeline cmd_bind_pipeline;
    PFN_vkCmdDraw cmd_draw;
    PFN_vkCmdEndRenderPass cmd_end_rp;
    PFN_vkCmdCopyImageToBuffer cmd_copy;
    PFN_vkCreateFence create_fence;
    PFN_vkDestroyFence destroy_fence;
    PFN_vkWaitForFences wait_fences;
    PFN_vkQueueSubmit queue_submit;
} TriangleCalls;

static void triangle_free_partials(TriangleResult *result, const TriangleCalls *calls,
                                   VkDevice device,
                                   VkPipeline *pipeline, int *has_pipeline,
                                   VkPipelineLayout *layout, int *has_layout,
                                   VkShaderModule *vert, int *has_vert,
                                   VkShaderModule *frag, int *has_frag,
                                   VkFramebuffer *framebuffer, int *has_framebuffer,
                                   VkRenderPass *render_pass, int *has_render_pass,
                                   VkImageView *view, int *has_view,
                                   VkImage *image, int *has_image,
                                   VkDeviceMemory *image_memory, int *has_image_memory,
                                   VkBuffer *staging, int *has_staging,
                                   VkDeviceMemory *staging_memory, int *has_staging_memory,
                                   void **mapped, int *has_mapped,
                                   VkCommandPool *pool, int *has_pool,
                                   VkCommandBuffer *cmd, int *has_cmd,
                                   VkFence *fence, int *has_fence) {
    uint64_t before;
    int started;
    if (result == NULL || calls == NULL) return;
    if (has_pipeline != NULL && *has_pipeline && calls->destroy_pipeline != NULL &&
        device != VK_NULL_HANDLE && pipeline != NULL) {
        started = triangle_call_begin(result, &before);
        calls->destroy_pipeline(device, *pipeline, NULL);
        if (started) (void)triangle_call_end(result, before);
        *has_pipeline = 0;
    }
    if (has_layout != NULL && *has_layout && calls->destroy_layout != NULL &&
        device != VK_NULL_HANDLE && layout != NULL) {
        started = triangle_call_begin(result, &before);
        calls->destroy_layout(device, *layout, NULL);
        if (started) (void)triangle_call_end(result, before);
        *has_layout = 0;
    }
    if (has_vert != NULL && *has_vert && calls->destroy_shader != NULL &&
        device != VK_NULL_HANDLE && vert != NULL) {
        started = triangle_call_begin(result, &before);
        calls->destroy_shader(device, *vert, NULL);
        if (started) (void)triangle_call_end(result, before);
        *has_vert = 0;
    }
    if (has_frag != NULL && *has_frag && calls->destroy_shader != NULL &&
        device != VK_NULL_HANDLE && frag != NULL) {
        started = triangle_call_begin(result, &before);
        calls->destroy_shader(device, *frag, NULL);
        if (started) (void)triangle_call_end(result, before);
        *has_frag = 0;
    }
    if (has_framebuffer != NULL && *has_framebuffer && calls->destroy_framebuffer != NULL &&
        device != VK_NULL_HANDLE && framebuffer != NULL) {
        started = triangle_call_begin(result, &before);
        calls->destroy_framebuffer(device, *framebuffer, NULL);
        if (started) (void)triangle_call_end(result, before);
        *has_framebuffer = 0;
    }
    if (has_render_pass != NULL && *has_render_pass && calls->destroy_render_pass != NULL &&
        device != VK_NULL_HANDLE && render_pass != NULL) {
        started = triangle_call_begin(result, &before);
        calls->destroy_render_pass(device, *render_pass, NULL);
        if (started) (void)triangle_call_end(result, before);
        *has_render_pass = 0;
    }
    if (has_view != NULL && *has_view && calls->destroy_view != NULL &&
        device != VK_NULL_HANDLE && view != NULL) {
        started = triangle_call_begin(result, &before);
        calls->destroy_view(device, *view, NULL);
        if (started) (void)triangle_call_end(result, before);
        *has_view = 0;
    }
    if (has_image != NULL && *has_image && calls->destroy_image != NULL &&
        device != VK_NULL_HANDLE && image != NULL) {
        started = triangle_call_begin(result, &before);
        calls->destroy_image(device, *image, NULL);
        if (started) (void)triangle_call_end(result, before);
        *has_image = 0;
    }
    if (has_mapped != NULL && *has_mapped && mapped != NULL && *mapped != NULL &&
        calls->unmap_memory != NULL && device != VK_NULL_HANDLE &&
        staging_memory != NULL && has_staging_memory != NULL && *has_staging_memory) {
        started = triangle_call_begin(result, &before);
        calls->unmap_memory(device, *staging_memory);
        if (started) (void)triangle_call_end(result, before);
        *mapped = NULL;
        *has_mapped = 0;
    }
    if (has_cmd != NULL && *has_cmd && has_pool != NULL && *has_pool &&
        calls->free_commands != NULL && device != VK_NULL_HANDLE && cmd != NULL &&
        pool != NULL) {
        started = triangle_call_begin(result, &before);
        calls->free_commands(device, *pool, 1U, cmd);
        if (started) (void)triangle_call_end(result, before);
        *has_cmd = 0;
    }
    if (has_staging != NULL && *has_staging && calls->destroy_buffer != NULL &&
        device != VK_NULL_HANDLE && staging != NULL) {
        started = triangle_call_begin(result, &before);
        calls->destroy_buffer(device, *staging, NULL);
        if (started) (void)triangle_call_end(result, before);
        *has_staging = 0;
    }
    if (has_image_memory != NULL && *has_image_memory && calls->free_memory != NULL &&
        device != VK_NULL_HANDLE && image_memory != NULL) {
        started = triangle_call_begin(result, &before);
        calls->free_memory(device, *image_memory, NULL);
        if (started) (void)triangle_call_end(result, before);
        *has_image_memory = 0;
    }
    if (has_staging_memory != NULL && *has_staging_memory && calls->free_memory != NULL &&
        device != VK_NULL_HANDLE && staging_memory != NULL) {
        started = triangle_call_begin(result, &before);
        calls->free_memory(device, *staging_memory, NULL);
        if (started) (void)triangle_call_end(result, before);
        *has_staging_memory = 0;
    }
    if (has_pool != NULL && *has_pool && calls->destroy_pool != NULL &&
        device != VK_NULL_HANDLE && pool != NULL) {
        started = triangle_call_begin(result, &before);
        calls->destroy_pool(device, *pool, NULL);
        if (started) (void)triangle_call_end(result, before);
        *has_pool = 0;
    }
    if (has_fence != NULL && *has_fence && calls->destroy_fence != NULL &&
        device != VK_NULL_HANDLE && fence != NULL) {
        started = triangle_call_begin(result, &before);
        calls->destroy_fence(device, *fence, NULL);
        if (started) (void)triangle_call_end(result, before);
        *has_fence = 0;
    }
}

static int triangle_workload(TriangleResult *result, TriangleGetDeviceProcAddr gdpa,
                             VkPhysicalDevice physical, VkDevice device, VkQueue queue,
                             uint32_t family, const char *owner) {
    TriangleCalls calls;
    VkPhysicalDeviceMemoryProperties mem_props;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory image_memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkRenderPass render_pass = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    VkShaderModule vert_module = VK_NULL_HANDLE;
    VkShaderModule frag_module = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory staging_memory = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    void *mapped = NULL;
    int has_image = 0;
    int has_image_memory = 0;
    int has_view = 0;
    int has_render_pass = 0;
    int has_framebuffer = 0;
    int has_vert = 0;
    int has_frag = 0;
    int has_layout = 0;
    int has_pipeline = 0;
    int has_staging = 0;
    int has_staging_memory = 0;
    int has_mapped = 0;
    int has_pool = 0;
    int has_cmd = 0;
    int has_fence = 0;
    uint64_t before;
    int timing_ok;
    memset(&calls, 0, sizeof(calls));
    memset(&mem_props, 0, sizeof(mem_props));

    if (gdpa == NULL) {
        triangle_set_failure(result, "owner", "device proc addr unavailable for triangle");
        return 0;
    }
    {
        static const size_t device_indices[] = {11U, 12U, 13U, 14U, 15U, 16U, 17U,
                                                18U, 19U, 20U, 21U, 22U, 23U, 24U,
                                                25U, 26U, 27U, 28U, 29U, 30U, 31U,
                                                32U, 33U, 34U, 35U, 36U, 37U, 38U,
                                                39U, 40U, 41U, 42U, 43U, 44U, 45U,
                                                46U, 47U, 48U, 49U, 50U};
        size_t slot;
        for (slot = 0U; slot < sizeof(device_indices) / sizeof(device_indices[0]); ++slot) {
            size_t target = device_indices[slot];
            if (!triangle_resolve_device_function(result, gdpa, device, owner, target,
                                                  triangle_function_names[target])) {
                triangle_set_failure(result, "owner", "required triangle PFN missing or owner mismatch");
                return 0;
            }
        }
    }
    calls.get_mem_props = NULL;
    calls.create_image = (PFN_vkCreateImage)(uintptr_t)result->functions[11U].address;
    calls.get_image_mem_req = (PFN_vkGetImageMemoryRequirements)(uintptr_t)result->functions[12U].address;
    calls.alloc_memory = (PFN_vkAllocateMemory)(uintptr_t)result->functions[13U].address;
    calls.free_memory = (PFN_vkFreeMemory)(uintptr_t)result->functions[14U].address;
    calls.bind_image_memory = (PFN_vkBindImageMemory)(uintptr_t)result->functions[15U].address;
    calls.create_view = (PFN_vkCreateImageView)(uintptr_t)result->functions[16U].address;
    calls.destroy_view = (PFN_vkDestroyImageView)(uintptr_t)result->functions[17U].address;
    calls.destroy_image = (PFN_vkDestroyImage)(uintptr_t)result->functions[18U].address;
    calls.create_render_pass = (PFN_vkCreateRenderPass)(uintptr_t)result->functions[19U].address;
    calls.destroy_render_pass = (PFN_vkDestroyRenderPass)(uintptr_t)result->functions[20U].address;
    calls.create_framebuffer = (PFN_vkCreateFramebuffer)(uintptr_t)result->functions[21U].address;
    calls.destroy_framebuffer = (PFN_vkDestroyFramebuffer)(uintptr_t)result->functions[22U].address;
    calls.create_shader = (PFN_vkCreateShaderModule)(uintptr_t)result->functions[23U].address;
    calls.destroy_shader = (PFN_vkDestroyShaderModule)(uintptr_t)result->functions[24U].address;
    calls.create_layout = (PFN_vkCreatePipelineLayout)(uintptr_t)result->functions[25U].address;
    calls.destroy_layout = (PFN_vkDestroyPipelineLayout)(uintptr_t)result->functions[26U].address;
    calls.create_pipelines = (PFN_vkCreateGraphicsPipelines)(uintptr_t)result->functions[27U].address;
    calls.destroy_pipeline = (PFN_vkDestroyPipeline)(uintptr_t)result->functions[28U].address;
    calls.create_buffer = (PFN_vkCreateBuffer)(uintptr_t)result->functions[29U].address;
    calls.destroy_buffer = (PFN_vkDestroyBuffer)(uintptr_t)result->functions[30U].address;
    calls.get_buffer_mem_req = (PFN_vkGetBufferMemoryRequirements)(uintptr_t)result->functions[31U].address;
    calls.bind_buffer_memory = (PFN_vkBindBufferMemory)(uintptr_t)result->functions[32U].address;
    calls.map_memory = (PFN_vkMapMemory)(uintptr_t)result->functions[33U].address;
    calls.unmap_memory = (PFN_vkUnmapMemory)(uintptr_t)result->functions[34U].address;
    calls.invalidate_ranges = (PFN_vkInvalidateMappedMemoryRanges)(uintptr_t)result->functions[35U].address;
    calls.create_pool = (PFN_vkCreateCommandPool)(uintptr_t)result->functions[36U].address;
    calls.destroy_pool = (PFN_vkDestroyCommandPool)(uintptr_t)result->functions[37U].address;
    calls.alloc_commands = (PFN_vkAllocateCommandBuffers)(uintptr_t)result->functions[38U].address;
    calls.free_commands = (PFN_vkFreeCommandBuffers)(uintptr_t)result->functions[39U].address;
    calls.begin_commands = (PFN_vkBeginCommandBuffer)(uintptr_t)result->functions[40U].address;
    calls.end_commands = (PFN_vkEndCommandBuffer)(uintptr_t)result->functions[41U].address;
    calls.cmd_begin_rp = (PFN_vkCmdBeginRenderPass)(uintptr_t)result->functions[42U].address;
    calls.cmd_bind_pipeline = (PFN_vkCmdBindPipeline)(uintptr_t)result->functions[43U].address;
    calls.cmd_draw = (PFN_vkCmdDraw)(uintptr_t)result->functions[44U].address;
    calls.cmd_end_rp = (PFN_vkCmdEndRenderPass)(uintptr_t)result->functions[45U].address;
    calls.cmd_copy = (PFN_vkCmdCopyImageToBuffer)(uintptr_t)result->functions[46U].address;
    calls.create_fence = (PFN_vkCreateFence)(uintptr_t)result->functions[47U].address;
    calls.destroy_fence = (PFN_vkDestroyFence)(uintptr_t)result->functions[48U].address;
    calls.wait_fences = (PFN_vkWaitForFences)(uintptr_t)result->functions[49U].address;
    calls.queue_submit = (PFN_vkQueueSubmit)(uintptr_t)result->functions[50U].address;

    {
        VkImageCreateInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        info.imageType = VK_IMAGE_TYPE_2D;
        info.format = VK_FORMAT_R8G8B8A8_UNORM;
        info.extent.width = TRIANGLE_EXTENT;
        info.extent.height = TRIANGLE_EXTENT;
        info.extent.depth = 1U;
        info.mipLevels = 1U;
        info.arrayLayers = 1U;
        info.samples = VK_SAMPLE_COUNT_1_BIT;
        info.tiling = VK_IMAGE_TILING_OPTIMAL;
        info.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        result->img_format = TRIANGLE_FORMAT_VALUE;
        result->img_extent_w = TRIANGLE_EXTENT;
        result->img_extent_h = TRIANGLE_EXTENT;
        result->img_usage = TRIANGLE_USAGE_VALUE;
        result->img_tiling = (uint32_t)VK_IMAGE_TILING_OPTIMAL;
        result->img_samples = 1U;
        result->img_mip_levels = 1U;
        result->img_array_layers = 1U;
        result->img_sharing = (uint32_t)VK_SHARING_MODE_EXCLUSIVE;
        result->img_create_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->img_result = calls.create_image(device, &info, NULL, &image);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->img_result, "vkCreateImage failed");
        if (result->img_result != VK_SUCCESS || image == VK_NULL_HANDLE) {
            if (result->img_result != VK_SUCCESS && image != VK_NULL_HANDLE) {
                result->img_handle_on_error = 1;
                triangle_set_failure(result, "vulkan", "vkCreateImage returned a handle with an error");
                image = VK_NULL_HANDLE;
            } else if (result->img_result == VK_SUCCESS) {
                triangle_set_failure(result, "vulkan", "vkCreateImage returned null");
            }
            goto triangle_done;
        }
        result->img_created = 1;
        has_image = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkMemoryRequirements req;
        memset(&req, 0, sizeof(req));
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        calls.get_image_mem_req(device, image, &req);
        if (!triangle_call_end(result, before)) goto triangle_done;
        result->img_req_queried = 1;
        result->img_req_size = (uint64_t)req.size;
        result->img_req_align = (uint64_t)req.alignment;
        result->img_req_bits = req.memoryTypeBits;
    }

    {
        PFN_vkGetPhysicalDeviceMemoryProperties get_props;
        uint32_t chosen;
        get_props = (PFN_vkGetPhysicalDeviceMemoryProperties)(uintptr_t)
            result->functions[6U].address;
        if (get_props == NULL) {
            triangle_set_failure(result, "owner", "memory-properties PFN unavailable");
            goto triangle_done;
        }
        calls.get_mem_props = get_props;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        get_props(physical, &mem_props);
        if (!triangle_call_end(result, before)) goto triangle_done;
        chosen = triangle_select_image_memory_index(&mem_props, result->img_req_bits);
        if (chosen == UINT32_MAX) {
            triangle_set_failure(result, "memory_type", "no memory type for image requirements");
            goto triangle_done;
        }
        result->img_mem_selected = 1;
        result->img_mem_index = chosen;
        result->img_mem_flags = (uint32_t)mem_props.memoryTypes[chosen].propertyFlags;
        result->img_mem_device_local = (mem_props.memoryTypes[chosen].propertyFlags &
                                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0U;
    }

    {
        VkMemoryAllocateInfo info;
        uint64_t aligned = result->img_req_size;
        if (result->img_req_size == 0U || result->img_req_align == 0U ||
            result->img_req_size > TRIANGLE_CAPACITY) {
            triangle_set_failure(result, "requirements", "image requirements exceed 16 MiB cap");
            goto triangle_done;
        }
        if (result->img_req_align > 1U &&
            (result->img_req_align & (result->img_req_align - 1U)) == 0U) {
            uint64_t mask = result->img_req_align - 1U;
            aligned = (result->img_req_size + mask) & ~mask;
        }
        if (aligned < result->img_req_size || aligned > TRIANGLE_CAPACITY) {
            triangle_set_failure(result, "requirements", "aligned image allocation exceeds 16 MiB cap");
            goto triangle_done;
        }
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        info.allocationSize = (VkDeviceSize)aligned;
        info.memoryTypeIndex = result->img_mem_index;
        result->img_alloc_requested = aligned;
        result->img_alloc_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->img_alloc_result = calls.alloc_memory(device, &info, NULL, &image_memory);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->img_alloc_result, "vkAllocateMemory for image failed");
        if (result->img_alloc_result != VK_SUCCESS || image_memory == VK_NULL_HANDLE) {
            if (result->img_alloc_result != VK_SUCCESS && image_memory != VK_NULL_HANDLE) {
                result->img_alloc_handle_on_error = 1;
                triangle_set_failure(result, "vulkan", "vkAllocateMemory returned a handle with an error");
                image_memory = VK_NULL_HANDLE;
            } else if (result->img_alloc_result == VK_SUCCESS) {
                triangle_set_failure(result, "vulkan", "vkAllocateMemory returned null");
            }
            goto triangle_done;
        }
        result->img_alloc_done = 1;
        has_image_memory = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkResult bind_result;
        result->img_bind_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        bind_result = calls.bind_image_memory(device, image, image_memory, 0U);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, bind_result, "vkBindImageMemory failed");
        if (bind_result != VK_SUCCESS) goto triangle_done;
        result->img_bind_ok = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkImageViewCreateInfo info;
        VkImageSubresourceRange range;
        memset(&info, 0, sizeof(info));
        memset(&range, 0, sizeof(range));
        range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        range.baseMipLevel = 0U;
        range.levelCount = 1U;
        range.baseArrayLayer = 0U;
        range.layerCount = 1U;
        info.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        info.image = image;
        info.viewType = VK_IMAGE_VIEW_TYPE_2D;
        info.format = VK_FORMAT_R8G8B8A8_UNORM;
        info.subresourceRange = range;
        result->view_create_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->view_result = calls.create_view(device, &info, NULL, &view);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->view_result, "vkCreateImageView failed");
        if (result->view_result != VK_SUCCESS || view == VK_NULL_HANDLE) {
            if (result->view_result != VK_SUCCESS && view != VK_NULL_HANDLE) {
                result->view_handle_on_error = 1;
                triangle_set_failure(result, "vulkan", "vkCreateImageView returned a handle with an error");
                view = VK_NULL_HANDLE;
            } else if (result->view_result == VK_SUCCESS) {
                triangle_set_failure(result, "vulkan", "vkCreateImageView returned null");
            }
            goto triangle_done;
        }
        result->view_created = 1;
        has_view = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkAttachmentDescription attachment;
        VkAttachmentReference color_ref;
        VkSubpassDescription subpass;
        VkSubpassDependency dependencies[2];
        VkRenderPassCreateInfo info;
        memset(&attachment, 0, sizeof(attachment));
        attachment.format = VK_FORMAT_R8G8B8A8_UNORM;
        attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        attachment.finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        memset(&color_ref, 0, sizeof(color_ref));
        color_ref.attachment = 0U;
        color_ref.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        memset(&subpass, 0, sizeof(subpass));
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1U;
        subpass.pColorAttachments = &color_ref;
        memset(dependencies, 0, sizeof(dependencies));
        dependencies[0].srcSubpass = VK_SUBPASS_EXTERNAL;
        dependencies[0].dstSubpass = 0U;
        dependencies[0].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependencies[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependencies[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dependencies[1].srcSubpass = 0U;
        dependencies[1].dstSubpass = VK_SUBPASS_EXTERNAL;
        dependencies[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependencies[1].dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
        dependencies[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        dependencies[1].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        info.attachmentCount = 1U;
        info.pAttachments = &attachment;
        info.subpassCount = 1U;
        info.pSubpasses = &subpass;
        info.dependencyCount = 2U;
        info.pDependencies = dependencies;
        result->rp_create_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->rp_result = calls.create_render_pass(device, &info, NULL, &render_pass);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->rp_result, "vkCreateRenderPass failed");
        if (result->rp_result != VK_SUCCESS || render_pass == VK_NULL_HANDLE) {
            if (result->rp_result != VK_SUCCESS && render_pass != VK_NULL_HANDLE) {
                result->rp_handle_on_error = 1;
                triangle_set_failure(result, "vulkan", "vkCreateRenderPass returned a handle with an error");
                render_pass = VK_NULL_HANDLE;
            } else if (result->rp_result == VK_SUCCESS) {
                triangle_set_failure(result, "vulkan", "vkCreateRenderPass returned null");
            }
            goto triangle_done;
        }
        result->rp_created = 1;
        has_render_pass = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkFramebufferCreateInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        info.renderPass = render_pass;
        info.attachmentCount = 1U;
        info.pAttachments = &view;
        info.width = TRIANGLE_EXTENT;
        info.height = TRIANGLE_EXTENT;
        info.layers = 1U;
        result->fb_create_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->fb_result = calls.create_framebuffer(device, &info, NULL, &framebuffer);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->fb_result, "vkCreateFramebuffer failed");
        if (result->fb_result != VK_SUCCESS || framebuffer == VK_NULL_HANDLE) {
            if (result->fb_result != VK_SUCCESS && framebuffer != VK_NULL_HANDLE) {
                result->fb_handle_on_error = 1;
                triangle_set_failure(result, "vulkan", "vkCreateFramebuffer returned a handle with an error");
                framebuffer = VK_NULL_HANDLE;
            } else if (result->fb_result == VK_SUCCESS) {
                triangle_set_failure(result, "vulkan", "vkCreateFramebuffer returned null");
            }
            goto triangle_done;
        }
        result->fb_created = 1;
        has_framebuffer = 1;
        if (!timing_ok) goto triangle_done;
    }

    result->vert_bytes = (uint32_t)sizeof(triangle_vert_spv);
    result->frag_bytes = (uint32_t)sizeof(triangle_frag_spv);
    {
        VkShaderModuleCreateInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        info.codeSize = sizeof(triangle_vert_spv);
        info.pCode = triangle_vert_spv;
        result->vert_create_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->vert_result = calls.create_shader(device, &info, NULL, &vert_module);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->vert_result, "vkCreateShaderModule for vertex failed");
        if (result->vert_result != VK_SUCCESS || vert_module == VK_NULL_HANDLE) {
            vert_module = VK_NULL_HANDLE;
            if (result->vert_result == VK_SUCCESS)
                triangle_set_failure(result, "vulkan", "vertex vkCreateShaderModule returned null");
            goto triangle_done;
        }
        result->vert_created = 1;
        has_vert = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkShaderModuleCreateInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        info.codeSize = sizeof(triangle_frag_spv);
        info.pCode = triangle_frag_spv;
        result->frag_create_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->frag_result = calls.create_shader(device, &info, NULL, &frag_module);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->frag_result, "vkCreateShaderModule for fragment failed");
        if (result->frag_result != VK_SUCCESS || frag_module == VK_NULL_HANDLE) {
            frag_module = VK_NULL_HANDLE;
            if (result->frag_result == VK_SUCCESS)
                triangle_set_failure(result, "vulkan", "fragment vkCreateShaderModule returned null");
            goto triangle_done;
        }
        result->frag_created = 1;
        has_frag = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkPipelineLayoutCreateInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        info.setLayoutCount = 0U;
        info.pSetLayouts = NULL;
        info.pushConstantRangeCount = 0U;
        info.pPushConstantRanges = NULL;
        result->layout_create_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->layout_result = calls.create_layout(device, &info, NULL, &layout);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->layout_result, "vkCreatePipelineLayout failed");
        if (result->layout_result != VK_SUCCESS || layout == VK_NULL_HANDLE) {
            layout = VK_NULL_HANDLE;
            if (result->layout_result == VK_SUCCESS)
                triangle_set_failure(result, "vulkan", "vkCreatePipelineLayout returned null");
            goto triangle_done;
        }
        result->layout_created = 1;
        has_layout = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkPipelineShaderStageCreateInfo stages[2];
        VkPipelineVertexInputStateCreateInfo vertex_input;
        VkPipelineInputAssemblyStateCreateInfo input_assembly;
        VkViewport viewport;
        VkRect2D scissor;
        VkPipelineViewportStateCreateInfo viewport_state;
        VkPipelineRasterizationStateCreateInfo rasterizer;
        VkPipelineMultisampleStateCreateInfo multisample;
        VkPipelineColorBlendAttachmentState blend_attachment;
        VkPipelineColorBlendStateCreateInfo blend;
        VkGraphicsPipelineCreateInfo info;
        memset(stages, 0, sizeof(stages));
        stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = vert_module;
        stages[0].pName = "main";
        stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = frag_module;
        stages[1].pName = "main";
        memset(&vertex_input, 0, sizeof(vertex_input));
        vertex_input.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        memset(&input_assembly, 0, sizeof(input_assembly));
        input_assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        input_assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        memset(&viewport, 0, sizeof(viewport));
        viewport.width = (float)TRIANGLE_EXTENT;
        viewport.height = (float)TRIANGLE_EXTENT;
        viewport.maxDepth = 1.0F;
        memset(&scissor, 0, sizeof(scissor));
        scissor.extent.width = TRIANGLE_EXTENT;
        scissor.extent.height = TRIANGLE_EXTENT;
        memset(&viewport_state, 0, sizeof(viewport_state));
        viewport_state.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewport_state.viewportCount = 1U;
        viewport_state.pViewports = &viewport;
        viewport_state.scissorCount = 1U;
        viewport_state.pScissors = &scissor;
        memset(&rasterizer, 0, sizeof(rasterizer));
        rasterizer.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
        rasterizer.cullMode = VK_CULL_MODE_NONE;
        rasterizer.frontFace = VK_FRONT_FACE_CLOCKWISE;
        rasterizer.lineWidth = 1.0F;
        memset(&multisample, 0, sizeof(multisample));
        multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        memset(&blend_attachment, 0, sizeof(blend_attachment));
        blend_attachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT |
                                          VK_COLOR_COMPONENT_G_BIT |
                                          VK_COLOR_COMPONENT_B_BIT |
                                          VK_COLOR_COMPONENT_A_BIT;
        memset(&blend, 0, sizeof(blend));
        blend.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        blend.attachmentCount = 1U;
        blend.pAttachments = &blend_attachment;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        info.stageCount = 2U;
        info.pStages = stages;
        info.pVertexInputState = &vertex_input;
        info.pInputAssemblyState = &input_assembly;
        info.pViewportState = &viewport_state;
        info.pRasterizationState = &rasterizer;
        info.pMultisampleState = &multisample;
        info.pColorBlendState = &blend;
        info.layout = layout;
        info.renderPass = render_pass;
        info.subpass = 0U;
        result->pipe_create_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->pipe_result = calls.create_pipelines(device, VK_NULL_HANDLE, 1U, &info,
                                                     NULL, &pipeline);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->pipe_result, "vkCreateGraphicsPipelines failed");
        if (result->pipe_result != VK_SUCCESS || pipeline == VK_NULL_HANDLE) {
            if (result->pipe_result != VK_SUCCESS && pipeline != VK_NULL_HANDLE) {
                result->pipe_handle_on_error = 1;
                triangle_set_failure(result, "vulkan", "vkCreateGraphicsPipelines returned a handle with an error");
                pipeline = VK_NULL_HANDLE;
            } else if (result->pipe_result == VK_SUCCESS) {
                triangle_set_failure(result, "vulkan", "vkCreateGraphicsPipelines returned null");
            }
            goto triangle_done;
        }
        result->pipe_created = 1;
        has_pipeline = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkBufferCreateInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        info.size = (VkDeviceSize)TRIANGLE_STAGING_SIZE;
        info.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        result->stg_size = TRIANGLE_STAGING_SIZE_U32;
        result->stg_usage = (uint32_t)VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        result->stg_create_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->stg_result = calls.create_buffer(device, &info, NULL, &staging);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->stg_result, "vkCreateBuffer for staging failed");
        if (result->stg_result != VK_SUCCESS || staging == VK_NULL_HANDLE) {
            if (result->stg_result != VK_SUCCESS && staging != VK_NULL_HANDLE) {
                result->stg_handle_on_error = 1;
                triangle_set_failure(result, "vulkan", "staging vkCreateBuffer returned a handle with an error");
                staging = VK_NULL_HANDLE;
            } else if (result->stg_result == VK_SUCCESS) {
                triangle_set_failure(result, "vulkan", "staging vkCreateBuffer returned null");
            }
            goto triangle_done;
        }
        result->stg_created = 1;
        has_staging = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkMemoryRequirements req;
        memset(&req, 0, sizeof(req));
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        calls.get_buffer_mem_req(device, staging, &req);
        if (!triangle_call_end(result, before)) goto triangle_done;
        result->stg_req_queried = 1;
        result->stg_req_size = (uint64_t)req.size;
        result->stg_req_align = (uint64_t)req.alignment;
        result->stg_req_bits = req.memoryTypeBits;
    }

    {
        uint32_t chosen;
        int coherent = 0;
        chosen = triangle_select_staging_memory_index(&mem_props, result->stg_req_bits, &coherent);
        if (chosen == UINT32_MAX) {
            triangle_set_failure(result, "memory_type", "no memory type with HOST_VISIBLE for staging requirements");
            goto triangle_done;
        }
        result->stg_mem_selected = 1;
        result->stg_mem_index = chosen;
        result->stg_mem_flags = (uint32_t)mem_props.memoryTypes[chosen].propertyFlags;
        result->stg_mem_visible = (mem_props.memoryTypes[chosen].propertyFlags &
                                   VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0U;
        result->stg_mem_coherent = (mem_props.memoryTypes[chosen].propertyFlags &
                                    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0U;
    }

    {
        VkMemoryAllocateInfo info;
        uint64_t aligned = result->stg_req_size;
        if (result->stg_req_size == 0U || result->stg_req_align == 0U ||
            result->stg_req_size > TRIANGLE_CAPACITY) {
            triangle_set_failure(result, "requirements", "staging requirements exceed 16 MiB cap");
            goto triangle_done;
        }
        if (result->stg_req_align > 1U &&
            (result->stg_req_align & (result->stg_req_align - 1U)) == 0U) {
            uint64_t mask = result->stg_req_align - 1U;
            aligned = (result->stg_req_size + mask) & ~mask;
        }
        if (aligned < result->stg_req_size || aligned > TRIANGLE_CAPACITY) {
            triangle_set_failure(result, "requirements", "aligned staging allocation exceeds 16 MiB cap");
            goto triangle_done;
        }
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        info.allocationSize = (VkDeviceSize)aligned;
        info.memoryTypeIndex = result->stg_mem_index;
        result->stg_alloc_requested = aligned;
        result->stg_alloc_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->stg_alloc_result = calls.alloc_memory(device, &info, NULL, &staging_memory);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->stg_alloc_result, "vkAllocateMemory for staging failed");
        if (result->stg_alloc_result != VK_SUCCESS || staging_memory == VK_NULL_HANDLE) {
            staging_memory = VK_NULL_HANDLE;
            if (result->stg_alloc_result == VK_SUCCESS)
                triangle_set_failure(result, "vulkan", "staging vkAllocateMemory returned null");
            goto triangle_done;
        }
        result->stg_alloc_done = 1;
        has_staging_memory = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkResult bind_result;
        result->stg_bind_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        bind_result = calls.bind_buffer_memory(device, staging, staging_memory, 0U);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, bind_result, "staging vkBindBufferMemory failed");
        if (bind_result != VK_SUCCESS) goto triangle_done;
        result->stg_bind_ok = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkResult map_result;
        result->stg_map_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        map_result = calls.map_memory(device, staging_memory, 0U,
                                      (VkDeviceSize)TRIANGLE_STAGING_SIZE,
                                      0U, &mapped);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, map_result, "vkMapMemory failed");
        if (map_result != VK_SUCCESS || mapped == NULL) {
            mapped = NULL;
            if (map_result == VK_SUCCESS)
                triangle_set_failure(result, "map", "vkMapMemory returned null pointer");
            goto triangle_done;
        }
        result->stg_map_done = 1;
        result->stg_map_non_null = 1;
        has_mapped = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkCommandPoolCreateInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        info.queueFamilyIndex = family;
        result->pool_flags = TRIANGLE_POOL_FLAGS;
        result->pool_family = family;
        result->pool_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->pool_result = calls.create_pool(device, &info, NULL, &pool);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->pool_result, "vkCreateCommandPool failed");
        if (result->pool_result != VK_SUCCESS || pool == VK_NULL_HANDLE) {
            pool = VK_NULL_HANDLE;
            if (result->pool_result == VK_SUCCESS)
                triangle_set_failure(result, "vulkan", "vkCreateCommandPool returned null");
            goto triangle_done;
        }
        result->pool_done = 1;
        has_pool = 1;
        if (!timing_ok) goto triangle_done;
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
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->cmd_result = calls.alloc_commands(device, &info, &cmd);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->cmd_result, "vkAllocateCommandBuffers failed");
        if (result->cmd_result != VK_SUCCESS || cmd == VK_NULL_HANDLE) {
            cmd = VK_NULL_HANDLE;
            if (result->cmd_result == VK_SUCCESS)
                triangle_set_failure(result, "vulkan", "vkAllocateCommandBuffers returned null");
            goto triangle_done;
        }
        result->cmd_done = 1;
        has_cmd = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkCommandBufferBeginInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        info.flags = 0U;
        result->begin_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->begin_result = calls.begin_commands(cmd, &info);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->begin_result, "vkBeginCommandBuffer failed");
        if (result->begin_result != VK_SUCCESS) goto triangle_done;
        result->begun = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkRenderPassBeginInfo rp_begin;
        VkClearValue clear;
        VkBufferImageCopy region;
        clear.color.float32[0] = 0.0F;
        clear.color.float32[1] = 1.0F;
        clear.color.float32[2] = 0.0F;
        clear.color.float32[3] = 1.0F;
        memset(&rp_begin, 0, sizeof(rp_begin));
        rp_begin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        rp_begin.renderPass = render_pass;
        rp_begin.framebuffer = framebuffer;
        rp_begin.renderArea.extent.width = TRIANGLE_EXTENT;
        rp_begin.renderArea.extent.height = TRIANGLE_EXTENT;
        rp_begin.clearValueCount = 1U;
        rp_begin.pClearValues = &clear;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        calls.cmd_begin_rp(cmd, &rp_begin, VK_SUBPASS_CONTENTS_INLINE);
        if (!triangle_call_end(result, before)) goto triangle_done;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        calls.cmd_bind_pipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
        if (!triangle_call_end(result, before)) goto triangle_done;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        calls.cmd_draw(cmd, 3U, 1U, 0U, 0U);
        if (!triangle_call_end(result, before)) goto triangle_done;
        result->draw_attempted = 1;
        result->draw_ok = 1;
        result->draw_count = 3U;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        calls.cmd_end_rp(cmd);
        if (!triangle_call_end(result, before)) goto triangle_done;
        memset(&region, 0, sizeof(region));
        region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        region.imageSubresource.layerCount = 1U;
        region.imageExtent.width = TRIANGLE_EXTENT;
        region.imageExtent.height = TRIANGLE_EXTENT;
        region.imageExtent.depth = 1U;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        calls.cmd_copy(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, staging,
                       1U, &region);
        if (!triangle_call_end(result, before)) goto triangle_done;
        result->copy_attempted = 1;
        result->copy_ok = 1;
    }

    {
        result->end_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->end_result = calls.end_commands(cmd);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->end_result, "vkEndCommandBuffer failed");
        if (result->end_result != VK_SUCCESS) goto triangle_done;
        result->ended = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkFenceCreateInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        info.flags = 0U;
        result->fence_flags = 0U;
        result->fence_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->fence_result = calls.create_fence(device, &info, NULL, &fence);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, result->fence_result, "vkCreateFence failed");
        if (result->fence_result != VK_SUCCESS || fence == VK_NULL_HANDLE) {
            fence = VK_NULL_HANDLE;
            if (result->fence_result == VK_SUCCESS)
                triangle_set_failure(result, "vulkan", "vkCreateFence returned null");
            goto triangle_done;
        }
        result->fence_done = 1;
        has_fence = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        VkSubmitInfo info;
        memset(&info, 0, sizeof(info));
        info.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        info.commandBufferCount = 1U;
        info.pCommandBuffers = &cmd;
        result->submit_attempted = 1;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        result->submit_result = calls.queue_submit(queue, 1U, &info, fence);
        timing_ok = triangle_call_end(result, before);
        ++result->submit_count;
        triangle_record_result(result, result->submit_result, "vkQueueSubmit failed");
        if (result->submit_result != VK_SUCCESS) goto triangle_done;
        if (!timing_ok) goto triangle_done;
        result->wait_attempted = 1;
        result->wait_timeout = TRIANGLE_TIMEOUT_NS;
        if (!triangle_call_begin(result, &before)) {
            triangle_set_failure(result, "timeout", "monotonic check before fence wait failed");
            goto triangle_done;
        }
        result->wait_result = calls.wait_fences(device, 1U, &fence, VK_TRUE,
                                                 (uint64_t)TRIANGLE_TIMEOUT_NS);
        timing_ok = triangle_call_end(result, before);
        ++result->wait_count;
        if (result->wait_result == VK_SUCCESS) {
            result->waited = 1;
            result->signaled = 1;
        } else if (result->wait_result == VK_TIMEOUT) {
            triangle_set_failure(result, "timeout", "vkWaitForFences timed out after 1s");
            goto triangle_done;
        } else {
            triangle_record_result(result, result->wait_result, "vkWaitForFences failed");
            if (!triangle_failure_is(result, "none"))
                triangle_set_failure(result, "vulkan", "vkWaitForFences failed");
            goto triangle_done;
        }
        if (!timing_ok) goto triangle_done;
    }

    if (!result->stg_mem_coherent) {
        VkMappedMemoryRange range;
        VkResult inv_result;
        memset(&range, 0, sizeof(range));
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = staging_memory;
        range.offset = 0U;
        range.size = (VkDeviceSize)TRIANGLE_STAGING_SIZE;
        result->inv_required = 1;
        result->inv_attempted = 1;
        result->inv_offset = 0U;
        result->inv_size = TRIANGLE_STAGING_SIZE;
        if (!triangle_call_begin(result, &before)) goto triangle_done;
        inv_result = calls.invalidate_ranges(device, 1U, &range);
        timing_ok = triangle_call_end(result, before);
        triangle_record_result(result, inv_result, "vkInvalidateMappedMemoryRanges failed");
        if (inv_result != VK_SUCCESS) goto triangle_done;
        result->inv_ok = 1;
        if (!timing_ok) goto triangle_done;
    }

    {
        const unsigned char *bytes;
        uint32_t clear = 0U;
        uint32_t index;
        result->read_attempted = 1;
        result->read_bytes = TRIANGLE_STAGING_SIZE_U32;
        result->read_pixels = TRIANGLE_PIXELS;
        bytes = (const unsigned char *)mapped;
        for (index = 0U; index < TRIANGLE_PIXELS; ++index) {
            if (bytes[index * 4U] == 0U && bytes[index * 4U + 1U] == 255U &&
                bytes[index * 4U + 2U] == 0U) {
                ++clear;
            }
        }
        result->read_clear = clear;
        result->read_triangle = TRIANGLE_PIXELS - clear;
        triangle_fnv1a(bytes, (size_t)TRIANGLE_STAGING_SIZE,
                       result->read_hash);
        result->read_verified = clear > 0U && clear < TRIANGLE_PIXELS;
        if (!result->read_verified)
            triangle_set_failure(result, "readback", "triangle readback lacks clear or triangle pixels");
    }

triangle_done:
    if (result->signaled && triangle_failure_is(result, "none")) {
        triangle_free_partials(result, &calls, device, &pipeline, &has_pipeline,
                               &layout, &has_layout, &vert_module, &has_vert,
                               &frag_module, &has_frag, &framebuffer, &has_framebuffer,
                               &render_pass, &has_render_pass, &view, &has_view,
                               &image, &has_image, &image_memory, &has_image_memory,
                               &staging, &has_staging, &staging_memory,
                               &has_staging_memory, &mapped, &has_mapped, &pool,
                               &has_pool, &cmd, &has_cmd, &fence, &has_fence);
        return result->read_verified;
    }
    if (triangle_in_flight(result)) {
        return 0;
    }
    triangle_free_partials(result, &calls, device, &pipeline, &has_pipeline,
                           &layout, &has_layout, &vert_module, &has_vert,
                           &frag_module, &has_frag, &framebuffer, &has_framebuffer,
                           &render_pass, &has_render_pass, &view, &has_view,
                           &image, &has_image, &image_memory, &has_image_memory,
                           &staging, &has_staging, &staging_memory,
                           &has_staging_memory, &mapped, &has_mapped, &pool,
                           &has_pool, &cmd, &has_cmd, &fence, &has_fence);
    return 0;
}

static void triangle_control(TriangleResult *result, TriangleGetInstanceProcAddr gipa,
                             VkInstance instance, VkPhysicalDevice physical,
                             const char *owner) {
    PFN_vkGetPhysicalDeviceQueueFamilyProperties get_families;
    PFN_vkGetPhysicalDeviceMemoryProperties get_mem_props = NULL;
    PFN_vkCreateDevice create_device;
    PFN_vkDestroyDevice destroy_device = NULL;
    TriangleGetDeviceProcAddr gdpa = NULL;
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

    if (!triangle_resolve_instance(result, gipa, instance, owner, 5U,
                                   "vkGetPhysicalDeviceQueueFamilyProperties") ||
        !triangle_resolve_instance(result, gipa, instance, owner, 6U,
                                   "vkGetPhysicalDeviceMemoryProperties") ||
        !triangle_resolve_instance(result, gipa, instance, owner, 7U, "vkCreateDevice") ||
        !triangle_resolve_instance(result, gipa, instance, owner, 8U, "vkDestroyDevice") ||
        !triangle_resolve_instance(result, gipa, instance, owner, 9U, "vkGetDeviceProcAddr")) {
        triangle_set_failure(result, "owner", "required device PFN missing or owner mismatch");
        return;
    }
    get_families = (PFN_vkGetPhysicalDeviceQueueFamilyProperties)(uintptr_t)
        result->functions[5U].address;
    get_mem_props = (PFN_vkGetPhysicalDeviceMemoryProperties)(uintptr_t)
        result->functions[6U].address;
    (void)get_mem_props;
    create_device = (PFN_vkCreateDevice)(uintptr_t)result->functions[7U].address;
    destroy_device = (PFN_vkDestroyDevice)(uintptr_t)result->functions[8U].address;
    gdpa = (TriangleGetDeviceProcAddr)(uintptr_t)result->functions[9U].address;

    if (!triangle_call_begin(result, &before)) return;
    get_families(physical, &reported, NULL);
    if (!triangle_call_end(result, before)) return;
    result->queue_count_queried = 1;
    result->queue_reported_count = reported;
    if (reported > MAX_QUEUE_FAMILIES) {
        triangle_set_failure(result, "queues", "queue-family count exceeds 16");
        return;
    }
    returned = reported;
    if (reported != 0U) {
        memset(families, 0, sizeof(families));
        if (!triangle_call_begin(result, &before)) return;
        get_families(physical, &returned, families);
        if (!triangle_call_end(result, before)) return;
        result->queue_fill_queried = 1;
        result->queue_returned_count = returned;
        result->queue_retained_count = returned > MAX_QUEUE_FAMILIES ? MAX_QUEUE_FAMILIES : returned;
        if (returned > MAX_QUEUE_FAMILIES || returned != reported) {
            triangle_set_failure(result, "queues", "queue-family count is incomplete or oversized");
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
    if (returned == 0U || !triangle_select_queue(families, returned, &index, &kind)) {
        triangle_set_failure(result, "no_queue", "no complete eligible queue family");
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
    if (!triangle_call_begin(result, &before)) return;
    result->device_create_result = create_device(physical, &device_create, NULL, &device);
    {
        int create_timing_valid = triangle_call_end(result, before);
        triangle_record_result(result, result->device_create_result, "vkCreateDevice failed");
        if (result->device_create_result != VK_SUCCESS || device == VK_NULL_HANDLE) {
            if (result->device_create_result != VK_SUCCESS && device != VK_NULL_HANDLE) {
                result->device_handle_returned_on_error = 1;
                triangle_set_failure(result, "vulkan", "vkCreateDevice returned a handle with an error");
                device = VK_NULL_HANDLE;
            } else if (result->device_create_result == VK_SUCCESS) {
                triangle_set_failure(result, "vulkan", "vkCreateDevice returned null");
            }
            return;
        }
        result->device_created = 1;
        result->device_live = 1;
        if (!create_timing_valid) goto triangle_cleanup;
    }

    {
        TriangleFunction gdpa_destroy;
        memset(&gdpa_destroy, 0, sizeof(gdpa_destroy));
        gdpa_destroy.name = "vkDestroyDevice";
        if (!triangle_observe_function(&gdpa_destroy, gdpa(device, "vkDestroyDevice"), owner)) {
            triangle_set_failure(result, "owner", "device vkDestroyDevice owner mismatch");
            goto triangle_cleanup;
        }
        destroy_device = (PFN_vkDestroyDevice)(uintptr_t)gdpa_destroy.address;
    }
    if (!triangle_resolve_device_function(result, gdpa, device, owner, 10U, "vkGetDeviceQueue")) {
        triangle_set_failure(result, "owner", "device vkGetDeviceQueue owner mismatch");
        goto triangle_cleanup;
    }
    if (result->functions[10U].owner_exact) {
        get_queue = (PFN_vkGetDeviceQueue)(uintptr_t)result->functions[10U].address;
        result->queue_get_attempted = 1;
        result->queue_index = 0U;
        if (!triangle_call_begin(result, &before)) goto triangle_cleanup;
        get_queue(device, index, 0U, &queue);
        if (!triangle_call_end(result, before)) goto triangle_cleanup;
        result->queue_non_null = queue != VK_NULL_HANDLE;
        result->queue_observed = result->queue_non_null;
        if (!result->queue_non_null) {
            triangle_set_failure(result, "queue", "vkGetDeviceQueue returned null");
            goto triangle_cleanup;
        }
    }
    if (triangle_failure_is(result, "none") && queue != VK_NULL_HANDLE) {
        (void)triangle_workload(result, gdpa, physical, device, queue, index, owner);
    }
    goto triangle_cleanup;

triangle_cleanup:
    if (triangle_in_flight(result)) {
        return;
    }
    if (destroy_device != NULL && result->device_live) {
        int cleanup_timing_started;
        result->device_destroy_attempted = 1;
        cleanup_timing_started = triangle_call_begin(result, &before);
        destroy_device(device, NULL);
        if (cleanup_timing_started) (void)triangle_call_end(result, before);
        result->device_destroy_ok = 1;
        result->device_live = 0;
    }
    return;
}

static MAYBE_UNUSED int triangle_target_properties(TriangleResult *result,
                                                   PFN_vkGetPhysicalDeviceProperties get_properties,
                                                   VkPhysicalDevice physical) {
    VkPhysicalDeviceProperties properties;
    uint64_t before;
    memset(&properties, 0, sizeof(properties));
    if (!triangle_call_begin(result, &before)) return 0;
    get_properties(physical, &properties);
    if (!triangle_call_end(result, before)) return 0;
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
        triangle_set_failure(result, "physical", "physical device is not the measured Mali-G610 target");
    return result->physical_target_exact;
}

static MAYBE_UNUSED int triangle_emit(TriangleResult *result, char *output, size_t capacity) {
    size_t length;
    triangle_classify(result);
    if (!triangle_encode_result(result, output, capacity)) {
        if (!triangle_encode_unknown_fallback(result, output, capacity)) return 0;
        triangle_classify(result);
        result->status = "UNKNOWN";
        result->native_exit = 1;
        if (!triangle_encode_unknown_fallback(result, output, capacity)) return 0;
    }
    length = strlen(output);
    if (length >= capacity || fwrite(output, 1U, length, stdout) != length ||
        fputc('\n', stdout) == EOF || fflush(stdout) != 0) return 0;
    return 1;
}

#ifndef PANVK_TRIANGLE_HOST_TEST
static int triangle_invalid_invocation(void) {
    TriangleResult result;
    char output[TRIANGLE_OUTPUT_LIMIT];
    triangle_init_result(&result, "");
    result.monotonic_start = monotonic_now();
    result.monotonic_end = monotonic_now();
    result.status = "UNKNOWN";
    result.failure_stage = "arguments";
    copy_error(result.failure_detail, "expected --run-id PVK-<32 lowercase hexadecimal characters>");
    result.native_exit = 1;
    return triangle_encode_result(&result, output, sizeof(output)) &&
           fwrite(output, 1U, strlen(output), stdout) == strlen(output) &&
           fputc('\n', stdout) != EOF && fflush(stdout) == 0 ? 1 : 1;
}

static int triangle_native_main(const char *run_id) {
    TriangleResult result;
    char canonical_icd[PATH_LIMIT];
    void *handle = NULL;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    TriangleGetInstanceProcAddr gipa = NULL;
    TriangleNegotiate negotiate = NULL;
    PFN_vkDestroyInstance destroy_instance = NULL;
    PFN_vkEnumeratePhysicalDevices enumerate_physical = NULL;
    PFN_vkGetPhysicalDeviceProperties get_properties = NULL;
    uint32_t negotiated = 7U;
    uint32_t reported = 0U;
    uint32_t returned;
    VkPhysicalDevice physicals[MAX_PHYSICAL_DEVICES];
    uint64_t before;
    uint64_t end_time;
    char output[TRIANGLE_OUTPUT_LIMIT];
    size_t index;

    triangle_init_result(&result, run_id);
    result.monotonic_start = monotonic_now();
    result.clock_start_valid = result.monotonic_start > 0U;
    result.scope_valid = scope_valid(result.cwd, run_id);
    if (!result.scope_valid) triangle_set_failure(&result, "scope", "UID, CWD, run ID, or environment mismatch");
    if (!result.clock_start_valid) triangle_set_failure(&result, "clock", "monotonic start unavailable");
    if (result.scope_valid && path_join(result.icd_path, sizeof(result.icd_path), result.cwd, ICD_NAME)) {
        if (!canonical_path(result.icd_path, canonical_icd, sizeof(canonical_icd)) ||
            strcmp(canonical_icd, result.icd_path) != 0) {
            triangle_set_failure(&result, "identity", "ICD is not canonical");
        } else {
            result.binding_valid = 1;
            result.before_hash_valid = hash_file(result.icd_path, &result.before, result.before_sha256);
            if (result.before_hash_valid) {
                (void)copy_bounded(result.icd_sha256, sizeof(result.icd_sha256), result.before_sha256, 65U);
                result.icd_bytes = result.before.size;
            }
        }
    } else {
        triangle_set_failure(&result, "path", "ICD path exceeds bound");
    }
    if (!result.binding_valid || !result.before_hash_valid) {
        triangle_set_failure(&result, "hash", "ICD identity, size, owner, or SHA-256 mismatch");
    } else if (result.clock_start_valid) {
        (void)dlerror();
        result.load_attempted = 1;
        handle = dlopen(result.icd_path, RTLD_NOW | RTLD_LOCAL);
        if (handle == NULL) {
            copy_error(result.load_error, dlerror());
            triangle_set_failure(&result, "loader", result.load_error[0] != '\0' ? result.load_error : "dlopen failed");
        } else {
            result.load_loaded = 1;
            triangle_observe_exports(&result, handle, canonical_icd);
            for (index = 0U; index < 3U; ++index) {
                if (!result.exports[index].found || !result.exports[index].owner_exact)
                    triangle_set_failure(&result, "export", "required ICD export missing or owner mismatch");
            }
            gipa = (TriangleGetInstanceProcAddr)(uintptr_t)dlsym(handle, export_names[0]);
            negotiate = (TriangleNegotiate)(uintptr_t)dlsym(handle, export_names[2]);
            if (gipa == NULL || negotiate == NULL || !result.exports[0].owner_exact ||
                !result.exports[2].owner_exact) {
                triangle_set_failure(&result, "export", "required ICD entrypoint missing or owner mismatch");
            } else {
                uint64_t call_before;
                result.interface_input = 7;
                if (triangle_call_begin(&result, &call_before)) {
                    result.interface_result = negotiate(&negotiated);
                    (void)triangle_call_end(&result, call_before);
                } else result.interface_result = VK_ERROR_INITIALIZATION_FAILED;
                result.interface_output = (int)negotiated;
                triangle_record_result(&result, (VkResult)result.interface_result, "ICD interface negotiation failed");
                result.interface_supported = result.interface_result == VK_SUCCESS && negotiated >= 2U && negotiated <= 7U;
                if (!result.interface_supported) triangle_set_failure(&result, "interface", "unsupported ICD interface");
            }
            if (gipa != NULL && result.interface_supported && triangle_failure_is(&result, "none")) {
                PFN_vkVoidFunction version_address = gipa(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
                result.enumerate_instance_version_found = version_address != NULL;
                if (version_address == NULL) {
                    result.legacy_api_10 = 1;
                    result.enumerate_instance_version_owner = 1;
                    result.enumerate_instance_version_result = VK_SUCCESS;
                } else {
                    uint64_t call_before;
                    result.enumerate_instance_version_owner = triangle_observe_function(&result.functions[0], version_address, canonical_icd);
                    if (!result.enumerate_instance_version_owner) {
                        triangle_set_failure(&result, "owner", "instance-version PFN owner mismatch");
                    } else if (triangle_call_begin(&result, &call_before)) {
                        uint32_t version = 0U;
                        PFN_vkEnumerateInstanceVersion version_fn = (PFN_vkEnumerateInstanceVersion)(uintptr_t)version_address;
                        result.enumerate_instance_version_result = version_fn(&version);
                        (void)triangle_call_end(&result, call_before);
                        result.api_reported = version;
                        triangle_record_result(&result, result.enumerate_instance_version_result, "instance API version query failed");
                        if (result.enumerate_instance_version_result != VK_SUCCESS || VK_API_VERSION_VARIANT(version) != 0U ||
                            VK_API_VERSION_MAJOR(version) != 1U || version < VK_API_VERSION_1_0)
                            triangle_set_failure(&result, "api", "invalid instance API version");
                    }
                }
                result.api_requested = result.api_reported;
                if (VK_API_VERSION_MAJOR(result.api_requested) > 1U ||
                    (VK_API_VERSION_MAJOR(result.api_requested) == 1U && VK_API_VERSION_MINOR(result.api_requested) > 3U))
                    result.api_requested = VK_API_VERSION_1_3;
                if (triangle_failure_is(&result, "none")) {
                    PFN_vkVoidFunction create_address = gipa(VK_NULL_HANDLE, "vkCreateInstance");
                    VkApplicationInfo app;
                    VkInstanceCreateInfo create;
                    if (!triangle_observe_function(&result.functions[1], create_address, canonical_icd)) {
                        triangle_set_failure(&result, "owner", "vkCreateInstance PFN owner mismatch");
                    } else {
                        memset(&app, 0, sizeof(app));
                        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
                        app.pApplicationName = "PanvkTriangle";
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
                        if (triangle_call_begin(&result, &before)) {
                            PFN_vkCreateInstance create_instance = (PFN_vkCreateInstance)(uintptr_t)create_address;
                            result.instance_create_result = create_instance(&create, NULL, &instance);
                            (void)triangle_call_end(&result, before);
                        } else result.instance_create_result = VK_ERROR_INITIALIZATION_FAILED;
                        triangle_record_result(&result, result.instance_create_result, "vkCreateInstance failed");
                        if (result.instance_create_result != VK_SUCCESS && instance != VK_NULL_HANDLE) {
                            result.instance_handle_returned_on_error = 1;
                            instance = VK_NULL_HANDLE;
                            triangle_set_failure(&result, "vulkan", "vkCreateInstance returned a handle with an error");
                        } else if (result.instance_create_result == VK_SUCCESS && instance == VK_NULL_HANDLE) {
                            triangle_set_failure(&result, "vulkan", "vkCreateInstance returned null");
                        } else if (result.instance_create_result == VK_SUCCESS) {
                            result.instance_created = 1;
                        }
                    }
                }
            }
            if (instance != VK_NULL_HANDLE && result.instance_created) {
                PFN_vkVoidFunction destroy_address = gipa(instance, "vkDestroyInstance");
                result.functions[2].name = "vkDestroyInstance";
                if (!triangle_observe_function(&result.functions[2], destroy_address, canonical_icd)) {
                    triangle_set_failure(&result, "owner", "vkDestroyInstance PFN owner mismatch");
                } else {
                    destroy_instance = (PFN_vkDestroyInstance)(uintptr_t)destroy_address;
                }
                if (destroy_instance != NULL && result.functions[2].owner_exact &&
                    triangle_failure_is(&result, "none")) {
                    PFN_vkVoidFunction enumerate_address = gipa(instance, "vkEnumeratePhysicalDevices");
                    PFN_vkVoidFunction properties_address = gipa(instance, "vkGetPhysicalDeviceProperties");
                    if (!triangle_observe_function(&result.functions[3], enumerate_address, canonical_icd) ||
                        !triangle_observe_function(&result.functions[4], properties_address, canonical_icd)) {
                        triangle_set_failure(&result, "owner", "required physical-device PFN owner mismatch");
                    } else {
                        enumerate_physical = (PFN_vkEnumeratePhysicalDevices)(uintptr_t)enumerate_address;
                        get_properties = (PFN_vkGetPhysicalDeviceProperties)(uintptr_t)properties_address;
                        if (triangle_call_begin(&result, &before)) {
                            result.physical_count_result = enumerate_physical(instance, &reported, NULL);
                            (void)triangle_call_end(&result, before);
                        } else result.physical_count_result = VK_ERROR_INITIALIZATION_FAILED;
                        result.physical_reported_count = reported;
                        triangle_record_result(&result, result.physical_count_result, "physical-device count failed");
                        if (result.physical_count_result == VK_SUCCESS && triangle_failure_is(&result, "none")) {
                            if (reported > MAX_PHYSICAL_DEVICES) {
                                triangle_set_failure(&result, "physical", "physical-device count exceeds 8");
                            } else if (reported != 0U) {
                                returned = reported;
                                memset(physicals, 0, sizeof(physicals));
                                if (triangle_call_begin(&result, &before)) {
                                    result.physical_fill_result = enumerate_physical(instance, &returned, physicals);
                                    (void)triangle_call_end(&result, before);
                                } else result.physical_fill_result = VK_ERROR_INITIALIZATION_FAILED;
                                result.physical_returned_count = returned;
                                triangle_record_result(&result, result.physical_fill_result, "physical-device fill failed");
                                result.physical_complete = result.physical_fill_result == VK_SUCCESS && returned == reported;
                                if (!result.physical_complete)
                                    triangle_set_failure(&result, "physical", "physical-device count is incomplete");
                                if (returned > MAX_PHYSICAL_DEVICES) returned = MAX_PHYSICAL_DEVICES;
                                result.physical_retained_count = returned;
                                if (result.physical_complete && returned == 1U && physicals[0] != VK_NULL_HANDLE &&
                                    triangle_failure_is(&result, "none")) {
                                    physical = physicals[0];
                                    (void)triangle_target_properties(&result, get_properties, physical);
                                    if (result.physical_target_exact)
                                        triangle_control(&result, gipa, instance, physical, canonical_icd);
                                } else if (returned != 1U) {
                                    triangle_set_failure(&result, "physical", "exactly one physical device required");
                                } else {
                                    triangle_set_failure(&result, "physical", "physical-device handle is null");
                                }
                            } else {
                                result.physical_complete = 1;
                                triangle_set_failure(&result, "physical", "zero physical devices");
                            }
                        }
                    }
                }
            }
        }
    }
    if (instance != VK_NULL_HANDLE && result.instance_created && !result.device_live &&
        !triangle_in_flight(&result) && destroy_instance != NULL &&
        result.functions[2].owner_exact) {
        result.instance_destroy_attempted = 1;
        {
            int cleanup_timing_started = triangle_call_begin(&result, &before);
            destroy_instance(instance, NULL);
            if (cleanup_timing_started) (void)triangle_call_end(&result, before);
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
            triangle_set_failure(&result, "identity", "ICD changed or failed post-observation verification");
    }
    if (handle != NULL && instance == VK_NULL_HANDLE && !result.device_live &&
        !triangle_in_flight(&result)) {
        result.dlclose_attempted = 1;
        result.dlclose_ok = dlclose(handle) == 0;
        if (!result.dlclose_ok) triangle_set_failure(&result, "cleanup", "dlclose failed");
    } else if (handle == NULL) {
        result.dlclose_ok = 0;
    }
    result.cleanup_complete = instance == VK_NULL_HANDLE && !result.device_live &&
        !triangle_in_flight(&result) && (handle == NULL || result.dlclose_ok);
    end_time = monotonic_now();
    result.monotonic_end = end_time;
    result.clock_valid = result.clock_start_valid && end_time > 0U && end_time >= result.monotonic_start;
    result.timing_valid = result.timing_valid && result.clock_valid;
    if (!result.clock_valid) triangle_set_failure(&result, "clock", "monotonic end unavailable or reversed");
    if (!triangle_emit(&result, output, sizeof(output))) return 1;
    return result.native_exit;
}

int main(int argc, char **argv) {
    if (argc != 3 || argv == NULL || argv[1] == NULL || argv[2] == NULL ||
        strcmp(argv[1], "--run-id") != 0 || !valid_run_id(argv[2])) return triangle_invalid_invocation();
    return triangle_native_main(argv[2]);
}
#endif

#ifdef PANVK_TRIANGLE_HOST_TEST
static int fake_case;
static int fake_create_called;
static int fake_destroy_called;
static int fake_submit_count;
static int fake_wait_count;
static int fake_alloc_count;
static int fake_shader_count;
static uint32_t fake_selected_family;
static unsigned char fake_mapped[16384];
static int fake_mapped_valid;
#define MAX_FAKE_CALLS 160U
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
    families[0].queueFlags = fake_case == 31 ? VK_QUEUE_TRANSFER_BIT : VK_QUEUE_GRAPHICS_BIT;
    families[0].queueCount = 1U;
    families[1].queueFlags = fake_case == 31 ? VK_QUEUE_COMPUTE_BIT : VK_QUEUE_TRANSFER_BIT;
    families[1].queueCount = 1U;
}

static void VKAPI_PTR fake_get_mem_props(VkPhysicalDevice physical,
                                         VkPhysicalDeviceMemoryProperties *props) {
    (void)physical;
    fake_record_call("MEMPROPS");
    memset(props, 0, sizeof(*props));
    props->memoryTypeCount = 2U;
    if (fake_case == 4) {
        props->memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
        props->memoryTypes[1].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
        return;
    }
    if (fake_case == 30) {
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

static VkResult VKAPI_PTR fake_create_image(VkDevice device, const VkImageCreateInfo *info,
                                             const VkAllocationCallbacks *allocator,
                                             VkImage *image) {
    (void)device; (void)allocator;
    fake_record_call("IMG_CREATE");
    if (fake_case == 2) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    if (info == NULL || image == NULL || info->imageType != VK_IMAGE_TYPE_2D ||
        info->format != VK_FORMAT_R8G8B8A8_UNORM ||
        info->extent.width != TRIANGLE_EXTENT ||
        info->extent.height != TRIANGLE_EXTENT || info->extent.depth != 1U ||
        info->mipLevels != 1U || info->arrayLayers != 1U ||
        info->samples != VK_SAMPLE_COUNT_1_BIT ||
        info->tiling != VK_IMAGE_TILING_OPTIMAL ||
        info->usage != (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                        VK_IMAGE_USAGE_TRANSFER_SRC_BIT) ||
        info->sharingMode != VK_SHARING_MODE_EXCLUSIVE ||
        info->initialLayout != VK_IMAGE_LAYOUT_UNDEFINED) return VK_ERROR_INITIALIZATION_FAILED;
    *image = (VkImage)(uintptr_t)0x61U;
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_destroy_image(VkDevice device, VkImage image,
                                         const VkAllocationCallbacks *allocator) {
    (void)device; (void)image; (void)allocator; fake_record_call("IMG_DESTROY");
}

static void VKAPI_PTR fake_get_image_mem_req(VkDevice device, VkImage image,
                                             VkMemoryRequirements *req) {
    (void)device; (void)image;
    fake_record_call("IMG_MEMREQ");
    memset(req, 0, sizeof(*req));
    if (fake_case == 3) {
        req->size = (VkDeviceSize)33554432U;
        req->alignment = 256U;
        req->memoryTypeBits = 2U;
        return;
    }
    req->size = (VkDeviceSize)65536U;
    req->alignment = 256U;
    req->memoryTypeBits = 2U;
}

static VkResult VKAPI_PTR fake_alloc_memory(VkDevice device, const VkMemoryAllocateInfo *info,
                                            const VkAllocationCallbacks *allocator,
                                            VkDeviceMemory *memory) {
    int first;
    (void)device; (void)allocator;
    first = fake_alloc_count == 0;
    fake_record_call(first ? "IMG_ALLOC" : "STG_ALLOC");
    ++fake_alloc_count;
    if (info == NULL || memory == NULL) return VK_ERROR_INITIALIZATION_FAILED;
    if (first && fake_case == 5) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    if (!first && fake_case == 6) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    *memory = (VkDeviceMemory)(uintptr_t)(first ? 0x62U : 0x63U);
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_free_memory(VkDevice device, VkDeviceMemory memory,
                                       const VkAllocationCallbacks *allocator) {
    (void)device; (void)memory; (void)allocator; fake_record_call("MEM_FREE");
}

static VkResult VKAPI_PTR fake_bind_image_memory(VkDevice device, VkImage image,
                                                 VkDeviceMemory memory,
                                                 VkDeviceSize offset) {
    (void)device; (void)image; (void)memory; (void)offset;
    fake_record_call("IMG_BIND");
    if (fake_case == 7) return VK_ERROR_MEMORY_MAP_FAILED;
    return VK_SUCCESS;
}

static VkResult VKAPI_PTR fake_create_view(VkDevice device, const VkImageViewCreateInfo *info,
                                           const VkAllocationCallbacks *allocator,
                                           VkImageView *view) {
    (void)device; (void)allocator;
    fake_record_call("VIEW_CREATE");
    if (fake_case == 8) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    if (info == NULL || view == NULL || info->viewType != VK_IMAGE_VIEW_TYPE_2D ||
        info->format != VK_FORMAT_R8G8B8A8_UNORM ||
        info->subresourceRange.aspectMask != VK_IMAGE_ASPECT_COLOR_BIT) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    *view = (VkImageView)(uintptr_t)0x64U;
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_destroy_view(VkDevice device, VkImageView view,
                                        const VkAllocationCallbacks *allocator) {
    (void)device; (void)view; (void)allocator; fake_record_call("VIEW_DESTROY");
}

static VkResult VKAPI_PTR fake_create_render_pass(VkDevice device,
                                                  const VkRenderPassCreateInfo *info,
                                                  const VkAllocationCallbacks *allocator,
                                                  VkRenderPass *render_pass) {
    (void)device; (void)allocator;
    fake_record_call("RP_CREATE");
    if (fake_case == 9) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    if (info == NULL || render_pass == NULL || info->attachmentCount != 1U ||
        info->subpassCount != 1U || info->dependencyCount != 2U ||
        info->pDependencies == NULL) return VK_ERROR_INITIALIZATION_FAILED;
    if (info->pDependencies[0].srcSubpass != VK_SUBPASS_EXTERNAL ||
        info->pDependencies[0].dstSubpass != 0U ||
        info->pDependencies[0].srcStageMask != VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT ||
        info->pDependencies[0].dstStageMask != VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT ||
        info->pDependencies[0].srcAccessMask != 0U ||
        info->pDependencies[0].dstAccessMask != VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT ||
        info->pDependencies[0].dependencyFlags != 0U ||
        info->pDependencies[1].srcSubpass != 0U ||
        info->pDependencies[1].dstSubpass != VK_SUBPASS_EXTERNAL ||
        info->pDependencies[1].srcStageMask != VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT ||
        info->pDependencies[1].dstStageMask != VK_PIPELINE_STAGE_TRANSFER_BIT ||
        info->pDependencies[1].srcAccessMask != VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT ||
        info->pDependencies[1].dstAccessMask != VK_ACCESS_TRANSFER_READ_BIT ||
        info->pDependencies[1].dependencyFlags != 0U)
        return VK_ERROR_INITIALIZATION_FAILED;
    *render_pass = (VkRenderPass)(uintptr_t)0x65U;
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_destroy_render_pass(VkDevice device, VkRenderPass render_pass,
                                               const VkAllocationCallbacks *allocator) {
    (void)device; (void)render_pass; (void)allocator; fake_record_call("RP_DESTROY");
}

static VkResult VKAPI_PTR fake_create_framebuffer(VkDevice device,
                                                  const VkFramebufferCreateInfo *info,
                                                  const VkAllocationCallbacks *allocator,
                                                  VkFramebuffer *framebuffer) {
    (void)device; (void)allocator;
    fake_record_call("FB_CREATE");
    if (fake_case == 10) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    if (info == NULL || framebuffer == NULL || info->attachmentCount != 1U ||
        info->width != TRIANGLE_EXTENT || info->height != TRIANGLE_EXTENT ||
        info->layers != 1U) return VK_ERROR_INITIALIZATION_FAILED;
    *framebuffer = (VkFramebuffer)(uintptr_t)0x66U;
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_destroy_framebuffer(VkDevice device, VkFramebuffer framebuffer,
                                               const VkAllocationCallbacks *allocator) {
    (void)device; (void)framebuffer; (void)allocator; fake_record_call("FB_DESTROY");
}

static VkResult VKAPI_PTR fake_create_shader(VkDevice device,
                                             const VkShaderModuleCreateInfo *info,
                                             const VkAllocationCallbacks *allocator,
                                             VkShaderModule *module) {
    int first;
    (void)device; (void)allocator;
    first = fake_shader_count == 0;
    fake_record_call(first ? "SHADER_VERT" : "SHADER_FRAG");
    ++fake_shader_count;
    if (info == NULL || module == NULL || info->pCode == NULL || info->codeSize == 0U ||
        info->codeSize % 4U != 0U) return VK_ERROR_INITIALIZATION_FAILED;
    if (first && fake_case == 11) return VK_ERROR_INVALID_SHADER_NV;
    if (!first && fake_case == 12) return VK_ERROR_INVALID_SHADER_NV;
    *module = (VkShaderModule)(uintptr_t)(first ? 0x67U : 0x68U);
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_destroy_shader(VkDevice device, VkShaderModule module,
                                          const VkAllocationCallbacks *allocator) {
    (void)device; (void)module; (void)allocator; fake_record_call("SHADER_DESTROY");
}

static VkResult VKAPI_PTR fake_create_layout(VkDevice device,
                                             const VkPipelineLayoutCreateInfo *info,
                                             const VkAllocationCallbacks *allocator,
                                             VkPipelineLayout *layout) {
    (void)device; (void)allocator;
    fake_record_call("LAYOUT_CREATE");
    if (fake_case == 13) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    if (info == NULL || layout == NULL || info->setLayoutCount != 0U ||
        info->pushConstantRangeCount != 0U) return VK_ERROR_INITIALIZATION_FAILED;
    *layout = (VkPipelineLayout)(uintptr_t)0x69U;
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_destroy_layout(VkDevice device, VkPipelineLayout layout,
                                          const VkAllocationCallbacks *allocator) {
    (void)device; (void)layout; (void)allocator; fake_record_call("LAYOUT_DESTROY");
}

static VkResult VKAPI_PTR fake_create_pipelines(VkDevice device, VkPipelineCache cache,
                                                uint32_t count,
                                                const VkGraphicsPipelineCreateInfo *infos,
                                                const VkAllocationCallbacks *allocator,
                                                VkPipeline *pipelines) {
    (void)device; (void)cache; (void)allocator;
    fake_record_call("PIPELINE_CREATE");
    if (fake_case == 14) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    if (infos == NULL || pipelines == NULL || count != 1U ||
        infos->stageCount != 2U || infos->subpass != 0U) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    *pipelines = (VkPipeline)(uintptr_t)0x6AU;
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_destroy_pipeline(VkDevice device, VkPipeline pipeline,
                                            const VkAllocationCallbacks *allocator) {
    (void)device; (void)pipeline; (void)allocator; fake_record_call("PIPE_DESTROY");
}

static VkResult VKAPI_PTR fake_create_buffer(VkDevice device, const VkBufferCreateInfo *info,
                                             const VkAllocationCallbacks *allocator,
                                             VkBuffer *buffer) {
    (void)device; (void)allocator;
    fake_record_call("BUF_CREATE");
    if (fake_case == 15) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    if (info == NULL || buffer == NULL ||
        info->size != (VkDeviceSize)TRIANGLE_STAGING_SIZE ||
        info->usage != VK_BUFFER_USAGE_TRANSFER_DST_BIT ||
        info->sharingMode != VK_SHARING_MODE_EXCLUSIVE) return VK_ERROR_INITIALIZATION_FAILED;
    *buffer = (VkBuffer)(uintptr_t)0x51U;
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_destroy_buffer(VkDevice device, VkBuffer buffer,
                                          const VkAllocationCallbacks *allocator) {
    (void)device; (void)buffer; (void)allocator; fake_record_call("BUF_DESTROY");
}

static void VKAPI_PTR fake_get_buffer_mem_req(VkDevice device, VkBuffer buffer,
                                               VkMemoryRequirements *req) {
    (void)device; (void)buffer;
    fake_record_call("BUF_MEMREQ");
    memset(req, 0, sizeof(*req));
    if (fake_case == 16) {
        req->size = (VkDeviceSize)33554432U;
        req->alignment = 64U;
        req->memoryTypeBits = 1U;
        return;
    }
    req->size = (VkDeviceSize)TRIANGLE_STAGING_SIZE;
    req->alignment = 64U;
    req->memoryTypeBits = 1U;
}

static VkResult VKAPI_PTR fake_bind_buffer_memory(VkDevice device, VkBuffer buffer,
                                                  VkDeviceMemory memory,
                                                  VkDeviceSize offset) {
    (void)device; (void)buffer; (void)memory; (void)offset;
    fake_record_call("STG_BIND");
    if (fake_case == 17) return VK_ERROR_MEMORY_MAP_FAILED;
    return VK_SUCCESS;
}

static VkResult VKAPI_PTR fake_map_memory(VkDevice device, VkDeviceMemory memory,
                                          VkDeviceSize offset, VkDeviceSize size,
                                          VkMemoryMapFlags flags, void **data) {
    (void)device; (void)memory; (void)offset; (void)size; (void)flags;
    fake_record_call("MAP");
    if (fake_case == 18) {
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
    if (fake_case == 19) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
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
    if (fake_case == 20) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
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
    if (fake_case == 21) return VK_ERROR_INITIALIZATION_FAILED;
    return VK_SUCCESS;
}

static VkResult VKAPI_PTR fake_end_commands(VkCommandBuffer buffer) {
    (void)buffer;
    fake_record_call("END");
    if (fake_case == 22) return VK_ERROR_INITIALIZATION_FAILED;
    return VK_SUCCESS;
}

static void VKAPI_PTR fake_cmd_begin_rp(VkCommandBuffer buffer,
                                        const VkRenderPassBeginInfo *begin,
                                        VkSubpassContents contents) {
    (void)buffer; (void)begin; (void)contents;
    fake_record_call("BEGIN_RP");
}

static void VKAPI_PTR fake_cmd_bind_pipeline(VkCommandBuffer buffer,
                                             VkPipelineBindPoint bind_point,
                                             VkPipeline pipeline) {
    (void)buffer; (void)bind_point; (void)pipeline;
    fake_record_call("BIND_PIPE");
}

static void VKAPI_PTR fake_cmd_draw(VkCommandBuffer buffer, uint32_t vertex_count,
                                    uint32_t instance_count, uint32_t first_vertex,
                                    uint32_t first_instance) {
    (void)buffer; (void)vertex_count; (void)instance_count;
    (void)first_vertex; (void)first_instance;
    fake_record_call("DRAW");
}

static void VKAPI_PTR fake_cmd_end_rp(VkCommandBuffer buffer) {
    (void)buffer;
    fake_record_call("END_RP");
}

static void VKAPI_PTR fake_cmd_copy(VkCommandBuffer buffer, VkImage image,
                                    VkImageLayout layout, VkBuffer dst,
                                    uint32_t count, const VkBufferImageCopy *regions) {
    (void)buffer; (void)image; (void)layout; (void)dst;
    (void)count; (void)regions;
    fake_record_call("COPY");
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
    if (fake_case == 24) return VK_TIMEOUT;
    if (fake_case == 25) return VK_ERROR_DEVICE_LOST;
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
    if (fake_case == 23) return VK_ERROR_OUT_OF_DEVICE_MEMORY;
    if (fake_mapped_valid) {
        unsigned char *bytes = fake_mapped;
        for (index = 0U; index < TRIANGLE_PIXELS; ++index) {
            int triangle_pixel;
            if (fake_case == 26) {
                triangle_pixel = 0;
            } else if (fake_case == 27) {
                triangle_pixel = 1;
            } else {
                triangle_pixel = index >= TRIANGLE_PIXELS / 2U;
            }
            if (triangle_pixel) {
                bytes[index * 4U] = 255U;
                bytes[index * 4U + 1U] = 0U;
                bytes[index * 4U + 2U] = 0U;
                bytes[index * 4U + 3U] = 255U;
            } else {
                bytes[index * 4U] = 0U;
                bytes[index * 4U + 1U] = 255U;
                bytes[index * 4U + 2U] = 0U;
                bytes[index * 4U + 3U] = 255U;
            }
        }
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
    if (fake_case == 28) {
        if (strcmp(name, "vkCreateImage") == 0) return NULL;
    }
    if (strcmp(name, "vkDestroyDevice") == 0) return (PFN_vkVoidFunction)fake_destroy_device;
    if (strcmp(name, "vkGetDeviceQueue") == 0) return (PFN_vkVoidFunction)fake_get_queue;
    if (strcmp(name, "vkCreateImage") == 0) return (PFN_vkVoidFunction)fake_create_image;
    if (strcmp(name, "vkGetImageMemoryRequirements") == 0) return (PFN_vkVoidFunction)fake_get_image_mem_req;
    if (strcmp(name, "vkAllocateMemory") == 0) return (PFN_vkVoidFunction)fake_alloc_memory;
    if (strcmp(name, "vkFreeMemory") == 0) return (PFN_vkVoidFunction)fake_free_memory;
    if (strcmp(name, "vkBindImageMemory") == 0) return (PFN_vkVoidFunction)fake_bind_image_memory;
    if (strcmp(name, "vkCreateImageView") == 0) return (PFN_vkVoidFunction)fake_create_view;
    if (strcmp(name, "vkDestroyImageView") == 0) return (PFN_vkVoidFunction)fake_destroy_view;
    if (strcmp(name, "vkDestroyImage") == 0) return (PFN_vkVoidFunction)fake_destroy_image;
    if (strcmp(name, "vkCreateRenderPass") == 0) return (PFN_vkVoidFunction)fake_create_render_pass;
    if (strcmp(name, "vkDestroyRenderPass") == 0) return (PFN_vkVoidFunction)fake_destroy_render_pass;
    if (strcmp(name, "vkCreateFramebuffer") == 0) return (PFN_vkVoidFunction)fake_create_framebuffer;
    if (strcmp(name, "vkDestroyFramebuffer") == 0) return (PFN_vkVoidFunction)fake_destroy_framebuffer;
    if (strcmp(name, "vkCreateShaderModule") == 0) return (PFN_vkVoidFunction)fake_create_shader;
    if (strcmp(name, "vkDestroyShaderModule") == 0) return (PFN_vkVoidFunction)fake_destroy_shader;
    if (strcmp(name, "vkCreatePipelineLayout") == 0) return (PFN_vkVoidFunction)fake_create_layout;
    if (strcmp(name, "vkDestroyPipelineLayout") == 0) return (PFN_vkVoidFunction)fake_destroy_layout;
    if (strcmp(name, "vkCreateGraphicsPipelines") == 0) return (PFN_vkVoidFunction)fake_create_pipelines;
    if (strcmp(name, "vkDestroyPipeline") == 0) return (PFN_vkVoidFunction)fake_destroy_pipeline;
    if (strcmp(name, "vkCreateBuffer") == 0) return (PFN_vkVoidFunction)fake_create_buffer;
    if (strcmp(name, "vkDestroyBuffer") == 0) return (PFN_vkVoidFunction)fake_destroy_buffer;
    if (strcmp(name, "vkGetBufferMemoryRequirements") == 0) return (PFN_vkVoidFunction)fake_get_buffer_mem_req;
    if (strcmp(name, "vkBindBufferMemory") == 0) return (PFN_vkVoidFunction)fake_bind_buffer_memory;
    if (strcmp(name, "vkMapMemory") == 0) return (PFN_vkVoidFunction)fake_map_memory;
    if (strcmp(name, "vkUnmapMemory") == 0) return (PFN_vkVoidFunction)fake_unmap_memory;
    if (strcmp(name, "vkInvalidateMappedMemoryRanges") == 0) return (PFN_vkVoidFunction)fake_invalidate_ranges;
    if (strcmp(name, "vkCreateCommandPool") == 0) return (PFN_vkVoidFunction)fake_create_pool;
    if (strcmp(name, "vkDestroyCommandPool") == 0) return (PFN_vkVoidFunction)fake_destroy_pool;
    if (strcmp(name, "vkAllocateCommandBuffers") == 0) return (PFN_vkVoidFunction)fake_alloc_commands;
    if (strcmp(name, "vkFreeCommandBuffers") == 0) return (PFN_vkVoidFunction)fake_free_commands;
    if (strcmp(name, "vkBeginCommandBuffer") == 0) return (PFN_vkVoidFunction)fake_begin_commands;
    if (strcmp(name, "vkEndCommandBuffer") == 0) return (PFN_vkVoidFunction)fake_end_commands;
    if (strcmp(name, "vkCmdBeginRenderPass") == 0) return (PFN_vkVoidFunction)fake_cmd_begin_rp;
    if (strcmp(name, "vkCmdBindPipeline") == 0) return (PFN_vkVoidFunction)fake_cmd_bind_pipeline;
    if (strcmp(name, "vkCmdDraw") == 0) return (PFN_vkVoidFunction)fake_cmd_draw;
    if (strcmp(name, "vkCmdEndRenderPass") == 0) return (PFN_vkVoidFunction)fake_cmd_end_rp;
    if (strcmp(name, "vkCmdCopyImageToBuffer") == 0) return (PFN_vkVoidFunction)fake_cmd_copy;
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

int panvk_triangle_test_fake(const char *case_name, char *output, size_t capacity) {
    TriangleResult result;
    const char *owner = "/fake/icd/libvulkan_panfrost.so";
    if (case_name == NULL || output == NULL) return 0;
    fake_case = 0;
    if (strcmp(case_name, "pass") == 0) fake_case = 0;
    else if (strcmp(case_name, "pass-nocoherent") == 0) fake_case = 30;
    else if (strcmp(case_name, "create-error") == 0) fake_case = 1;
    else if (strcmp(case_name, "image-error") == 0) fake_case = 2;
    else if (strcmp(case_name, "image-requirements-cap") == 0) fake_case = 3;
    else if (strcmp(case_name, "no-memory-type") == 0) fake_case = 4;
    else if (strcmp(case_name, "image-alloc-error") == 0) fake_case = 5;
    else if (strcmp(case_name, "staging-alloc-error") == 0) fake_case = 6;
    else if (strcmp(case_name, "image-bind-error") == 0) fake_case = 7;
    else if (strcmp(case_name, "view-error") == 0) fake_case = 8;
    else if (strcmp(case_name, "renderpass-error") == 0) fake_case = 9;
    else if (strcmp(case_name, "framebuffer-error") == 0) fake_case = 10;
    else if (strcmp(case_name, "vert-shader-error") == 0) fake_case = 11;
    else if (strcmp(case_name, "frag-shader-error") == 0) fake_case = 12;
    else if (strcmp(case_name, "pipeline-layout-error") == 0) fake_case = 13;
    else if (strcmp(case_name, "pipeline-error") == 0) fake_case = 14;
    else if (strcmp(case_name, "staging-buffer-error") == 0) fake_case = 15;
    else if (strcmp(case_name, "staging-requirements-cap") == 0) fake_case = 16;
    else if (strcmp(case_name, "staging-bind-error") == 0) fake_case = 17;
    else if (strcmp(case_name, "map-null") == 0) fake_case = 18;
    else if (strcmp(case_name, "pool-error") == 0) fake_case = 19;
    else if (strcmp(case_name, "cmd-alloc-error") == 0) fake_case = 20;
    else if (strcmp(case_name, "begin-error") == 0) fake_case = 21;
    else if (strcmp(case_name, "end-error") == 0) fake_case = 22;
    else if (strcmp(case_name, "submit-error") == 0) fake_case = 23;
    else if (strcmp(case_name, "fence-timeout") == 0) fake_case = 24;
    else if (strcmp(case_name, "wait-error") == 0) fake_case = 25;
    else if (strcmp(case_name, "clear-only") == 0) fake_case = 26;
    else if (strcmp(case_name, "full-cover") == 0) fake_case = 27;
    else if (strcmp(case_name, "owner") == 0) fake_case = 28;
    else if (strcmp(case_name, "clock-after-create") == 0) fake_case = 29;
    else if (strcmp(case_name, "transfer-only") == 0) fake_case = 31;
    else return 0;
    fake_owner_mode = 1;
    fake_clock_fail_after_create = fake_case == 29;
    fake_create_called = 0; fake_destroy_called = 0;
    fake_submit_count = 0; fake_wait_count = 0;
    fake_alloc_count = 0; fake_shader_count = 0;
    fake_mapped_valid = 0;
    memset(fake_mapped, 0, sizeof(fake_mapped));
    fake_call_count = 0U; memset(fake_calls, 0, sizeof(fake_calls));
    triangle_init_result(&result, "PVK-0123456789abcdef0123456789abcdef");
    result.scope_valid = 1; result.binding_valid = 1; result.identity_stable = 1;
    result.load_loaded = 1; result.interface_supported = 1; result.instance_created = 1;
    result.clock_start_valid = 1; result.clock_valid = 1; result.timing_valid = 1;
    result.physical_complete = 1; result.physical_returned_count = 1;
    result.physical_target_exact = 1; result.physical_properties_valid = 1;
    result.physical_vendor_id = EXPECTED_VENDOR; result.physical_device_id = EXPECTED_DEVICE;
    (void)copy_bounded(result.physical_name, sizeof(result.physical_name), EXPECTED_NAME, sizeof(EXPECTED_NAME));
    triangle_control(&result, fake_gipa, (VkInstance)(uintptr_t)1U,
                     (VkPhysicalDevice)(uintptr_t)2U, owner);
    if (!triangle_in_flight(&result)) {
        result.instance_destroy_ok = 1; result.dlclose_ok = 1;
        result.cleanup_complete = !result.device_live;
    } else {
        result.cleanup_complete = 0;
    }
    result.after_hash_valid = result.before_hash_valid = result.identity_stable;
    triangle_classify(&result);
    return triangle_encode_result(&result, output, capacity);
}

int panvk_triangle_test_submit_counts(int *submit, int *wait) {
    if (submit != NULL) *submit = fake_submit_count;
    if (wait != NULL) *wait = fake_wait_count;
    return fake_submit_count == 1 && fake_wait_count == 1;
}

int panvk_triangle_test_call_sequence(char *output, size_t capacity) {
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

int panvk_triangle_test_encode_oversized_fallback(char *output, size_t capacity) {
    TriangleResult result;
    size_t index;
    triangle_init_result(&result, "PVK-0123456789abcdef0123456789abcdef");
    result.scope_valid = 1; result.binding_valid = 1; result.identity_stable = 1;
    result.load_loaded = 1; result.load_attempted = 1;
    result.status = "TRIANGLE_OFFSCREEN_OBSERVED";
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
    triangle_classify(&result);
    if (triangle_encode_result(&result, output, capacity)) return 0;
    return triangle_encode_unknown_fallback(&result, output, capacity);
}
#endif
