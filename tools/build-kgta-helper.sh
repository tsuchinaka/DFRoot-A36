#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/29.0.14206865}"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang"
if [ ! -x "$CC" ]; then
    echo "kgta-helper: clang not found at $CC" >&2
    echo "set ANDROID_NDK_HOME or ANDROID_HOME to an NDK 29 install" >&2
    exit 1
fi
mkdir -p "$ROOT/app/src/main/assets"
"$CC" -O2 -fPIE -pie -s -o "$ROOT/app/src/main/assets/kgta_helper" \
    "$ROOT/tools/kgta_helper.c" -ldl
echo "built $ROOT/app/src/main/assets/kgta_helper"
