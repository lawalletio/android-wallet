#!/usr/bin/env bash
# Rebuilds the Payjoin Dev Kit (rust-payjoin's payjoin-ffi) for the app: the native library per ABI into
# app/src/main/jniLibs and its generated Kotlin bindings (package org.payjoindevkit) into app/src/main/java.
# There's no published Android artifact, so both are built here from a pinned tag and committed.
#
# Needs rustup (stable, with the Android targets), cargo-ndk (`cargo install cargo-ndk`) and an NDK.
#   scripts/build-payjoin-ffi.sh [tag]
set -euo pipefail

TAG=${1:-payjoin-ffi-0.26.0}
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK=${ANDROID_NDK_HOME:-$(ls -d "$HOME/Library/Android/sdk/ndk/"* | sort -V | tail -1)}
ABIS=(arm64-v8a armeabi-v7a x86_64)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

export RUSTUP_TOOLCHAIN=stable ANDROID_NDK_HOME="$NDK"
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android >/dev/null
git clone -q --depth 1 --branch "$TAG" https://github.com/payjoin/rust-payjoin.git "$WORK/src"
cd "$WORK/src"
cargo ndk $(printf -- '-t %s ' "${ABIS[@]}") --platform 28 -o "$WORK/jni" build --release -p payjoin-ffi

STRIP=$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip)
for abi in "${ABIS[@]}"; do
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  "$STRIP" --strip-unneeded -o "$ROOT/app/src/main/jniLibs/$abi/libpayjoin_ffi.so" "$WORK/jni/$abi/libpayjoin_ffi.so"
done

cd payjoin-ffi
# Android's cleaner (JNA's), not java.lang.ref.Cleaner: that one is API 33+ and minSdk is 28.
sed -i.bak 's/^\[bindings.kotlin\]$/[bindings.kotlin]\nandroid_cleaner = true/' uniffi.toml
cargo run -q --release -p payjoin-ffi --bin uniffi-bindgen -- generate --library "$WORK/jni/arm64-v8a/libpayjoin_ffi.so" \
  --language kotlin --out-dir "$WORK/kotlin" --no-format
mkdir -p "$ROOT/app/src/main/java/org/payjoindevkit"
cp "$WORK/kotlin/org/payjoindevkit/payjoin.kt" "$ROOT/app/src/main/java/org/payjoindevkit/payjoin.kt"
echo "payjoin-ffi $TAG → jniLibs (${ABIS[*]}) and org/payjoindevkit/payjoin.kt"
