    .arch armv8-a

    .include "include.inc"

    .global libc_start
    .global libc_data
    .global libc_len
    .global libc_first_inst_copy
    .data

libc_data:
executable_path:
    .asciz "/vendor/bin/modprobe"
lib_path:
    .asciz "/vendor/lib64/libbinderdebug.so"
mod_args:
    .asciz ""
ksud_path:
    .asciz "/dev/.ksud"
ksud_staged_path:
    .asciz "/data/system/ksud"
logcat_path:
    .asciz "/system/bin/logcat"
root_path:
    .asciz "/"
mark_modprobe:
    .asciz "/dev/dfm0"
mark_mod_fail:
    .asciz "/dev/dfm7"
mark_memfd_fail:
    .asciz "/dev/dfm8"
mark_dev_fail:
    .asciz "/dev/dfm9"
mark_data_fail:
    .asciz "/dev/dfmA"
mark_bind_ns:
    .asciz "/dev/dfm3"
mark_ns_fail:
    .asciz "/dev/dfmB"
mark_bind_ok:
    .asciz "/dev/dfm4"
mark_bind_fail:
    .asciz "/dev/dfmC"
mark_exec_fail:
    .asciz "/dev/dfm6"
mark_ksud_ok:
    .asciz "/dev/dfm5"
    .global libc_ksud_proc_path_off
libc_ksud_proc_path_off:
    .int s2_ksud_proc_path - libc_data
s2_ksud_proc_path:
    .space 64

argv_prog:
    .asciz "logcat"
argv_late:
    .asciz "late-load"
argv_pkgname:
    .asciz "--package-name"
argv_ksu:
    .asciz "me.weishu.kernelsu"
argv_stage_from:
    .asciz "--stage-from"
argv_ro_partitions:
    .asciz "--ro-partitions"
argv_soft_reboot:
    .asciz "--soft-reboot"

    .balign 4
    .global libc_soft_reboot_off
libc_soft_reboot_off:
    .int soft_reboot_flag - libc_data
soft_reboot_flag:
    .byte 0

    .align 4

/*
 * Copy the ksud memfd ([sp,0]) to \dst.
 * Stack layout while running (frame of 4112 bytes, owned by the caller):
 *   [sp,0]  source fd      [sp,4]  destination fd
 *   [sp,12] bytes pending  [sp,16] 4096-byte buffer
 * Marks \fail if the destination cannot be opened or an I/O error truncates
 * the copy, so the app can tell a truncated ksud from a working one.
 */
.macro copy_ksud dstpath, failpath
    mov  x0, -100
    adr  x1, \dstpath
    movl x2, (O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC)
    mov  x3, 0755
    mov  x8, #SYS_openat
    svc  0
    tbnz w0, #31, 8f
    str  w0, [sp, 4]
97:
    ldr  w0, [sp, 0]
    add  x1, sp, #16
    mov  x2, #4096
    mov  x8, #SYS_read
    svc  0
    cmp  x0, #0
    beq  96f
    blt  98f
    str  w0, [sp, 12]
    add  x9, sp, #16
95:
    ldr  w0, [sp, 4]
    mov  x1, x9
    ldr  w2, [sp, 12]
    mov  x8, #SYS_write
    svc  0
    cmp  x0, #0
    ble  98f
    add  x9, x9, x0
    ldr  w2, [sp, 12]
    sub  w2, w2, w0
    str  w2, [sp, 12]
    cbnz w2, 95b
    b    97b
98:
    create_mark \failpath
96:
    ldr  w0, [sp, 4]
    mov  x8, #SYS_close
    svc  0
    b    9f
8:
    create_mark \failpath
9:
.endm

libc_start:
    bti c
    stp x0, x1, [sp, #-16]!
    stp x2, x3, [sp, #-16]!
    stp x4, x5, [sp, #-16]!
    stp x6, x7, [sp, #-16]!
    stp x8, x9, [sp, #-16]!
    stp x26, x27, [sp, #-16]!
    stp x28, x29, [sp, #-16]!
    str x30, [sp, #-16]!

    // x0 -> int argc
    ldr     w1, [x0]            // w1 = argc
    cbz     w1, matched      // argc == 0, run directly

    // argv layout: int argc; int pad; char *argv[argc];
    add     x2, x0, #8
    ldr     x3, [x2]            // x3 = argv[0]
    cbz     x3, return_now

    adr     x4, executable_path // x4 = expected path

strcmp_loop:
    ldrb    w5, [x3], #1
    ldrb    w6, [x4], #1
    cmp     w5, w6
    b.ne    return_now
    cbnz    w5, strcmp_loop     // not at '\0' yet, keep comparing

matched:
    // argv[0] == executable_path

    // open lib
    mov     x0, -100
    adr     x1, lib_path
    mov    x2, O_RDONLY
    mov     x8, #SYS_openat
    svc     0
    mov     x26, x0         // save lib fd

    // finit_module
    adr x1, mod_args
    mov x2, xzr
    mov x8, #SYS_finit_module
    svc 0
    cbnz w0, mod_load_fail

    // close lib fd
    mov     x0, x26
    mov     x8, #SYS_close
    svc     0

    create_mark mark_modprobe
    b mod_ok

mod_load_fail:
    // The module refused to load (vermagic/KMI). Loading ksud anyway would
    // leave SELinux enforcing and the chain would die silently later.
    create_mark mark_mod_fail
    mov     x0, #1
    mov     x8, #SYS_exit
    svc     #0

mod_ok:

    // Copy ksud from memfd to /dev/.ksud (bind mount target) and
    // /data/system/ksud (--stage-from). 4096-byte buffer, and every write is
    // looped to completion: a short write used to leave a truncated ksud.
    // 4112 = 16 bytes of bookkeeping + the 4096-byte buffer; ADD/SUB
    // immediates stop at 4095, so the frame is sized through x9.
    mov  x9, #4112
    sub  sp, sp, x9

    create_mark s2_pre_mark

    mov  x0, -100
    adr  x1, s2_ksud_proc_path
    mov  x2, O_RDONLY
    mov  x3, xzr
    mov  x8, #SYS_openat
    svc  0
    tbnz w0, #31, s2_memfd_fail
    str  w0, [sp, 0]
    create_mark s2_fail_mark

    // copy 1: memfd -> /dev/.ksud (bind mount target)
    copy_ksud s2_dst_path, mark_dev_fail

    // seek src back to 0 for second copy
    ldr  w0, [sp, 0]
    mov  x1, xzr
    mov  x2, xzr
    mov  x8, #SYS_lseek
    svc  0

    // copy 2: memfd -> /data/system/ksud (--stage-from target)
    copy_ksud s2_dst2_path, mark_data_fail

s2_cp_src_close:
    ldr  w0, [sp, 0]
    mov  x8, #SYS_close
    svc  0

s2_cp_done:
    mov  x9, #4112
    add  sp, sp, x9
    b    s2_copy_strs_end

s2_memfd_fail:
    mov  x9, #4112
    add  sp, sp, x9
    create_mark mark_memfd_fail
    b    s2_copy_strs_end

    .balign 4
s2_dst_path:
    .asciz "/dev/.ksud"
    .balign 4
s2_dst2_path:
    .asciz "/data/system/ksud"
    .balign 4
s2_pre_mark:
    .asciz "/dev/dfm1"
    .balign 4
s2_fail_mark:
    .asciz "/dev/dfm2"
    .balign 4
s2_copy_strs_end:

    sub sp, sp, 96

    create_mark mark_bind_ns

    movl x0, (CLONE_NEWNS)
    mov x8, #SYS_unshare
    svc 0
    b.ne ns_fail

    // mount(NULL, "/", NULL, MS_REC|MS_PRIVATE, NULL)
    mov x0, xzr
    adr x1, root_path
    mov x2, xzr
    movl x3, (MS_REC | MS_PRIVATE)
    mov x4, xzr
    mov x8, #SYS_mount
    svc 0
    b.ne ns_fail

    create_mark mark_bind_ok

    // mount(ksud_path, logcat_path, NULL, MS_BIND, NULL)
    adr x9, ksud_path
    mov x0, x9
    adr x1, logcat_path
    mov x2, xzr
    mov x3, MS_BIND
    mov x4, xzr
    mov x8, #SYS_mount
    svc 0
    b.ne bind_fail

    // Status pipe. The write end is O_CLOEXEC: EOF means execve succeeded,
    // one byte means execve returned. This removes the fixed post-launch wait.
    movn w9, #0
    str w9, [sp, 72]        // read fd
    str w9, [sp, 76]        // write fd
    add x9, sp, #72
    mov x0, x9
    movl w1, O_CLOEXEC
    mov x8, #SYS_pipe2
    svc 0

    // x26=1: include --soft-reboot; x26=0: omit it.
    adr     x9, soft_reboot_flag
    ldrb    w26, [x9]

    // argv = [logcat, late-load, --package-name,
    //         me.weishu.kernelsu, --stage-from, ksud_staged_path, [--soft-reboot,] NULL]
    adr x0, argv_prog
    str x0, [sp]

    adr x0, argv_late
    str x0, [sp, 8]

    adr x0, argv_pkgname
    str x0, [sp, 16]

    adr x0, argv_ksu
    str x0, [sp, 24]

    adr x0, argv_stage_from
    str x0, [sp, 32]

    adr x0, ksud_staged_path
    str x0, [sp, 40]

    adr x0, argv_ro_partitions
    str x0, [sp, 48]

    cbz     x26, no_soft_reboot
    adr     x0, argv_soft_reboot
    str     x0, [sp, 56]
    b       fork_ksud
no_soft_reboot:
    str     xzr, [sp, 56]
fork_ksud:

    // fork: child execve's ksud; parent marks success and exits
    movl x0, SIGCHLD
    mov x1, xzr
    mov x2, xzr
    mov x3, xzr
    mov x4, xzr
    mov x8, #SYS_clone
    svc 0
    str x0, [sp, 64]    // child: 0 (64-bit NULL terminator); parent: child pid (branches away)
    cbnz w0, s2_wait_ksud

    // child: close the read end, then execve ksud (bind-mounted over logcat)
    ldr w0, [sp, 72]
    cmp w0, #0
    b.lt 11f
    mov x8, #SYS_close
    svc 0
11:
    adr x0, logcat_path
    mov x1, sp
    mov x2, xzr
    mov x8, #SYS_execve
    svc 0

    // execve returned: report one byte to the parent, then the marker.
    ldr w0, [sp, 76]
    cmp w0, #0
    b.lt 12f
    add x1, sp, #80
    mov w2, #1
    strb w2, [x1]
    mov x8, #SYS_write
    svc 0
12:
    create_mark mark_exec_fail
    mov x0, #1
    mov x8, #SYS_exit
    svc #0

s2_wait_ksud:
    // Close the write end, then block until execve either succeeds (EOF) or
    // fails (one status byte).
    ldr w0, [sp, 76]
    cmp w0, #0
    b.lt 13f
    mov x8, #SYS_close
    svc 0
    ldr w0, [sp, 72]
    cmp w0, #0
    b.lt 13f
13:
    ldr w0, [sp, 72]
    cmp w0, #0
    b.lt 14f
    add x1, sp, #80
    mov x2, #1
    mov x8, #SYS_read
    svc 0
    cmn w0, #4           // EINTR
    b.eq 13b
    mov w27, w0
    ldr w0, [sp, 72]
    mov x8, #SYS_close
    svc 0
    cmp w27, #0
    b.ne 15f
14:
    create_mark mark_ksud_ok
    b myexit
15:
    create_mark mark_exec_fail

myexit:
    mov x0, #1
    mov x8, #SYS_exit
    svc #0

ns_fail:
    create_mark mark_ns_fail
    mov x0, #1
    mov x8, #SYS_exit
    svc #0

bind_fail:
    create_mark mark_bind_fail
    mov x0, #1
    mov x8, #SYS_exit
    svc #0

return_now:
    ldr x30, [sp], #16
    ldp x28, x29, [sp], #16
    ldp x26, x27, [sp], #16
    ldp x8, x9, [sp], #16
    ldp x6, x7, [sp], #16
    ldp x4, x5, [sp], #16
    ldp x2, x3, [sp], #16
    ldp x0, x1, [sp], #16
libc_first_inst_copy:
    stp x29, x30, [sp, #-0x30]!
    b end2
end2:

libc_end:
libc_len:
    .word libc_end - libc_data
