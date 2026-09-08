#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
# Every configuration split is required to preserve the installed resource set.
# The distinct package ID keeps the original Proto installation and saves separate.
adb install-multiple --no-incremental -r artifacts/exact-clone/*.apk
adb shell am start -n local.proto.clone/local.proto.clone.Launcher
