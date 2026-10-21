#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    ndk="$ANDROID_NDK_HOME"
else
    sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -z "$sdk" ]]; then
        if [[ "$(uname -s)" == "Darwin" ]]; then
            sdk="$HOME/Library/Android/sdk"
        else
            sdk="$HOME/Android/Sdk"
        fi
    fi
    # Some developer machines place the SDK one directory deeper; normalize it.
    if [[ ! -d "$sdk/ndk" && -d "$sdk/sdk/ndk" ]]; then
        sdk="$sdk/sdk"
    fi
    ndk="$sdk/ndk/${DUMBER_NDK_VERSION:-30.0.16248370}"
    if [[ ! -d "$ndk" && -d "$sdk/ndk" ]]; then
        # Prefer the pinned version but permit a compatible locally installed NDK.
        for installed_ndk in "$sdk"/ndk/*; do
            [[ -d "$installed_ndk" ]] && ndk="$installed_ndk"
        done
    fi
fi
case "$(uname -s)" in
    Linux) host_tag="linux-x86_64" ;;
    Darwin) host_tag="darwin-x86_64" ;;
    *) echo "Unsupported build host: $(uname -s)" >&2; exit 1 ;;
esac
toolchain="$ndk/toolchains/llvm/prebuilt/$host_tag/bin"
if [[ ! -x "$toolchain/llvm-ar" ]]; then
    echo "Android NDK toolchain not found at $ndk (set ANDROID_NDK_HOME to override)" >&2
    exit 1
fi
rust_toolchain="${DUMBER_RUST_TOOLCHAIN:-stable}"
rustc_path="$(rustup which --toolchain "$rust_toolchain" rustc)"
# Build every Android ABI supported by the app's minSdk. The one-target override
# remains useful for local iteration; CI/release builds include all four.
targets="${DUMBER_RUST_TARGETS:-${DUMBER_RUST_TARGET:-aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android}}"
for target in $targets; do
    case "$target" in
        aarch64-linux-android) abi=arm64-v8a; compiler=aarch64-linux-android23 ;;
        armv7-linux-androideabi) abi=armeabi-v7a; compiler=armv7a-linux-androideabi23 ;;
        i686-linux-android) abi=x86; compiler=i686-linux-android23 ;;
        x86_64-linux-android) abi=x86_64; compiler=x86_64-linux-android23 ;;
        *) echo "Unsupported target: $target" >&2; exit 1 ;;
    esac
    suffix="${target//-/_}"
    upper_suffix="$(printf '%s' "$suffix" | tr '[:lower:]' '[:upper:]')"
    export "CARGO_TARGET_${upper_suffix}_LINKER=$toolchain/$compiler-clang"
    export "CC_$suffix=$toolchain/$compiler-clang"
    export "CXX_$suffix=$toolchain/$compiler-clang++"
    export "AR_$suffix=$toolchain/llvm-ar"
    RUSTC="$rustc_path" rustup run "$rust_toolchain" cargo build \
        --locked --release --manifest-path vendor/dumber-chat-core/Cargo.toml --target "$target"
    mkdir -p "app/src/main/jniLibs/$abi"
    cp "${CARGO_TARGET_DIR:-vendor/dumber-chat-core/target}/$target/release/libdumber_chat_core.so" "app/src/main/jniLibs/$abi/"
    "$toolchain/llvm-strip" --strip-unneeded "app/src/main/jniLibs/$abi/libdumber_chat_core.so"
done
