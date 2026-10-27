# DFRoot A36

> [!WARNING]
> **Internal test code. Not recommended for use.**
> This is research code for one test device (Samsung Galaxy A36 5G, SM-A366E).
> It patches kernel memory, writes a kernel module, trips Knox, can brick the
> phone, void the warranty and wipe data. It is published only because I use it
> for my own internal tests and because the chain is worth reading. Do not
> install it on a daily driver, on a device you do not own, or on hardware you
> cannot afford to lose. No support, no warranty.

Temporary root for the Samsung Galaxy A36 5G (`SM-A366E`), using the DirtyFrag
bug (`CVE-2026-43284`). You open the app, tap one button, and `su` starts
answering `uid=0` for the KernelSU install it just dropped. No reboot, no cable.
The same chain can also be started from an adb shell.

This is a port and repackaging of the DFRoot app for the A36, with the UI
rebuilt in Jetpack Compose using Material 3 Expressive. It also re-applies the
Knox Guard unlock after the root chain on this device.

Current internal build: `3.4.0-adb` (`versionCode 14`).

## Tested on

This is the exact setup it was built and confirmed working against. Other
kernels and firmware may need a different `dirtyfrag` module.

- Device: Samsung Galaxy A36 5G (`SM-A366E`), Snapdragon 6 Gen 3, arm64
- Android: 16 (SDK 36)
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
5. `Runner.applyKgFix` re-applies the Knox Guard unlock after the overlay.

## Knox Guard handling

The app does not bypass the TA. After root it uses the same unlock path as the
system, because the TA state (not the `knox.kg.state` property) is what matters.

- A small arm64 helper (`app/src/main/assets/kgta_helper`, source in
  `tools/kgta_helper.c`) is extracted to the app's `filesDir` and executed as
  root. It `dlopen`s `/system/lib64/aidl_comm_kg_client.so` and calls the vendor
  `vendor.samsung.hardware.tlc.kg.ISehKg` HAL. That HAL accepts uid 0 or 1000
  and sends TA command `0x10a` (`TZ_KGTA_unlockScreen`); it does not require the
  Java `com.samsung.android.knoxguard.STATUS` permission.
- The Java path still runs afterwards: `su 10085 -c 'service call
  knoxguard_service 35'`. It performs the lock-screen unbind and updates the
  cached `knox.kg.state` property. Both paths are idempotent.
- The state is read from the TA (`service call knoxguard_service 29`); the
  property is only a mirror.
- On a device without the Samsung KG HAL, the helper fails cleanly and the
  Java path is used. The HAL path is root-only; a normal app cannot reach the
  vendor service.

The helper can be rebuilt with:

```sh
./tools/build-kgta-helper.sh
```

The source and script are in `tools/`; the asset is committed so the normal app
build does not need to rebuild it.

## UI

- Home, Log and About live behind a `FloatingActionButtonMenu` at the bottom
  end. There is no bottom navigation bar and no reserved band: content runs
  under the FAB, with scroll clearance inside each screen.
- Home has the status hero (LIVE / OFFLINE / checking), the compact kernel
  version, device, `su`, KernelSU, the auto-jailbreak switch and the Run again
  FAB. The Log tab shows the run log; About shows version, links and credits.
- Material 3 Expressive theming, dynamic color on Android 12 and up, and
  `MaterialShapes` decorations in the hero.

## Features

- One-tap root from the app, or a headless adb broadcast.
- Auto jailbreak on boot, with retries. Cold boots are hit or miss, so the
  receiver tries three times.
- Root-only Knox Guard unlock through the vendor HAL, with the Java uid 10085
  path retained for lock-screen cleanup and the state property.
- Floating FAB menu for Home/Log/About.
- Log in the app and under logcat tag `dfroot`; `run.log` in the app's private
  storage.
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

Open the app and tap Jailbreak (or Run again). Watch the Log tab. When it
finishes, `su -c id` should return `uid=0`. If KernelSU does not grant the app,
the status card says so and tells you to add DFRoot to the KernelSU superuser
list. The app shows `LIVE` only when `su` actually answers.

Auto jailbreak on boot is the switch on Home. It enables the boot receiver
component, nothing else.

## ADB trigger

The same chain can be started from an adb shell without tapping the UI:

```sh
adb shell am broadcast -a df.root.action.ROOT -n df.root/.RootReceiver --receiver-foreground
```

`RootReceiver` is exported but requires `android.permission.DUMP`; the adb
shell holds it, ordinary apps do not. The receiver runs `Runner.run` in a
background thread and logs under logcat tag `dfroot`:

```sh
adb logcat -s dfroot
adb shell su -c id
```

The `--receiver-foreground` flag gives the broadcast higher priority. If the
system kills the process during a long run, use the in-app button instead; the
same `Runner.claim` prevents concurrent runs.

## Notes and gotchas

- Never `rmmod kernelsu`. It panics the kernel and takes USB down with it.
- The root shell has an empty `PATH`. Always use absolute paths.
- The native watchdog reports `rc=2` even when the chain worked. Success is
  judged by watching the modules load and `su` answering, not by the return
  code.
- The stage 1 mutex lives at `/dev/df`. If a run dies halfway it stays behind
  and poisons boot until you reboot.
- The ADB receiver is shell-only by permission. Do not lower it to an
  unprotected receiver; any app could trigger a kernel exploit.
- The KG helper assumes the Samsung vendor HAL and `/system/lib64` arm64. On
  another device it fails and the Java path is used.
- Patch the KO variants only if you must. They are built outside this repo with
  the Samsung DDK and copied into `app/src/main/jni/ko/`.
- This is one-device research code. Do not treat it as a portable root tool.

## Project layout

```
app/src/main/java/df/root/     MainActivity (JNI host), DFRootApp, HomeScreen,
                               LogScreen, AboutScreen, DfrootViewModel, Runner,
                               RootProbe, RootRecord, BootReceiver, RootReceiver
app/src/main/jni/              exp.c, libc.S, libcxx.S, elf_parser.c, ko/*
app/src/main/assets/ksud       KernelSU userspace binary
app/src/main/assets/kgta_helper  arm64 KG vendor-HAL helper
tools/kgta_helper.c            helper source
tools/build-kgta-helper.sh     helper build script (NDK)
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
- meticha, Material 3 Expressive Catalog (FAB menu reference):
  https://github.com/meticha/material-3-expressive-catalog

A36 port and Compose UI: @tsuchinaka

## Final warning

This is exploit code. It writes to kernel memory. It can brick the device, trip
Knox, or lose your warranty. Use it only on hardware you own and can afford to
lose. I do not condone using it on devices you do not own, and I do not provide
support for it.
