#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-$HOME/android-sdk/ndk/29.0.14206865}"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang"
mkdir -p "$ROOT/app/src/main/assets"
"$CC" -O2 -fPIE -pie -s -o "$ROOT/app/src/main/assets/kgta_helper" \
    "$ROOT/tools/kgta_helper.c" -ldl
echo "built $ROOT/app/src/main/assets/kgta_helper"
