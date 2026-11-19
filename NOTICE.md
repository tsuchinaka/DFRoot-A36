# Third-party components

DFRoot A36 packages work that is not MIT. The MIT license in LICENSE covers
the A36 port, the Compose UI and the persistence/service code added here.

## Bundled binaries

- `app/src/main/assets/ksud` sha256 `63046bf5a6409e5f394998604e859517465cd39764edda0125ea2eb873d5e561`
  Patched KernelSU userspace build (supports `late-load --stage-from`,
  `--ro-partitions` and `--soft-reboot`). KernelSU is GPL-2.0; see
  https://github.com/tiann/KernelSU. Rebuild provenance is not in this repo;
  pin the hash if you redistribute it.
- `app/src/main/assets/kgta_helper` sha256 `738735c46e8427491a7df2d85a7c7429a725f4e499f4c10dd5121781b2294aff`
  Built from `tools/kgta_helper.c` in this repo.

## Kernel module images

- `app/src/main/jni/ko/*.ko` are built from `dirtyfrag-lkm/dirtyfrag.c`
  (GPL-2.0, author polygraphene) with the Samsung DDK images
  `ghcr.io/ylarod/ddk-min`. The android15-6.6 image used here has sha256
  `6658df7da8b2e90a9d15dd551cbdc7a2405d707fdd13ee8892a1d7c635d884c0`;
  pin the Docker image by digest for reproducible builds.

## Source and ideas

- diabl0w, DFRoot: https://github.com/diabl0w/DFRoot
- LSPosed, LSPromise: https://github.com/LSPosed/LSPromise
- polygraphene, DFReroot (GPL-2.0): https://github.com/polygraphene/DFReroot
- combeng6th, DirtyInit: https://github.com/combeng6th/DirtyInit
- tiann, KernelSU (GPL-2.0): https://github.com/tiann/KernelSU
- rushiranpise, Root My Galaxy Next: https://github.com/rushiranpise/Root-My-Galaxy-Next
- BuSung-dev, Root My Galaxy Payloads: https://github.com/BuSung-dev/Root-My-Galaxy-Payloads
- marin-m, vmlinux-to-elf: https://github.com/marin-m/vmlinux-to-elf
- meticha, Material 3 Expressive Catalog: https://github.com/meticha/material-3-expressive-catalog
- `app/src/main/jni/include.inc` carries the Odzhan BSD 3-clause header.
