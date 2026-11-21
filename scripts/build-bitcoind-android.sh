#!/usr/bin/env bash
#
# Build the bitcoind daemon that POCKETCORE ships, for 64-bit ARM Android.
#
# The app does not reimplement Bitcoin. It executes Bitcoin Core's own daemon
# from its native library directory, so every block this app reports has been
# hashed and every signature checked by Core's code. That binary is produced by
# this script and placed at
#
#     app/src/main/jniLibs/arm64-v8a/libbitcoind.so
#
# named ".so" only because that is where Android extracts an executable and
# gives it the executable bit (the build sets useLegacyPackaging = true). The
# file itself is a position-independent ELF executable, stripped.
#
# WHAT IT BUILDS
#   Bitcoin Core v32.1 from the official bitcoincore.org release tarball,
#   configured for aarch64-linux-android26 with the Android NDK, wallet, GUI,
#   IPC, external signer, ZMQ, USDT and the test/benchmark programs disabled.
#   The result is stripped with llvm-strip and copied into the app.
#
# INPUTS (each can be given instead of being downloaded)
#   BITCOIN_SRC     an unpacked Bitcoin Core v32.1 source tree (for example a
#                   git checkout of the v32.1 tag, or the tarball's directory).
#   BOOST_SRC       a Boost tree that contains a "boost/" header directory,
#                   e.g. an unpacked boost_1_86_0, or /usr/include when Boost
#                   headers were installed by the distribution. Only headers
#                   are used: Core 32.1 needs no compiled Boost library.
#   ANDROID_NDK_HOME / ANDROID_NDK_ROOT / ANDROID_NDK, or an SDK that has
#                   ndk/$NDK_VERSION installed.
#   BUILD_DIR       build tree (default: <repo>/build-arm64)
#   CACHE_DIR       where downloads are kept (default: <repo>/build-arm64-cache)
#   JOBS            parallel compile jobs (default: number of CPUs, max 8)
#   STRICT_HASH=1   fail if the produced binary is not byte-identical to the
#                   one recorded in docs/BUILD-RECORD.md
#
# With neither BITCOIN_SRC nor BOOST_SRC set, both archives are downloaded over
# HTTPS from their official hosts and checked against the SHA-256 values below,
# so an unattended run needs no network state beyond that.
#
# Usage:
#   scripts/build-bitcoind-android.sh
#   BITCOIN_SRC=/src/bitcoin-32.1 BOOST_SRC=/usr/include scripts/build-bitcoind-android.sh
#   CLEAN=1 scripts/build-bitcoind-android.sh
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Pinned versions. Changing any of these changes the daemon in the APK, so the
# build record in docs/BUILD-RECORD.md has to change with them.
# ---------------------------------------------------------------------------
BITCOIN_VERSION="32.1"
BITCOIN_TARBALL_URL="https://bitcoincore.org/bin/bitcoin-core-${BITCOIN_VERSION}/bitcoin-${BITCOIN_VERSION}.tar.gz"
BITCOIN_TARBALL_SHA256="a5f2f61e32cf7f790641c9ec306a3c88ca7b5a556946956b1f35ffd7e27813f9"

BOOST_VERSION="1.86.0"
BOOST_ARCHIVE_NAME="boost_1_86_0"
BOOST_TARBALL_URL="https://archives.boost.io/release/${BOOST_VERSION}/source/${BOOST_ARCHIVE_NAME}.tar.gz"
BOOST_TARBALL_SHA256="2575e74ffc3ef1cd0babac2c1ee8bdb5782a0ee672b1912da40e5b4b591ca01f"

NDK_VERSION="30.0.16248370"
# The same release under the older "r30" name, which is how a side-loaded NDK
# is usually unpacked. Both are accepted when looking for the toolchain.
NDK_R_TAG="r${NDK_VERSION%%.*}"
ANDROID_ABI="arm64-v8a"
ANDROID_API="26"

# The daemon as shipped in versionCode 10. Same source, same NDK, same flags
# and the same build path produce the same bytes - checked by relinking the
# object files of the shipped build in its own build directory. From another
# build directory the file is identical except for the 20-byte
# .note.gnu.build-id the linker derives from the output (which carries the
# build path), so a fresh checkout reproduces everything but those bytes.
# STRICT_HASH=1 turns that into a failure instead of a note.
EXPECTED_SHA256="6becfe26657d216dc84161f9f9c0e4beb6f76634ed6f74d1a8330719a8c74f02"
EXPECTED_SIZE="10729904"

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd -- "$SCRIPT_DIR/.." && pwd)
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs/$ANDROID_ABI"
OUT_SO="$JNI_DIR/libbitcoind.so"

BUILD_DIR="${BUILD_DIR:-$ROOT_DIR/build-arm64}"
CACHE_DIR="${CACHE_DIR:-$ROOT_DIR/build-arm64-cache}"

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# ---------------------------------------------------------------------------
# Tool checks
# ---------------------------------------------------------------------------
have cmake || die "cmake is required (3.22 or newer; Bitcoin Core 32.1 needs it)"
have python3 || die "python3 is required by Bitcoin Core's build scripts"
have ninja || die "ninja is required (the build uses the Ninja generator)"
if ! have curl && ! have wget; then
  die "need curl or wget to fetch the source archives (or pass BITCOIN_SRC and BOOST_SRC)"
fi
if ! have sha256sum && ! have shasum; then
  die "need sha256sum or shasum to check the source archives"
fi

ntasks() {
  local n
  if [ -n "${JOBS:-}" ]; then
    printf '%s\n' "$JOBS"
    return
  fi
  n=4
  if have nproc; then
    n=$(nproc)
  elif have sysctl; then
    n=$(sysctl -n hw.ncpu)
  fi
  if [ "$n" -gt 8 ]; then
    n=8
  fi
  printf '%s\n' "$n"
}

sha256_of() {
  if have sha256sum; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

fetch() { # fetch <url> <dest>
  local url="$1" dest="$2"
  mkdir -p "$(dirname "$dest")"
  if [ -s "$dest" ]; then
    log "using cached $(basename "$dest")"
    return
  fi
  log "downloading $url"
  if have curl; then
    curl -fL --retry 3 --connect-timeout 20 -o "$dest.part" "$url"
  else
    wget -O "$dest.part" "$url"
  fi
  mv "$dest.part" "$dest"
}

verify_sha256() { # verify_sha256 <file> <expected> <what>
  local file="$1" expected="$2" what="$3" actual
  actual=$(sha256_of "$file")
  if [ "$actual" != "$expected" ]; then
    die "$what SHA-256 mismatch: expected $expected, got $actual ($file)"
  fi
  log "$what SHA-256 verified: $actual"
}

# ---------------------------------------------------------------------------
# Android NDK
# ---------------------------------------------------------------------------
find_ndk() {
  local candidate
  for candidate in \
    "${ANDROID_NDK_HOME:-}" \
    "${ANDROID_NDK_ROOT:-}" \
    "${ANDROID_NDK:-}" \
    "${ANDROID_SDK_ROOT:-}/ndk/$NDK_VERSION" \
    "${ANDROID_SDK_ROOT:-}/ndk/android-ndk-$NDK_R_TAG" \
    "${ANDROID_HOME:-}/ndk/$NDK_VERSION" \
    "${ANDROID_HOME:-}/ndk/android-ndk-$NDK_R_TAG" \
    "/opt/android-sdk/ndk/$NDK_VERSION" \
    "/opt/android-ndk-$NDK_R_TAG" \
    "$HOME/Android/Sdk/ndk/$NDK_VERSION" \
    "$HOME/Android/Sdk/ndk/android-ndk-$NDK_R_TAG" \
    "$HOME/Android/Sdk/ndk-bundle"
  do
    [ -n "$candidate" ] || continue
    if [ -f "$candidate/build/cmake/android.toolchain.cmake" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

NDK_PATH=$(find_ndk) || die "Android NDK not found. Set ANDROID_NDK_HOME to an NDK $NDK_R_TAG ($NDK_VERSION) install (the directory that holds build/cmake/android.toolchain.cmake)."
TOOLCHAIN_FILE="$NDK_PATH/build/cmake/android.toolchain.cmake"
[ -f "$TOOLCHAIN_FILE" ] || die "missing $TOOLCHAIN_FILE"
log "NDK: $NDK_PATH"

# The llvm-strip that belongs to this NDK, so the binary is stripped by the
# same toolchain that compiled it.
HOST_TAG=""
case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
  Linux)  HOST_TAG="linux-x86_64" ;;
  *) die "unsupported host $(uname -s): the NDK ships prebuilts for Linux and macOS only" ;;
esac
LLVM_STRIP="$NDK_PATH/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-strip"
[ -x "$LLVM_STRIP" ] || die "missing $LLVM_STRIP"
log "clang: $("$NDK_PATH/toolchains/llvm/prebuilt/$HOST_TAG/bin/clang" --version | head -1)"

# ---------------------------------------------------------------------------
# Bitcoin Core source
# ---------------------------------------------------------------------------
if [ -n "${BITCOIN_SRC:-}" ]; then
  BITCOIN_SRC=$(cd -- "$BITCOIN_SRC" && pwd)
  [ -f "$BITCOIN_SRC/src/bitcoind.cpp" ] || die "BITCOIN_SRC=$BITCOIN_SRC does not look like a Bitcoin Core source tree (no src/bitcoind.cpp)"
  log "using Bitcoin Core source at $BITCOIN_SRC"
else
  mkdir -p "$CACHE_DIR"
  BITCOIN_TARBALL="$CACHE_DIR/bitcoin-${BITCOIN_VERSION}.tar.gz"
  fetch "$BITCOIN_TARBALL_URL" "$BITCOIN_TARBALL"
  verify_sha256 "$BITCOIN_TARBALL" "$BITCOIN_TARBALL_SHA256" "Bitcoin Core $BITCOIN_VERSION tarball"
  BITCOIN_SRC="$CACHE_DIR/bitcoin-${BITCOIN_VERSION}"
  if [ ! -f "$BITCOIN_SRC/src/bitcoind.cpp" ]; then
    log "unpacking $(basename "$BITCOIN_TARBALL")"
    rm -rf "$BITCOIN_SRC"
    tar -xzf "$BITCOIN_TARBALL" -C "$CACHE_DIR"
  fi
  log "using Bitcoin Core source at $BITCOIN_SRC"
fi

# ---------------------------------------------------------------------------
# Fresh build tree?
# ---------------------------------------------------------------------------
if [ "${CLEAN:-0}" = "1" ]; then
  log "removing $BUILD_DIR (CLEAN=1)"
  rm -rf "$BUILD_DIR"
fi

# ---------------------------------------------------------------------------
# Boost headers
# ---------------------------------------------------------------------------
if [ -z "${BOOST_SRC:-}" ]; then
  mkdir -p "$CACHE_DIR"
  BOOST_TARBALL="$CACHE_DIR/${BOOST_ARCHIVE_NAME}.tar.gz"
  fetch "$BOOST_TARBALL_URL" "$BOOST_TARBALL"
  verify_sha256 "$BOOST_TARBALL" "$BOOST_TARBALL_SHA256" "Boost $BOOST_VERSION tarball"
  BOOST_SRC="$CACHE_DIR/$BOOST_ARCHIVE_NAME"
  if [ ! -d "$BOOST_SRC/boost" ]; then
    log "unpacking $(basename "$BOOST_TARBALL") (large: about 840 MB of headers)"
    rm -rf "$BOOST_SRC"
    tar -xzf "$BOOST_TARBALL" -C "$CACHE_DIR"
  fi
fi
BOOST_SRC=$(cd -- "$BOOST_SRC" && pwd)
[ -d "$BOOST_SRC/boost" ] || die "BOOST_SRC=$BOOST_SRC has no boost/ directory"
log "using Boost headers at $BOOST_SRC"

# Bitcoin Core finds Boost through find_package(Boost). There is no compiled
# Boost here - only the headers - so a two-file CMake package that exposes
# Boost::headers (and Boost::boost, older name) stands in for a full Boost
# install. Boost_DIR points at it below.
BOOST_CMAKE_DIR="$BUILD_DIR/boost-cmake"
mkdir -p "$BOOST_CMAKE_DIR"
cat > "$BOOST_CMAKE_DIR/BoostConfigVersion.cmake" <<'EOF'
set(PACKAGE_VERSION "1.86.0")
set(PACKAGE_VERSION_COMPATIBLE TRUE)
set(PACKAGE_VERSION_EXACT TRUE)
EOF
cat > "$BOOST_CMAKE_DIR/BoostConfig.cmake" <<EOF
# Generated by scripts/build-bitcoind-android.sh. Header-only Boost for the
# cross build: Bitcoin Core 32.1 links Boost::headers and nothing else.
set(Boost_VERSION "$BOOST_VERSION")
set(Boost_VERSION_STRING "$BOOST_VERSION")
set(Boost_INCLUDE_DIR "$BOOST_SRC")
set(Boost_INCLUDE_DIRS "\${Boost_INCLUDE_DIR}")

if(NOT TARGET Boost::headers)
  add_library(Boost::headers INTERFACE IMPORTED)
  set_target_properties(Boost::headers PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "\${Boost_INCLUDE_DIR}"
    INTERFACE_COMPILE_DEFINITIONS "BOOST_MULTI_INDEX_DISABLE_SERIALIZATION"
  )
endif()

if(NOT TARGET Boost::boost)
  add_library(Boost::boost INTERFACE IMPORTED)
  set_target_properties(Boost::boost PROPERTIES
    INTERFACE_LINK_LIBRARIES Boost::headers
  )
endif()
EOF
log "wrote $BOOST_CMAKE_DIR/BoostConfig.cmake"

# ---------------------------------------------------------------------------
# Configure
# ---------------------------------------------------------------------------
mkdir -p "$BUILD_DIR"

# -O2 -g0 and no assertions mirrors the shipped daemon: optimised, no debug
# info (the file is stripped afterwards anyway), one ABI.
OPT_FLAGS="-O2 -g0"

log "configuring Bitcoin Core $BITCOIN_VERSION for $ANDROID_ABI (android-$ANDROID_API)"
cmake -S "$BITCOIN_SRC" -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
  -DANDROID_ABI="$ANDROID_ABI" \
  -DANDROID_PLATFORM="android-$ANDROID_API" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS="$OPT_FLAGS" \
  -DCMAKE_CXX_FLAGS="$OPT_FLAGS" \
  -DCMAKE_CXX_STANDARD_LIBRARIES="-latomic -lm" \
  -DBoost_DIR="$BOOST_CMAKE_DIR" \
  -DBUILD_BITCOIN_BIN=OFF \
  -DBUILD_DAEMON=ON \
  -DBUILD_CLI=OFF \
  -DBUILD_GUI=OFF \
  -DBUILD_TESTS=OFF \
  -DBUILD_BENCH=OFF \
  -DBUILD_FUZZ_BINARY=OFF \
  -DBUILD_FOR_FUZZING=OFF \
  -DBUILD_TX=OFF \
  -DBUILD_UTIL=OFF \
  -DBUILD_UTIL_CHAINSTATE=OFF \
  -DBUILD_KERNEL_LIB=OFF \
  -DENABLE_WALLET=OFF \
  -DENABLE_IPC=OFF \
  -DENABLE_EXTERNAL_SIGNER=OFF \
  -DWITH_ZMQ=OFF \
  -DWITH_USDT=OFF \
  -DWITH_CCACHE=OFF

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
JOBS_N=$(ntasks)
log "building bitcoind with $JOBS_N jobs"
cmake --build "$BUILD_DIR" --target bitcoind --parallel "$JOBS_N"

BUILT="$BUILD_DIR/bin/bitcoind"
[ -f "$BUILT" ] || die "the build finished but $BUILT is missing"

# ---------------------------------------------------------------------------
# Strip and install
# ---------------------------------------------------------------------------
mkdir -p "$JNI_DIR"
log "stripping with $(basename "$LLVM_STRIP")"
cp "$BUILT" "$OUT_SO"
"$LLVM_STRIP" --strip-all "$OUT_SO"
chmod 0755 "$OUT_SO"

SIZE=$(wc -c < "$OUT_SO" | tr -d ' ')
SHA=$(sha256_of "$OUT_SO")

log "wrote $OUT_SO"
log "size:    $SIZE bytes (shipped: $EXPECTED_SIZE)"
log "sha256:  $SHA"
log "shipped: $EXPECTED_SHA256"

if have file; then
  file "$OUT_SO" | sed 's/^/    /'
fi

if [ "$SHA" = "$EXPECTED_SHA256" ]; then
  log "byte-identical to the daemon recorded in docs/BUILD-RECORD.md"
elif [ "${STRICT_HASH:-0}" = "1" ]; then
  die "STRICT_HASH=1 and the binary differs from the recorded daemon; check NDK, Boost, flags and build path"
else
  log "note: differs from the recorded daemon. A different NDK, Boost revision or"
  log "      build path is the usual reason; check the ELF notes before assuming more."
  log "      Record the new SHA-256 and size in docs/BUILD-RECORD.md if this build ships."
fi

log "done"
