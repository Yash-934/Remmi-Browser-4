#!/bin/bash
set -e

# Find NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    if [ -d "$ANDROID_HOME/ndk/25.1.8937393" ]; then
        export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/25.1.8937393"
    elif [ -d "$ANDROID_SDK_ROOT/ndk/25.1.8937393" ]; then
        export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/25.1.8937393"
    elif [ -d "$ANDROID_HOME/ndk-bundle" ]; then
        export ANDROID_NDK_HOME="$ANDROID_HOME/ndk-bundle"
    else
        NDK_DIR=$(find "${ANDROID_HOME:-$ANDROID_SDK_ROOT}/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -n 1)
        if [ -n "$NDK_DIR" ]; then
            export ANDROID_NDK_HOME="$NDK_DIR"
        fi
    fi
fi

if [ -z "$ANDROID_NDK_HOME" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "ERROR: Android NDK not found. Please set ANDROID_NDK_HOME."
    exit 1
fi

echo "Using NDK at $ANDROID_NDK_HOME"

cd "$(dirname "$0")/../rust" || exit 1

# Ensure targets are added
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

# Ensure cargo-ndk is installed
if ! command -v cargo-ndk >/dev/null 2>&1; then
    echo "Installing cargo-ndk..."
    cargo install cargo-ndk
fi

# Build for all required targets
echo "Building libadblock_rust.so for arm64-v8a, armeabi-v7a, and x86_64..."
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 build --release

# Copy to jniLibs
JNI_LIBS="../app/src/main/jniLibs"
mkdir -p "$JNI_LIBS/arm64-v8a"
mkdir -p "$JNI_LIBS/armeabi-v7a"
mkdir -p "$JNI_LIBS/x86_64"

cp -f target/aarch64-linux-android/release/libadblock_rust.so "$JNI_LIBS/arm64-v8a/"
cp -f target/armv7-linux-androideabi/release/libadblock_rust.so "$JNI_LIBS/armeabi-v7a/"
cp -f target/x86_64-linux-android/release/libadblock_rust.so "$JNI_LIBS/x86_64/"

echo "Successfully built and copied libadblock_rust.so for all architectures."

echo "SHA-256 Hashes of generated native libraries:"
sha256sum "$JNI_LIBS/arm64-v8a/libadblock_rust.so"
sha256sum "$JNI_LIBS/armeabi-v7a/libadblock_rust.so"
sha256sum "$JNI_LIBS/x86_64/libadblock_rust.so"


