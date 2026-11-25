# DFRoot A36

> [!WARNING]
> Internal research code for one test device. It patches kernel memory, writes a
> kernel module, trips Knox, can brick the phone and void the warranty. Use it
> only on hardware you own and can afford to lose.

Temporary KernelSU root for the Samsung Galaxy A36 5G (`SM-A366E`) using
DirtyFrag (`CVE-2026-43284`). Tap one button, `su` starts working, no reboot and
no cable. It can also root on boot/unlock and re-apply the Knox Guard unlock.

Current release: [`v4.3.0-ui`](https://github.com/tsuchinaka/DFRoot-A36/releases/tag/v4.3.0-ui)
- APK: `dfroot.apk`
- Version: `4.3.0-ui` (`versionCode 32`), arm64 only
- SHA-256: `09f88f23fe02fc92237ec885537a175b709e6872f1ed6c2af31eeb78ce99652e`
- Signed with the v4.0 key, so it updates in place
- CI build/tests passed; this revision was not tested on hardware

## Requirements

- Galaxy A36 5G (`SM-A366E`, `a36xq`)
- Kernel `6.6.98-android15-8-33419078-abogkiA366EXXSBCZG3-4k`
- Firmware `A366EXXSBCZG3`
- KernelSU Manager already installed

Other firmware or kernels are refused. The KO table is exact per
Android release and kernel major.minor.

## Install

```sh
adb install -r dfroot.apk
```

If the old install uses a different signing key, uninstall it first. That wipes
the app's private data.

## Use

1. Open DFRoot.
2. Tap **Jailbreak** and wait for the log to finish.
3. `su -c id` should return `uid=0`.
4. If KernelSU refuses the app, add DFRoot to its superuser list.
5. Enable **Auto-jailbreak on boot** if you want boot/unlock retries and the
   keep service.

The app shows `LIVE` only when `su` actually answers. KernelSU still controls
the allowlist.

ADB trigger:

```sh
adb shell am broadcast -a df.root.action.ROOT -n df.root/.RootReceiver --receiver-foreground
adb logcat -s dfroot
```

The receiver requires `android.permission.DUMP`; ordinary apps cannot call it.

## What changed in 4.3

- Home shows the real chain state: rooted, needs-reboot, no-allowlist, stalled
  or a retry countdown, read from the `root-stage` file the chain persists.
- A root side card reports heartbeat age, supervisor, watchdog, kernel module,
  exploit mutex and boot scripts from the paths the supervisor publishes.
- The run card shows the live phase and an elapsed timer instead of an
  unlabelled progress bar; the last pass is a structured row (rc, duration,
  action).
- Root-side actions appear only when they apply: reboot (confirmed) on
  needs-reboot, manager deep link and copy-su on no-allowlist.
- The auto-root switch writes its intent to device-protected storage and the
  supervisor honours it, so toggling it off is no longer undone every 2 minutes.
- Bottom navigation replaces the FAB menu; whole-row toggles; status colour
  tokens in the theme; severity colouring and per-pass dividers in the log.
- Diagnostics export includes the chain state, root side, last Knox Guard
  result and `sup.log`.

## What changed in 4.2

- A failed stage no longer poisons the boot: the mutex `/dev/df` is released on
  every hard stage-2 failure, `finit_module -EEXIST` counts as loaded, and
  stale `/dev/dfm*` markers from an earlier pass are ignored.
- Each chunk is read once and reused for retries instead of recomputing IVs
  against half-patched page cache; `crash_dump64` and `libbinderdebug.so` are
  restored after the chain.
- The keep service runs in `:chain` with a file-locked chain claim, so the UI
  cannot start a second exploit over a boot pass.
- `BootChain` stopped holding a class monitor for minutes: `tryLock`, a bounded
  60 s wait on a held mutex, explicit `needs-reboot` and `no-allowlist` states,
  and a 30-minute backoff after six failures in one boot.
- Root-side supervisor v2 re-enables its own components, clears a force-stopped
  package, clears a poisoned mutex while `su` is dead, and has an independent
  `dfroot-watch.sh` respawner.
- The Knox Guard pass no longer blocks the keep tick.

## What changed in 4.1

- Native patching batches 4 KB page-cache chunks instead of forking
  `crash_dump64` for every 16-byte block.
- Stage 2 reports ksud exec success/failure through a pipe instead of a fixed
  wait.
- JNI validates keys, ICV length, port, socket type and paths.
- UI probes only while visible and reuses a fresh root heartbeat.
- The keep service stops when auto-root is off and root is dead.
- Logs rotate at 512 KB and render lazily.
- Native and Java metrics are included in diagnostics.

## Build

Requirements: JDK 21, Android SDK Platform 37, Build Tools 37, NDK
`29.0.14206865` and CMake 3.22.1.

```sh
./create-keystore.sh
./gradlew :app:assembleRelease
```

Fast internal build without R8 or resource shrinking:

```sh
./gradlew :app:assembleRelease -Pdfroot.minify=false
```

Tests and artifact checks:

```sh
./gradlew :app:test
./tools/check-build.sh
```

CI runs the same steps on push, pull request or manual dispatch and uploads the
APK as an artifact. Tag releases need `DFROOT_KEYSTORE_B64`,
`DFROOT_STORE_PASSWORD`, `DFROOT_KEY_ALIAS` and `DFROOT_KEY_PASSWORD` in
repository secrets. Without them, publish the locally signed APK manually.

## Notes

- Return code `0` means ksud was launched, not that root is live. The app still
  probes `su`.
- The stage-1 mutex is `/dev/df`. A dead stage releases it before exiting, so a
  later pass in the same boot can retry; `BootChain` reports `needs-reboot` only
  when su is dead and the mutex is still held.
- Markers are compared by creation time, so `/dev/dfm*` files from a previous
  pass do not look like results of the current one.
- Never `rmmod kernelsu`; it panics the kernel.
- The Knox Guard TA unlock can write a one-way fuse. Treat it as irreversible.
- Patch KO images only with the Samsung DDK, and keep their provenance.
- This is not a portable root tool.

## Project layout

```
app/src/main/java/df/root/    app, service, state, UI and logs
app/src/main/jni/             exp.c, libc.S, libcxx.S, elf_parser.c, ko/
app/src/main/assets/ksud      patched KernelSU userspace
app/src/main/assets/kgta_helper
tools/                        helper source and artifact checks
dirtyfrag-lkm/                LKM source
```

## Credits

Exploit work is not mine. Key upstream projects:

- diabl0w, DFRoot: https://github.com/diabl0w/DFRoot
- LSPosed, LSPromise: https://github.com/LSPosed/LSPromise
- polygraphene, DFReroot: https://github.com/polygraphene/DFReroot
- combeng6th, DirtyInit: https://github.com/combeng6th/DirtyInit
- tiann, KernelSU: https://github.com/tiann/KernelSU
- rushiranpise, Root My Galaxy Next: https://github.com/rushiranpise/Root-My-Galaxy-Next
- BuSung-dev, Root My Galaxy Payloads: https://github.com/BuSung-dev/Root-My-Galaxy-Payloads
- marin-m, vmlinux-to-elf: https://github.com/marin-m/vmlinux-to-elf

A36 port and Compose UI: @tsuchinaka

## License

The A36 port, UI and service code are MIT (`LICENSE`). Bundled binaries and
upstream sources keep their own licenses; see `NOTICE.md`.
