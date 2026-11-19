#!/bin/sh
# Verifies that the release APK carries the expected native markers and that
# the A36 module still declares a 6.6 vermagic. Runs in CI after assembleRelease.
set -eu

APK=app/build/outputs/apk/release/app-release.apk
test -f "$APK"

TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT
unzip -p "$APK" lib/arm64-v8a/libexp.so > "$TMP"
strings "$TMP" | grep -q '/dev/dfmC'
strings "$TMP" | grep -q '/vendor/bin/modprobe'
strings "$TMP" | grep -q '/data/system/ksud'

strings app/src/main/jni/ko/dirtyfrag-android15-6.6.ko | grep -q 'vermagic=6.6'

echo "artifact checks OK"
