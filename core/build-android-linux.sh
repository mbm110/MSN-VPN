#!/bin/bash
# Linux equivalent of core/build-android.ps1
# Builds libaether.so for Android using cargo + NDK clang toolchain
set -e

ABI="${1:-arm64-v8a}"
API=24

case "$ABI" in
  arm64-v8a)
    TARGET_TRIPLE="aarch64-linux-android"
    CLANG_PREFIX="aarch64-linux-android"
    INCLUDE_ARCH="aarch64-linux-android"
    ;;
  armeabi-v7a)
    TARGET_TRIPLE="armv7-linux-androideabi"
    CLANG_PREFIX="armv7a-linux-androideabi"
    INCLUDE_ARCH="arm-linux-androideabi"
    ;;
  *)
    echo "Unknown ABI: $ABI" >&2
    exit 1
    ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CRATE="$ROOT/core/aether"
TARGET_DIR="$CRATE/target-android"

SDK="/tmp/android-sdk"
NDK="$SDK/ndk/26.3.11579264"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CMAKE="$SDK/cmake/3.22.1/bin/cmake"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"

export ANDROID_NDK_HOME="$NDK"
export ANDROID_NDK_ROOT="$NDK"
export CMAKE="$CMAKE"
export CMAKE_GENERATOR="Ninja"
export CARGO_TARGET_DIR="$TARGET_DIR"
export PATH="$(dirname "$CMAKE"):$BIN:$PATH"

# ring/boring-sys look for aarch64-linux-android-clang (no API level) in PATH
ln -sf "$BIN/${CLANG_PREFIX}24-clang" "$BIN/${CLANG_PREFIX}-clang"
ln -sf "$BIN/${CLANG_PREFIX}24-clang++" "$BIN/${CLANG_PREFIX}-clang++"

# Ensure Rust target installed
rustup target add "$TARGET_TRIPLE"

# Step 1: bootstrap BoringSSL (uses cargo ndk-style build)
pushd "$CRATE"
echo "=== Bootstrap BoringSSL ==="
cargo build --release --lib --target "$TARGET_TRIPLE" || true

BSSL_OUT=$(find "$TARGET_DIR/$TARGET_TRIPLE/release/build" -maxdepth 1 -type d -name 'boring-sys-*' 2>/dev/null | head -1)
if [ -z "$BSSL_OUT" ]; then
  echo "BoringSSL bootstrap output not found" >&2
  exit 1
fi
BSSL_BUILD="$BSSL_OUT/out/build"
if [ ! -f "$BSSL_BUILD/libssl.a" ]; then
  echo "libssl.a not found in $BSSL_BUILD" >&2
  exit 1
fi

# Step 2: real build with NDK clang linker
RUST_ENV_SUFFIX=$(echo "$TARGET_TRIPLE" | tr '[:lower:]' '[:upper:]' | tr '-' '_')
RUST_TARGET_SUFFIX=$(echo "$TARGET_TRIPLE" | tr '-' '_')

export BORING_BSSL_PATH="$BSSL_BUILD"
export BORING_BSSL_INCLUDE_PATH="$BSSL_OUT/out/boringssl/src/include"
export BORING_BSSL_ASSUME_PATCHED="1"
export CLANG_PATH="$BIN/clang"
export CARGO_TARGET_${RUST_ENV_SUFFIX}_LINKER="$BIN/${CLANG_PREFIX}${API}-clang"
export CARGO_TARGET_${RUST_ENV_SUFFIX}_AR="$BIN/llvm-ar"
export AR_${RUST_TARGET_SUFFIX}="$BIN/llvm-ar"
export CC_${RUST_TARGET_SUFFIX}="$BIN/clang"
export CXX_${RUST_TARGET_SUFFIX}="$BIN/clang++"
export CFLAGS_${RUST_TARGET_SUFFIX}="--target=${CLANG_PREFIX}${API}"
export CXXFLAGS_${RUST_TARGET_SUFFIX}="--target=${CLANG_PREFIX}${API}"
export BINDGEN_EXTRA_CLANG_ARGS_${RUST_TARGET_SUFFIX}="--target=${CLANG_PREFIX}${API} --sysroot=${SYSROOT} -I${SYSROOT}/usr/include/${INCLUDE_ARCH}"
export RUSTFLAGS="-C link-arg=-Wl,-soname,libaether.so -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"

echo "=== Building libaether.so ==="
cargo build --release --lib --target "$TARGET_TRIPLE"

LIBRARY="$TARGET_DIR/$TARGET_TRIPLE/release/libaether.so"
if [ ! -f "$LIBRARY" ]; then
  echo "libaether.so not produced" >&2
  exit 1
fi

for dest in "$ROOT/core/android-libs/$ABI" "$ROOT/app/src/main/jniLibs/$ABI"; do
  mkdir -p "$dest"
  cp "$LIBRARY" "$dest/libaether.so"
  echo "Copied to $dest/libaether.so"
done

popd
echo "DONE"
