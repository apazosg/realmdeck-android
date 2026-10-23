#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

/*
 * A deliberately small Android WSI control.  This is a JNI library, not a
 * Vulkan loader: the ICD is authenticated before dlopen and every Vulkan PFN
 * is obtained from that ICD's GIPA/GDPA chain.  The host-test section at the
 * end exercises policy and encoding without loading Android or an ICD.
 */
#include <stdatomic.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#ifndef PANVK_WSI_HOST_TEST
#include <jni.h>
#endif
#include <limits.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#ifndef PANVK_WSI_HOST_TEST
#include <android/native_window.h>
#include <android/native_window_jni.h>
#else
typedef struct ANativeWindow { int32_t width; int32_t height; } ANativeWindow;
#endif

#include "/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include/vulkan/vulkan_core.h"
#include "/home/adrian/wowforever-artifacts/integrated-runtime/probe-build/app/src/main/cpp/vortekrenderer/include/vulkan/vulkan_android.h"
#include "triangle_spirv.h"

/* The control keeps compact, auditable one-call guards in a few hot paths. */
#if defined(__GNUC__) || defined(__clang__)
#pragma GCC diagnostic ignored "-Wmisleading-indentation"
#endif

#define WSI_SCHEMA "wowforever.android-panvk-wsi"
#define WSI_VERSION 1
#define EXPECTED_UID 10330U
#define RUN_PREFIX "PWSI-"
#define RUN_LENGTH 37U
#define PATH_LIMIT 4096U
#define ERROR_LIMIT 256U
#define JSON_LIMIT (64U * 1024U)
#define JOURNAL_LIMIT (64U * 1024U)
#define JOURNAL_ROWS 256U
#define MAX_CALLS 96U
#define MAX_PFNS 64U
#define ICD_NAME "libvulkan_panfrost.so"
#define ICD_SIZE UINT64_C(31142504)
#define ICD_SHA256 "5d9fdb881a66ddcd62dc06d3f6d9fe9c89b6740aaa94e3d7181d1dabab8284f9"
#define WIDTH 320U
#define HEIGHT 240U
#define MAX_EXTENSIONS 256U
#define MAX_FORMATS 32U
#define MAX_MODES 16U
#define MAX_IMAGES 4U
#define MAX_FRAMES 3U
#define VK_API_VERSION_1_0_LOCAL VK_MAKE_VERSION(1, 0, 0)
#define PROC_NAME "com.wowforever.arm64probe:panvk_wsi"
#ifndef O_CLOEXEC
#define O_CLOEXEC 0
#endif
#ifndef O_NOFOLLOW
#define O_NOFOLLOW 0
#endif

typedef PFN_vkVoidFunction (VKAPI_PTR *WsiGipa)(VkInstance, const char *);
typedef PFN_vkVoidFunction (VKAPI_PTR *WsiGdpa)(VkDevice, const char *);
typedef struct Sha256 {
    uint32_t h[8];
    uint64_t bits;
    unsigned char block[64];
    size_t used;
} Sha256;

static _Atomic uint64_t g_cancelled_generation = 0U;
#ifdef PANVK_WSI_HOST_TEST
static int g_host_window_releases = 0;
#endif
#ifndef PANVK_WSI_HOST_TEST
static _Atomic uint64_t g_started_generation = 0U;
#endif
#ifndef PANVK_WSI_HOST_TEST
static atomic_flag g_worker = ATOMIC_FLAG_INIT;
#endif

static const uint32_t sha_k[64] = {
    0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
    0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
    0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
    0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
    0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
    0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
    0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
    0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
    0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
    0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
    0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
    0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
    0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
    0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
    0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
    0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U
};

static uint32_t sha_rotr(uint32_t x, unsigned n) { return (x >> n) | (x << (32U - n)); }

static void sha_init(Sha256 *s) {
    static const uint32_t initial[8] = { 0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U,
        0xa54ff53aU, 0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U };
    memcpy(s->h, initial, sizeof(initial)); s->bits = 0U; s->used = 0U;
}

static void sha_block(Sha256 *s, const unsigned char *in) {
    uint32_t w[64], a, b, c, d, e, f, g, h;
    unsigned i;
    for (i = 0U; i < 16U; ++i) w[i] = ((uint32_t)in[4U*i] << 24U) |
        ((uint32_t)in[4U*i+1U] << 16U) | ((uint32_t)in[4U*i+2U] << 8U) | in[4U*i+3U];
    for (i = 16U; i < 64U; ++i) {
        uint32_t x = sha_rotr(w[i-15U], 7U) ^ sha_rotr(w[i-15U], 18U) ^ (w[i-15U] >> 3U);
        uint32_t y = sha_rotr(w[i-2U], 17U) ^ sha_rotr(w[i-2U], 19U) ^ (w[i-2U] >> 10U);
        w[i] = w[i-16U] + x + w[i-7U] + y;
    }
    a=s->h[0]; b=s->h[1]; c=s->h[2]; d=s->h[3]; e=s->h[4]; f=s->h[5]; g=s->h[6]; h=s->h[7];
    for (i = 0U; i < 64U; ++i) {
        uint32_t s1=sha_rotr(e,6U)^sha_rotr(e,11U)^sha_rotr(e,25U), ch=(e&f)^((~e)&g);
        uint32_t s0=sha_rotr(a,2U)^sha_rotr(a,13U)^sha_rotr(a,22U), maj=(a&b)^(a&c)^(b&c);
        uint32_t t1=h+s1+ch+sha_k[i]+w[i], t2=s0+maj;
        h=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
    }
    s->h[0]+=a; s->h[1]+=b; s->h[2]+=c; s->h[3]+=d; s->h[4]+=e; s->h[5]+=f; s->h[6]+=g; s->h[7]+=h;
}

static void sha_update(Sha256 *s, const unsigned char *in, size_t n) {
    s->bits += (uint64_t)n * 8U;
    while (n != 0U) {
        size_t take = 64U - s->used; if (take > n) take = n;
        memcpy(s->block + s->used, in, take); s->used += take; in += take; n -= take;
        if (s->used == 64U) { sha_block(s, s->block); s->used = 0U; }
    }
}

static void sha_final(Sha256 *s, unsigned char out[32]) {
    uint64_t bits=s->bits; size_t i;
    s->block[s->used++] = 0x80U;
    while (s->used != 56U) { if (s->used == 64U) { sha_block(s,s->block); s->used=0U; } s->block[s->used++]=0U; }
    for (i=0U;i<8U;++i) s->block[56U+i]=(unsigned char)(bits >> (56U-8U*i));
    sha_block(s,s->block);
    for (i=0U;i<8U;++i) { out[4U*i]=(unsigned char)(s->h[i]>>24U); out[4U*i+1U]=(unsigned char)(s->h[i]>>16U); out[4U*i+2U]=(unsigned char)(s->h[i]>>8U); out[4U*i+3U]=(unsigned char)s->h[i]; }
}

static int copy_s(char *out, size_t cap, const char *in, size_t limit) {
    size_t n;
    if (out == NULL || cap == 0U || in == NULL) return 0;
    n = strnlen(in, limit + 1U); if (n > limit || n + 1U > cap) return 0;
    memcpy(out, in, n); out[n] = '\0'; return 1;
}

static void error_s(char out[ERROR_LIMIT + 1U], const char *in) {
    if (in == NULL) { out[0] = '\0'; return; }
    (void)copy_s(out, ERROR_LIMIT + 1U, in, ERROR_LIMIT);
}

static int valid_run(const char *run) {
    size_t i;
    if (run == NULL || strnlen(run, RUN_LENGTH + 1U) != RUN_LENGTH || strncmp(run, RUN_PREFIX, 5U) != 0) return 0;
    for (i=5U;i<RUN_LENGTH;++i) if (!((run[i]>='0'&&run[i]<='9')||(run[i]>='a'&&run[i]<='f'))) return 0;
    return 1;
}

static int scope_for(const char *path, const char *run) {
    static const char *const prefixes[] = {
        "/data/user/0/com.wowforever.arm64probe/files/forever/controls/panvk-wsi/",
        "/data/data/com.wowforever.arm64probe/files/forever/controls/panvk-wsi/"
    };
    size_t i;
    if (!valid_run(run) || path == NULL) return 0;
    for (i=0U;i<sizeof(prefixes)/sizeof(prefixes[0]);++i) {
        size_t n=strlen(prefixes[i]); if (strncmp(path,prefixes[i],n)==0 && strcmp(path+n,run)==0) return 1;
    }
    return 0;
}

static int exact_paths(const char *dir, const char *icd, const char *run) {
    char expected[PATH_LIMIT];
    if (!scope_for(dir, run) || snprintf(expected,sizeof(expected),"%s/%s",dir,ICD_NAME) <= 0) return 0;
    return icd != NULL && strcmp(icd,expected)==0;
}

static int canonical_scope_valid(const char *dir, const char *canonical_dir,
                                 const char *icd, const char *canonical_icd,
                                 const char *run) {
    /* Java may preserve /data/data while Bionic resolves /data/user/0. Both
       authorities must be the exact same package/RUN and fixed leaf. */
    return scope_for(dir,run) && scope_for(canonical_dir,run) &&
           exact_paths(dir,icd,run) && exact_paths(canonical_dir,canonical_icd,run);
}

static int derive_icd_dir(char *dir,size_t capacity,const char *icd,const char *run,const char *journal){
    char expected[PATH_LIMIT], output[PATH_LIMIT]; const char *slash;
    if(dir==NULL||icd==NULL||run==NULL||journal==NULL)return 0;
    slash=strrchr(icd,'/'); if(slash==NULL||slash==icd)return 0;
    if((size_t)(slash-icd)>=capacity)return 0;
    memcpy(dir,icd,(size_t)(slash-icd));dir[slash-icd]='\0';
    if(!scope_for(dir,run)||!exact_paths(dir,icd,run))return 0;
    if(snprintf(expected,sizeof(expected),"%s/native.jsonl",dir)<=0||snprintf(output,sizeof(output),"%s",journal)<=0)return 0;
    return strcmp(expected,output)==0;
}

static int proc_name_valid(void){
    char name[128];int fd;ssize_t n;
    fd=open("/proc/self/cmdline",O_RDONLY|O_CLOEXEC|O_NOFOLLOW);if(fd<0)return 0;
    n=read(fd,name,sizeof(name)-1U);(void)close(fd);if(n<=0)return 0;name[n]='\0';
    return strcmp(name,PROC_NAME)==0;
}

static uint64_t now_ns(void) {
    struct timespec t; if (clock_gettime(CLOCK_MONOTONIC,&t)!=0 || t.tv_sec<0 || t.tv_nsec<0) return 0U;
    return (uint64_t)t.tv_sec*UINT64_C(1000000000)+(uint64_t)t.tv_nsec;
}

static int hash_icd(const char *path, char hex[65], struct stat *identity) {
    static const char digits[]="0123456789abcdef"; unsigned char buf[65536U], digest[32];
    struct stat listed, opened; Sha256 sha; uint64_t total=0U; ssize_t got; int fd=-1, ok=0; size_t i;
    memset(hex,0,65U); if (identity!=NULL) memset(identity,0,sizeof(*identity));
    if (path==NULL || lstat(path,&listed)!=0 || !S_ISREG(listed.st_mode) || listed.st_uid!=(uid_t)EXPECTED_UID || listed.st_size!=(off_t)ICD_SIZE) return 0;
    fd=open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW); if (fd<0 || fstat(fd,&opened)!=0) goto done;
    if (!S_ISREG(opened.st_mode)||opened.st_uid!=(uid_t)EXPECTED_UID||opened.st_dev!=listed.st_dev||opened.st_ino!=listed.st_ino||opened.st_size!=listed.st_size||(opened.st_mode&07777U)!=(listed.st_mode&07777U)) goto done;
    sha_init(&sha);
    for (;;) { got=read(fd,buf,sizeof(buf)); if (got==0) break; if (got<0) { if(errno==EINTR) continue; goto done; } if ((uint64_t)got>ICD_SIZE-total) goto done; sha_update(&sha,buf,(size_t)got); total+=(uint64_t)got; }
    if (fstat(fd,&opened)!=0 || total!=ICD_SIZE || opened.st_dev!=listed.st_dev || opened.st_ino!=listed.st_ino || opened.st_size!=listed.st_size || opened.st_uid!=listed.st_uid) goto done;
    sha_final(&sha,digest); for(i=0U;i<32U;++i){hex[2U*i]=digits[digest[i]>>4U];hex[2U*i+1U]=digits[digest[i]&15U];} hex[64]='\0';
    if (identity!=NULL) *identity=opened;
    ok=strcmp(hex,ICD_SHA256)==0;
done: if(fd>=0 && close(fd)!=0) ok=0; return ok;
}

typedef struct Json { char *p; size_t cap, used; int ok; } Json;
static int ja(Json *j,const char *fmt,...) { va_list ap; int n; if(j==NULL||!j->ok||j->used>=j->cap){if(j!=NULL)j->ok=0;return 0;} va_start(ap,fmt); n=vsnprintf(j->p+j->used,j->cap-j->used,fmt,ap); va_end(ap); if(n<0||(size_t)n>=j->cap-j->used){j->ok=0;return 0;} j->used+=(size_t)n;return 1; }
static int js(Json *j,const char *s,size_t limit) { static const char hex[]="0123456789abcdef"; size_t i; if(!ja(j,"\""))return 0; if(s!=NULL)for(i=0U;i<limit&&s[i]!='\0';++i){unsigned char c=(unsigned char)s[i]; if(c=='"'||c=='\\'){if(!ja(j,"\\%c",c))return 0;}else if(c=='\n'){if(!ja(j,"\\n"))return 0;}else if(c=='\r'){if(!ja(j,"\\r"))return 0;}else if(c=='\t'){if(!ja(j,"\\t"))return 0;}else if(c<0x20U||c>=0x80U){if(!ja(j,"\\u00%c%c",hex[c>>4U],hex[c&15U]))return 0;}else if(!ja(j,"%c",c))return 0;}return ja(j,"\""); }
static int jb(Json *j,int v){return ja(j,v?"true":"false");}

typedef struct Call {
    char stage[ERROR_LIMIT + 1U];
    int result;
} Call;

typedef struct PfnRecord {
    char name[ERROR_LIMIT + 1U];
    char path[PATH_LIMIT];
    int owner_exact;
} PfnRecord;

typedef struct FrameResult {
    int acquire_called, submit_called, present_called, fence_called;
    uint32_t image_index;
    VkResult acquire_result, submit_result, present_result, fence_result;
} FrameResult;

typedef struct WsiResult {
    char run[RUN_LENGTH + 1U], icd[PATH_LIMIT], icd_sha[65], after_sha[65];
    char owner_before[PATH_LIMIT], owner_after[PATH_LIMIT], owner[PATH_LIMIT];
    char status[32], failure_stage[64], failure[ERROR_LIMIT + 1U];
    int pid, uid, scope_valid, proc_valid, load_attempted, load_loaded, cancelled;
    int native_exit, output_valid, window_owned, surface_created, instance_created, device_created;
    int surface_width, surface_height, queue_family, image_count, frame_count;
    int cleanup_complete, unknown_retained, queue_idle_called, queue_retired, journal_io_failed, call_overflow, clock_valid, identity_stable;
    int present_success_count, instance_called, surface_called, device_called, swapchain_called, idle_called;
    int physical_count, queue_family_count, device_extension_count;
    uint32_t surface_min_images, surface_max_images, surface_min_width, surface_min_height;
    uint32_t surface_max_width, surface_max_height, surface_current_width, surface_current_height;
    VkImageUsageFlags surface_usage; VkSurfaceTransformFlagsKHR surface_transforms;
    VkCompositeAlphaFlagsKHR surface_alpha;
    uint64_t generation, start_ns, end_ns, icd_bytes;
    VkResult last_result, instance_result, surface_result, device_result, swapchain_result, idle_result;
    VkFormat selected_format; VkColorSpaceKHR selected_colorspace;
    VkSurfaceTransformFlagBitsKHR pre_transform; VkCompositeAlphaFlagBitsKHR composite_alpha;
    uint32_t physical_vendor, physical_device; char physical_name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE];
    VkQueueFlags queue_flags; VkBool32 queue_surface_support;
    uint32_t instance_extensions, format_count, mode_count;
    Call calls[MAX_CALLS]; size_t call_count;
    PfnRecord pfns[MAX_PFNS]; size_t pfn_count;
    FrameResult frames[MAX_FRAMES];
    int journal_fd, journal_rows; size_t journal_bytes;
} WsiResult;

static void failure(WsiResult *r,const char *stage,const char *detail){if(r->failure_stage[0]=='\0'||strcmp(r->failure_stage,"none")==0){copy_s(r->failure_stage,sizeof(r->failure_stage),stage,63U);error_s(r->failure,detail);}}
static int is_cancelled(uint64_t generation){return atomic_load_explicit(&g_cancelled_generation,memory_order_acquire)>=generation;}

static void call_record(WsiResult *r,const char *stage,VkResult result){if(r->call_count<MAX_CALLS){copy_s(r->calls[r->call_count].stage,sizeof(r->calls[r->call_count].stage),stage,ERROR_LIMIT);r->calls[r->call_count].result=(int)result;++r->call_count;}else{r->call_overflow=1;failure(r,"calls","lifecycle call witness capacity exceeded");}r->last_result=result;}

static void journal_line(WsiResult *r,const char *event,const char *stage,int result,int has_result){
    char line[1024]; int n;
    if(r->journal_io_failed)return;
    if(r->journal_fd<0){r->journal_io_failed=1;failure(r,"journal","journal is not open");return;}
    if(r->journal_rows>=(int)JOURNAL_ROWS||r->journal_bytes>=JOURNAL_LIMIT){r->journal_io_failed=1;failure(r,"journal","journal quota exceeded");return;}
    if(has_result)n=snprintf(line,sizeof(line),"{\"schema\":\"%s\",\"version\":1,\"runId\":\"%s\",\"pid\":%d,\"generation\":%" PRIu64 ",\"event\":\"%s\",\"callStage\":\"%s\",\"result\":%d}\n",WSI_SCHEMA,r->run,r->pid,r->generation,event,stage,result);
    else n=snprintf(line,sizeof(line),"{\"schema\":\"%s\",\"version\":1,\"runId\":\"%s\",\"pid\":%d,\"generation\":%" PRIu64 ",\"event\":\"%s\",\"callStage\":\"%s\",\"result\":null}\n",WSI_SCHEMA,r->run,r->pid,r->generation,event,stage);
    if(n<=0||(size_t)n>=sizeof(line)||(size_t)n>JOURNAL_LIMIT-r->journal_bytes){r->journal_io_failed=1;failure(r,"journal","journal line exceeds quota");return;}
    if(write(r->journal_fd,line,(size_t)n)!=n || fsync(r->journal_fd)!=0){r->journal_io_failed=1;failure(r,"journal","journal I/O failed; no further Vulkan calls");}
    else {r->journal_bytes+=(size_t)n;++r->journal_rows;}
}
static int before_call(WsiResult *r,const char *stage){if(r->journal_io_failed||r->call_overflow)return 0;journal_line(r,"before",stage,0,0);return !r->journal_io_failed;}
static int after_call(WsiResult *r,const char *stage,VkResult result){call_record(r,stage,result);journal_line(r,"after",stage,(int)result,1);if(r->journal_io_failed)failure(r,"journal","journal I/O failed; no further Vulkan calls");return !r->journal_io_failed&&!r->call_overflow;}
static int after_void(WsiResult *r,const char *stage){return after_call(r,stage,VK_SUCCESS);}

static int fixed_string_equal(const char *value,size_t capacity,const char *expected){size_t n;if(value==NULL||expected==NULL)return 0;n=strnlen(value,capacity);return n<capacity&&strcmp(value,expected)==0;}
static int json_result_value(Json *j,int called,VkResult result){return called?ja(j,"%d",(int)result):ja(j,"null");}

static int json_result(const WsiResult *r,char *out,size_t cap){
    Json j={out,cap,0U,1}; size_t i;
     if(!ja(&j,"{\"schema\":")||!js(&j,WSI_SCHEMA,sizeof(WSI_SCHEMA))||!ja(&j,",\"version\":1,\"runId\":")||!js(&j,r->run,RUN_LENGTH)||!ja(&j,",\"pid\":%d,\"uid\":%d,\"generation\":%" PRIu64, r->pid,r->uid,r->generation)||!ja(&j,",\"startNs\":%" PRIu64 ",\"endNs\":%" PRIu64 ",\"clockValid\":",r->start_ns,r->end_ns)||!jb(&j,r->clock_valid)||!ja(&j,",\"scopeValid\":")||!jb(&j,r->scope_valid)||!ja(&j,",\"procValid\":")||!jb(&j,r->proc_valid)||!ja(&j,",\"loadAttempted\":")||!jb(&j,r->load_attempted)||!ja(&j,",\"loadLoaded\":")||!jb(&j,r->load_loaded)||!ja(&j,",\"icdPath\":")||!js(&j,r->icd,PATH_LIMIT)||!ja(&j,",\"icdBytes\":%" PRIu64 ",\"icdSha256\":",r->icd_bytes)||!js(&j,r->icd_sha,64U)||!ja(&j,",\"icdAfterSha256\":")||!js(&j,r->after_sha,64U)||!ja(&j,",\"afterSHA\":")||!js(&j,r->after_sha,64U)||!ja(&j,",\"icdIdentityStable\":")||!jb(&j,r->identity_stable)||!ja(&j,",\"icdOwner\":")||!js(&j,r->owner,PATH_LIMIT)||!ja(&j,",\"icdOwnerBefore\":")||!js(&j,r->owner_before,PATH_LIMIT)||!ja(&j,",\"icdOwnerAfter\":")||!js(&j,r->owner_after,PATH_LIMIT))return 0;
     if(!ja(&j,",\"surface\":{\"fromSurface\":")||!jb(&j,r->window_owned)||!ja(&j,",\"windowOwned\":")||!jb(&j,r->window_owned)||!ja(&j,",\"width\":%d,\"height\":%d,\"createResult\":",r->surface_width,r->surface_height)||!json_result_value(&j,r->surface_called,r->surface_result)||!ja(&j,",\"created\":")||!jb(&j,r->surface_created)||!ja(&j,"},\"instance\":{\"extensionCount\":%u,\"createResult\":",r->instance_extensions)||!json_result_value(&j,r->instance_called,r->instance_result)||!ja(&j,",\"created\":")||!jb(&j,r->instance_created)||!ja(&j,"},\"physical\":{\"count\":%d,\"vendorId\":%u,\"deviceId\":%u,\"name\":",r->physical_count,r->physical_vendor,r->physical_device)||!js(&j,r->physical_name,sizeof(r->physical_name))||!ja(&j,"},\"queue\":{\"familyCount\":%d,\"familyIndex\":%d,\"flags\":%u,\"surfaceSupport\":",r->queue_family_count,r->queue_family,(unsigned)r->queue_flags)||!jb(&j,r->queue_surface_support)||!ja(&j,"},\"device\":{\"extensionCount\":%d,\"createResult\":",r->device_extension_count)||!json_result_value(&j,r->device_called,r->device_result)||!ja(&j,",\"created\":")||!jb(&j,r->device_created)||!ja(&j,"},\"surfaceCapabilities\":{\"minImageCount\":%u,\"maxImageCount\":%u,\"minExtent\":[%u,%u],\"maxExtent\":[%u,%u],\"currentExtent\":[%u,%u],\"usage\":%u,\"transforms\":%u,\"alpha\":%u},",r->surface_min_images,r->surface_max_images,r->surface_min_width,r->surface_min_height,r->surface_max_width,r->surface_max_height,r->surface_current_width,r->surface_current_height,(unsigned)r->surface_usage,(unsigned)r->surface_transforms,(unsigned)r->surface_alpha)||!ja(&j,"\"swapchain\":{\"createResult\":")||!json_result_value(&j,r->swapchain_called,r->swapchain_result)||!ja(&j,",\"imageCount\":%d,\"format\":%d,\"colorSpace\":%d,\"extentWidth\":%u,\"extentHeight\":%u,\"preTransform\":%u,\"compositeAlpha\":%u},\"formatCount\":%u,\"modeCount\":%u,",r->image_count,(int)r->selected_format,(int)r->selected_colorspace,r->surface_current_width,r->surface_current_height,(unsigned)r->pre_transform,(unsigned)r->composite_alpha,r->format_count,r->mode_count)||!ja(&j,"\"frames\":{\"count\":%d,\"max\":3,\"presentSuccessCount\":%d,\"items\":[",r->frame_count,r->present_success_count))return 0;
     for(i=0U;i<MAX_FRAMES;++i){const FrameResult *f=&r->frames[i];if((i!=0U&&!ja(&j,","))||!ja(&j,"{\"slot\":%zu,\"imageIndex\":%u,\"acquireCalled\":",i,f->image_index)||!jb(&j,f->acquire_called)||!ja(&j,",\"acquireResult\":")||!json_result_value(&j,f->acquire_called,f->acquire_result)||!ja(&j,",\"submitCalled\":")||!jb(&j,f->submit_called)||!ja(&j,",\"submitResult\":")||!json_result_value(&j,f->submit_called,f->submit_result)||!ja(&j,",\"presentCalled\":")||!jb(&j,f->present_called)||!ja(&j,",\"presentResult\":")||!json_result_value(&j,f->present_called,f->present_result)||!ja(&j,",\"fenceCalled\":")||!jb(&j,f->fence_called)||!ja(&j,",\"fenceResult\":")||!json_result_value(&j,f->fence_called,f->fence_result)||!ja(&j,"}"))return 0;}
     if(!ja(&j,"]},\"apiPresentSuccess\":")||!jb(&j,r->present_success_count==MAX_FRAMES)||!ja(&j,",\"counts\":{\"calls\":%zu,\"callCapacity\":%u,\"journalRows\":%d,\"journalRowCapacity\":%u,\"journalBytes\":%zu,\"journalByteCapacity\":%u,\"presentSuccess\":%d,\"physical\":%d,\"queueFamilies\":%d,\"deviceExtensions\":%d},\"pfns\":[",r->call_count,MAX_CALLS,r->journal_rows,JOURNAL_ROWS,r->journal_bytes,JOURNAL_LIMIT,r->present_success_count,r->physical_count,r->queue_family_count,r->device_extension_count))return 0;
      for(i=0U;i<r->pfn_count;++i){if((i!=0U&&!ja(&j,","))||!ja(&j,"{\"name\":")||!js(&j,r->pfns[i].name,ERROR_LIMIT)||!ja(&j,",\"ownerExact\":")||!jb(&j,r->pfns[i].owner_exact)||!ja(&j,",\"path\":")||!js(&j,r->pfns[i].path,PATH_LIMIT)||!ja(&j,"}"))return 0;}
     if(!ja(&j,"],\"calls\":["))return 0;
    for(i=0U;i<r->call_count;++i){if((i!=0U&&!ja(&j,","))||!ja(&j,"{\"callStage\":")||!js(&j,r->calls[i].stage,ERROR_LIMIT)||!ja(&j,",\"result\":%d}",r->calls[i].result))return 0;}
     if(!ja(&j,"],\"cancelRequested\":")||!jb(&j,r->cancelled)||!ja(&j,",\"cleanup\":{\"queueWaitIdleCalled\":")||!jb(&j,r->queue_idle_called)||!ja(&j,",\"queueRetired\":")||!jb(&j,r->queue_retired)||!ja(&j,",\"queueWaitIdleResult\":")||!json_result_value(&j,r->idle_called,r->idle_result)||!ja(&j,",\"complete\":")||!jb(&j,r->cleanup_complete)||!ja(&j,",\"unknownResourceRetained\":")||!jb(&j,r->unknown_retained)||!ja(&j,"},\"journal\":{\"rows\":%d,\"rowCapacity\":%u,\"bytes\":%zu,\"byteCapacity\":%u,\"ioFailed\":",r->journal_rows,JOURNAL_ROWS,r->journal_bytes,JOURNAL_LIMIT)||!jb(&j,r->journal_io_failed)||!ja(&j,"},\"androidPresentationObserved\":false,\"wine\":false,\"d3d\":false,\"wow\":false,\"status\":")||!js(&j,r->status,sizeof(r->status))||!ja(&j,",\"failureStage\":")||!js(&j,r->failure_stage,sizeof(r->failure_stage))||!ja(&j,",\"failureDetail\":")||!js(&j,r->failure,sizeof(r->failure))||!ja(&j,",\"nativeExit\":%d}",r->native_exit))return 0;
    return j.ok&&j.used<cap;
}

typedef struct WsiCtx WsiCtx;
typedef struct WsiFns {
    WsiCtx *ctx;
    WsiGipa gipa; WsiGdpa gdpa; PFN_vkEnumerateInstanceExtensionProperties enum_instance_ext;
    PFN_vkCreateInstance create_instance; PFN_vkDestroyInstance destroy_instance;
    PFN_vkEnumeratePhysicalDevices enum_physical; PFN_vkGetPhysicalDeviceProperties get_properties;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties get_families; PFN_vkEnumerateDeviceExtensionProperties enum_device_ext;
    PFN_vkCreateAndroidSurfaceKHR create_surface; PFN_vkDestroySurfaceKHR destroy_surface;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR surface_support; PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR surface_caps;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR surface_formats; PFN_vkGetPhysicalDeviceSurfacePresentModesKHR surface_modes;
    PFN_vkCreateDevice create_device; PFN_vkDestroyDevice destroy_device; PFN_vkGetDeviceQueue get_queue;
    PFN_vkCreateSwapchainKHR create_swapchain; PFN_vkDestroySwapchainKHR destroy_swapchain;
    PFN_vkGetSwapchainImagesKHR get_swapchain_images; PFN_vkAcquireNextImageKHR acquire;
    PFN_vkQueuePresentKHR present; PFN_vkQueueSubmit submit; PFN_vkQueueWaitIdle queue_idle;
    PFN_vkCreateRenderPass create_renderpass; PFN_vkDestroyRenderPass destroy_renderpass;
    PFN_vkCreateImageView create_view; PFN_vkDestroyImageView destroy_view;
    PFN_vkCreateShaderModule create_shader; PFN_vkDestroyShaderModule destroy_shader;
    PFN_vkCreatePipelineLayout create_layout; PFN_vkDestroyPipelineLayout destroy_layout;
    PFN_vkCreateGraphicsPipelines create_pipeline; PFN_vkDestroyPipeline destroy_pipeline;
    PFN_vkCreateFramebuffer create_framebuffer; PFN_vkDestroyFramebuffer destroy_framebuffer;
    PFN_vkCreateCommandPool create_pool; PFN_vkDestroyCommandPool destroy_pool;
    PFN_vkAllocateCommandBuffers alloc_commands; PFN_vkFreeCommandBuffers free_commands;
    PFN_vkBeginCommandBuffer begin_command; PFN_vkEndCommandBuffer end_command;
    PFN_vkCmdBeginRenderPass cmd_begin_renderpass; PFN_vkCmdBindPipeline cmd_bind_pipeline;
    PFN_vkCmdDraw cmd_draw; PFN_vkCmdEndRenderPass cmd_end_renderpass;
    PFN_vkCreateSemaphore create_semaphore; PFN_vkDestroySemaphore destroy_semaphore;
    PFN_vkCreateFence create_fence; PFN_vkDestroyFence destroy_fence; PFN_vkWaitForFences wait_fences;
} WsiFns;

static int owner_is(void *address,const char *canonical){
    Dl_info info; char *path;
    memset(&info,0,sizeof(info)); if(address==NULL||canonical==NULL||dladdr(address,&info)==0||info.dli_fname==NULL)return 0;
    path=realpath(info.dli_fname,NULL); if(path==NULL)return 0; {int ok=strcmp(path,canonical)==0;free(path);return ok;}
}

static int owner_path(void *address,char *out,size_t capacity){
    Dl_info info; char *path;
    if(out==NULL||capacity==0U||address==NULL){return 0;}
    out[0]='\0';memset(&info,0,sizeof(info));
    if(dladdr(address,&info)==0||info.dli_fname==NULL)return 0;
    path=realpath(info.dli_fname,NULL);if(path==NULL)return 0;
    if(!copy_s(out,capacity,path,PATH_LIMIT-1U)){free(path);return 0;}free(path);return 1;
}

static int record_pfn(WsiCtx *c,const char *name,PFN_vkVoidFunction p,const char *canonical);
static void *resolve_symbol(void *handle,const char *name,const char *canonical){void *p; (void)dlerror();p=dlsym(handle,name);if(p==NULL||dlerror()!=NULL||!owner_is(p,canonical))return NULL;return p;}
static PFN_vkVoidFunction resolve_instance(WsiFns *f,VkInstance instance,const char *name,const char *canonical){PFN_vkVoidFunction p=f->gipa(instance,name);if(p!=NULL&&owner_is((void*)p,canonical)&&f->ctx!=NULL&&!record_pfn(f->ctx,name,p,canonical))return NULL;return p!=NULL&&owner_is((void*)p,canonical)?p:NULL;}
static PFN_vkVoidFunction resolve_device_pfn(WsiFns *f,VkDevice device,const char *name,const char *canonical){PFN_vkVoidFunction p=f->gdpa(device,name);if(p!=NULL&&owner_is((void*)p,canonical)&&f->ctx!=NULL&&!record_pfn(f->ctx,name,p,canonical))return NULL;return p!=NULL&&owner_is((void*)p,canonical)?p:NULL;}

#define RESOLVE_I(ctx, member, type, name, canon) do { PFN_vkVoidFunction _p=resolve_instance(&(ctx)->fn,(ctx)->instance,name,canon); if(_p==NULL){failure(&(ctx)->result,"pfn",name);return 0;} (ctx)->fn.member=(type)_p; } while(0)
#define RESOLVE_D(ctx, member, type, name, canon) do { PFN_vkVoidFunction _p=resolve_device_pfn(&(ctx)->fn,(ctx)->device,name,canon); if(_p==NULL){failure(&(ctx)->result,"pfn",name);return 0;} (ctx)->fn.member=(type)_p; } while(0)

struct WsiCtx {
    WsiResult result; WsiFns fn; void *icd; ANativeWindow *window; VkInstance instance; VkSurfaceKHR surface;
    struct stat icd_identity_before; int icd_identity_valid;
    VkPhysicalDevice physical; VkDevice device; VkQueue queue; VkSwapchainKHR swapchain;
    VkRenderPass renderpass; VkPipelineLayout layout; VkPipeline pipeline; VkShaderModule shaders[2]; VkCommandPool pool;
    VkCommandBuffer commands[MAX_FRAMES]; VkImage images[MAX_IMAGES]; VkImageView views[MAX_IMAGES]; VkFramebuffer framebuffers[MAX_IMAGES];
    VkSemaphore acquire_semaphores[MAX_FRAMES], present_semaphores[MAX_FRAMES]; VkFence fences[MAX_FRAMES];
    uint32_t image_count, command_count; int submitted; int quiescent; int handle_live; int window_released;
};

static int record_pfn(WsiCtx *c,const char *name,PFN_vkVoidFunction p,const char *canonical){
    PfnRecord *record;char path[PATH_LIMIT];size_t i;
    if(!owner_path((void *)p,path,sizeof(path))){failure(&c->result,"pfn",name);return 0;}
    for(i=0U;i<c->result.pfn_count;++i){
        if(strcmp(c->result.pfns[i].name,name)==0){
            if(c->result.pfns[i].owner_exact&&strcmp(c->result.pfns[i].path,path)==0&&strcmp(path,canonical)==0)return 1;
            failure(&c->result,"pfn","resolved symbol has inconsistent owner/path");return 0;
        }
    }
    if(c->result.pfn_count>=MAX_PFNS){failure(&c->result,"pfn","PFN witness capacity exceeded");return 0;}
    record=&c->result.pfns[c->result.pfn_count];memset(record,0,sizeof(*record));
    if(!copy_s(record->name,sizeof(record->name),name,ERROR_LIMIT)||!copy_s(record->path,sizeof(record->path),path,PATH_LIMIT-1U)){failure(&c->result,"pfn",name);return 0;}
    record->owner_exact=strcmp(path,canonical)==0;if(!record->owner_exact){failure(&c->result,"pfn","resolved symbol owner mismatch");return 0;}
    ++c->result.pfn_count;return 1;
}

static int check_cancel(WsiCtx *c){if(c->result.journal_io_failed){failure(&c->result,"journal","journal I/O failed; no further Vulkan calls");return 0;}if(is_cancelled(c->result.generation)){c->result.cancelled=1;failure(&c->result,"cancel","generation cancelled");return 0;}return 1;}
static int surface_still_fixed(WsiCtx *c){
#ifndef PANVK_WSI_HOST_TEST
    return c->window!=NULL&&ANativeWindow_getWidth(c->window)==(int32_t)WIDTH&&ANativeWindow_getHeight(c->window)==(int32_t)HEIGHT;
#else
    return c->window!=NULL&&c->window->width==(int32_t)WIDTH&&c->window->height==(int32_t)HEIGHT;
#endif
}
static int instance_extensions(WsiCtx *c,const char *canon){
    VkExtensionProperties exts[MAX_EXTENSIONS]; uint32_t n=MAX_EXTENSIONS; VkResult r;
    c->fn.enum_instance_ext=(PFN_vkEnumerateInstanceExtensionProperties)resolve_instance(&c->fn,VK_NULL_HANDLE,"vkEnumerateInstanceExtensionProperties",canon); if(c->fn.enum_instance_ext==NULL){failure(&c->result,"pfn","vkEnumerateInstanceExtensionProperties");return 0;}
    if(!before_call(&c->result,"vkEnumerateInstanceExtensionProperties"))return 0; r=c->fn.enum_instance_ext(NULL,&n,NULL); if(!after_call(&c->result,"vkEnumerateInstanceExtensionProperties",r))return 0; if(r!=VK_SUCCESS||n>MAX_EXTENSIONS){failure(&c->result,"instance_extensions","count exceeds 256 or query failed");return 0;}
    {uint32_t fill=n;if(!before_call(&c->result,"vkEnumerateInstanceExtensionProperties.fill"))return 0;r=c->fn.enum_instance_ext(NULL,&fill,exts);if(!after_call(&c->result,"vkEnumerateInstanceExtensionProperties.fill",r))return 0;if(r!=VK_SUCCESS||fill!=n||fill>MAX_EXTENSIONS){failure(&c->result,"instance_extensions","incomplete or changed bounded fill");return 0;}n=fill;}
    {int surface=0,android=0;uint32_t i;for(i=0U;i<n;++i){if(fixed_string_equal(exts[i].extensionName,sizeof(exts[i].extensionName),VK_KHR_SURFACE_EXTENSION_NAME))surface=1;if(fixed_string_equal(exts[i].extensionName,sizeof(exts[i].extensionName),VK_KHR_ANDROID_SURFACE_EXTENSION_NAME))android=1;}if(!surface||!android){failure(&c->result,"instance_extensions","required KHR surface extensions absent");return 0;}c->result.instance_extensions=n;}
    return 1;
}

static int choose_queue(WsiCtx *c,uint32_t *family,const char *canon){
    VkQueueFamilyProperties q[32];uint32_t n=32U,reported,i;VkResult r;VkBool32 supported=VK_FALSE;
    c->fn.get_families=(PFN_vkGetPhysicalDeviceQueueFamilyProperties)resolve_instance(&c->fn,c->instance,"vkGetPhysicalDeviceQueueFamilyProperties",canon);if(c->fn.get_families==NULL){failure(&c->result,"pfn","vkGetPhysicalDeviceQueueFamilyProperties");return 0;}
    if(!before_call(&c->result,"vkGetPhysicalDeviceQueueFamilyProperties.count"))return 0;c->fn.get_families(c->physical,&n,NULL);if(!after_void(&c->result,"vkGetPhysicalDeviceQueueFamilyProperties.count"))return 0;if(n==0U||n>32U){failure(&c->result,"queue_families","count outside 1..32");return 0;}reported=n;memset(q,0,sizeof(q));n=32U;if(!before_call(&c->result,"vkGetPhysicalDeviceQueueFamilyProperties.fill"))return 0;c->fn.get_families(c->physical,&n,q);if(!after_void(&c->result,"vkGetPhysicalDeviceQueueFamilyProperties.fill"))return 0;if(n!=reported||n==0U||n>32U){failure(&c->result,"queue_families","count changed or exceeded bound");return 0;}c->result.queue_family_count=(int)n;
    c->fn.surface_support=(PFN_vkGetPhysicalDeviceSurfaceSupportKHR)resolve_instance(&c->fn,c->instance,"vkGetPhysicalDeviceSurfaceSupportKHR",canon);if(c->fn.surface_support==NULL){failure(&c->result,"pfn","vkGetPhysicalDeviceSurfaceSupportKHR");return 0;}
    for(i=0U;i<n;++i){if((q[i].queueFlags&VK_QUEUE_GRAPHICS_BIT)==0U||q[i].queueCount==0U)continue;if(!before_call(&c->result,"vkGetPhysicalDeviceSurfaceSupportKHR"))return 0;r=c->fn.surface_support(c->physical,i,c->surface,&supported);if(!after_call(&c->result,"vkGetPhysicalDeviceSurfaceSupportKHR",r))return 0;if(r==VK_SUCCESS&&supported){*family=i;c->result.queue_family=(int)i;c->result.queue_flags=q[i].queueFlags;c->result.queue_surface_support=supported;return 1;}}
    failure(&c->result,"queue","no graphics queue with surface support");return 0;
}

static int surface_info(WsiCtx *c,const char *canon){
    VkSurfaceCapabilitiesKHR caps;VkSurfaceFormatKHR formats[MAX_FORMATS];VkPresentModeKHR modes[MAX_MODES];uint32_t nf=MAX_FORMATS,nm=MAX_MODES;VkResult r;uint32_t i;
    c->fn.surface_caps=(PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR)resolve_instance(&c->fn,c->instance,"vkGetPhysicalDeviceSurfaceCapabilitiesKHR",canon);c->fn.surface_formats=(PFN_vkGetPhysicalDeviceSurfaceFormatsKHR)resolve_instance(&c->fn,c->instance,"vkGetPhysicalDeviceSurfaceFormatsKHR",canon);c->fn.surface_modes=(PFN_vkGetPhysicalDeviceSurfacePresentModesKHR)resolve_instance(&c->fn,c->instance,"vkGetPhysicalDeviceSurfacePresentModesKHR",canon);
    if(c->fn.surface_caps==NULL||c->fn.surface_formats==NULL||c->fn.surface_modes==NULL){failure(&c->result,"pfn","surface query PFN missing");return 0;}
    memset(&caps,0,sizeof(caps));if(!before_call(&c->result,"vkGetPhysicalDeviceSurfaceCapabilitiesKHR"))return 0;r=c->fn.surface_caps(c->physical,c->surface,&caps);if(!after_call(&c->result,"vkGetPhysicalDeviceSurfaceCapabilitiesKHR",r))return 0;if(r!=VK_SUCCESS||caps.currentExtent.width!=WIDTH||caps.currentExtent.height!=HEIGHT||caps.minImageExtent.width>WIDTH||caps.minImageExtent.height>HEIGHT||caps.maxImageExtent.width<WIDTH||caps.maxImageExtent.height<HEIGHT||!(caps.supportedUsageFlags&VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)){failure(&c->result,"surface_caps","surface extent or usage is outside fixed bounds");return 0;}c->result.surface_min_images=caps.minImageCount;c->result.surface_max_images=caps.maxImageCount;c->result.surface_min_width=caps.minImageExtent.width;c->result.surface_min_height=caps.minImageExtent.height;c->result.surface_max_width=caps.maxImageExtent.width;c->result.surface_max_height=caps.maxImageExtent.height;c->result.surface_current_width=caps.currentExtent.width;c->result.surface_current_height=caps.currentExtent.height;c->result.surface_usage=caps.supportedUsageFlags;c->result.surface_transforms=caps.supportedTransforms;c->result.surface_alpha=caps.supportedCompositeAlpha;
    if((caps.supportedTransforms&VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)==0U||(caps.supportedCompositeAlpha&VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)==0U){failure(&c->result,"surface_caps","required known transform or alpha mode unavailable");return 0;}c->result.pre_transform=VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;c->result.composite_alpha=VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    if(!before_call(&c->result,"vkGetPhysicalDeviceSurfaceFormatsKHR"))return 0;r=c->fn.surface_formats(c->physical,c->surface,&nf,NULL);if(!after_call(&c->result,"vkGetPhysicalDeviceSurfaceFormatsKHR",r))return 0;if(r!=VK_SUCCESS||nf==0U||nf>MAX_FORMATS){failure(&c->result,"surface_formats","format count exceeds 32 or query failed");return 0;}memset(formats,0,sizeof(formats));{uint32_t fill=nf;if(!before_call(&c->result,"vkGetPhysicalDeviceSurfaceFormatsKHR.fill"))return 0;r=c->fn.surface_formats(c->physical,c->surface,&fill,formats);if(!after_call(&c->result,"vkGetPhysicalDeviceSurfaceFormatsKHR.fill",r))return 0;if(r!=VK_SUCCESS||fill!=nf||fill>MAX_FORMATS){failure(&c->result,"surface_formats","bounded format fill was incomplete or changed");return 0;}}
    for(i=0U;i<nf;++i){if(formats[i].format==VK_FORMAT_R8G8B8A8_UNORM&&formats[i].colorSpace==VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)break;}
    if(i==nf){failure(&c->result,"surface_formats","required UNORM/sRGB format unavailable");return 0;}c->result.format_count=nf;c->result.selected_format=formats[i].format;c->result.selected_colorspace=formats[i].colorSpace;
    if(!before_call(&c->result,"vkGetPhysicalDeviceSurfacePresentModesKHR"))return 0;r=c->fn.surface_modes(c->physical,c->surface,&nm,NULL);if(!after_call(&c->result,"vkGetPhysicalDeviceSurfacePresentModesKHR",r))return 0;if(r!=VK_SUCCESS||nm==0U||nm>MAX_MODES){failure(&c->result,"surface_modes","present mode count exceeds 16 or query failed");return 0;}memset(modes,0,sizeof(modes));{uint32_t fill=nm;if(!before_call(&c->result,"vkGetPhysicalDeviceSurfacePresentModesKHR.fill"))return 0;r=c->fn.surface_modes(c->physical,c->surface,&fill,modes);if(!after_call(&c->result,"vkGetPhysicalDeviceSurfacePresentModesKHR.fill",r))return 0;if(r!=VK_SUCCESS||fill!=nm||fill>MAX_MODES){failure(&c->result,"surface_modes","bounded mode fill was incomplete or changed");return 0;}}for(i=0U;i<nm;++i)if(modes[i]==VK_PRESENT_MODE_FIFO_KHR)break;if(i==nm){failure(&c->result,"surface_modes","FIFO unavailable");return 0;}c->result.mode_count=nm;
    if(caps.minImageCount>MAX_IMAGES||(caps.maxImageCount!=0U&&caps.maxImageCount<2U)){failure(&c->result,"swapchain_bounds","surface image count cannot satisfy 2..4");return 0;}c->image_count=caps.minImageCount>2U?caps.minImageCount:2U;if(caps.maxImageCount!=0U&&c->image_count>caps.maxImageCount)c->image_count=caps.maxImageCount;if(c->image_count<2U||c->image_count>MAX_IMAGES){failure(&c->result,"swapchain_bounds","image count outside fixed 2..4");return 0;}return 1;
}

static int resolve_device(WsiCtx *c,const char *canon){
#define D(member,type,name) RESOLVE_D(c,member,type,name,canon)
    D(destroy_device,PFN_vkDestroyDevice,"vkDestroyDevice");D(get_queue,PFN_vkGetDeviceQueue,"vkGetDeviceQueue");D(create_swapchain,PFN_vkCreateSwapchainKHR,"vkCreateSwapchainKHR");D(destroy_swapchain,PFN_vkDestroySwapchainKHR,"vkDestroySwapchainKHR");D(get_swapchain_images,PFN_vkGetSwapchainImagesKHR,"vkGetSwapchainImagesKHR");D(acquire,PFN_vkAcquireNextImageKHR,"vkAcquireNextImageKHR");D(present,PFN_vkQueuePresentKHR,"vkQueuePresentKHR");D(submit,PFN_vkQueueSubmit,"vkQueueSubmit");D(queue_idle,PFN_vkQueueWaitIdle,"vkQueueWaitIdle");D(create_renderpass,PFN_vkCreateRenderPass,"vkCreateRenderPass");D(destroy_renderpass,PFN_vkDestroyRenderPass,"vkDestroyRenderPass");D(create_view,PFN_vkCreateImageView,"vkCreateImageView");D(destroy_view,PFN_vkDestroyImageView,"vkDestroyImageView");D(create_shader,PFN_vkCreateShaderModule,"vkCreateShaderModule");D(destroy_shader,PFN_vkDestroyShaderModule,"vkDestroyShaderModule");D(create_layout,PFN_vkCreatePipelineLayout,"vkCreatePipelineLayout");D(destroy_layout,PFN_vkDestroyPipelineLayout,"vkDestroyPipelineLayout");D(create_pipeline,PFN_vkCreateGraphicsPipelines,"vkCreateGraphicsPipelines");D(destroy_pipeline,PFN_vkDestroyPipeline,"vkDestroyPipeline");D(create_framebuffer,PFN_vkCreateFramebuffer,"vkCreateFramebuffer");D(destroy_framebuffer,PFN_vkDestroyFramebuffer,"vkDestroyFramebuffer");D(create_pool,PFN_vkCreateCommandPool,"vkCreateCommandPool");D(destroy_pool,PFN_vkDestroyCommandPool,"vkDestroyCommandPool");D(alloc_commands,PFN_vkAllocateCommandBuffers,"vkAllocateCommandBuffers");D(free_commands,PFN_vkFreeCommandBuffers,"vkFreeCommandBuffers");D(begin_command,PFN_vkBeginCommandBuffer,"vkBeginCommandBuffer");D(end_command,PFN_vkEndCommandBuffer,"vkEndCommandBuffer");D(cmd_begin_renderpass,PFN_vkCmdBeginRenderPass,"vkCmdBeginRenderPass");D(cmd_bind_pipeline,PFN_vkCmdBindPipeline,"vkCmdBindPipeline");D(cmd_draw,PFN_vkCmdDraw,"vkCmdDraw");D(cmd_end_renderpass,PFN_vkCmdEndRenderPass,"vkCmdEndRenderPass");D(create_semaphore,PFN_vkCreateSemaphore,"vkCreateSemaphore");D(destroy_semaphore,PFN_vkDestroySemaphore,"vkDestroySemaphore");D(create_fence,PFN_vkCreateFence,"vkCreateFence");D(destroy_fence,PFN_vkDestroyFence,"vkDestroyFence");D(wait_fences,PFN_vkWaitForFences,"vkWaitForFences");
#undef D
    return 1;
}

static int create_render_objects(WsiCtx *c){
    VkAttachmentDescription att={0U,VK_FORMAT_R8G8B8A8_UNORM,VK_SAMPLE_COUNT_1_BIT,VK_ATTACHMENT_LOAD_OP_CLEAR,VK_ATTACHMENT_STORE_OP_STORE,VK_ATTACHMENT_LOAD_OP_DONT_CARE,VK_ATTACHMENT_STORE_OP_DONT_CARE,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_PRESENT_SRC_KHR};
    att.format=c->result.selected_format;
    VkAttachmentReference ref={0U,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};VkSubpassDescription sub={0U,VK_PIPELINE_BIND_POINT_GRAPHICS,0U,NULL,1U,&ref,NULL,NULL,0U,NULL};
    VkSubpassDependency deps[2]={{VK_SUBPASS_EXTERNAL,0U,VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,0U,VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,0U},{0U,VK_SUBPASS_EXTERNAL,VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,0U,0U}};
    VkRenderPassCreateInfo rp={VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,NULL,0U,1U,&att,1U,&sub,2U,deps};VkResult r;uint32_t i;VkRenderPass created_renderpass=VK_NULL_HANDLE;
    if(!before_call(&c->result,"vkCreateRenderPass"))return 0;r=c->fn.create_renderpass(c->device,&rp,NULL,&created_renderpass);if(r==VK_SUCCESS&&created_renderpass!=VK_NULL_HANDLE)c->renderpass=created_renderpass;if(!after_call(&c->result,"vkCreateRenderPass",r))return 0;if(r!=VK_SUCCESS||created_renderpass==VK_NULL_HANDLE){c->result.last_result=r;failure(&c->result,"renderpass","create failed");return 0;}
    {VkPipelineShaderStageCreateInfo stages[2];VkShaderModuleCreateInfo si[2]={{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,NULL,0U,sizeof(triangle_vert_spv),triangle_vert_spv},{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,NULL,0U,sizeof(triangle_frag_spv),triangle_frag_spv}};for(i=0U;i<2U;++i){VkShaderModule shader=VK_NULL_HANDLE;const char *stage=i==0U?"vkCreateShaderModule.vertex":"vkCreateShaderModule.fragment";if(!before_call(&c->result,stage))return 0;r=c->fn.create_shader(c->device,&si[i],NULL,&shader);if(r==VK_SUCCESS&&shader!=VK_NULL_HANDLE)c->shaders[i]=shader;if(!after_call(&c->result,stage,r))return 0;if(r!=VK_SUCCESS||shader==VK_NULL_HANDLE){failure(&c->result,"shader","shader module failed");return 0;}}memset(stages,0,sizeof(stages));stages[0]=(VkPipelineShaderStageCreateInfo){VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,NULL,0U,VK_SHADER_STAGE_VERTEX_BIT,c->shaders[0],"main",NULL};stages[1]=(VkPipelineShaderStageCreateInfo){VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,NULL,0U,VK_SHADER_STAGE_FRAGMENT_BIT,c->shaders[1],"main",NULL};
        VkPipelineVertexInputStateCreateInfo vi={VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,NULL,0U,0U,NULL,0U,NULL};VkPipelineInputAssemblyStateCreateInfo ia={VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,NULL,0U,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,VK_FALSE};VkViewport vp={0.0f,0.0f,(float)WIDTH,(float)HEIGHT,0.0f,1.0f};VkRect2D sc={{0,0},{WIDTH,HEIGHT}};VkPipelineViewportStateCreateInfo vs={VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,NULL,0U,1U,&vp,1U,&sc};VkPipelineRasterizationStateCreateInfo rs={VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,NULL,0U,VK_FALSE,VK_FALSE,VK_POLYGON_MODE_FILL,VK_CULL_MODE_NONE,VK_FRONT_FACE_CLOCKWISE,VK_FALSE,0.0f,0.0f,0.0f,1.0f};VkPipelineMultisampleStateCreateInfo ms={VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,NULL,0U,VK_SAMPLE_COUNT_1_BIT,VK_FALSE,1.0f,NULL,VK_FALSE,VK_FALSE};VkPipelineColorBlendAttachmentState ba={VK_FALSE,VK_BLEND_FACTOR_ZERO,VK_BLEND_FACTOR_ZERO,VK_BLEND_OP_ADD,VK_BLEND_FACTOR_ZERO,VK_BLEND_FACTOR_ZERO,VK_BLEND_OP_ADD,VK_COLOR_COMPONENT_R_BIT|VK_COLOR_COMPONENT_G_BIT|VK_COLOR_COMPONENT_B_BIT|VK_COLOR_COMPONENT_A_BIT};VkPipelineColorBlendStateCreateInfo cb={VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,NULL,0U,VK_FALSE,VK_LOGIC_OP_COPY,1U,&ba,{0,0,0,0}};VkPipelineLayoutCreateInfo li={VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,NULL,0U,0U,NULL,0U,NULL};VkGraphicsPipelineCreateInfo pi={VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,NULL,0U,2U,stages,&vi,&ia,NULL,&vs,&rs,&ms,NULL,&cb,NULL,c->layout,c->renderpass,0U,VK_NULL_HANDLE,-1};
         VkPipelineLayout created_layout=VK_NULL_HANDLE;if(!before_call(&c->result,"vkCreatePipelineLayout"))return 0;r=c->fn.create_layout(c->device,&li,NULL,&created_layout);if(r==VK_SUCCESS&&created_layout!=VK_NULL_HANDLE)c->layout=created_layout;if(!after_call(&c->result,"vkCreatePipelineLayout",r))return 0;if(r!=VK_SUCCESS||created_layout==VK_NULL_HANDLE){failure(&c->result,"pipeline_layout","create failed");return 0;}pi.layout=c->layout;VkPipeline created_pipeline=VK_NULL_HANDLE;if(!before_call(&c->result,"vkCreateGraphicsPipelines"))return 0;r=c->fn.create_pipeline(c->device,VK_NULL_HANDLE,1U,&pi,NULL,&created_pipeline);if(r==VK_SUCCESS&&created_pipeline!=VK_NULL_HANDLE)c->pipeline=created_pipeline;if(!after_call(&c->result,"vkCreateGraphicsPipelines",r))return 0;if(r!=VK_SUCCESS||created_pipeline==VK_NULL_HANDLE){failure(&c->result,"pipeline","create failed");return 0;}
    }
    for(i=0U;i<c->image_count;++i){VkImageViewCreateInfo vi={VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,NULL,0U,c->images[i],VK_IMAGE_VIEW_TYPE_2D,c->result.selected_format,{VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY,VK_COMPONENT_SWIZZLE_IDENTITY},{VK_IMAGE_ASPECT_COLOR_BIT,0U,1U,0U,1U}};VkFramebufferCreateInfo fi={VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,NULL,0U,c->renderpass,1U,&c->views[i],WIDTH,HEIGHT,1U};VkImageView view=VK_NULL_HANDLE;if(!before_call(&c->result,"vkCreateImageView"))return 0;r=c->fn.create_view(c->device,&vi,NULL,&view);if(r==VK_SUCCESS&&view!=VK_NULL_HANDLE)c->views[i]=view;if(!after_call(&c->result,"vkCreateImageView",r))return 0;if(r!=VK_SUCCESS||view==VK_NULL_HANDLE){failure(&c->result,"image_view","create failed");return 0;}VkFramebuffer framebuffer=VK_NULL_HANDLE;if(!before_call(&c->result,"vkCreateFramebuffer"))return 0;r=c->fn.create_framebuffer(c->device,&fi,NULL,&framebuffer);if(r==VK_SUCCESS&&framebuffer!=VK_NULL_HANDLE)c->framebuffers[i]=framebuffer;if(!after_call(&c->result,"vkCreateFramebuffer",r))return 0;if(r!=VK_SUCCESS||framebuffer==VK_NULL_HANDLE){failure(&c->result,"framebuffer","create failed");return 0;}}
    {VkCommandPoolCreateInfo pool={VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,NULL,VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,c->result.queue_family};VkCommandPool created_pool=VK_NULL_HANDLE;if(!before_call(&c->result,"vkCreateCommandPool"))return 0;r=c->fn.create_pool(c->device,&pool,NULL,&created_pool);if(r==VK_SUCCESS&&created_pool!=VK_NULL_HANDLE)c->pool=created_pool;if(!after_call(&c->result,"vkCreateCommandPool",r))return 0;if(r!=VK_SUCCESS||created_pool==VK_NULL_HANDLE){failure(&c->result,"command_pool","create failed");return 0;}VkCommandBufferAllocateInfo ai={VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,NULL,c->pool,VK_COMMAND_BUFFER_LEVEL_PRIMARY,MAX_FRAMES};VkCommandBuffer allocated[MAX_FRAMES]={VK_NULL_HANDLE,VK_NULL_HANDLE,VK_NULL_HANDLE};if(!before_call(&c->result,"vkAllocateCommandBuffers"))return 0;r=c->fn.alloc_commands(c->device,&ai,allocated);if(r==VK_SUCCESS) {memcpy(c->commands,allocated,sizeof(allocated));c->command_count=MAX_FRAMES;}if(!after_call(&c->result,"vkAllocateCommandBuffers",r))return 0;if(r!=VK_SUCCESS){failure(&c->result,"command_buffers","allocate failed");return 0;}}
    return 1;
}

static int create_swapchain(WsiCtx *c){
    VkSwapchainCreateInfoKHR si={VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,NULL,0U,c->surface,c->image_count,c->result.selected_format,c->result.selected_colorspace,{WIDTH,HEIGHT},1U,VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,VK_SHARING_MODE_EXCLUSIVE,0U,NULL,c->result.pre_transform,c->result.composite_alpha,VK_PRESENT_MODE_FIFO_KHR,VK_TRUE,VK_NULL_HANDLE};VkResult r;uint32_t n=MAX_IMAGES;
    VkSwapchainKHR created=VK_NULL_HANDLE;if(!before_call(&c->result,"vkCreateSwapchainKHR"))return 0;r=c->fn.create_swapchain(c->device,&si,NULL,&created);c->result.swapchain_called=1;c->result.swapchain_result=r;if(r==VK_SUCCESS&&created!=VK_NULL_HANDLE)c->swapchain=created;if(!after_call(&c->result,"vkCreateSwapchainKHR",r))return 0;if(r!=VK_SUCCESS||created==VK_NULL_HANDLE){failure(&c->result,"swapchain","create failed");return 0;}
    if(!before_call(&c->result,"vkGetSwapchainImagesKHR.count"))return 0;r=c->fn.get_swapchain_images(c->device,c->swapchain,&n,NULL);if(!after_call(&c->result,"vkGetSwapchainImagesKHR.count",r))return 0;if(r!=VK_SUCCESS||n!=c->image_count||n<2U||n>MAX_IMAGES){failure(&c->result,"swapchain_images","image count outside bound or changed");return 0;}VkImage images[MAX_IMAGES]={VK_NULL_HANDLE,VK_NULL_HANDLE,VK_NULL_HANDLE,VK_NULL_HANDLE};uint32_t fill=n;if(!before_call(&c->result,"vkGetSwapchainImagesKHR.fill"))return 0;r=c->fn.get_swapchain_images(c->device,c->swapchain,&fill,images);if(r==VK_SUCCESS&&fill==n&&fill<=MAX_IMAGES){memcpy(c->images,images,sizeof(images));c->image_count=fill;c->result.image_count=(int)fill;}if(!after_call(&c->result,"vkGetSwapchainImagesKHR.fill",r))return 0;if(r!=VK_SUCCESS||fill!=n||fill>MAX_IMAGES){failure(&c->result,"swapchain_images","bounded fill was incomplete or changed");return 0;}return 1;
}

static int draw_frame(WsiCtx *c,uint32_t slot){
    uint32_t image_index=0U;VkResult r;FrameResult *frame=&c->result.frames[slot];VkCommandBufferBeginInfo bi={VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,NULL,0U,NULL};VkClearValue clear={{{0.0f,1.0f,0.0f,1.0f}}};VkRenderPassBeginInfo ri={VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,NULL,c->renderpass,VK_NULL_HANDLE,{{0,0},{WIDTH,HEIGHT}},1U,&clear};VkSubmitInfo submit={VK_STRUCTURE_TYPE_SUBMIT_INFO,NULL,1U,&c->acquire_semaphores[slot],(const VkPipelineStageFlags[]){VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT},1U,&c->commands[slot],1U,&c->present_semaphores[slot]};VkPresentInfoKHR present={VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,NULL,1U,&c->present_semaphores[slot],1U,&c->swapchain,&image_index,NULL};
    if(!check_cancel(c))return 0;
    if(!surface_still_fixed(c)){failure(&c->result,"surface_resize","surface changed without recreation or retry");return 0;}
    c->quiescent=0;if(!before_call(&c->result,"vkAcquireNextImageKHR"))return 0;r=c->fn.acquire(c->device,c->swapchain,UINT64_C(500000000),c->acquire_semaphores[slot],VK_NULL_HANDLE,&image_index);frame->acquire_called=1;frame->acquire_result=r;if(!after_call(&c->result,"vkAcquireNextImageKHR",r))return 0;if(r!=VK_SUCCESS){failure(&c->result,"acquire","acquire did not return VK_SUCCESS");return 0;}if(image_index>=c->image_count){failure(&c->result,"acquire","driver returned an out-of-range image index");return 0;}frame->image_index=image_index;
    if(!check_cancel(c))return 0;
    ri.framebuffer=c->framebuffers[image_index];
    if(!before_call(&c->result,"vkBeginCommandBuffer"))return 0;r=c->fn.begin_command(c->commands[slot],&bi);if(!after_call(&c->result,"vkBeginCommandBuffer",r))return 0;if(r!=VK_SUCCESS){failure(&c->result,"command_begin","begin failed");return 0;}
    if(!before_call(&c->result,"vkCmdBeginRenderPass"))return 0;c->fn.cmd_begin_renderpass(c->commands[slot],&ri,VK_SUBPASS_CONTENTS_INLINE);if(!after_void(&c->result,"vkCmdBeginRenderPass"))return 0;
    if(!before_call(&c->result,"vkCmdBindPipeline"))return 0;c->fn.cmd_bind_pipeline(c->commands[slot],VK_PIPELINE_BIND_POINT_GRAPHICS,c->pipeline);if(!after_void(&c->result,"vkCmdBindPipeline"))return 0;
    if(!before_call(&c->result,"vkCmdDraw"))return 0;c->fn.cmd_draw(c->commands[slot],3U,1U,0U,0U);if(!after_void(&c->result,"vkCmdDraw"))return 0;
    if(!before_call(&c->result,"vkCmdEndRenderPass"))return 0;c->fn.cmd_end_renderpass(c->commands[slot]);if(!after_void(&c->result,"vkCmdEndRenderPass"))return 0;
    if(!before_call(&c->result,"vkEndCommandBuffer"))return 0;r=c->fn.end_command(c->commands[slot]);if(!after_call(&c->result,"vkEndCommandBuffer",r))return 0;if(r!=VK_SUCCESS){failure(&c->result,"command_end","end failed");return 0;}
    if(!check_cancel(c))return 0;
    if(!before_call(&c->result,"vkQueueSubmit"))return 0;r=c->fn.submit(c->queue,1U,&submit,c->fences[slot]);frame->submit_called=1;frame->submit_result=r;if(!after_call(&c->result,"vkQueueSubmit",r))return 0;if(r!=VK_SUCCESS){failure(&c->result,"submit","submit failed");return 0;}c->submitted=1;
    if(is_cancelled(c->result.generation)){c->result.cancelled=1;failure(&c->result,"cancel","generation cancelled after submit");return 0;}
    if(!before_call(&c->result,"vkQueuePresentKHR"))return 0;r=c->fn.present(c->queue,&present);frame->present_called=1;frame->present_result=r;if(!after_call(&c->result,"vkQueuePresentKHR",r))return 0;if(r!=VK_SUCCESS){failure(&c->result,"present","present did not return VK_SUCCESS");return 0;}
    if(!before_call(&c->result,"vkWaitForFences"))return 0;r=c->fn.wait_fences(c->device,1U,&c->fences[slot],VK_TRUE,UINT64_C(1000000000));frame->fence_called=1;frame->fence_result=r;if(!after_call(&c->result,"vkWaitForFences",r))return 0;if(r!=VK_SUCCESS){failure(&c->result,"fence","fence did not signal within 1 second");return 0;}
    ++c->result.present_success_count;
    ++c->result.frame_count;return 1;
}

static void release_window(WsiCtx *c){
    if(c->window==NULL||c->window_released)return;
#ifndef PANVK_WSI_HOST_TEST
    ANativeWindow_release(c->window);
#else
    ++g_host_window_releases;
#endif
    c->window_released=1;
}

static void cleanup(WsiCtx *c){
    uint32_t i;int safe=1;
    if(c->result.unknown_retained||!c->quiescent){c->result.unknown_retained=1;c->result.cleanup_complete=0;return;}
    if(c->device!=VK_NULL_HANDLE&&(c->fn.destroy_device==NULL||(c->command_count!=0U&&c->fn.free_commands==NULL)||(c->pool!=VK_NULL_HANDLE&&c->fn.destroy_pool==NULL)||(c->swapchain!=VK_NULL_HANDLE&&c->fn.destroy_swapchain==NULL)||(c->renderpass!=VK_NULL_HANDLE&&c->fn.destroy_renderpass==NULL)||(c->layout!=VK_NULL_HANDLE&&c->fn.destroy_layout==NULL)||(c->pipeline!=VK_NULL_HANDLE&&c->fn.destroy_pipeline==NULL)||(c->shaders[0]!=VK_NULL_HANDLE&&c->fn.destroy_shader==NULL)||(c->shaders[1]!=VK_NULL_HANDLE&&c->fn.destroy_shader==NULL)))safe=0;
    for(i=0U;i<MAX_FRAMES;++i)if((c->fences[i]!=VK_NULL_HANDLE&&c->fn.destroy_fence==NULL)||(c->acquire_semaphores[i]!=VK_NULL_HANDLE&&c->fn.destroy_semaphore==NULL)||(c->present_semaphores[i]!=VK_NULL_HANDLE&&c->fn.destroy_semaphore==NULL))safe=0;
    for(i=0U;i<c->image_count;++i)if((c->views[i]!=VK_NULL_HANDLE&&c->fn.destroy_view==NULL)||(c->framebuffers[i]!=VK_NULL_HANDLE&&c->fn.destroy_framebuffer==NULL))safe=0;
    if(c->instance!=VK_NULL_HANDLE&&c->fn.destroy_instance==NULL)safe=0;
    if(c->surface!=VK_NULL_HANDLE&&c->fn.destroy_surface==NULL)safe=0;
    if(!safe){c->result.unknown_retained=1;c->result.cleanup_complete=0;return;}
    for(i=0U;i<MAX_FRAMES;++i){
        if(c->fences[i]!=VK_NULL_HANDLE)c->fn.destroy_fence(c->device,c->fences[i],NULL);
        if(c->acquire_semaphores[i]!=VK_NULL_HANDLE)c->fn.destroy_semaphore(c->device,c->acquire_semaphores[i],NULL);
        if(c->present_semaphores[i]!=VK_NULL_HANDLE)c->fn.destroy_semaphore(c->device,c->present_semaphores[i],NULL);
    }
    if(c->pool!=VK_NULL_HANDLE&&c->command_count!=0U&&c->fn.free_commands!=NULL)c->fn.free_commands(c->device,c->pool,c->command_count,c->commands);
    for(i=0U;i<c->image_count;++i){if(c->framebuffers[i]!=VK_NULL_HANDLE)c->fn.destroy_framebuffer(c->device,c->framebuffers[i],NULL);if(c->views[i]!=VK_NULL_HANDLE)c->fn.destroy_view(c->device,c->views[i],NULL);}
    if(c->pipeline!=VK_NULL_HANDLE&&c->fn.destroy_pipeline!=NULL)c->fn.destroy_pipeline(c->device,c->pipeline,NULL);
    if(c->layout!=VK_NULL_HANDLE&&c->fn.destroy_layout!=NULL)c->fn.destroy_layout(c->device,c->layout,NULL);
    if(c->shaders[0]!=VK_NULL_HANDLE&&c->fn.destroy_shader!=NULL)c->fn.destroy_shader(c->device,c->shaders[0],NULL);
    if(c->shaders[1]!=VK_NULL_HANDLE&&c->fn.destroy_shader!=NULL)c->fn.destroy_shader(c->device,c->shaders[1],NULL);
    if(c->renderpass!=VK_NULL_HANDLE&&c->fn.destroy_renderpass!=NULL)c->fn.destroy_renderpass(c->device,c->renderpass,NULL);
    if(c->swapchain!=VK_NULL_HANDLE&&c->fn.destroy_swapchain!=NULL)c->fn.destroy_swapchain(c->device,c->swapchain,NULL);
    if(c->pool!=VK_NULL_HANDLE&&c->fn.destroy_pool!=NULL)c->fn.destroy_pool(c->device,c->pool,NULL);
    if(c->device!=VK_NULL_HANDLE&&c->fn.destroy_device!=NULL)c->fn.destroy_device(c->device,NULL);
    if(c->surface!=VK_NULL_HANDLE&&c->fn.destroy_surface!=NULL)c->fn.destroy_surface(c->instance,c->surface,NULL);
    if(c->instance!=VK_NULL_HANDLE&&c->fn.destroy_instance!=NULL)c->fn.destroy_instance(c->instance,NULL);
    if(c->icd!=NULL)(void)dlclose(c->icd);
    release_window(c);
    c->result.cleanup_complete=1;
}

static int retire_queue(WsiCtx *c){
    VkResult r;
    if(!c->submitted)return 1;
    if(!before_call(&c->result,"vkQueueWaitIdle")){if(!c->quiescent)c->result.unknown_retained=1;return 0;}
    r=c->fn.queue_idle(c->queue);c->result.idle_called=1;c->result.queue_idle_called=1;c->result.idle_result=r;
    if(!after_call(&c->result,"vkQueueWaitIdle",r)){if(!c->quiescent)c->result.unknown_retained=1;return 0;}
    if(r==VK_SUCCESS&&!c->result.journal_io_failed){c->quiescent=1;c->result.queue_retired=1;return 1;}
    if(!c->quiescent)c->result.unknown_retained=1;failure(&c->result,"queue_wait_idle","retirement not proven");return 0;
}

static int run_native(WsiCtx *c,const char *icd_path,const char *journal_path,const char *run,uint64_t generation,void *jni_env,void *surface_object){
    char dir[PATH_LIMIT],canonical[PATH_LIMIT],canonical_dir[PATH_LIMIT],journal[PATH_LIMIT];struct stat st;VkResult r;uint32_t n=1U,i;VkPhysicalDevice devices[8];VkPhysicalDeviceProperties prop;VkDeviceQueueCreateInfo qi;VkDeviceCreateInfo di;float priority=1.0f;
#ifdef PANVK_WSI_HOST_TEST
    (void)jni_env;
#endif
    memset(c,0,sizeof(*c)); c->result.journal_fd=-1; c->quiescent=1;
    c->result.generation=generation; c->result.pid=(int)getpid();
    c->result.uid=(int)getuid(); c->result.start_ns=now_ns();
    copy_s(c->result.failure_stage,sizeof(c->result.failure_stage),"none",63U);
    copy_s(c->result.run,sizeof(c->result.run),run,RUN_LENGTH);
    copy_s(c->result.icd,sizeof(c->result.icd),icd_path,PATH_LIMIT-1U);
    if(c->result.uid!=(int)EXPECTED_UID){failure(&c->result,"identity","process UID is not 10330");return 0;}
    if(!derive_icd_dir(dir,sizeof(dir),icd_path,run,journal_path)){failure(&c->result,"path","ICD or full native.jsonl path outside private WSI scope");return 0;}
    if(!proc_name_valid()){failure(&c->result,"process","process name is not " PROC_NAME);return 0;}
    c->result.proc_valid=1;
    if(realpath(dir,canonical_dir)==NULL||realpath(icd_path,canonical)==NULL||
       !canonical_scope_valid(dir,canonical_dir,icd_path,canonical,run)){
        failure(&c->result,"path","canonical run directory or ICD is outside exact package/RUN");return 0;
    }
    if(lstat(dir,&st)!=0||!S_ISDIR(st.st_mode)||st.st_uid!=(uid_t)EXPECTED_UID||lstat(icd_path,&st)!=0){failure(&c->result,"path","private owner or directory check failed");return 0;}
    c->result.icd_bytes=(uint64_t)st.st_size;
    if(!hash_icd(icd_path,c->result.icd_sha,&st)){failure(&c->result,"hash","ICD size/owner/SHA mismatch");return 0;}
    copy_s(c->result.owner,sizeof(c->result.owner),canonical,PATH_LIMIT-1U);
    copy_s(c->result.owner_before,sizeof(c->result.owner_before),canonical,PATH_LIMIT-1U);
    copy_s(journal,sizeof(journal),journal_path,PATH_LIMIT-1U);
    c->result.journal_fd=open(journal,O_WRONLY|O_CREAT|O_APPEND|O_CLOEXEC|O_NOFOLLOW,0600);
    if(c->result.journal_fd<0){failure(&c->result,"journal","cannot open native journal");return 0;}
    journal_line(&c->result,"start","run",0,0);
    if(!check_cancel(c)){c->result.cancelled=1;return 1;}
    c->result.scope_valid=1;
    c->icd_identity_before=st;
    c->icd_identity_valid=1;
#ifndef PANVK_WSI_HOST_TEST
    c->window=ANativeWindow_fromSurface((JNIEnv *)jni_env,(jobject)surface_object);
#else
    c->window=(ANativeWindow *)surface_object;
#endif
    if(c->window==NULL){failure(&c->result,"surface","ANativeWindow_fromSurface returned null");return 0;}
    c->result.window_owned=1;
#ifndef PANVK_WSI_HOST_TEST
    c->result.surface_width=ANativeWindow_getWidth(c->window);c->result.surface_height=ANativeWindow_getHeight(c->window);
#else
    c->result.surface_width=c->window->width;c->result.surface_height=c->window->height;
#endif
    if(c->result.surface_width!=(int)WIDTH||c->result.surface_height!=(int)HEIGHT){failure(&c->result,"surface","surface must be exactly 320x240");return 0;}
    c->result.load_attempted=1;
    c->icd=dlopen(icd_path,RTLD_NOW|RTLD_LOCAL);if(c->icd==NULL){error_s(c->result.failure,dlerror());failure(&c->result,"dlopen",c->result.failure);return 0;}c->result.load_loaded=1;c->fn.ctx=c;c->fn.gipa=(WsiGipa)resolve_symbol(c->icd,"vk_icdGetInstanceProcAddr",canonical);if(c->fn.gipa==NULL||!record_pfn(c,"vk_icdGetInstanceProcAddr",(PFN_vkVoidFunction)c->fn.gipa,canonical)){failure(&c->result,"export","vk_icdGetInstanceProcAddr owner mismatch");return 0;}
    /* vkDestroyInstance is instance-scoped: GIPA(NULL, ...) is allowed to return
       NULL. Resolve it immediately after successful instance creation instead. */
    if(!instance_extensions(c,canonical))return 0;
     c->fn.create_instance=(PFN_vkCreateInstance)c->fn.gipa(VK_NULL_HANDLE,"vkCreateInstance");if(c->fn.create_instance==NULL||!owner_is((void*)c->fn.create_instance,canonical)||!record_pfn(c,"vkCreateInstance",(PFN_vkVoidFunction)c->fn.create_instance,canonical)){failure(&c->result,"pfn","vkCreateInstance owner mismatch");return 0;}/* create_instance uses the canonical ICD GIPA already set. */
    {
        static const char *const names[]={VK_KHR_SURFACE_EXTENSION_NAME,VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
        VkApplicationInfo app={VK_STRUCTURE_TYPE_APPLICATION_INFO,NULL,"panvk-wsi",1,"wowforever",1,VK_API_VERSION_1_0_LOCAL};
        VkInstanceCreateInfo ci={VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,NULL,0U,&app,0U,NULL,2U,names};
        VkInstance created_instance=VK_NULL_HANDLE;
        if(!before_call(&c->result,"vkCreateInstance"))return 0;
        r=c->fn.create_instance(&ci,NULL,&created_instance);
        c->result.instance_called=1;c->result.instance_result=r;
        if(r==VK_SUCCESS&&created_instance!=VK_NULL_HANDLE){
            c->instance=created_instance;c->result.instance_created=1;
            /* CPU symbol lookup retained even if journaling subsequently fails,
               so a valid owned instance has its verified teardown function. */
            c->fn.destroy_instance=(PFN_vkDestroyInstance)resolve_instance(&c->fn,c->instance,"vkDestroyInstance",canonical);
        }
        if(!after_call(&c->result,"vkCreateInstance",r))return 0;
        if(r!=VK_SUCCESS||created_instance==VK_NULL_HANDLE){failure(&c->result,"instance","create failed or returned null");return 0;}
        if(c->fn.destroy_instance==NULL){failure(&c->result,"pfn","owned instance destroy PFN missing");return 0;}
    }
    c->fn.gipa=(WsiGipa)c->fn.gipa; c->fn.enum_physical=(PFN_vkEnumeratePhysicalDevices)resolve_instance(&c->fn,c->instance,"vkEnumeratePhysicalDevices",canonical);c->fn.get_properties=(PFN_vkGetPhysicalDeviceProperties)resolve_instance(&c->fn,c->instance,"vkGetPhysicalDeviceProperties",canonical);c->fn.create_surface=(PFN_vkCreateAndroidSurfaceKHR)resolve_instance(&c->fn,c->instance,"vkCreateAndroidSurfaceKHR",canonical);c->fn.destroy_surface=(PFN_vkDestroySurfaceKHR)resolve_instance(&c->fn,c->instance,"vkDestroySurfaceKHR",canonical);if(c->fn.destroy_instance==NULL||c->fn.enum_physical==NULL||c->fn.get_properties==NULL||c->fn.create_surface==NULL||c->fn.destroy_surface==NULL){failure(&c->result,"pfn","instance PFN missing");return 0;}
     {VkAndroidSurfaceCreateInfoKHR si={VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,NULL,0U,c->window};VkSurfaceKHR created_surface=VK_NULL_HANDLE;if(!before_call(&c->result,"vkCreateAndroidSurfaceKHR"))return 0;r=c->fn.create_surface(c->instance,&si,NULL,&created_surface);c->result.surface_called=1;c->result.surface_result=r;if(r==VK_SUCCESS&&created_surface!=VK_NULL_HANDLE){c->surface=created_surface;c->result.surface_created=1;}if(!after_call(&c->result,"vkCreateAndroidSurfaceKHR",r))return 0;if(r!=VK_SUCCESS||created_surface==VK_NULL_HANDLE){failure(&c->result,"surface","create failed or returned null");return 0;}}
     if(!before_call(&c->result,"vkEnumeratePhysicalDevices.count"))return 0;r=c->fn.enum_physical(c->instance,&n,NULL);if(!after_call(&c->result,"vkEnumeratePhysicalDevices.count",r))return 0;if(r!=VK_SUCCESS||n!=1U){failure(&c->result,"physical","expected exactly one physical device");return 0;}c->result.physical_count=(int)n;uint32_t physical_fill=1U;if(!before_call(&c->result,"vkEnumeratePhysicalDevices.fill"))return 0;r=c->fn.enum_physical(c->instance,&physical_fill,devices);if(!after_call(&c->result,"vkEnumeratePhysicalDevices.fill",r))return 0;if(r!=VK_SUCCESS||physical_fill!=1U){failure(&c->result,"physical","physical fill failed or changed");return 0;}c->physical=devices[0];memset(&prop,0,sizeof(prop));if(!before_call(&c->result,"vkGetPhysicalDeviceProperties"))return 0;c->fn.get_properties(c->physical,&prop);if(!after_void(&c->result,"vkGetPhysicalDeviceProperties"))return 0;if(prop.vendorID!=UINT32_C(0x13b5)||prop.deviceID!=UINT32_C(0xa8670000)||!fixed_string_equal(prop.deviceName,sizeof(prop.deviceName),"Mali-G610 MC6")){failure(&c->result,"physical","unexpected physical device");return 0;}c->result.physical_vendor=prop.vendorID;c->result.physical_device=prop.deviceID;memcpy(c->result.physical_name,prop.deviceName,sizeof(c->result.physical_name));c->result.physical_name[sizeof(c->result.physical_name)-1U]='\0';
    if(!choose_queue(c,(uint32_t *)&c->result.queue_family,canonical)||!surface_info(c,canonical))return 0;
     c->fn.enum_device_ext=(PFN_vkEnumerateDeviceExtensionProperties)resolve_instance(&c->fn,c->instance,"vkEnumerateDeviceExtensionProperties",canonical);if(c->fn.enum_device_ext==NULL){failure(&c->result,"pfn","device extension enumeration missing");return 0;}{VkExtensionProperties exts[MAX_EXTENSIONS];uint32_t ne=MAX_EXTENSIONS;int found=0;if(!before_call(&c->result,"vkEnumerateDeviceExtensionProperties.count"))return 0;r=c->fn.enum_device_ext(c->physical,NULL,&ne,NULL);if(!after_call(&c->result,"vkEnumerateDeviceExtensionProperties.count",r))return 0;if(r!=VK_SUCCESS||ne>MAX_EXTENSIONS){failure(&c->result,"device_extensions","count query failed or exceeded 256");return 0;}uint32_t fill=ne;if(!before_call(&c->result,"vkEnumerateDeviceExtensionProperties.fill"))return 0;r=c->fn.enum_device_ext(c->physical,NULL,&fill,exts);if(!after_call(&c->result,"vkEnumerateDeviceExtensionProperties.fill",r))return 0;if(r!=VK_SUCCESS||fill!=ne||fill>MAX_EXTENSIONS){failure(&c->result,"device_extensions","incomplete or changed extension fill");return 0;}c->result.device_extension_count=(int)fill;for(i=0U;i<fill;++i)if(fixed_string_equal(exts[i].extensionName,sizeof(exts[i].extensionName),VK_KHR_SWAPCHAIN_EXTENSION_NAME))found=1;if(!found){failure(&c->result,"device_extensions","swapchain extension absent");return 0;}}
       qi=(VkDeviceQueueCreateInfo){VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,NULL,0U,(uint32_t)c->result.queue_family,1U,&priority};di=(VkDeviceCreateInfo){VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,NULL,0U,1U,&qi,0U,NULL,1U,(const char *const[]){VK_KHR_SWAPCHAIN_EXTENSION_NAME},NULL};c->fn.create_device=(PFN_vkCreateDevice)resolve_instance(&c->fn,c->instance,"vkCreateDevice",canonical);c->fn.destroy_device=(PFN_vkDestroyDevice)resolve_instance(&c->fn,c->instance,"vkDestroyDevice",canonical);c->fn.gdpa=(WsiGdpa)resolve_instance(&c->fn,c->instance,"vkGetDeviceProcAddr",canonical);if(c->fn.create_device==NULL||c->fn.destroy_device==NULL||c->fn.gdpa==NULL){failure(&c->result,"pfn","device creation PFN missing");return 0;}{VkDevice created=VK_NULL_HANDLE;if(!before_call(&c->result,"vkCreateDevice"))return 0;r=c->fn.create_device(c->physical,&di,NULL,&created);c->result.device_called=1;c->result.device_result=r;if(r==VK_SUCCESS&&created!=VK_NULL_HANDLE){c->device=created;c->result.device_created=1;}if(!after_call(&c->result,"vkCreateDevice",r))return 0;if(r!=VK_SUCCESS||created==VK_NULL_HANDLE){failure(&c->result,"device",r==VK_SUCCESS?"success returned null device":"create failed");return 0;}}if(!resolve_device(c,canonical))return 0;if(!before_call(&c->result,"vkGetDeviceQueue"))return 0;c->fn.get_queue(c->device,(uint32_t)c->result.queue_family,0U,&c->queue);if(!after_void(&c->result,"vkGetDeviceQueue"))return 0;if(c->queue==VK_NULL_HANDLE){failure(&c->result,"queue","null queue");return 0;}if(!create_swapchain(c)||!create_render_objects(c))return 0;
      for(i=0U;i<MAX_FRAMES;++i){VkSemaphoreCreateInfo sem={VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,NULL,0U};VkFenceCreateInfo fe={VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,NULL,0U};VkSemaphore acquire=VK_NULL_HANDLE,present=VK_NULL_HANDLE;VkFence fence=VK_NULL_HANDLE;char stage[64];(void)snprintf(stage,sizeof(stage),"vkCreateSemaphore.acquire.%u",i);if(!before_call(&c->result,stage))return 0;r=c->fn.create_semaphore(c->device,&sem,NULL,&acquire);if(r==VK_SUCCESS&&acquire!=VK_NULL_HANDLE)c->acquire_semaphores[i]=acquire;if(!after_call(&c->result,stage,r))return 0;if(r!=VK_SUCCESS||acquire==VK_NULL_HANDLE){failure(&c->result,"frame_resources","acquire semaphore create failed");return 0;}(void)snprintf(stage,sizeof(stage),"vkCreateSemaphore.present.%u",i);if(!before_call(&c->result,stage))return 0;r=c->fn.create_semaphore(c->device,&sem,NULL,&present);if(r==VK_SUCCESS&&present!=VK_NULL_HANDLE)c->present_semaphores[i]=present;if(!after_call(&c->result,stage,r))return 0;if(r!=VK_SUCCESS||present==VK_NULL_HANDLE){failure(&c->result,"frame_resources","present semaphore create failed");return 0;}(void)snprintf(stage,sizeof(stage),"vkCreateFence.%u",i);if(!before_call(&c->result,stage))return 0;r=c->fn.create_fence(c->device,&fe,NULL,&fence);if(r==VK_SUCCESS&&fence!=VK_NULL_HANDLE)c->fences[i]=fence;if(!after_call(&c->result,stage,r))return 0;if(r!=VK_SUCCESS||fence==VK_NULL_HANDLE){failure(&c->result,"frame_resources","fence create failed");return 0;}}
    for(i=0U;i<MAX_FRAMES;++i){if(!check_cancel(c))break;if(!draw_frame(c,i))break;}
    if(c->result.journal_io_failed){if(!c->quiescent)c->result.unknown_retained=1;return 1;}
     (void)retire_queue(c);
    return 1;
}

static void classify(WsiResult *r){
    int exact_frames=1;unsigned i;
    for(i=0U;i<MAX_FRAMES;++i){const FrameResult *f=&r->frames[i];if(!f->acquire_called||!f->submit_called||!f->present_called||!f->fence_called||f->acquire_result!=VK_SUCCESS||f->submit_result!=VK_SUCCESS||f->present_result!=VK_SUCCESS||f->fence_result!=VK_SUCCESS)exact_frames=0;}
    r->cancelled|=is_cancelled(r->generation);
    if(r->cancelled)copy_s(r->status,sizeof(r->status),"CANCELLED",31U);
    else if(r->unknown_retained)copy_s(r->status,sizeof(r->status),"UNKNOWN",31U);
    else if(r->call_overflow)copy_s(r->status,sizeof(r->status),"UNKNOWN",31U);
    else if(r->journal_io_failed)copy_s(r->status,sizeof(r->status),"UNKNOWN",31U);
    else if(r->failure_stage[0]!='\0'&&strcmp(r->failure_stage,"none")!=0)copy_s(r->status,sizeof(r->status),"VK_ERROR",31U);
    else if(r->image_count>=2&&r->image_count<=(int)MAX_IMAGES&&r->frame_count==MAX_FRAMES&&r->present_success_count==MAX_FRAMES&&exact_frames&&r->queue_idle_called&&r->idle_called&&r->idle_result==VK_SUCCESS&&r->cleanup_complete&&r->output_valid&&r->clock_valid&&r->identity_stable&&!r->journal_io_failed&&!r->cancelled)copy_s(r->status,sizeof(r->status),"API_PRESENT_COMPLETED",31U);
    else copy_s(r->status,sizeof(r->status),"UNKNOWN",31U);
    r->native_exit=strcmp(r->status,"API_PRESENT_COMPLETED")==0?0:1;
}
static void close_journal(WsiCtx *c){if(c->result.journal_fd>=0){if(fsync(c->result.journal_fd)!=0){c->result.journal_io_failed=1;failure(&c->result,"journal","journal close fsync failed");}if(close(c->result.journal_fd)!=0){c->result.journal_io_failed=1;failure(&c->result,"journal","journal close failed");}c->result.journal_fd=-1;}}
static void finish(WsiCtx *c){
    if(c->icd_identity_valid){
        struct stat after;char canonical[PATH_LIMIT];
        c->result.identity_stable=hash_icd(c->result.icd,c->result.after_sha,&after)&&
            realpath(c->result.icd,canonical)!=NULL&&strcmp(canonical,c->result.owner_before)==0&&
            after.st_dev==c->icd_identity_before.st_dev&&after.st_ino==c->icd_identity_before.st_ino&&
            after.st_size==c->icd_identity_before.st_size&&
            (after.st_mode&07777U)==(c->icd_identity_before.st_mode&07777U);
        if(c->result.identity_stable)copy_s(c->result.owner_after,sizeof(c->result.owner_after),canonical,PATH_LIMIT-1U);
        else failure(&c->result,"identity","ICD changed or failed post-run verification");
    }
    cleanup(c);close_journal(c);c->result.end_ns=now_ns();
    c->result.clock_valid=c->result.start_ns>0U&&c->result.end_ns>=c->result.start_ns;
    copy_s(c->result.status,sizeof(c->result.status),"UNKNOWN",31U);c->result.native_exit=1;
}

#ifndef PANVK_WSI_HOST_TEST
JNIEXPORT jstring JNICALL Java_app_gamenative_PanvkWsiBridge_nativeRun(JNIEnv *env,jclass klass,jobject surface,jstring icd_path,jstring output_path,jstring run_id,jlong generation){const char *icd,*journal,*run;WsiCtx c; jstring answer;uint64_t expected=0U;char json[JSON_LIMIT];(void)klass;if(env==NULL||surface==NULL||icd_path==NULL||output_path==NULL||run_id==NULL||generation<=0)return NULL;if(!atomic_compare_exchange_strong_explicit(&g_started_generation,&expected,(uint64_t)generation,memory_order_acq_rel,memory_order_acquire))return NULL;if(atomic_flag_test_and_set_explicit(&g_worker,memory_order_acquire))return NULL;icd=(*env)->GetStringUTFChars(env,icd_path,NULL);journal=(*env)->GetStringUTFChars(env,output_path,NULL);run=(*env)->GetStringUTFChars(env,run_id,NULL);if(icd==NULL||journal==NULL||run==NULL){if(icd!=NULL)(*env)->ReleaseStringUTFChars(env,icd_path,icd);if(journal!=NULL)(*env)->ReleaseStringUTFChars(env,output_path,journal);if(run!=NULL)(*env)->ReleaseStringUTFChars(env,run_id,run);atomic_flag_clear_explicit(&g_worker,memory_order_release);return NULL;}run_native(&c,icd,journal,run,(uint64_t)generation,(void *)env,(void *)surface);finish(&c);if(!json_result(&c.result,json,sizeof(json))){c.result.output_valid=0;classify(&c.result);(void)json_result(&c.result,json,sizeof(json));}else{c.result.output_valid=1;classify(&c.result);if(!json_result(&c.result,json,sizeof(json))){c.result.output_valid=0;classify(&c.result);(void)json_result(&c.result,json,sizeof(json));}}answer=(*env)->NewStringUTF(env,json);(*env)->ReleaseStringUTFChars(env,icd_path,icd);(*env)->ReleaseStringUTFChars(env,output_path,journal);(*env)->ReleaseStringUTFChars(env,run_id,run);atomic_flag_clear_explicit(&g_worker,memory_order_release);return answer;}

JNIEXPORT void JNICALL Java_app_gamenative_PanvkWsiBridge_nativeCancel(JNIEnv *env,jclass klass,jlong generation){uint64_t wanted=(uint64_t)generation,old;(void)env;(void)klass;if(generation<=0)return;old=atomic_load_explicit(&g_cancelled_generation,memory_order_relaxed);while(old<wanted&&!atomic_compare_exchange_weak_explicit(&g_cancelled_generation,&old,wanted,memory_order_release,memory_order_relaxed)){} }
#else
/* Host seam: fake PFN/window policy is testable without a device or Android. */
int panvk_wsi_test_generation(uint64_t generation){uint64_t before=atomic_load(&g_cancelled_generation);if(generation>before)atomic_store(&g_cancelled_generation,generation);return is_cancelled(generation)&&is_cancelled(generation-1U);}
static int g_host_started=0;
int panvk_wsi_test_one_use(void){if(g_host_started)return 0;g_host_started=1;return 1;}
int panvk_wsi_test_path(const char *journal,const char *icd,const char *run){char dir[PATH_LIMIT];return derive_icd_dir(dir,sizeof(dir),icd,run,journal);}
int panvk_wsi_test_window(int32_t width,int32_t height){ANativeWindow w={width,height};return w.width==320&&w.height==240;}
int panvk_wsi_test_bounds(uint32_t ext,uint32_t formats,uint32_t modes,uint32_t images){return ext<=MAX_EXTENSIONS&&formats<=MAX_FORMATS&&modes<=MAX_MODES&&images>0U&&images<=MAX_IMAGES;}
int panvk_wsi_test_queue(uint32_t flags,int surface){return (flags&VK_QUEUE_GRAPHICS_BIT)!=0U&&surface!=0;}
static VkResult VKAPI_PTR wsi_fake_error_handle(VkInstance instance,const VkInstanceCreateInfo *info,const VkAllocationCallbacks *allocator,VkInstance *out){(void)instance;(void)info;(void)allocator;if(out!=NULL)*out=(VkInstance)(uintptr_t)1U;return VK_ERROR_INITIALIZATION_FAILED;}
int panvk_wsi_test_error_handle(void){VkInstance value=VK_NULL_HANDLE;return wsi_fake_error_handle(VK_NULL_HANDLE,NULL,NULL,&value)!=VK_SUCCESS&&value!=VK_NULL_HANDLE;}
int panvk_wsi_test_cleanup_guard(void){WsiCtx c;memset(&c,0,sizeof(c));c.quiescent=1;c.device=(VkDevice)(uintptr_t)1U;cleanup(&c);return c.result.unknown_retained&&c.result.cleanup_complete==0;}
int panvk_wsi_test_cancel_submitted(void){WsiCtx c;memset(&c,0,sizeof(c));c.result.generation=23U;c.submitted=1;c.quiescent=0;atomic_store(&g_cancelled_generation,23U);return !check_cancel(&c)&&c.submitted==1&&c.quiescent==0;}
static VkResult VKAPI_PTR wsi_fake_present(VkQueue queue,const VkPresentInfoKHR *info){(void)queue;return info!=NULL&&info->sType==VK_STRUCTURE_TYPE_PRESENT_INFO_KHR&&info->waitSemaphoreCount==1U&&info->swapchainCount==1U?VK_SUCCESS:VK_ERROR_INITIALIZATION_FAILED;}
static VkResult g_fake_acquire_result=VK_SUCCESS;
static VkResult VKAPI_PTR wsi_fake_acquire(VkDevice device,VkSwapchainKHR swapchain,uint64_t timeout,VkSemaphore semaphore,VkFence fence,uint32_t *image){(void)device;(void)swapchain;(void)semaphore;(void)fence;if(image!=NULL)*image=1U;return g_fake_acquire_result!=VK_SUCCESS?g_fake_acquire_result:(timeout==UINT64_C(500000000)?VK_SUCCESS:VK_TIMEOUT);}
static VkResult VKAPI_PTR wsi_fake_wait(VkDevice device,uint32_t count,const VkFence *fences,VkBool32 all,uint64_t timeout){(void)device;(void)fences;return count==1U&&all==VK_TRUE&&timeout==UINT64_C(1000000000)?VK_SUCCESS:VK_TIMEOUT;}
static VkResult g_fake_submit_result=VK_SUCCESS;static VkResult g_fake_idle_result=VK_SUCCESS;static int g_fake_cancel_after_submit=0;
static VkResult VKAPI_PTR wsi_fake_begin(VkCommandBuffer command,const VkCommandBufferBeginInfo *info){(void)command;(void)info;return VK_SUCCESS;}
static void VKAPI_PTR wsi_fake_cmd_begin(VkCommandBuffer command,const VkRenderPassBeginInfo *info,VkSubpassContents contents){(void)command;(void)info;(void)contents;}
static void VKAPI_PTR wsi_fake_cmd_bind(VkCommandBuffer command,VkPipelineBindPoint bind,VkPipeline pipeline){(void)command;(void)bind;(void)pipeline;}
static void VKAPI_PTR wsi_fake_cmd_draw(VkCommandBuffer command,uint32_t v,uint32_t i,uint32_t fv,uint32_t fi){(void)command;(void)v;(void)i;(void)fv;(void)fi;}
static void VKAPI_PTR wsi_fake_cmd_end(VkCommandBuffer command){(void)command;}
static VkResult VKAPI_PTR wsi_fake_end(VkCommandBuffer command){(void)command;return VK_SUCCESS;}
static VkResult VKAPI_PTR wsi_fake_submit(VkQueue queue,uint32_t count,const VkSubmitInfo *info,VkFence fence){(void)queue;(void)count;(void)info;(void)fence;if(g_fake_cancel_after_submit)atomic_store(&g_cancelled_generation,77U);return g_fake_submit_result;}
static VkResult VKAPI_PTR wsi_fake_idle(VkQueue queue){(void)queue;return g_fake_idle_result;}
static int g_setup_destroy_count=0;
static VkResult VKAPI_PTR wsi_fake_create_swapchain(VkDevice device,const VkSwapchainCreateInfoKHR *info,const VkAllocationCallbacks *allocator,VkSwapchainKHR *out){(void)device;(void)info;(void)allocator;if(out!=NULL)*out=(VkSwapchainKHR)(uintptr_t)0x51U;return VK_SUCCESS;}
static void VKAPI_PTR wsi_fake_destroy_swapchain(VkDevice device,VkSwapchainKHR swapchain,const VkAllocationCallbacks *allocator){(void)device;(void)swapchain;(void)allocator;++g_setup_destroy_count;}
static void VKAPI_PTR wsi_fake_destroy_device(VkDevice device,const VkAllocationCallbacks *allocator){(void)device;(void)allocator;}
int panvk_wsi_test_setup_journal_failure_cleanup(void){WsiCtx c;char path[]="/tmp/panvk-wsi-setup-XXXXXX";int fd;memset(&c,0,sizeof(c));c.quiescent=1;c.device=(VkDevice)(uintptr_t)1U;c.image_count=2U;c.result.journal_rows=(int)JOURNAL_ROWS-1;fd=mkstemp(path);if(fd<0)return 0;(void)unlink(path);c.result.journal_fd=fd;c.fn.create_swapchain=wsi_fake_create_swapchain;c.fn.destroy_swapchain=wsi_fake_destroy_swapchain;c.fn.destroy_device=wsi_fake_destroy_device;g_setup_destroy_count=0;if(create_swapchain(&c)!=0)return 0;cleanup(&c);(void)close(fd);c.result.journal_fd=-1;return g_setup_destroy_count==1&&c.result.cleanup_complete&&!c.result.unknown_retained&&c.result.journal_io_failed;}
static int wsi_fake_journal(WsiCtx *c){char path[]="/tmp/panvk-wsi-host-XXXXXX";int fd=mkstemp(path);if(fd<0)return 0;(void)unlink(path);c->result.journal_fd=fd;return 1;}
static VkResult VKAPI_PTR wsi_fake_get_swapchain_images(VkDevice device,VkSwapchainKHR swapchain,uint32_t *count,VkImage *images){(void)device;(void)swapchain;if(count==NULL)return VK_ERROR_INITIALIZATION_FAILED;if(images!=NULL){if(*count<2U)return VK_INCOMPLETE;images[0]=(VkImage)(uintptr_t)11U;images[1]=(VkImage)(uintptr_t)12U;}*count=2U;return VK_SUCCESS;}
int panvk_wsi_test_swapchain_count(void){WsiCtx c;int ok;memset(&c,0,sizeof(c));c.quiescent=1;c.image_count=2U;c.device=(VkDevice)(uintptr_t)1U;c.fn.create_swapchain=wsi_fake_create_swapchain;c.fn.get_swapchain_images=wsi_fake_get_swapchain_images;if(!wsi_fake_journal(&c))return 0;ok=create_swapchain(&c)&&c.result.image_count==2&&c.image_count==2U&&c.images[0]!=VK_NULL_HANDLE&&c.images[1]!=VK_NULL_HANDLE;close_journal(&c);return ok;}
static void wsi_fake_frame_context(WsiCtx *c,ANativeWindow *window){memset(c,0,sizeof(*c));c->result.journal_fd=-1;c->result.generation=77U;c->result.pid=1;c->result.uid=EXPECTED_UID;c->window=window;c->result.window_owned=1;c->result.surface_width=WIDTH;c->result.surface_height=HEIGHT;c->image_count=2U;c->swapchain=(VkSwapchainKHR)(uintptr_t)1U;c->queue=(VkQueue)(uintptr_t)1U;c->device=(VkDevice)(uintptr_t)1U;c->renderpass=(VkRenderPass)(uintptr_t)1U;c->pipeline=(VkPipeline)(uintptr_t)1U;c->quiescent=1;c->fn.acquire=wsi_fake_acquire;c->fn.present=wsi_fake_present;c->fn.wait_fences=wsi_fake_wait;c->fn.begin_command=wsi_fake_begin;c->fn.cmd_begin_renderpass=wsi_fake_cmd_begin;c->fn.cmd_bind_pipeline=wsi_fake_cmd_bind;c->fn.cmd_draw=wsi_fake_cmd_draw;c->fn.cmd_end_renderpass=wsi_fake_cmd_end;c->fn.end_command=wsi_fake_end;c->fn.submit=wsi_fake_submit;c->fn.queue_idle=wsi_fake_idle;c->framebuffers[0]=(VkFramebuffer)(uintptr_t)1U;c->framebuffers[1]=(VkFramebuffer)(uintptr_t)2U;c->commands[0]=(VkCommandBuffer)(uintptr_t)1U;c->commands[1]=(VkCommandBuffer)(uintptr_t)2U;c->commands[2]=(VkCommandBuffer)(uintptr_t)3U;c->acquire_semaphores[0]=(VkSemaphore)(uintptr_t)1U;c->acquire_semaphores[1]=(VkSemaphore)(uintptr_t)2U;c->acquire_semaphores[2]=(VkSemaphore)(uintptr_t)3U;c->present_semaphores[0]=(VkSemaphore)(uintptr_t)4U;c->present_semaphores[1]=(VkSemaphore)(uintptr_t)5U;c->present_semaphores[2]=(VkSemaphore)(uintptr_t)6U;c->fences[0]=(VkFence)(uintptr_t)1U;c->fences[1]=(VkFence)(uintptr_t)2U;c->fences[2]=(VkFence)(uintptr_t)3U;}
static int wsi_fake_finish_context(WsiCtx *c){int ok=retire_queue(c);c->device=VK_NULL_HANDLE;cleanup(c);close_journal(c);return ok;}
int panvk_wsi_test_fake_pfns(void){VkPresentInfoKHR present={VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,NULL,1U,(const VkSemaphore *)(uintptr_t)1U,1U,(const VkSwapchainKHR *)(uintptr_t)1U,(const uint32_t *)(uintptr_t)1U,NULL};uint32_t image=0U;return wsi_fake_present((VkQueue)(uintptr_t)1U,&present)==VK_SUCCESS&&wsi_fake_acquire((VkDevice)(uintptr_t)1U,(VkSwapchainKHR)(uintptr_t)1U,UINT64_C(500000000),(VkSemaphore)(uintptr_t)1U,VK_NULL_HANDLE,&image)==VK_SUCCESS&&wsi_fake_wait(VK_NULL_HANDLE,1U,(const VkFence *)(uintptr_t)1U,VK_TRUE,UINT64_C(1000000000))==VK_SUCCESS;}
int panvk_wsi_test_three_frames(void){WsiCtx c;ANativeWindow w={WIDTH,HEIGHT};unsigned i;g_fake_acquire_result=VK_SUCCESS;g_fake_submit_result=VK_SUCCESS;g_fake_idle_result=VK_SUCCESS;g_fake_cancel_after_submit=0;g_host_window_releases=0;atomic_store(&g_cancelled_generation,0U);wsi_fake_frame_context(&c,&w);if(!wsi_fake_journal(&c))return 2;for(i=0U;i<MAX_FRAMES;++i)if(!draw_frame(&c,i))return 3;if(!wsi_fake_finish_context(&c))return 4;if(c.result.frame_count!=MAX_FRAMES)return 5;if(c.result.present_success_count!=MAX_FRAMES)return 6;if(c.result.journal_rows!=62)return 7;if(g_host_window_releases!=1)return 8;return 0;}
int panvk_wsi_test_journal_stop(void){WsiCtx c;ANativeWindow w={WIDTH,HEIGHT};int before=0;wsi_fake_frame_context(&c,&w);c.result.journal_io_failed=1;if(before_call(&c.result,"vkCreateRenderPass"))before=1;return !before&&c.result.journal_io_failed;}
int panvk_wsi_test_journal_capacity(void){WsiResult r;memset(&r,0,sizeof(r));r.journal_fd=-1;r.journal_rows=(int)JOURNAL_ROWS;before_call(&r,"overflow");return r.journal_io_failed&&strcmp(r.failure_stage,"journal")==0;}
int panvk_wsi_test_resize(void){WsiCtx c;ANativeWindow w={321,240};wsi_fake_frame_context(&c,&w);if(!wsi_fake_journal(&c))return 0;return !draw_frame(&c,0)&&!c.result.frames[0].acquire_called;}
int panvk_wsi_test_cancel_after_submit(void){WsiCtx c;ANativeWindow w={WIDTH,HEIGHT};g_fake_acquire_result=VK_SUCCESS;g_fake_cancel_after_submit=1;atomic_store(&g_cancelled_generation,0U);wsi_fake_frame_context(&c,&w);if(!wsi_fake_journal(&c))return 0;draw_frame(&c,0);g_fake_cancel_after_submit=0;return c.result.frames[0].submit_called&&!c.result.frames[0].present_called;}
int panvk_wsi_test_idle_failure_retains_window(void){WsiCtx c;ANativeWindow w={WIDTH,HEIGHT};g_fake_acquire_result=VK_SUCCESS;g_fake_idle_result=VK_ERROR_DEVICE_LOST;wsi_fake_frame_context(&c,&w);if(!wsi_fake_journal(&c))return 0;if(!draw_frame(&c,0)){}(void)retire_queue(&c);cleanup(&c);g_fake_idle_result=VK_SUCCESS;return c.result.unknown_retained&&!c.window_released;}
int panvk_wsi_test_null_error_safe(void){WsiCtx c;memset(&c,0,sizeof(c));c.quiescent=1;c.device=(VkDevice)(uintptr_t)1U;c.result.device_called=1;c.result.device_result=VK_ERROR_INITIALIZATION_FAILED;cleanup(&c);return c.result.unknown_retained&&!c.result.cleanup_complete;}
int panvk_wsi_test_partial_destroy_safe(void){WsiCtx c;memset(&c,0,sizeof(c));c.quiescent=1;c.device=(VkDevice)(uintptr_t)1U;c.fn.destroy_device=NULL;cleanup(&c);return c.result.unknown_retained&&!c.result.cleanup_complete;}
int panvk_wsi_test_pfn_dedup(void){WsiCtx c;char path[PATH_LIMIT];memset(&c,0,sizeof(c));if(!owner_path((void *)wsi_fake_present,path,sizeof(path)))return 0;if(!record_pfn(&c,"vkFake",(PFN_vkVoidFunction)wsi_fake_present,path))return 0;if(!record_pfn(&c,"vkFake",(PFN_vkVoidFunction)wsi_fake_present,path))return 0;if(c.result.pfn_count!=1U)return 0;memset(&c,0,sizeof(c));return !record_pfn(&c,"vkFake",(PFN_vkVoidFunction)wsi_fake_present,"/not-the-owner");}
int panvk_wsi_test_present_dependency(void){VkSubpassDependency dep={0U,VK_SUBPASS_EXTERNAL,VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,0U,0U};return dep.srcSubpass==0U&&dep.dstSubpass==VK_SUBPASS_EXTERNAL&&(dep.srcAccessMask&VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)!=0U&&dep.dstAccessMask==0U&&dep.dstStageMask==VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;}
int panvk_wsi_test_encode(char *out,size_t cap){WsiResult r;unsigned i;memset(&r,0,sizeof(r));copy_s(r.run,sizeof(r.run),"PWSI-0123456789abcdef0123456789abcdef",RUN_LENGTH);r.pid=7;r.uid=EXPECTED_UID;r.generation=12U;r.scope_valid=1;r.proc_valid=1;r.load_attempted=1;r.load_loaded=1;r.instance_created=1;r.device_created=1;r.surface_width=320;r.surface_height=240;r.image_count=2;r.frame_count=3;r.present_success_count=3;r.cleanup_complete=1;r.queue_idle_called=1;r.idle_called=1;r.clock_valid=1;r.output_valid=1;r.instance_called=1;r.surface_called=1;r.device_called=1;r.swapchain_called=1;r.instance_result=VK_SUCCESS;r.surface_result=VK_SUCCESS;r.device_result=VK_SUCCESS;r.swapchain_result=VK_SUCCESS;r.idle_result=VK_SUCCESS;r.selected_format=VK_FORMAT_R8G8B8A8_UNORM;r.selected_colorspace=VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;r.pre_transform=VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;r.composite_alpha=VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;r.physical_vendor=UINT32_C(0x13b5);r.physical_device=UINT32_C(0xa8670000);copy_s(r.physical_name,sizeof(r.physical_name),"Mali-G610 MC6",sizeof(r.physical_name)-1U);r.queue_flags=VK_QUEUE_GRAPHICS_BIT;r.queue_surface_support=VK_TRUE;for(i=0U;i<MAX_FRAMES;++i){r.frames[i].image_index=i%2U;r.frames[i].acquire_called=1;r.frames[i].submit_called=1;r.frames[i].present_called=1;r.frames[i].fence_called=1;r.frames[i].acquire_result=VK_SUCCESS;r.frames[i].submit_result=VK_SUCCESS;r.frames[i].present_result=VK_SUCCESS;r.frames[i].fence_result=VK_SUCCESS;}copy_s(r.status,sizeof(r.status),"API_PRESENT_COMPLETED",31U);copy_s(r.failure_stage,sizeof(r.failure_stage),"none",63U);return json_result(&r,out,cap);}
int panvk_wsi_test_negative_json(char *out,size_t cap,int kind){WsiResult *r;WsiCtx c;ANativeWindow w={WIDTH,HEIGHT};int fd;memset(&c,0,sizeof(c));r=&c.result;copy_s(r->run,sizeof(r->run),"PWSI-0123456789abcdef0123456789abcdef",RUN_LENGTH);r->pid=7;r->uid=EXPECTED_UID;r->generation=31U;r->surface_width=WIDTH;r->surface_height=HEIGHT;r->output_valid=1;r->native_exit=1;if(kind==1){r->journal_fd=-1;before_call(r,"vkCreateInstance");classify(r);return json_result(r,out,cap);}if(kind==2){fd=open("/dev/null",O_WRONLY|O_CLOEXEC);if(fd<0)return 0;r->journal_fd=fd;r->journal_rows=(int)JOURNAL_ROWS;before_call(r,"vkCreateInstance");classify(r);(void)close(fd);r->journal_fd=-1;return json_result(r,out,cap);}if(kind==4){r->scope_valid=1;r->load_attempted=1;r->load_loaded=0;copy_s(r->failure_stage,sizeof(r->failure_stage),"dlopen",63U);copy_s(r->failure,sizeof(r->failure),"ICD dlopen failed",ERROR_LIMIT);copy_s(r->status,sizeof(r->status),"VK_ERROR",31U);return json_result(r,out,cap);}g_fake_acquire_result=VK_ERROR_DEVICE_LOST;wsi_fake_frame_context(&c,&w);if(!wsi_fake_journal(&c))return 0;(void)draw_frame(&c,0U);c.device=VK_NULL_HANDLE;cleanup(&c);close_journal(&c);copy_s(r->status,sizeof(r->status),"VK_ERROR",31U);r->native_exit=1;g_fake_acquire_result=VK_SUCCESS;return json_result(r,out,cap);}
#endif
