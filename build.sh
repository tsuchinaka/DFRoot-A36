#!/bin/sh

set -eu
cd "$(dirname "$0")"

./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk ./dirtyfrag.apk
ls -l ./dirtyfrag.apk
echo "OK: ./dirtyfrag.apk"
