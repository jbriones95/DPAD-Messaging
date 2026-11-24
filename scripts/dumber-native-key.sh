#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Keep this deterministic across CI and release jobs. The native artifact name
# is derived from every source file plus the compiler/NDK/target configuration.
{
    sha256sum scripts/build-dumber-core.sh
    find vendor/dumber-chat-core -type f \
        ! -path '*/target/*' \
        ! -path '*/.git/*' \
        -print0 | sort -z | xargs -0 sha256sum
    printf '%s\n' \
        "${DUMBER_RUST_TOOLCHAIN:-stable}" \
        "${DUMBER_NDK_VERSION:-30.0.16248370}" \
        "${DUMBER_RUST_TARGETS:-aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android}"
} | sha256sum | awk '{print substr($1, 1, 16)}'
