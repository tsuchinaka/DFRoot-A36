# DFRoot A36

Temporary root for the Samsung Galaxy A36 5G (`SM-A366E`), using the DirtyFrag
bug (`CVE-2026-43284`). You open the app, tap one button, and `su` starts
answering `uid=0` for the KernelSU install it just dropped. No reboot, no cable.

This is a port and repackaging of the DFRoot app for the A36, with the UI
rebuilt in Jetpack Compose using Material 3 Expressive.

## Tested on

This is the exact setup it was built and confirmed working against. Other
kernels and firmware may need a different `dirtyfrag` module.

- Device: Samsung Galaxy A36 5G (`SM-A366E`), Snapdragon 6 Gen 3, arm64
- Android: 15
- Kernel: `6.6.98-android15-8-33419078-abogkiA366EXXSBCZG3-4k`
- Firmware: `A366EXXSBCZG3`
- Module used: `dirtyfrag-android15-6.6.ko`


## What it actually does

The bug lets an unprivileged app corrupt the kernel page cache of a mapped
shared library without write access. The chain uses that to patch small stubs
into `libc.so` and `libc++.so`, gets code running as `root`, and then loads the
bundled `dirtyfrag.ko` module. From there the app forks a root shell, stages
`ksud`, and KernelSU takes over and installs itself. Once KernelSU is live, the
app flips SELinux back to enforcing and applies a mount overlay so the modules
are visible everywhere.

The pieces are:

1. App builds an IPsec transform with `IpSecManager` (`Runner.run`).
2. Native code (`app/src/main/jni`) rides the AES-CBC splice primitive to patch
   the libraries and run the root shell.
3. `dirtyfrag.ko` goes in via `/vendor/bin/modprobe`, which is why the shell
   needs an empty `PATH` and absolute paths everywhere.
4. `ksud` runs, KernelSU loads, and `Runner.applyOverlay` does
   `su --mount-master -c 'sh /data/dfroot/apply.sh'` if that script exists.
   It is optional: nothing in this tree creates it, and KernelSU already mounts
   modules in the global namespace, so a missing file is skipped, not an error.

## Features

- One tap root from the app, no adb and no reboot.
- Auto jailbreak on boot, with retries. Cold boots are hit or miss, so the
  receiver tries three times.
- Settings, Log and About tabs. The log mirrors everything to `run.log` in the
  app's private storage.
- Adaptive launcher icon, Material 3 Expressive theming, dynamic color on
  Android 12 and up.
- KernelSU is used as the manager. The app is registered as the debug manager
  so it can write the allowlist without already being root.

## Requirements

- Galaxy A36 5G (`SM-A366E`), arm64, Android with the vulnerable kernel.
- KernelSU Manager installed first: https://github.com/tiann/KernelSU/releases
- To build: JDK 17, Android SDK with Platform 37 and Build Tools 37, and NDK
  `29.0.14206865` (set in `app/build.gradle.kts`). CMake and Ninja come with
  the NDK.

## Building

```sh
./create-keystore.sh        # only if app/keystore.jks does not exist yet
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

Or use the wrapper script, which also drops `dirtyfrag.apk` next to the project:

```sh
./build.sh
```

Install on a connected device:

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build runs R8 and resource shrinking. The JNI entry point is kept by
name in `app/proguard-rules.pro`, so do not strip that file.

## Using it

Open the app and tap Jailbreak. Watch the Log tab. When it finishes, `su -c id`
should return `uid=0`. If KernelSU does not grant the app, the Status card says
so and tells you to add DFRoot to the KernelSU superuser list. The app shows
`root: LIVE` only when `su` actually answers.

Auto jailbreak on boot is a switch in Settings. It enables the boot receiver
component, nothing else.

## Notes and gotchas

- Never `rmmod kernelsu`. It panics the kernel and takes USB down with it.
- The root shell has an empty `PATH`. Always use absolute paths.
- The native watchdog reports `rc=2` even when the chain worked. Success is
  judged by watching the modules load and `su` answering, not by the return
  code.
- The stage 1 mutex lives at `/dev/df`. If a run dies halfway it stays behind
  and poisons boot until you reboot.
- Patch the KO variants only if you must. They are built outside this repo with
  the Samsung DDK and copied into `app/src/main/jni/ko/`.

## Project layout

```
app/src/main/java/df/root/     MainActivity (JNI host), Runner, RootProbe,
                               RootRecord, BootReceiver, Compose UI
app/src/main/jni/              exp.c, libc.S, libcxx.S, elf_parser.c, ko/*
app/src/main/assets/ksud       KernelSU userspace binary
app/src/main/res/              icon, strings, themes
```

## Credits

The exploit is not my work. I put the pieces together for this device and
rebuilt the UI.

- diabl0w, the original DFRoot app: https://github.com/diabl0w/DFRoot
- LSPosed, LSPromise and the DirtyFrag proof of concept:
  https://github.com/LSPosed/LSPromise
- polygraphene, DFReroot and the SELinux permissive modules:
  https://github.com/polygraphene/DFReroot
- combeng6th, DirtyInit and the unprivileged XFRM socket method:
  https://github.com/combeng6th/DirtyInit
- tiann, KernelSU: https://github.com/tiann/KernelSU
- rushiranpise, Root My Galaxy Next:
  https://github.com/rushiranpise/Root-My-Galaxy-Next
- BuSung-dev, Root My Galaxy Payloads:
  https://github.com/BuSung-dev/Root-My-Galaxy-Payloads
- marin-m, vmlinux-to-elf: https://github.com/marin-m/vmlinux-to-elf

A36 port and Compose UI: @tsuchinaka

## Warning

This is exploit code. It writes to kernel memory. It can brick the device, trip
Knox, or lose you warranty. Use it on hardware you own and can afford to lose.

I do not condone or endorse doing any of this. It is published for research and
for people who own the hardware and want to understand how the bug works.
Please do not run it on a device you do not own, and do not use it to break
other people's phones.
