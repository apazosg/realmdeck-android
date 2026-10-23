#define _GNU_SOURCE

/*
 * This is deliberately a sibling of DriReturnObserver.c, not an evolution of
 * it.  The only guest memory observed here is four bytes at screen + 64 before
 * and after one forwarded call.  The provider/layout gate is intentionally
 * strict: a failed gate is a control-invalid child, never a best-effort
 * observation.
 */

#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>

#ifndef ELF64_ST_VISIBILITY
#define ELF64_ST_VISIBILITY(value) ((value) & 0x3U)
#endif

#define OBSERVER_SCHEMA "wowforever.android-dri-version-observer/version1"
#define EXPECTED_UID 10330U
#define MAX_RUN_ID 38U
#define MAX_PATH_STRING 4096U
#define MAX_RECORD 3072U
#define MAX_OUTPUT_BYTES 4096U
#define MAX_RECORDS 4U
#define MAX_ATTRIBUTE_PAIRS 16U
#define MAX_ATTRIBUTE_VALUES (MAX_ATTRIBUTE_PAIRS * 2U)
#define PROVIDER_RELATIVE "guest/lib/libgallium-26.2.4.so"
#define OBSERVER_LIBRARY "libdri_version_observer.so"
#define EXPECTED_PROVIDER_SIZE UINT64_C(17656040)
#define EXPECTED_PROVIDER_SHA256 "910cc41d1a77427dafd16c8049425504576879a30767aa7473ab70629ed348b5"
#define DRI_FUNCTION_VA UINT64_C(0xaa856c)
#define DRI_SCREEN_FIELD_OFFSET UINT64_C(64)
#define DRI_MAXIMUM_FIELD_VA UINT64_C(0xaa88b4)
#define DRI_API3_SELECTION_VA UINT64_C(0xaa8870)

typedef void *(*DriCreateContextAttribsFn)(void *, int, const void *, void *, unsigned,
                                           const uint32_t *, unsigned *error, void *data, bool thread_safe);

void *driCreateContextAttribs(void *, int, const void *, void *, unsigned,
                              const uint32_t *, unsigned *, void *, bool);

typedef struct AttributeCapture {
    uint32_t values[MAX_ATTRIBUTE_VALUES];
    unsigned pairs;
    int observed;
    int oversized;
} AttributeCapture;

typedef struct ReadResult {
    int attempted;
    long bytes;
    int error;
    uint32_t value;
    int valueValid;
} ReadResult;

static _Atomic unsigned call_sequence;
static _Atomic unsigned record_count;
static _Atomic unsigned output_bytes;
static __thread int recursion_guard;

#ifndef DRI_VERSION_OBSERVER_HOST_TEST
static int path_join(char *, size_t, const char *, const char *);
static int canonical_path(const char *, char *, size_t);
static int validate_cmdline(const char *, const char *);
#endif

static size_t bounded_length(const char *value, size_t limit, int *terminated) {
    size_t index;
    if (terminated != NULL) *terminated = 0;
    if (value == NULL) return 0U;
    for (index = 0U; index < limit; ++index) {
        if (value[index] == '\0') {
            if (terminated != NULL) *terminated = 1;
            return index;
        }
    }
    return limit;
}

static int copy_bounded(char *out, size_t capacity, const char *value, size_t limit) {
    int terminated = 0;
    size_t length;
    if (out == NULL || capacity == 0U || value == NULL) return 0;
    length = bounded_length(value, limit, &terminated);
    if (!terminated || length + 1U > capacity) return 0;
    memcpy(out, value, length);
    out[length] = '\0';
    return 1;
}

#ifndef DRI_VERSION_OBSERVER_HOST_TEST
static int valid_run_id(const char *run_id) {
    size_t index;
    if (run_id == NULL || bounded_length(run_id, MAX_RUN_ID + 1U, NULL) != MAX_RUN_ID ||
        memcmp(run_id, "VTEST-", 6U) != 0) return 0;
    for (index = 6U; index < MAX_RUN_ID; ++index) {
        unsigned char value = (unsigned char)run_id[index];
        if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) return 0;
    }
    return 1;
}

static int path_join(char *out, size_t capacity, const char *base, const char *relative) {
    int written;
    if (out == NULL || base == NULL || relative == NULL) return 0;
    written = snprintf(out, capacity, "%s/%s", base, relative);
    return written > 0 && (size_t)written < capacity;
}

static int cmdline_text_equal(const unsigned char *value, size_t length, const char *expected) {
    size_t expected_length;
    if (value == NULL || expected == NULL) return 0;
    expected_length = strlen(expected);
    return length == expected_length && memcmp(value, expected, length) == 0;
}

static int validate_cmdline(const char *expected_executable, const char *run_id) {
    unsigned char data[4096U];
    const unsigned char *arguments[4];
    size_t lengths[4];
    size_t used = 0U, start = 0U, argument_count = 0U, index;
    int fd;
    if (expected_executable == NULL || run_id == NULL) return 0;
    fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return 0;
    while (used < sizeof(data)) {
        ssize_t count = read(fd, data + used, sizeof(data) - used);
        if (count > 0) used += (size_t)count;
        else if (count == 0) break;
        else if (errno != EINTR) { (void)close(fd); return 0; }
    }
    if (close(fd) != 0 || used == 0U || used == sizeof(data) || data[used - 1U] != '\0') return 0;
    for (index = 0U; index < used; ++index) {
        if (data[index] == '\0') {
            if (index == start || argument_count >= 4U) return 0;
            arguments[argument_count] = data + start;
            lengths[argument_count++] = index - start;
            start = index + 1U;
        }
    }
    if (start != used) return 0;
    if (argument_count == 4U && cmdline_text_equal(arguments[0], lengths[0], "/system/bin/linker64")) {
        start = 1U;
    } else if (argument_count != 3U) return 0;
    return argument_count == start + 3U &&
           cmdline_text_equal(arguments[start], lengths[start], expected_executable) &&
           cmdline_text_equal(arguments[start + 1U], lengths[start + 1U], "--run-id") &&
           cmdline_text_equal(arguments[start + 2U], lengths[start + 2U], run_id);
}

static int scoped_run(char *run_id, size_t capacity) {
    static const char *const prefixes[] = {
        "/data/user/0/com.wowforever.arm64probe/files/forever/controls/vtest-loader/",
        "/data/data/com.wowforever.arm64probe/files/forever/controls/vtest-loader/"
    };
    char canonical[MAX_PATH_STRING], expected_observer[MAX_PATH_STRING];
    char expected_executable[MAX_PATH_STRING], canonical_executable[MAX_PATH_STRING];
    const char *preload;
    size_t index;
    if (getuid() != (uid_t)EXPECTED_UID || realpath(".", canonical) == NULL ||
        !path_join(expected_observer, sizeof(expected_observer), canonical,
                   "guest/observer/" OBSERVER_LIBRARY) ||
        !path_join(expected_executable, sizeof(expected_executable), canonical,
                   "guest/bin/GuestEglDriVersion") ||
        !canonical_path(expected_executable, canonical_executable, sizeof(canonical_executable)) ||
        strcmp(canonical_executable, expected_executable) != 0) return 0;
    preload = getenv("LD_PRELOAD");
    if (preload == NULL || strcmp(preload, expected_observer) != 0) return 0;
    for (index = 0U; index < sizeof(prefixes) / sizeof(prefixes[0]); ++index) {
        size_t prefix_length = strlen(prefixes[index]);
        if (strncmp(canonical, prefixes[index], prefix_length) == 0 &&
            valid_run_id(canonical + prefix_length) && strchr(canonical + prefix_length, '/') == NULL &&
            copy_bounded(run_id, capacity, canonical + prefix_length, MAX_RUN_ID + 1U))
            return validate_cmdline(expected_executable, run_id);
    }
    return 0;
}

static int stat_identity(const char *path, struct stat *snapshot) {
    struct stat listed, opened;
    int fd, stat_result, close_result;
    if (path == NULL || snapshot == NULL || lstat(path, &listed) != 0 || !S_ISREG(listed.st_mode) ||
        listed.st_uid != getuid()) return 0;
    fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return 0;
    stat_result = fstat(fd, &opened);
    close_result = close(fd);
    if (stat_result != 0 || close_result != 0 || !S_ISREG(opened.st_mode) || opened.st_uid != getuid() ||
        opened.st_dev != listed.st_dev || opened.st_ino != listed.st_ino || opened.st_size != listed.st_size ||
        (opened.st_mode & 07777U) != (listed.st_mode & 07777U)) return 0;
    *snapshot = opened;
    return 1;
}

static int same_identity(const struct stat *before, const struct stat *after) {
    return before != NULL && after != NULL && before->st_dev == after->st_dev &&
           before->st_ino == after->st_ino && before->st_size == after->st_size &&
           (before->st_mode & 07777U) == (after->st_mode & 07777U);
}

static int canonical_path(const char *path, char *out, size_t capacity) {
    char *resolved;
    int result;
    if (path == NULL || out == NULL || capacity == 0U) return 0;
    resolved = realpath(path, NULL);
    if (resolved == NULL) return 0;
    result = copy_bounded(out, capacity, resolved, capacity - 1U);
    free(resolved);
    return result;
}

static int address_owner(void *address, char *owner, size_t capacity) {
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (address == NULL || dladdr(address, &info) == 0 || info.dli_fname == NULL) return 0;
    return canonical_path(info.dli_fname, owner, capacity);
}
#endif

#ifdef DRI_VERSION_OBSERVER_HOST_TEST
static int same_identity(const struct stat *before, const struct stat *after) {
    return before != NULL && after != NULL && before->st_dev == after->st_dev &&
           before->st_ino == after->st_ino && before->st_size == after->st_size &&
           (before->st_mode & 07777U) == (after->st_mode & 07777U);
}
#endif

/* Small self-contained SHA-256, used only for the bounded provider identity gate. */
typedef struct Sha256 { uint32_t h[8]; uint64_t bits; unsigned char block[64]; size_t used; } Sha256;
static uint32_t sha_rotr(uint32_t x, unsigned n) { return (x >> n) | (x << (32U - n)); }
static const uint32_t sha_k[64] = {
    0x428a2f98U,0x71374491U,0xb5c0fbcfU,0xe9b5dba5U,0x3956c25bU,0x59f111f1U,0x923f82a4U,0xab1c5ed5U,
    0xd807aa98U,0x12835b01U,0x243185beU,0x550c7dc3U,0x72be5d74U,0x80deb1feU,0x9bdc06a7U,0xc19bf174U,
    0xe49b69c1U,0xefbe4786U,0x0fc19dc6U,0x240ca1ccU,0x2de92c6fU,0x4a7484aaU,0x5cb0a9dcU,0x76f988daU,
    0x983e5152U,0xa831c66dU,0xb00327c8U,0xbf597fc7U,0xc6e00bf3U,0xd5a79147U,0x06ca6351U,0x14292967U,
    0x27b70a85U,0x2e1b2138U,0x4d2c6dfcU,0x53380d13U,0x650a7354U,0x766a0abbU,0x81c2c92eU,0x92722c85U,
    0xa2bfe8a1U,0xa81a664bU,0xc24b8b70U,0xc76c51a3U,0xd192e819U,0xd6990624U,0xf40e3585U,0x106aa070U,
    0x19a4c116U,0x1e376c08U,0x2748774cU,0x34b0bcb5U,0x391c0cb3U,0x4ed8aa4aU,0x5b9cca4fU,0x682e6ff3U,
    0x748f82eeU,0x78a5636fU,0x84c87814U,0x8cc70208U,0x90befffaU,0xa4506cebU,0xbef9a3f7U,0xc67178f2U
};
static void sha_init(Sha256 *s) {
    static const uint32_t initial[8] = {0x6a09e667U,0xbb67ae85U,0x3c6ef372U,0xa54ff53aU,0x510e527fU,0x9b05688cU,0x1f83d9abU,0x5be0cd19U};
    memcpy(s->h, initial, sizeof(initial)); s->bits = 0U; s->used = 0U;
}
static void sha_block(Sha256 *s, const unsigned char *p) {
    uint32_t w[64], a,b,c,d,e,f,g,h,t1,t2; unsigned i;
    for (i=0U;i<16U;++i) w[i]=(uint32_t)p[i*4U]<<24|(uint32_t)p[i*4U+1U]<<16|(uint32_t)p[i*4U+2U]<<8|p[i*4U+3U];
    for (i=16U;i<64U;++i) { uint32_t q=sha_rotr(w[i-15U],7U)^sha_rotr(w[i-15U],18U)^(w[i-15U]>>3U); uint32_t r=sha_rotr(w[i-2U],17U)^sha_rotr(w[i-2U],19U)^(w[i-2U]>>10U); w[i]=w[i-16U]+q+w[i-7U]+r; }
    a=s->h[0];b=s->h[1];c=s->h[2];d=s->h[3];e=s->h[4];f=s->h[5];g=s->h[6];h=s->h[7];
    for (i=0U;i<64U;++i) { uint32_t S1=sha_rotr(e,6U)^sha_rotr(e,11U)^sha_rotr(e,25U); uint32_t ch=(e&f)^((~e)&g); uint32_t S0=sha_rotr(a,2U)^sha_rotr(a,13U)^sha_rotr(a,22U); uint32_t maj=(a&b)^(a&c)^(b&c); t1=h+S1+ch+sha_k[i]+w[i]; t2=S0+maj; h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2; }
    s->h[0]+=a;s->h[1]+=b;s->h[2]+=c;s->h[3]+=d;s->h[4]+=e;s->h[5]+=f;s->h[6]+=g;s->h[7]+=h;
}
static void sha_update(Sha256 *s, const unsigned char *p, size_t n) {
    s->bits += (uint64_t)n * 8U;
    while (n != 0U) { size_t take=64U-s->used; if (take>n) take=n; memcpy(s->block+s->used,p,take); s->used+=take; p+=take; n-=take; if (s->used==64U) { sha_block(s,s->block); s->used=0U; } }
}
static void sha_final(Sha256 *s, unsigned char out[32]) {
    size_t i; uint64_t bits=s->bits; s->block[s->used++]=0x80U;
    while (s->used != 56U) { if (s->used==64U) { sha_block(s,s->block); s->used=0U; } s->block[s->used++]=0U; }
    for (i=0U;i<8U;++i) s->block[56U+i]=(unsigned char)(bits>>(56U-8U*i));
    sha_block(s,s->block);
    for (i=0U;i<8U;++i) { out[i*4U]=(unsigned char)(s->h[i]>>24); out[i*4U+1U]=(unsigned char)(s->h[i]>>16); out[i*4U+2U]=(unsigned char)(s->h[i]>>8); out[i*4U+3U]=(unsigned char)s->h[i]; }
}

#ifdef DRI_VERSION_OBSERVER_HOST_TEST
int dri_observer_test_scope = 1;
int dri_observer_test_layout = 1;
int dri_observer_test_read_mode;
DriCreateContextAttribsFn dri_observer_test_provider;
void dri_observer_test_set_provider(DriCreateContextAttribsFn provider) { dri_observer_test_provider = provider; }
void dri_observer_test_set_scope(int valid) { dri_observer_test_scope = valid; }
void dri_observer_test_set_layout(int valid) { dri_observer_test_layout = valid; }
void dri_observer_test_set_read_mode(int mode) { dri_observer_test_read_mode = mode; }
int dri_observer_test_sha256_hex(const unsigned char *data, size_t length, char *out, size_t capacity) {
    static const char digits[]="0123456789abcdef"; Sha256 s; unsigned char digest[32]; size_t i;
    if (data == NULL || out == NULL || capacity < 65U) return 0;
    sha_init(&s); sha_update(&s,data,length); sha_final(&s,digest);
    for(i=0U;i<32U;++i){out[i*2U]=digits[digest[i]>>4];out[i*2U+1U]=digits[digest[i]&15U];} out[64]='\0'; return 1;
}
void dri_observer_test_reset(void) { atomic_store(&call_sequence,0U); atomic_store(&record_count,0U); atomic_store(&output_bytes,0U); dri_observer_test_scope=1; dri_observer_test_layout=1; dri_observer_test_read_mode=0; }
#endif

static int read_file_exact(int fd, uint64_t offset, void *buffer, size_t size) {
    size_t used=0U;
    if (offset > (uint64_t)INT64_MAX || buffer == NULL) return 0;
    while (used < size) { ssize_t n=pread(fd,(unsigned char *)buffer+used,size-used,(off_t)offset+used); if(n<=0) return 0; used+=(size_t)n; }
    return 1;
}
static int range_ok(uint64_t offset, uint64_t size, uint64_t file_size) { return offset <= file_size && size <= file_size-offset; }
static int map_va(const Elf64_Phdr *phdr, size_t count, uint64_t va, uint64_t size, uint64_t file_size, uint64_t *offset) {
    size_t i;
    if (offset == NULL) return 0;
    for(i=0U;i<count;++i) if(phdr[i].p_type==PT_LOAD && va>=phdr[i].p_vaddr && va-phdr[i].p_vaddr<=phdr[i].p_filesz && size<=phdr[i].p_filesz-(va-phdr[i].p_vaddr)) {
        uint64_t candidate=phdr[i].p_offset+(va-phdr[i].p_vaddr); if(candidate>=phdr[i].p_offset && range_ok(candidate,size,file_size)){*offset=candidate;return 1;}
    }
    return 0;
}

static int provider_layout_verified(int fd, uint64_t file_size) {
    Elf64_Ehdr eh; Elf64_Phdr *phdr=NULL; Elf64_Dyn dyn[128]; uint64_t dynamic_va=0U,dynamic_size=0U;
    uint64_t sym_va=0U,str_va=0U,str_size=0U,syment=0U,gnu_hash_va=0U; uint64_t dynamic_off;
    uint64_t sym_off,str_off,hash_off; unsigned long long symbol_count=0U; unsigned i; int result=0;
    static const unsigned char expected[] = {
        0x9f,0x11,0x00,0x71, 0x60,0x09,0x46,0x7a, 0xe8,0xf7,0xff,0x54,
        0x9f,0x0d,0x00,0x71, 0xee,0x17,0x9f,0x1a, 0x7f,0x0d,0x00,0x71,
        0xeb,0x93,0x8e,0x1a, 0x9f,0x0d,0x00,0x71, 0x4e,0x00,0x80,0x52,
        0x23,0xf7,0xff,0x54, 0x0b,0xf7,0x07,0x37, 0x0b,0x08,0x80,0x52,
        0x05,0x00,0x00,0x14, 0x6b,0x01,0x0c,0x2a, 0x7f,0x09,0x00,0x71,
        0x42,0xf6,0xff,0x54, 0x0b,0x09,0x80,0x52, 0x0b,0x68,0x6b,0xb8,
        0x6e,0x00,0x80,0x52, 0xeb,0xf5,0xff,0x34, 0xbf,0x01,0x0b,0x6b,
        0xa8,0xf5,0xff,0x54
    };
    if (!read_file_exact(fd,0U,&eh,sizeof(eh)) || memcmp(eh.e_ident,ELFMAG,SELFMAG)!=0 ||
        eh.e_ident[EI_CLASS]!=ELFCLASS64 || eh.e_ident[EI_DATA]!=ELFDATA2LSB || eh.e_type!=ET_DYN ||
        eh.e_machine!=EM_AARCH64 || eh.e_phentsize!=sizeof(Elf64_Phdr) || eh.e_phnum==0U || eh.e_phnum>128U ||
        eh.e_phoff > file_size || (uint64_t)eh.e_phnum*sizeof(Elf64_Phdr)>file_size-eh.e_phoff) return 0;
    phdr=(Elf64_Phdr *)calloc(eh.e_phnum,sizeof(*phdr)); if(phdr==NULL) return 0;
    if(!read_file_exact(fd,eh.e_phoff,phdr,(size_t)eh.e_phnum*sizeof(*phdr))) goto done;
    for(i=0U;i<eh.e_phnum;++i) if(phdr[i].p_type==PT_DYNAMIC){dynamic_va=phdr[i].p_vaddr;dynamic_size=phdr[i].p_filesz;break;}
    if(dynamic_va==0U || dynamic_size==0U || dynamic_size>sizeof(dyn)*sizeof(*dyn) || !map_va(phdr,eh.e_phnum,dynamic_va,dynamic_size,file_size,&dynamic_off) || !read_file_exact(fd,dynamic_off,dyn,(size_t)dynamic_size)) goto done;
    for(i=0U;i<dynamic_size/sizeof(Elf64_Dyn);++i) { if(dyn[i].d_tag==DT_NULL) break; if(dyn[i].d_tag==DT_SYMTAB)sym_va=dyn[i].d_un.d_ptr; else if(dyn[i].d_tag==DT_STRTAB)str_va=dyn[i].d_un.d_ptr; else if(dyn[i].d_tag==DT_STRSZ)str_size=dyn[i].d_un.d_val; else if(dyn[i].d_tag==DT_SYMENT)syment=dyn[i].d_un.d_val; else if(dyn[i].d_tag==DT_GNU_HASH)gnu_hash_va=dyn[i].d_un.d_ptr; }
    if(sym_va==0U||str_va==0U||str_size==0U||syment!=sizeof(Elf64_Sym)||gnu_hash_va==0U||!map_va(phdr,eh.e_phnum,sym_va,sizeof(Elf64_Sym),file_size,&sym_off)||!map_va(phdr,eh.e_phnum,str_va,str_size,file_size,&str_off)||!map_va(phdr,eh.e_phnum,gnu_hash_va,16U,file_size,&hash_off)) goto done;
    { uint32_t head[4], max_bucket=0U; uint64_t buckets_off,chains_off; if(!read_file_exact(fd,hash_off,head,sizeof(head))||head[0]==0U||head[1]>100000U||head[2]>UINT32_MAX/sizeof(uint64_t))goto done; buckets_off=hash_off+16U+(uint64_t)head[2]*sizeof(uint64_t); if(!range_ok(buckets_off,(uint64_t)head[0]*sizeof(uint32_t),file_size))goto done; for(i=0U;i<head[0];++i){uint32_t b; if(!read_file_exact(fd,buckets_off+(uint64_t)i*sizeof(uint32_t),&b,sizeof(b)))goto done; if(b>max_bucket)max_bucket=b;} if(max_bucket<head[1]) symbol_count=head[1]; else {chains_off=buckets_off+(uint64_t)head[0]*sizeof(uint32_t)+(uint64_t)(max_bucket-head[1])*sizeof(uint32_t); for(symbol_count=(unsigned long long)max_bucket+1U;symbol_count<100000U;++symbol_count){uint32_t chain; if(!read_file_exact(fd,chains_off+(symbol_count-max_bucket-1U)*sizeof(uint32_t),&chain,sizeof(chain)))goto done; if((chain&1U)!=0U){++symbol_count;break;}} if(symbol_count>=100000U)goto done;} }
    {
        uint64_t instruction_offset;
        unsigned char actual[sizeof(expected)];
        if (!map_va(phdr, eh.e_phnum, DRI_API3_SELECTION_VA, sizeof(expected),
                    file_size, &instruction_offset) ||
            !read_file_exact(fd, instruction_offset, actual, sizeof(actual)) ||
            memcmp(actual, expected, sizeof(actual)) != 0) goto done;
    }
    {
        static const char target[] = "driCreateContextAttribs";
        Elf64_Sym symbol;
        unsigned long long index;
        int target_found = 0;
        for (index = 0U; index < symbol_count; ++index) {
            uint64_t symbol_offset, name_offset;
            unsigned char name[sizeof(target)];
            if (!map_va(phdr, eh.e_phnum, sym_va + index * syment, sizeof(symbol),
                        file_size, &symbol_offset) ||
                !read_file_exact(fd, symbol_offset, &symbol, sizeof(symbol))) goto done;
            if (symbol.st_name >= str_size ||
                !map_va(phdr, eh.e_phnum, str_va + symbol.st_name, sizeof(name),
                        file_size, &name_offset) ||
                !read_file_exact(fd, name_offset, name, sizeof(name)) ||
                memcmp(name, target, sizeof(name)) != 0) continue;
            target_found = 1;
            if (symbol.st_value != DRI_FUNCTION_VA || symbol.st_size != 896U ||
                symbol.st_shndx == SHN_UNDEF || ELF64_ST_BIND(symbol.st_info) != STB_GLOBAL ||
                ELF64_ST_TYPE(symbol.st_info) != STT_FUNC ||
                ELF64_ST_VISIBILITY(symbol.st_other) != STV_DEFAULT) goto done;
            break;
        }
        result = target_found;
    }
done: free(phdr); return result;
}

static int sha256_file(const char *path, uint64_t expected_size, char out[65]) {
    static const char digits[] = "0123456789abcdef";
    unsigned char buffer[65536U], digest[32];
    Sha256 sha;
    struct stat listed, opened;
    uint64_t total = 0U;
    ssize_t got = 0;
    int fd = -1;
    int ok = 0;
    size_t i;

    if (path == NULL || out == NULL || lstat(path, &listed) != 0 || !S_ISREG(listed.st_mode) ||
        (uint64_t)listed.st_size != expected_size) return 0;
    fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0 || fstat(fd, &opened) != 0 || !same_identity(&listed, &opened)) goto done;
    sha_init(&sha);
    while ((got = read(fd, buffer, sizeof(buffer))) > 0) {
        if (total > expected_size - (uint64_t)got) goto done;
        sha_update(&sha, buffer, (size_t)got);
        total += (uint64_t)got;
    }
    if (got < 0 || total != expected_size || fstat(fd, &opened) != 0 ||
        !same_identity(&listed, &opened)) goto done;
    sha_final(&sha, digest);
    for (i = 0U; i < sizeof(digest); ++i) {
        out[i * 2U] = digits[digest[i] >> 4];
        out[i * 2U + 1U] = digits[digest[i] & 15U];
    }
    out[64] = '\0';
    ok = 1;
done:
    if (fd >= 0 && close(fd) != 0) ok = 0;
    return ok;
}

static int provider_file_verified(const char *path) {
    struct stat listed, opened;
    char digest[65];
    int fd = -1;
    int ok = 0;

    if (path == NULL || lstat(path, &listed) != 0 || !S_ISREG(listed.st_mode) ||
        listed.st_uid != getuid() || (uint64_t)listed.st_size != EXPECTED_PROVIDER_SIZE ||
        !sha256_file(path, EXPECTED_PROVIDER_SIZE, digest) ||
        strcmp(digest, EXPECTED_PROVIDER_SHA256) != 0) return 0;
    fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0 || fstat(fd, &opened) != 0 || !same_identity(&listed, &opened)) goto done;
    ok = provider_layout_verified(fd, EXPECTED_PROVIDER_SIZE);
done:
    if (fd >= 0 && close(fd) != 0) ok = 0;
    return ok;
}

#ifdef DRI_VERSION_OBSERVER_HOST_TEST
int dri_observer_test_provider_layout_path(const char *path) {
    struct stat snapshot;
    int fd = -1;
    int ok = 0;
    if (path == NULL || lstat(path, &snapshot) != 0 || !S_ISREG(snapshot.st_mode)) return 0;
    fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd >= 0) ok = provider_layout_verified(fd, (uint64_t)snapshot.st_size);
    if (fd >= 0 && close(fd) != 0) ok = 0;
    return ok;
}

int dri_observer_test_provider_file_verified(const char *path) {
    return provider_file_verified(path);
}
#endif

static int resolve_provider(DriCreateContextAttribsFn *result, void **retained_handle, int *layout_verified) {
#ifdef DRI_VERSION_OBSERVER_HOST_TEST
    if(result==NULL||retained_handle==NULL||layout_verified==NULL||!dri_observer_test_scope||!dri_observer_test_layout||dri_observer_test_provider==NULL)return 0;
    *result=dri_observer_test_provider;*retained_handle=NULL;*layout_verified=1;return 1;
#else
    char cwd[MAX_PATH_STRING], path[MAX_PATH_STRING], canonical[MAX_PATH_STRING], owner[MAX_PATH_STRING], self[MAX_PATH_STRING];
    struct stat before, after;
    void *handle, *symbol;
    const char *error;
    if(result==NULL||retained_handle==NULL||layout_verified==NULL||!scoped_run(cwd,sizeof(cwd))||realpath(".",cwd)==NULL||!path_join(path,sizeof(path),cwd,PROVIDER_RELATIVE)||!stat_identity(path,&before)||(uint64_t)before.st_size!=EXPECTED_PROVIDER_SIZE||!canonical_path(path,canonical,sizeof(canonical))||strcmp(path,canonical)!=0||!provider_file_verified(path))return 0;
    handle=dlopen(path, RTLD_NOLOAD | RTLD_NOW | RTLD_LOCAL);if(handle==NULL)return 0;(void)dlerror();symbol=dlsym(handle,"driCreateContextAttribs");error=dlerror();
    if(symbol==NULL||error!=NULL||!address_owner(symbol,owner,sizeof(owner))||strcmp(owner,canonical)!=0||!address_owner((void *)&driCreateContextAttribs,self,sizeof(self))||strcmp(owner,self)==0||!stat_identity(path,&after)||!same_identity(&before,&after)){(void)dlclose(handle);return 0;}
    memcpy(result,&symbol,sizeof(symbol));*retained_handle=handle;*layout_verified=1;return *result!=NULL;
#endif
}

static int capture_attributes(AttributeCapture *capture, unsigned count, const uint32_t *attributes) {
    unsigned index;
    memset(capture,0,sizeof(*capture));
    if(count==0U){capture->observed=1;return 1;}
    if(attributes==NULL||count>MAX_ATTRIBUTE_PAIRS){capture->oversized=count>MAX_ATTRIBUTE_PAIRS;return 0;}
    capture->pairs=count;capture->observed=1;for(index=0U;index<count*2U;++index)capture->values[index]=attributes[index];return 1;
}
static int scope_matches(int api, unsigned count, const uint32_t *attributes) {
    if(api!=3||count!=2U||attributes==NULL)return 0;
    return ((attributes[0]==0U&&attributes[1]==3U&&attributes[2]==1U&&attributes[3]==3U) ||
            (attributes[0]==1U&&attributes[1]==3U&&attributes[2]==0U&&attributes[3]==3U));
}
static int append_text(char *out,size_t capacity,size_t *used,const char *format,...) { va_list args;int written; if(out==NULL||used==NULL||*used>=capacity||format==NULL)return 0;va_start(args,format);written=vsnprintf(out+*used,capacity-*used,format,args);va_end(args);if(written<0||(size_t)written>=capacity-*used)return 0;*used+=(size_t)written;return 1; }

static ReadResult read_maximum(void *screen) {
    ReadResult result = {0, -1, 0, 0U, 0};
    struct iovec local, remote;
    long read_result;
    uintptr_t address;
    uintptr_t field_address;

    if (screen == NULL) return result;
    address = (uintptr_t)screen;
    if (address > UINTPTR_MAX - DRI_SCREEN_FIELD_OFFSET) return result;
    field_address = address + DRI_SCREEN_FIELD_OFFSET;
    if (field_address > UINTPTR_MAX - (sizeof(uint32_t) - 1U)) return result;
    result.attempted = 1;
    local.iov_base = &result.value;
    local.iov_len = sizeof(result.value);
    remote.iov_base = (void *)field_address;
    remote.iov_len = sizeof(result.value);
#ifdef DRI_VERSION_OBSERVER_HOST_TEST
    if(dri_observer_test_read_mode==1){result.bytes=-1;result.error=EPERM;return result;} if(dri_observer_test_read_mode==2){result.bytes=-1;result.error=ENOSYS;return result;} if(dri_observer_test_read_mode==3){result.bytes=2;result.error=EIO;return result;}
#endif
#if defined(SYS_process_vm_readv)
    errno=0;read_result=syscall(SYS_process_vm_readv,getpid(),&local,1U,&remote,1U,0U);result.bytes=read_result;result.error=errno;
#else
    result.bytes=-1;result.error=ENOSYS;
#endif
    result.valueValid=result.bytes==(long)sizeof(result.value);return result;
}

static int reasonable(uint32_t value) { return value==0U||(value>=10U&&value<=99U); }
static const char *inference(void *result, unsigned *error, const ReadResult *before,
                             const ReadResult *after) {
    int stable = before != NULL && after != NULL && before->valueValid && after->valueValid &&
                 before->value == after->value && reasonable(before->value) &&
                 before->error == 0 && after->error == 0;
    if (!stable) return "UNKNOWN";
    if (result == NULL && error != NULL && *error == 3U)
        return before->value < 33U ? "EARLY_CHECK_WOULD_REJECT" : "NOT_EXPLAINED_BY_EARLY_CHECK";
    if (result != NULL && error != NULL && *error == 0U) return "NO_BAD_VERSION";
    return "UNKNOWN";
}

static int write_record(const char *run_id,unsigned sequence,int api,unsigned count,bool thread_safe,const AttributeCapture *attributes,int provider_verified,int forwarded,int layout_verified,int scope_match,int requested_version,const ReadResult *before,const ReadResult *after,int measurement_valid,const char *check,void *result,unsigned *error,int incoming_errno,int returned_errno) {
    char line[MAX_RECORD];size_t used=0U;unsigned pair;int error_present=error!=NULL;unsigned error_value=error_present?*error:0U;unsigned slot=atomic_fetch_add(&record_count,1U);
    if(slot>=MAX_RECORDS||run_id==NULL||attributes==NULL||before==NULL||after==NULL)return 1;
    if(!append_text(line,sizeof(line),&used,"WFDRI_VERSION {\"schema\":\"%s\",\"runId\":\"%s\",\"pid\":%ld,\"uid\":%lu,\"callSequence\":%u,\"api\":%d,\"numAttribs\":%u,\"threadSafe\":%s,\"attributesObserved\":%s,\"attributesOversize\":%s,\"attributePairs\":[",OBSERVER_SCHEMA,run_id,(long)getpid(),(unsigned long)getuid(),sequence,api,count,thread_safe?"true":"false",attributes->observed?"true":"false",attributes->oversized?"true":"false"))return 0;
    for(pair=0U;pair<attributes->pairs;++pair)if(!append_text(line,sizeof(line),&used,"%s[%u,%u]",pair==0U?"":",",attributes->values[pair*2U],attributes->values[pair*2U+1U]))return 0;
    if(!append_text(line,sizeof(line),&used,"],\"providerVerified\":%s,\"forwarded\":%s,\"resultPresent\":%s,\"errorPresent\":%s,\"error\":%s",provider_verified?"true":"false",forwarded?"true":"false",result!=NULL?"true":"false",error_present?"true":"false",error_present?"":"null"))return 0;
    if(error_present&&!append_text(line,sizeof(line),&used,"%u",error_value))return 0;
#define NUM_OR_NULL(ok, value) ((ok) ? "" : "null")
    if(!append_text(line,sizeof(line),&used,",\"incomingErrno\":%d,\"returnedErrno\":%d,\"layoutVerified\":%s,\"scopeMatches\":%s,\"requestedVersion\":%s,\"maximumBefore\":",incoming_errno,returned_errno,layout_verified?"true":"false",scope_match?"true":"false",requested_version>=0?"33":"null"))return 0;
    if(before->valueValid&&!append_text(line,sizeof(line),&used,"%u",before->value)) return 0;
    if(!before->valueValid&&!append_text(line,sizeof(line),&used,"null")) return 0;
    if(!append_text(line,sizeof(line),&used,",\"maximumAfter\":%s",NUM_OR_NULL(after->valueValid,""))) return 0;
    if(after->valueValid&&!append_text(line,sizeof(line),&used,"%u",after->value)) return 0;
    if(!append_text(line,sizeof(line),&used,",\"beforeReadBytes\":%s",before->attempted?"":"null")) return 0;
    if(before->attempted&&!append_text(line,sizeof(line),&used,"%ld",before->bytes)) return 0;
    if(!append_text(line,sizeof(line),&used,",\"afterReadBytes\":%s",after->attempted?"":"null")) return 0;
    if(after->attempted&&!append_text(line,sizeof(line),&used,"%ld",after->bytes)) return 0;
    if(!append_text(line,sizeof(line),&used,",\"beforeReadErrno\":%s",before->attempted?"":"null")) return 0;
    if(before->attempted&&!append_text(line,sizeof(line),&used,"%d",before->error)) return 0;
    if(!append_text(line,sizeof(line),&used,",\"afterReadErrno\":%s",after->attempted?"":"null")) return 0;
    if(after->attempted&&!append_text(line,sizeof(line),&used,"%d",after->error)) return 0;
    if(!append_text(line,sizeof(line),&used,",\"measurementValid\":%s,\"checkInference\":\"%s\"}\n",measurement_valid?"true":"false",check==NULL?"UNKNOWN":check)) return 0;
#undef NUM_OR_NULL
    if(used>=MAX_OUTPUT_BYTES||atomic_fetch_add(&output_bytes,(unsigned)used)+used>MAX_OUTPUT_BYTES||write(STDERR_FILENO,line,used)!=(ssize_t)used) return 0;
    return 1;
}

static int current_run_id(char *run_id,size_t capacity) {
#ifdef DRI_VERSION_OBSERVER_HOST_TEST
    return copy_bounded(run_id,capacity,"VTEST-0123456789abcdef0123456789abcdef",MAX_RUN_ID+1U);
#else
    return scoped_run(run_id,capacity);
#endif
}

__attribute__((visibility("default")))
void *driCreateContextAttribs(void *screen,int api,const void *config,void *shared,unsigned num_attribs,const uint32_t *attribs,unsigned *error,void *data,bool thread_safe) {
    AttributeCapture captured;DriCreateContextAttribsFn provider;void *retained_handle=NULL;char run_id[MAX_RUN_ID+1U];unsigned sequence;int incoming_errno=errno,returned_errno,layout_verified=0,scope_match;int requested_version=-1,measurement_valid=0;ReadResult before={0,-1,0,0U,0},after={0,-1,0,0U,0};void *result;
    if(recursion_guard||!current_run_id(run_id,sizeof(run_id))) _exit(125);
    recursion_guard=1; scope_match=scope_matches(api,num_attribs,attribs);
    if(scope_match) requested_version=33;
    (void)capture_attributes(&captured,num_attribs,attribs);
    if(!resolve_provider(&provider,&retained_handle,&layout_verified)) _exit(125);
    if(layout_verified&&scope_match){before=read_maximum(screen);}
    errno=incoming_errno;result=provider(screen,api,config,shared,num_attribs,attribs,error,data,thread_safe);returned_errno=errno;
    if(layout_verified&&scope_match){after=read_maximum(screen);measurement_valid=before.valueValid&&after.valueValid&&before.error==0&&after.error==0&&before.value==after.value&&reasonable(before.value);}
    sequence=atomic_fetch_add(&call_sequence,1U)+1U;(void)write_record(run_id,sequence,api,num_attribs,thread_safe,&captured,1,1,layout_verified,scope_match,requested_version,&before,&after,measurement_valid,inference(result,error,&before,&after),result,error,incoming_errno,returned_errno);
    if(retained_handle!=NULL) (void)dlclose(retained_handle);
    errno=returned_errno; recursion_guard=0; return result;
}
