#include <syscall.h>
#include <stdlib.h>
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>

// Build with:
// $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang \
//   splicehelper.c -o splicehelper -nodefaultlibs -nostartfiles -ffreestanding -static && \
// $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip splicehelper

// argv[0] = program name
// argv[1] = file offset (decimal string)
// argv[2] = file path
// argv[3] = "r"  read 16 bytes and write them to fd 0
//           "rb" read argv[4] bytes (max 4096) and write them to fd 0
//           "sb" splice argv[4] bytes of file pages into fd 1
//           absent: splice 16 bytes of the file page into fd 1

#define OUT_FD  0
#define PIPE_FD 1
#define BULK_SZ 4096

static unsigned char rbuf[BULK_SZ];

__attribute__((naked)) static long mysyscall1(unsigned long arg0, unsigned long nr) {
    asm volatile("mov x8, x1\nsvc 0\nret\n":::"x8");
}

__attribute__((naked)) static long mysyscall3(
    unsigned long a0, unsigned long a1, unsigned long a2, unsigned long nr) {
    asm volatile("mov x8, x3\nsvc 0\nret\n":::"x8");
}

__attribute__((naked)) static long mysyscall6(
    unsigned long a0, unsigned long a1, unsigned long a2,
    unsigned long a3, unsigned long a4, unsigned long a5, unsigned long nr) {
    asm volatile("mov x8, x6\nsvc 0\nret\n":::"x8");
}

static unsigned long parse_int(char *s) {
    unsigned long val = 0;
    while (*s) { val = val * 10 + (unsigned long)(*s - '0'); s++; }
    return val;
}

static int streq(const char *a, const char *b) {
    while (*a && *b && *a == *b) { a++; b++; }
    return *a == *b;
}

void start_c(void *argblock) {
    int argc = (int)*(long *)argblock;
    char **argv = (char **)argblock + 1;

    /* argv[1] and argv[2] are mandatory; a bare exec used to read past them. */
    if (argc < 3 || argv[1] == NULL || argv[2] == NULL) {
        mysyscall1(1, __NR_exit_group);
    }

    off64_t off = (off64_t)parse_int(argv[1]);
    char *target = argv[2];
    char *mode = (argc >= 4) ? argv[3] : (char *)0;

    int file_fd = (int)mysyscall3(
        (unsigned long)AT_FDCWD,
        (unsigned long)target,
        (unsigned long)O_RDONLY,
        __NR_openat);
    if (file_fd < 0) {
        mysyscall1(1, __NR_exit_group);
    }

    if (mode && streq(mode, "r")) {
        /* Read mode: lseek to offset, read 16 bytes, write to OUT_FD */
        /* Exit codes: 0=ok, 1=read<16, 2=write<16 */
        mysyscall3((unsigned long)file_fd, (unsigned long)off, SEEK_SET, __NR_lseek);
        long n = mysyscall3((unsigned long)file_fd, (unsigned long)rbuf, 16, __NR_read);
        if (n != 16) {
            mysyscall1(1, __NR_exit_group);
        }
        long w = mysyscall3(OUT_FD, (unsigned long)rbuf, 16, __NR_write);
        mysyscall1((unsigned long)(w == 16 ? 0 : 2), __NR_exit_group);
    }

    unsigned long want = BULK_SZ;
    if (argc >= 5 && argv[4] != NULL) {
        want = parse_int(argv[4]);
    }
    if (want == 0 || want > BULK_SZ) {
        mysyscall1(1, __NR_exit_group);
    }

    if (mode && streq(mode, "rb")) {
        /* Bulk read: exact length, so a partial final chunk is not a failure. */
        mysyscall3((unsigned long)file_fd, (unsigned long)off, SEEK_SET, __NR_lseek);
        unsigned long total = 0;
        while (total < want) {
            long n = mysyscall3((unsigned long)file_fd,
                                (unsigned long)(rbuf + total),
                                want - total, __NR_read);
            if (n <= 0) break;
            total += (unsigned long)n;
        }
        unsigned long done = 0;
        while (done < total) {
            long w = mysyscall3(OUT_FD, (unsigned long)(rbuf + done),
                                total - done, __NR_write);
            if (w <= 0) {
                mysyscall1(2, __NR_exit_group);
            }
            done += (unsigned long)w;
        }
        mysyscall1((unsigned long)(total == want ? 0 : 1), __NR_exit_group);
    }

    if (mode && streq(mode, "sb")) {
        /* One helper exec fills the pipe with the whole chunk of page refs. */
        unsigned long moved = 0;
        while (moved < want) {
            long n = mysyscall6(
                (unsigned long)file_fd,
                (unsigned long)&off,
                PIPE_FD,
                (unsigned long)NULL,
                want - moved,
                SPLICE_F_MOVE,
                __NR_splice);
            if (n <= 0) {
                mysyscall1(1, __NR_exit_group);
            }
            moved += (unsigned long)n;
        }
        mysyscall1(0, __NR_exit_group);
    }

    /* Splice mode: splice 16-byte page into PIPE_FD */
    long ret = mysyscall6(
        (unsigned long)file_fd,
        (unsigned long)&off,
        PIPE_FD,
        (unsigned long)NULL,
        16,
        SPLICE_F_MOVE,
        __NR_splice);
    mysyscall1((unsigned long)(ret == 16 ? 0 : 1), __NR_exit_group);
}

__attribute__((naked)) void _start() {
    asm(".extern start_c\nmov x0, sp\nb start_c\n");
}
