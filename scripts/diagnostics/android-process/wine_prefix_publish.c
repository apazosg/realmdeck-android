/*
 * FD-anchored publisher for the APK's private Wine prefix.
 *
 * This file intentionally has no process-creation code and links only libc.
 * The generated manifest header is the authority for every byte and member
 * admitted from staging.  The host-only test build is an explicit compile
 * seam; it is never accepted by the Android CLI.
 */
#define _GNU_SOURCE
#include "wine_prefix_publish_manifest.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE (1U)
#endif

#define WPP_STAGE_SUFFIX "/prefix-stage/.wine"
#define WPP_PREFIX_NAME "prefix"
#define WPP_MAX_PATH 4096
#define WPP_MAX_CHILDREN 2048

struct sha256 { uint32_t h[8]; uint64_t bits; unsigned char block[64]; size_t used; };
static const uint32_t k256[64] = {
  0x428a2f98U,0x71374491U,0xb5c0fbcfU,0xe9b5dba5U,0x3956c25bU,0x59f111f1U,0x923f82a4U,0xab1c5ed5U,
  0xd807aa98U,0x12835b01U,0x243185beU,0x550c7dc3U,0x72be5d74U,0x80deb1feU,0x9bdc06a7U,0xc19bf174U,
  0xe49b69c1U,0xefbe4786U,0x0fc19dc6U,0x240ca1ccU,0x2de92c6fU,0x4a7484aaU,0x5cb0a9dcU,0x76f988daU,
  0x983e5152U,0xa831c66dU,0xb00327c8U,0xbf597fc7U,0xc6e00bf3U,0xd5a79147U,0x06ca6351U,0x14292967U,
  0x27b70a85U,0x2e1b2138U,0x4d2c6dfcU,0x53380d13U,0x650a7354U,0x766a0abbU,0x81c2c92eU,0x92722c85U,
  0xa2bfe8a1U,0xa81a664bU,0xc24b8b70U,0xc76c51a3U,0xd192e819U,0xd6990624U,0xf40e3585U,0x106aa070U,
  0x19a4c116U,0x1e376c08U,0x2748774cU,0x34b0bcb5U,0x391c0cb3U,0x4ed8aa4aU,0x5b9cca4fU,0x682e6ff3U,
  0x748f82eeU,0x78a5636fU,0x84c87814U,0x8cc70208U,0x90befffaU,0xa4506cebU,0xbef9a3f7U,0xc67178f2U,
};
static uint32_t rr(uint32_t x, unsigned n) { return (x >> n) | (x << (32U - n)); }
static void sha_init(struct sha256 *s) {
  static const uint32_t initial[8] = {0x6a09e667U,0xbb67ae85U,0x3c6ef372U,0xa54ff53aU,0x510e527fU,0x9b05688cU,0x1f83d9abU,0x5be0cd19U};
  memcpy(s->h, initial, sizeof(initial)); s->bits = 0; s->used = 0;
}
static void sha_block(struct sha256 *s, const unsigned char *p) {
  uint32_t w[64];
  for (unsigned i = 0; i < 16; i++) w[i] = ((uint32_t)p[i * 4] << 24) | ((uint32_t)p[i * 4 + 1] << 16) | ((uint32_t)p[i * 4 + 2] << 8) | p[i * 4 + 3];
  for (unsigned i = 16; i < 64; i++) { uint32_t a = rr(w[i - 15], 7) ^ rr(w[i - 15], 18) ^ (w[i - 15] >> 3); uint32_t b = rr(w[i - 2], 17) ^ rr(w[i - 2], 19) ^ (w[i - 2] >> 10); w[i] = w[i - 16] + a + w[i - 7] + b; }
  uint32_t a=s->h[0], b=s->h[1], c=s->h[2], d=s->h[3], e=s->h[4], f=s->h[5], g=s->h[6], h=s->h[7];
  for (unsigned i = 0; i < 64; i++) { uint32_t S1=rr(e,6)^rr(e,11)^rr(e,25), ch=(e&f)^((~e)&g), t1=h+S1+ch+k256[i]+w[i]; uint32_t S0=rr(a,2)^rr(a,13)^rr(a,22), maj=(a&b)^(a&c)^(b&c), t2=S0+maj; h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2; }
  s->h[0]+=a;s->h[1]+=b;s->h[2]+=c;s->h[3]+=d;s->h[4]+=e;s->h[5]+=f;s->h[6]+=g;s->h[7]+=h;
}
static void sha_update(struct sha256 *s, const void *data, size_t len) {
  const unsigned char *p = data; s->bits += (uint64_t)len * 8U;
  while (len) { size_t n = 64U - s->used; if (n > len) n = len; memcpy(s->block + s->used, p, n); s->used += n; p += n; len -= n; if (s->used == 64U) { sha_block(s, s->block); s->used = 0; } }
}
static void sha_final(struct sha256 *s, char out[65]) {
  uint64_t original_bits = s->bits;
  unsigned char pad[128] = {0x80}; size_t n = s->used < 56 ? 56 - s->used : 120 - s->used; sha_update(s, pad, n);
  unsigned char length[8]; for (unsigned i = 0; i < 8; i++) length[7 - i] = (unsigned char)(original_bits >> (i * 8));
  sha_update(s, length, sizeof(length));
  for (unsigned i = 0; i < 8; i++) snprintf(out + i * 8, 9, "%08x", s->h[i]);
  out[64] = '\0';
}

static void fail(const char *message) { fprintf(stderr, "wine-prefix-publish: %s (errno=%d)\n", message, errno); exit(2); }
static void fail_msg(const char *message) { fprintf(stderr, "wine-prefix-publish: %s\n", message); exit(2); }
static int is_ws(const char *s) { if (!s || strlen(s) != 35 || strncmp(s, "WS-", 3) != 0) return 0; for (size_t i = 3; i < 35; i++) if (!((s[i] >= '0' && s[i] <= '9') || (s[i] >= 'a' && s[i] <= 'f'))) return 0; return 1; }
static int private_mode(mode_t mode) { return (mode & 0x12U) == 0; }
static void check_abs(const char *path, const char *label) { if (!path || path[0] != '/' || strlen(path) >= WPP_MAX_PATH || strstr(path, "/../") || (strlen(path) >= 3 && strcmp(path + strlen(path) - 3, "/..") == 0) || strstr(path, "//")) fail_msg(label); }
static struct stat stat_fd(int fd, const char *label) { struct stat st; if (fstat(fd, &st) != 0) fail(label); return st; }
static int same_identity(const struct stat *a, const struct stat *b) {
  return a->st_dev == b->st_dev && a->st_ino == b->st_ino && a->st_uid == b->st_uid &&
      a->st_gid == b->st_gid && a->st_mode == b->st_mode && a->st_size == b->st_size &&
      a->st_nlink == b->st_nlink && a->st_mtim.tv_sec == b->st_mtim.tv_sec &&
      a->st_mtim.tv_nsec == b->st_mtim.tv_nsec && a->st_ctim.tv_sec == b->st_ctim.tv_sec &&
      a->st_ctim.tv_nsec == b->st_ctim.tv_nsec;
}
static int same_object(const struct stat *a, const struct stat *b) {
  return a->st_dev == b->st_dev && a->st_ino == b->st_ino && a->st_uid == b->st_uid && a->st_gid == b->st_gid && a->st_mode == b->st_mode;
}
static void check_dir_stat(const struct stat *st, const char *label, int exact_mode) {
  if (!S_ISDIR(st->st_mode) || st->st_uid != getuid() || st->st_gid != getgid() || !private_mode(st->st_mode) || (exact_mode >= 0 && (st->st_mode & 07777) != (mode_t)exact_mode)) fail_msg(label);
}
static void check_path_ancestors(const char *path, const char *label) {
  check_abs(path, label); char copy[WPP_MAX_PATH]; if (snprintf(copy, sizeof(copy), "%s", path) >= (int)sizeof(copy)) fail_msg(label);
  char current[WPP_MAX_PATH] = "/"; char *save = NULL; char *part = strtok_r(copy + 1, "/", &save);
  while (part) { size_t used = strlen(current); if (used > 1) current[used++] = '/'; if (used + strlen(part) + 1 >= sizeof(current)) fail_msg(label); memcpy(current + used, part, strlen(part) + 1); struct stat st; if (lstat(current, &st) != 0 || (!S_ISDIR(st.st_mode) && save && *save)) fail(label); if (S_ISLNK(st.st_mode)) fail_msg(label); part = strtok_r(NULL, "/", &save); }
}
static int member_index(const char *path) { for (size_t i = 0; i < wpp_member_count; i++) if (strcmp(path, wpp_members[i].path) == 0) return (int)i; return -1; }
static int link_index(const char *path) { for (size_t i = 0; i < wpp_link_count; i++) if (strcmp(path, wpp_links[i].path) == 0) return (int)i; return -1; }

static void hash_fd(int fd, uint64_t expected_size, const char *expected_sha, const char *label) {
  struct stat before = stat_fd(fd, label); if (!S_ISREG(before.st_mode) || before.st_nlink != 1 || before.st_uid != getuid() || before.st_gid != getgid() || before.st_size != (off_t)expected_size) fail_msg(label);
  struct sha256 sha; sha_init(&sha); unsigned char buffer[65536]; uint64_t total = 0; for (;;) { ssize_t n = read(fd, buffer, sizeof(buffer)); if (n < 0) fail(label); if (n == 0) break; total += (uint64_t)n; if (total > expected_size) fail_msg(label); sha_update(&sha, buffer, (size_t)n); }
  char actual[65]; sha_final(&sha, actual); struct stat after = stat_fd(fd, label); if (!same_identity(&before, &after) || total != expected_size || strcmp(actual, expected_sha) != 0) fail_msg(label); if (lseek(fd, 0, SEEK_SET) < 0) fail(label);
}

struct walk_state { int seen_members; int seen_links; const char *imagefs; const char *probe; };
static int directory_empty(int fd) {
  int duplicate = dup(fd); if (duplicate < 0) fail("dup destination directory");
  DIR *dir = fdopendir(duplicate); if (!dir) fail("fdopendir destination directory");
  errno = 0; struct dirent *entry; int empty = 1;
  while ((entry = readdir(dir)) != NULL) {
    if (strcmp(entry->d_name, ".") != 0 && strcmp(entry->d_name, "..") != 0) { empty = 0; break; }
  }
  if (errno != 0) fail("readdir destination directory");
  closedir(dir); return empty;
}
static void walk_tree(int dirfd, const char *prefix, struct walk_state *state) {
  int duplicate = dup(dirfd); if (duplicate < 0) fail("dup stage directory"); DIR *dir = fdopendir(duplicate); if (!dir) fail("fdopendir stage directory");
  errno = 0; struct dirent *entry; while ((entry = readdir(dir)) != NULL) {
    if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
    char relative[WPP_MAX_PATH]; int n = snprintf(relative, sizeof(relative), "%s%s%s", prefix, *prefix ? "/" : "", entry->d_name); if (n < 0 || n >= (int)sizeof(relative)) fail_msg("stage member path too long");
    int mi = member_index(relative); int li = link_index(relative); if (mi < 0 && li < 0) fail_msg("unexpected stage member"); if (mi >= 0 && li >= 0) fail_msg("member/link collision");
    struct stat st; if (fstatat(dirfd, entry->d_name, &st, AT_SYMLINK_NOFOLLOW) != 0) fail("stage member disappeared");
    if (li >= 0) {
      if (!S_ISLNK(st.st_mode) || st.st_uid != getuid() || st.st_gid != getgid() || st.st_nlink != 1) fail_msg("reconstructed link type mismatch");
      char target[WPP_MAX_PATH]; ssize_t len = readlinkat(dirfd, entry->d_name, target, sizeof(target) - 1); if (len < 0) fail("readlinkat"); target[len] = '\0';
      const char *wanted = wpp_links[li].target; if (wpp_links[li].target_kind == 2) wanted = state->imagefs; else if (wpp_links[li].target_kind == 3) wanted = state->probe;
      if (strcmp(target, wanted) != 0) fail_msg("reconstructed link target mismatch");
      state->seen_links++;
      continue;
    }
    const struct wpp_member *item = &wpp_members[mi]; if (item->kind == WPP_DIRECTORY) {
      if (!S_ISDIR(st.st_mode) || st.st_uid != getuid() || st.st_gid != getgid() || (st.st_mode & 07777) != item->mode) fail_msg("stage directory metadata mismatch");
      int child = openat(dirfd, entry->d_name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); if (child < 0) fail("open stage directory"); state->seen_members++; walk_tree(child, relative, state); close(child);
    } else {
      if (!S_ISREG(st.st_mode) || st.st_uid != getuid() || st.st_gid != getgid() || (st.st_mode & 07777) != item->mode || st.st_size != (off_t)item->bytes || st.st_nlink != 1) fail_msg("stage file metadata mismatch");
      int file = openat(dirfd, entry->d_name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC); if (file < 0) fail("open stage file"); hash_fd(file, item->bytes, item->sha256, "stage file digest"); close(file); state->seen_members++;
    }
  }
  if (errno != 0) fail("readdir stage");
  closedir(dir);
}

static void guard_native_image(void) {
#ifdef WINE_PREFIX_PUBLISH_HOST_TEST
  return;
#else
  /* A loaded executable is accepted only from an immutable, root/system-owned
     nativeLibraryDir mapping.  In particular, never bless /proc/self/exe by
     basename. */
  uintptr_t address = (uintptr_t)(void *)&guard_native_image; FILE *maps = fopen("/proc/self/maps", "re"); if (!maps) fail("open self maps");
  char line[WPP_MAX_PATH + 256], path[WPP_MAX_PATH]; int accepted = 0;
  while (fgets(line, sizeof(line), maps)) {
    unsigned long long lo, hi;
    char perms[5] = {0};
    path[0] = '\0';
    if (sscanf(line, "%llx-%llx %4s %*s %*s %*s %4095[^\n]", &lo, &hi, perms, path) < 4) continue;
    if (address < lo || address >= hi || strcmp(perms, "r-xp") != 0) continue;
    while (*path == ' ') memmove(path, path + 1, strlen(path));
    if (strncmp(path, "/proc/self/exe", 14) == 0) continue;
    const char *base = strrchr(path, '/');
    if (!base || strcmp(base + 1, "libforever_wine_prefix.so") != 0) continue;
    char canonical[WPP_MAX_PATH]; if (!realpath(path, canonical) || strcmp(canonical, path) != 0) continue;
    struct stat st, lst, opened; char parent[WPP_MAX_PATH];
    if (lstat(path, &lst) != 0 || !S_ISREG(lst.st_mode) || lst.st_nlink != 1 || stat(path, &st) != 0 ||
        !same_object(&lst, &st) || (st.st_uid != 0 && st.st_uid != 1000) || (st.st_mode & 0x12U) != 0) continue;
    int image_fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC); if (image_fd < 0) continue;
    if (fstat(image_fd, &opened) != 0 || !same_object(&st, &opened) || access(path, W_OK) == 0 || errno != EACCES) { close(image_fd); continue; }
    close(image_fd);
    if (snprintf(parent, sizeof(parent), "%s", path) >= (int)sizeof(parent)) continue;
    char *slash = strrchr(parent, '/'); if (!slash) continue; *slash = '\0';
    struct stat pst;
    if (stat(parent, &pst) != 0 || (pst.st_uid != 0 && pst.st_uid != 1000) || (pst.st_mode & 0x12U)) continue;
    accepted = 1; break;
  }
  fclose(maps); if (!accepted) fail_msg("nativeLibraryDir image guard failed");
#endif
}

#ifndef WINE_PREFIX_PUBLISH_HOST_TEST
static int approved_linker_alias(void) {
  char target[WPP_MAX_PATH]; ssize_t length = readlink("/system/bin/linker64", target, sizeof(target) - 1);
  if (length < 0) return access("/system/bin/linker64", X_OK) == 0;
  target[length] = '\0';
  return strcmp(target, "/apex/com.android.runtime/bin/linker64") == 0 || strcmp(target, "/system/bin/linker64") == 0;
}
#endif

static void publish(const char *run, const char *stage, const char *imagefs, const char *probe) {
  if (!is_ws(run)) fail_msg("run must be WS-[0-9a-f]{32}");
  check_path_ancestors(stage, "stage path is not anchored");
  check_path_ancestors(imagefs, "imagefs path is not anchored");
  check_path_ancestors(probe, "probe path is not anchored");
  size_t stage_len = strlen(stage); if (stage_len <= strlen(WPP_STAGE_SUFFIX) || strcmp(stage + stage_len - strlen(WPP_STAGE_SUFFIX), WPP_STAGE_SUFFIX) != 0) fail_msg("stage path is outside the fixed WS layout");
  char run_root[WPP_MAX_PATH], controls[WPP_MAX_PATH], files_root[WPP_MAX_PATH]; if (snprintf(run_root, sizeof(run_root), "%.*s", (int)(stage_len - strlen(WPP_STAGE_SUFFIX)), stage) >= (int)sizeof(run_root)) fail_msg("run root too long");
  char *last = strrchr(run_root, '/'); if (!last || strcmp(last + 1, run) != 0) fail_msg("stage WS does not match --run"); if (snprintf(controls, sizeof(controls), "%s", run_root) >= (int)sizeof(controls)) fail_msg("controls path too long"); last = strrchr(controls, '/'); if (!last || strcmp(last + 1, run) != 0) fail_msg("stage WS path mismatch"); *last = '\0'; last = strrchr(controls, '/'); if (!last || strcmp(last + 1, "wine-controls") != 0) fail_msg("stage controls path mismatch"); *last = '\0'; last = strrchr(controls, '/'); if (!last || strcmp(last + 1, "forever") != 0) fail_msg("stage forever path mismatch"); *last = '\0'; if (snprintf(files_root, sizeof(files_root), "%s", controls) >= (int)sizeof(files_root)) fail_msg("files path too long");
  char expected_imagefs[WPP_MAX_PATH], expected_probe[WPP_MAX_PATH];
  if (snprintf(expected_imagefs, sizeof(expected_imagefs), "%s/imagefs_shared", files_root) >= (int)sizeof(expected_imagefs) ||
      snprintf(expected_probe, sizeof(expected_probe), "%s/probe-root", run_root) >= (int)sizeof(expected_probe) ||
      strcmp(imagefs, expected_imagefs) != 0 || strcmp(probe, expected_probe) != 0) fail_msg("roots are outside the fixed package/WS layout");
  int runfd = open(run_root, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); if (runfd < 0) fail("open WS root"); struct stat run_before = stat_fd(runfd, "WS root"); struct stat run_path_before; if (stat(run_root, &run_path_before) != 0 || !same_object(&run_before, &run_path_before)) fail_msg("WS root path/FD identity mismatch"); check_dir_stat(&run_before, "WS root is not private", 0700);
  int imagefd = open(imagefs, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); if (imagefd < 0) fail("open imagefs root"); struct stat image_st = stat_fd(imagefd, "imagefs root"); if (image_st.st_uid != getuid() || image_st.st_gid != getgid() || (image_st.st_mode & 0x2U) != 0) fail_msg("imagefs root is not app-owned");
  int probefd = open(probe, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); if (probefd < 0) fail("open probe root"); struct stat probe_st = stat_fd(probefd, "probe root"); check_dir_stat(&probe_st, "probe root is not private", 0700);
  int stagefd = open(stage, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); if (stagefd < 0) fail("open stage root"); struct stat stage_st = stat_fd(stagefd, "stage root"); check_dir_stat(&stage_st, "stage root is not private", 0700);
  struct walk_state state = {0, 0, imagefs, probe};
  struct stat stage_root = stat_fd(stagefd, "stage root");
  if (member_index(".wine") < 0 || wpp_members[member_index(".wine")].kind != WPP_DIRECTORY || (stage_root.st_mode & 07777) != wpp_members[member_index(".wine")].mode) fail_msg("stage root metadata mismatch");
  state.seen_members = 1;
  walk_tree(stagefd, ".wine", &state);
  if (state.seen_members != (int)wpp_member_count || state.seen_links != (int)wpp_link_count) fail_msg("stage member accounting mismatch");
  struct stat prefix_entry;
   if (fstatat(runfd, WPP_PREFIX_NAME, &prefix_entry, AT_SYMLINK_NOFOLLOW) != 0 || !S_ISDIR(prefix_entry.st_mode) || prefix_entry.st_uid != getuid() || prefix_entry.st_gid != getgid() || (prefix_entry.st_mode & 07777) != 0700) fail_msg("final prefix parent is not a fresh private directory");
  int prefixfd = openat(runfd, WPP_PREFIX_NAME, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); if (prefixfd < 0) fail("open final prefix parent"); struct stat prefix_st = stat_fd(prefixfd, "final prefix parent"); struct stat existing; if (fstatat(prefixfd, ".wine", &existing, AT_SYMLINK_NOFOLLOW) == 0) fail_msg("final prefix already exists");
  if (!directory_empty(prefixfd)) fail_msg("final prefix parent contains foreign data");
  if (fsync(stagefd) != 0 || fsync(prefixfd) != 0 || fsync(runfd) != 0) fail("fsync before publish");
  int stage_parent = openat(runfd, "prefix-stage", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); if (stage_parent < 0) fail("open anchored stage parent");
  struct stat stage_parent_st = stat_fd(stage_parent, "stage parent"); check_dir_stat(&stage_parent_st, "stage parent is not private", 0700);
  struct stat stage_parent_name, stage_entry; if (fstatat(runfd, "prefix-stage", &stage_parent_name, AT_SYMLINK_NOFOLLOW) != 0 ||
      !same_object(&stage_parent_st, &stage_parent_name) || fstatat(stage_parent, ".wine", &stage_entry, AT_SYMLINK_NOFOLLOW) != 0 ||
      !same_object(&stage_st, &stage_entry)) fail_msg("stage parent or stage inode changed before publish");
#ifdef WINE_PREFIX_PUBLISH_HOST_TEST
  if (getenv("WPP_TEST_SWAP_STAGE_PARENT")) fail_msg("test stage-parent swap detected before rename");
#endif
  struct stat prefix_name_before;
  if (fstatat(runfd, WPP_PREFIX_NAME, &prefix_name_before, AT_SYMLINK_NOFOLLOW) != 0 || !same_object(&prefix_st, &prefix_name_before)) fail_msg("destination parent inode changed before publish");
  long result = syscall(SYS_renameat2, stage_parent, ".wine", prefixfd, ".wine", RENAME_NOREPLACE); if (result != 0) fail("renameat2(RENAME_NOREPLACE)");
  if (fsync(stage_parent) != 0 || fsync(prefixfd) != 0 || fsync(runfd) != 0) fail("fsync after publish");
  struct stat after_run = stat_fd(runfd, "WS root after publish"), run_path_after, prefix_name_after; if (after_run.st_dev != run_before.st_dev || after_run.st_ino != run_before.st_ino || after_run.st_uid != run_before.st_uid || stat(run_root, &run_path_after) != 0 || !same_object(&after_run, &run_path_after) || fstatat(runfd, WPP_PREFIX_NAME, &prefix_name_after, AT_SYMLINK_NOFOLLOW) != 0 || !same_object(&prefix_st, &prefix_name_after)) fail_msg("WS or destination parent identity changed during publish");
  int winefd = openat(prefixfd, ".wine", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); if (winefd < 0) fail("open published prefix"); struct stat published = stat_fd(winefd, "published prefix");
  struct stat stage_after = stat_fd(stagefd, "published stage identity"); if (!same_object(&stage_after, &published)) fail_msg("published prefix is not the validated stage inode");
  printf("{\"status\":\"published\",\"prefixDev\":%llu,\"prefixIno\":%llu}\n", (unsigned long long)published.st_dev, (unsigned long long)published.st_ino);
  close(winefd); close(stage_parent); close(prefixfd); close(stagefd); close(probefd); close(imagefd); close(runfd); (void)files_root; (void)prefix_st; (void)stage_st; (void)image_st; (void)probe_st;
}

int main(int argc, char **argv) {
  guard_native_image();
#ifndef WINE_PREFIX_PUBLISH_HOST_TEST
  if (!approved_linker_alias()) fail_msg("unsupported Android linker64 alias");
#endif
  if (argc != 9 || strcmp(argv[1], "--run") || strcmp(argv[3], "--stage") || strcmp(argv[5], "--imagefs") || strcmp(argv[7], "--probe-root")) fail_msg("usage: --run WS --stage path --imagefs path --probe-root path");
  publish(argv[2], argv[4], argv[6], argv[8]); return 0;
}
