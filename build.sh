#!/bin/sh

set -eu
cd "$(dirname "$0")"

./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk ./dfroot.apk
ls -l ./dfroot.apk
echo "OK: ./dfroot.apk"
