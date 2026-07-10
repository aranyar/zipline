#!/bin/bash
#
# Builds Hermes for the host (macOS, x86_64 + arm64) using CMake.
#
# Produces:
#   - build_host_hermesc/bin/hermesc                       (JS -> HBC compiler, host arch)
#   - build_host_hermesc/bin/shermes                       (Static Hermes compiler, host arch)
#   - build_host_hermesc/ImportHostCompilers.cmake         (consumed by Android cross-build)
#   - build_macos/lib/libhermesvm.dylib                    (full VM w/ compiler; fat)
#   - build_macos/lib/libhermesvmlean.dylib                (lean VM; fat)
#   - build_macos/jsi/libjsi.dylib                         (fat; standalone)
#   - build_macos_static/lib/libhermesvm_a.a               (full VM, static)
#   - build_macos_static/jsi/libjsi.a                      (JSI, static)
#
# The *_static build dir is needed to produce a single fat dylib that
# statically links Hermes (see ./build_jni_dylib.sh). The shared lib
# (libhermesvm.dylib) has compileJS() hidden by -fvisibility=hidden, so
# zipline's JNI glue (Context::compile) can't reach it through the shared
# lib. By force-loading libhermesvm_a.a + libjsi.a into our own .dylib,
# we get every symbol Hermes exposes.
#
# Toolchain required: cmake >= 3.21, ninja, python3, Xcode/clang.

set -euo pipefail

# Resolve repo-relative paths regardless of caller cwd.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HERMES_SRC="$SCRIPT_DIR/native/hermes"
HERMES_BUILD_HOST="$HERMES_SRC/build_host_hermesc"
HERMES_BUILD_MACOS="$HERMES_SRC/build_macos"
HERMES_BUILD_STATIC="$HERMES_SRC/build_macos_static"
JNI_BUILD_SRC="$SCRIPT_DIR/native/hermes-jni-build"

JOBS="$(sysctl -n hw.ncpu 2>/dev/null || nproc)"

# Find a JDK with JNI headers if the current JAVA_HOME doesn't have them
detect_java_home_with_jni() {
  local java_home="${1:-}"
  if [[ -n "$java_home" && -d "$java_home/include" ]]; then
    echo "$java_home"
    return 0
  fi
  # Try to find a JDK with JNI headers
  for jdk in /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    if [[ -d "$jdk/include" ]]; then
      echo "$jdk"
      return 0
    fi
  done
  return 1
}

# Use JAVA_HOME from environment if it has JNI headers, otherwise detect one
if [[ -z "${JAVA_HOME:-}" ]] || [[ ! -d "${JAVA_HOME}/include" ]]; then
  DETECTED_JAVA_HOME="$(detect_java_home_with_jni "${JAVA_HOME:-}")" || true
  if [[ -n "$DETECTED_JAVA_HOME" ]]; then
    export JAVA_HOME="$DETECTED_JAVA_HOME"
    echo "==> Using detected JDK with JNI headers: $JAVA_HOME"
  fi
fi

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "ERROR: this script currently only supports macOS hosts." >&2
  echo "       (Hermes macOS framework scripts use xcrun; Linux/macOS shared-lib path needs adjusting.)" >&2
  exit 1
fi

if ! command -v cmake >/dev/null 2>&1; then
  echo "ERROR: cmake not found in PATH. Install with: brew install cmake" >&2
  exit 1
fi
if ! command -v ninja >/dev/null 2>&1; then
  echo "ERROR: ninja not found in PATH. Install with: brew install ninja" >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 not found in PATH." >&2
  exit 1
fi

# Pin to the vendored Hermes revision so bytecode stays compatible.
HERMES_REV="$(git -C "$HERMES_SRC" rev-parse HEAD 2>/dev/null || echo '<not a git repo>')"
echo "==> Building Hermes @ $HERMES_REV"
echo "    source:         $HERMES_SRC"
echo "    host build:     $HERMES_BUILD_HOST"
echo "    macOS build:    $HERMES_BUILD_MACOS"
echo "    macOS static:   $HERMES_BUILD_STATIC"
echo "    jobs:           $JOBS"

###############################################################################
# Step 1: host hermesc + shermes. Needed to satisfy IMPORT_HOST_COMPILERS
# in step 2 (InternalJavaScript step uses both).
###############################################################################
echo ""
echo "==> [1/4] Building host hermesc + shermes ..."
cmake -S "$HERMES_SRC" -B "$HERMES_BUILD_HOST" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release
cmake --build "$HERMES_BUILD_HOST" --target hermesc shermes -j "$JOBS"

if [[ ! -x "$HERMES_BUILD_HOST/bin/hermesc" ]]; then
  echo "ERROR: hermesc binary not produced at $HERMES_BUILD_HOST/bin/hermesc" >&2
  exit 1
fi
if [[ ! -x "$HERMES_BUILD_HOST/bin/shermes" ]]; then
  echo "ERROR: shermes binary not produced at $HERMES_BUILD_HOST/bin/shermes" >&2
  exit 1
fi
echo "    hermesc: $HERMES_BUILD_HOST/bin/hermesc"
echo "    shermes: $HERMES_BUILD_HOST/bin/shermes"
echo "    cmake-import: $HERMES_BUILD_HOST/ImportHostCompilers.cmake"

###############################################################################
# Step 2: macOS host VMs (libhermesvm.dylib full + libhermesvmlean.dylib lean
# + libjsi.dylib). Built in one CMake invocation to share object files.
###############################################################################
echo ""
echo "==> [2/4] Building macOS shared VMs (x86_64 + arm64) ..."
cmake -S "$HERMES_SRC" -B "$HERMES_BUILD_MACOS" -G Ninja \
  -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DCMAKE_OSX_ARCHITECTURES="x86_64;arm64" \
  -DCMAKE_OSX_DEPLOYMENT_TARGET=10.15 \
  -DHERMES_ENABLE_DEBUGGER=OFF \
  -DHERMES_ENABLE_INTL=ON \
  -DHERMES_ENABLE_TEST_SUITE=OFF \
  -DHERMES_ENABLE_TOOLS=OFF \
  -DHERMES_BUILD_SHARED_JSI=ON \
  -DHERMES_BUILD_APPLE_FRAMEWORK=OFF \
  -DJSI_DIR="$HERMES_SRC/API/jsi" \
  -DIMPORT_HOST_COMPILERS="$HERMES_BUILD_HOST/ImportHostCompilers.cmake"
cmake --build "$HERMES_BUILD_MACOS" --target hermesvm hermesvmlean jsi -j "$JOBS"

###############################################################################
# iOS Simulator shared VM (x86_64 + arm64-simulator). Built separately because
# iOS Simulator requires different platform/SDK settings. Output is a single
# universal dylib placed at $HERMES_IOS_SIM/libhermesvm.dylib.
###############################################################################
echo ""
echo "==> [3/4] Building iOS Simulator shared VM (x86_64 + arm64-simulator) ..."
HERMES_BUILD_IOS_SIM="$HERMES_SRC/build_ios_simulator"
HERMES_IOS_SIM="$HERMES_SRC/ios_simulator_lib"

# Find iOS Simulator SDK path. Prefer the latest installed.
IOS_SIM_SDK_PATH="$(xcrun --sdk iphonesimulator --show-sdk-path 2>/dev/null || true)"
if [[ -z "$IOS_SIM_SDK_PATH" ]]; then
  echo "ERROR: iOS Simulator SDK not found. Install Xcode Command Line Tools." >&2
  exit 1
fi

cmake -S "$HERMES_SRC" -B "$HERMES_BUILD_IOS_SIM" -G Ninja \
  -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DCMAKE_SYSTEM_NAME=iOS \
  -DCMAKE_OSX_SYSROOT=iphonesimulator \
  -DCMAKE_OSX_DEPLOYMENT_TARGET=14.0 \
  -DCMAKE_OSX_ARCHITECTURES="x86_64;arm64" \
  -DHERMES_ENABLE_DEBUGGER=OFF \
  -DHERMES_ENABLE_INTL=ON \
  -DHERMES_ENABLE_TEST_SUITE=OFF \
  -DHERMES_ENABLE_TOOLS=OFF \
  -DHERMES_BUILD_SHARED_JSI=ON \
  -DHERMES_BUILD_APPLE_FRAMEWORK=ON \
  -DHERMESVM_LEAN=ON \
  -DJSI_DIR="$HERMES_SRC/API/jsi" \
  -DIMPORT_HOST_COMPILERS="$HERMES_BUILD_HOST/ImportHostCompilers.cmake"
cmake --build "$HERMES_BUILD_IOS_SIM" --target hermesvmlean -j "$JOBS"
# Remove any stale hermesvm.framework from a previous full build before
# the staging step renames the new lean framework.
rm -rf "$HERMES_BUILD_IOS_SIM/lib/hermesvm.framework"

# Stage the iOS Simulator dylib AND framework at known locations for
# Kotlin/Native consumers. We also copy hermes-ios.h and hermes-core.h
# alongside. The lean build produces hermesvmlean.framework which we
# expose as hermesvm.framework for backwards compatibility with the
# downstream consumers.
mkdir -p "$HERMES_IOS_SIM"
if [[ -f "$HERMES_BUILD_IOS_SIM/lib/libhermesvmlean.dylib" ]]; then
  cp "$HERMES_BUILD_IOS_SIM/lib/libhermesvmlean.dylib" "$HERMES_IOS_SIM/libhermesvm-ios-simulator.dylib"
  echo "    -> $HERMES_IOS_SIM/libhermesvm-ios-simulator.dylib"
fi
if [[ -d "$HERMES_BUILD_IOS_SIM/lib/hermesvmlean.framework" ]]; then
  rm -rf "$HERMES_IOS_SIM/hermesvm.framework"
  # Update CFBundleExecutable in Info.plist before staging so the bundle
  # stays internally consistent.
  if [[ -f "$HERMES_BUILD_IOS_SIM/lib/hermesvmlean.framework/Info.plist" ]]; then
    /usr/libexec/PlistBuddy -c "Set :CFBundleExecutable hermesvm" "$HERMES_BUILD_IOS_SIM/lib/hermesvmlean.framework/Info.plist" 2>/dev/null || true
  fi
  cp -R "$HERMES_BUILD_IOS_SIM/lib/hermesvmlean.framework" "$HERMES_IOS_SIM/hermesvm.framework"
  # Fix the install_name in the staged binary too.
  if [[ -f "$HERMES_IOS_SIM/hermesvm.framework/hermesvm" ]]; then
    install_name_tool -id "@rpath/hermesvm.framework/hermesvm" "$HERMES_IOS_SIM/hermesvm.framework/hermesvm"
  fi
  echo "    -> $HERMES_IOS_SIM/hermesvm.framework (iOS Simulator, lean)"
fi

###############################################################################
# Build our iOS glue framework (hermes-core.cpp + hermes-ios.c + Hermes)
# via hermes-jni-build for iOS device (arm64). This produces a dylib that
# we convert to a framework.
###############################################################################
echo ""
echo "==> Building iOS device glue framework (arm64, lean) ..."
IOS_DEVICE_BUILD="$JNI_BUILD_SRC/build_ios_device"
IOS_DEVICE_DYLIB="$IOS_DEVICE_BUILD/_hermes/lib/hermesvm.framework/hermesvm"

cmake -S "$JNI_BUILD_SRC" -B "$IOS_DEVICE_BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DCMAKE_SYSTEM_NAME=iOS \
  -DCMAKE_OSX_SYSROOT=/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk \
  -DCMAKE_OSX_DEPLOYMENT_TARGET=14.0 \
  -DCMAKE_OSX_ARCHITECTURES=arm64 \
  -DHERMESVM_LEAN=ON \
  -DHERMES_SRC="$HERMES_SRC" \
  -DIMPORT_HOST_COMPILERS="$HERMES_BUILD_HOST/ImportHostCompilers.cmake"
cmake --build "$IOS_DEVICE_BUILD" --target hermesvmlean -j "$JOBS"

# With lean enabled the framework bundle is named hermesvmlean.framework
# (rename later in the script). Adjust the verification path accordingly.
if [[ -f "$IOS_DEVICE_BUILD/_hermes/lib/hermesvmlean.framework/hermesvmlean" ]]; then
  IOS_DEVICE_DYLIB="$IOS_DEVICE_BUILD/_hermes/lib/hermesvmlean.framework/hermesvmlean"
fi

if [[ -f "$IOS_DEVICE_DYLIB" ]]; then
  echo "    -> $IOS_DEVICE_DYLIB (iOS device lean framework binary)"
else
  echo "    ERROR: iOS device hermesvmlean not built"
  ls -la "$IOS_DEVICE_BUILD/" 2>/dev/null | head -10 || true
fi

###############################################################################
# Build our iOS glue framework (hermes-core.cpp + hermes-ios.c + Hermes)
# via hermes-jni-build for iOS simulator (x86_64 + arm64). This produces a
# dylib that we convert to a framework.
###############################################################################
echo ""
echo "==> Building iOS simulator glue framework (x86_64 + arm64, lean) ..."
IOS_SIM_GLUE_BUILD="$JNI_BUILD_SRC/build_ios_simulator_glue"
IOS_SIM_DYLIB="$IOS_SIM_GLUE_BUILD/_hermes/lib/hermesvm.framework/hermesvm"

cmake -S "$JNI_BUILD_SRC" -B "$IOS_SIM_GLUE_BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DCMAKE_SYSTEM_NAME=iOS \
  -DCMAKE_OSX_SYSROOT=/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk \
  -DCMAKE_OSX_DEPLOYMENT_TARGET=14.0 \
  -DCMAKE_OSX_ARCHITECTURES="x86_64;arm64" \
  -DHERMESVM_LEAN=ON \
  -DHERMES_SRC="$HERMES_SRC" \
  -DIMPORT_HOST_COMPILERS="$HERMES_BUILD_HOST/ImportHostCompilers.cmake" \
  >/dev/null
cmake --build "$IOS_SIM_GLUE_BUILD" --target hermesvmlean -j "$JOBS" >/dev/null

# With lean enabled the framework bundle is named hermesvmlean.framework
# (rename later in the script). Adjust the verification path accordingly.
if [[ -f "$IOS_SIM_GLUE_BUILD/_hermes/lib/hermesvmlean.framework/hermesvmlean" ]]; then
  IOS_SIM_DYLIB="$IOS_SIM_GLUE_BUILD/_hermes/lib/hermesvmlean.framework/hermesvmlean"
fi

if [[ -f "$IOS_SIM_DYLIB" ]]; then
  echo "    -> $IOS_SIM_DYLIB (iOS simulator lean framework binary)"
else
  echo "    ERROR: iOS simulator hermesvmlean not built"
  ls -la "$IOS_SIM_GLUE_BUILD/" 2>/dev/null | head -10 || true
fi

###############################################################################
# Rename hermesvmlean.framework to hermesvm.framework so consumers (which
# reference `-framework hermesvm`) keep working without changes. Also rename
# the inner binary, update Info.plist's CFBundleExecutable, and fix the
# Mach-O install_name so dynamic linking still resolves the bundle.
###############################################################################
rename_lean_framework() {
  local src_dir="$1"
  if [[ -d "$src_dir/hermesvmlean.framework" ]]; then
    # Remove any stale hermesvm.framework from a previous full build so the
    # rename doesn't nest the new framework inside the old one.
    rm -rf "$src_dir/hermesvm.framework"
    if [[ -f "$src_dir/hermesvmlean.framework/hermesvmlean" ]]; then
      mv "$src_dir/hermesvmlean.framework/hermesvmlean" "$src_dir/hermesvmlean.framework/hermesvm"
    fi
    # Update Info.plist so CFBundleExecutable points to the renamed binary.
    if [[ -f "$src_dir/hermesvmlean.framework/Info.plist" ]]; then
      /usr/libexec/PlistBuddy -c "Set :CFBundleExecutable hermesvm" "$src_dir/hermesvmlean.framework/Info.plist" 2>/dev/null || true
    fi
    mv "$src_dir/hermesvmlean.framework" "$src_dir/hermesvm.framework"
    # Fix the install_name embedded in the binary so dependent dylibs
    # resolve @rpath/hermesvm.framework/hermesvm (not hermesvmlean).
    if [[ -f "$src_dir/hermesvm.framework/hermesvm" ]]; then
      install_name_tool -id "@rpath/hermesvm.framework/hermesvm" "$src_dir/hermesvm.framework/hermesvm"
    fi
  fi
}
rename_lean_framework "$IOS_DEVICE_BUILD/_hermes/lib"
rename_lean_framework "$IOS_SIM_GLUE_BUILD/_hermes/lib"

###############################################################################
# Build the hermesvm.xcframework from our glue frameworks.
###############################################################################
echo ""
echo "==> Building hermesvm.xcframework with OUR GLUE (device + simulator) ..."
HERMES_XCFRAMEWORK="$SCRIPT_DIR/build/hermesvm.xcframework"
rm -rf "$HERMES_XCFRAMEWORK"
IOS_DEVICE_FMWK="$JNI_BUILD_SRC/build_ios_device/_hermes/lib/hermesvm.framework"
IOS_SIM_FMWK="$JNI_BUILD_SRC/build_ios_simulator_glue/_hermes/lib/hermesvm.framework"

add_hermes_headers() {
  local framework_path="$1"
  mkdir -p "$framework_path/Headers"
  cp "$SCRIPT_DIR/native/hermes-ios/hermes-ios.h" "$framework_path/Headers/"
  cp "$SCRIPT_DIR/native/hermes-core.h" "$framework_path/Headers/"
}

if [[ -f "$IOS_DEVICE_FMWK/hermesvm" && -f "$IOS_SIM_FMWK/hermesvm" ]]; then
  add_hermes_headers "$IOS_DEVICE_FMWK"
  add_hermes_headers "$IOS_SIM_FMWK"
  xcodebuild -create-xcframework \
    -framework "$IOS_DEVICE_FMWK" \
    -framework "$IOS_SIM_FMWK" \
    -output "$HERMES_XCFRAMEWORK" 2>&1 | tail -3
  echo "    -> $HERMES_XCFRAMEWORK"
else
  echo "    WARN: missing device or simulator framework"
  echo "    device: $IOS_DEVICE_FMWK"
  echo "    sim:    $IOS_SIM_FMWK"
fi

###############################################################################
# Step 4: same build with HERMES_BUILD_SHARED_JSI=OFF, so we get the static
# libjsi.a alongside libhermesvm_a.a. These two are force-loaded into
# zipline's JNI dylib (see native/hermes/build_jni_dylib.sh).
###############################################################################
echo ""
echo "==> [4/4] Building macOS static libs (x86_64 + arm64) ..."
cmake -S "$HERMES_SRC" -B "$HERMES_BUILD_STATIC" -G Ninja \
  -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DCMAKE_OSX_ARCHITECTURES="x86_64;arm64" \
  -DCMAKE_OSX_DEPLOYMENT_TARGET=10.15 \
  -DHERMES_ENABLE_DEBUGGER=OFF \
  -DHERMES_ENABLE_INTL=ON \
  -DHERMES_ENABLE_TEST_SUITE=OFF \
  -DHERMES_ENABLE_TOOLS=OFF \
  -DHERMES_BUILD_SHARED_JSI=OFF \
  -DHERMES_BUILD_APPLE_FRAMEWORK=OFF \
  -DJSI_DIR="$HERMES_SRC/API/jsi" \
  -DIMPORT_HOST_COMPILERS="$HERMES_BUILD_HOST/ImportHostCompilers.cmake"
cmake --build "$HERMES_BUILD_STATIC" --target hermesvm hermesvm jsi -j "$JOBS"

###############################################################################
# Verify outputs.
###############################################################################
echo ""
echo "==> Verifying outputs ..."
EXPECTED=(
  "$HERMES_BUILD_MACOS/lib/libhermesvm.dylib"
  "$HERMES_BUILD_MACOS/lib/libhermesvmlean.dylib"
  "$HERMES_BUILD_MACOS/jsi/libjsi.dylib"
  "$HERMES_BUILD_STATIC/lib/libhermesvm_a.a"
  "$HERMES_BUILD_STATIC/jsi/libjsi.a"
)
MISSING=0
for f in "${EXPECTED[@]}"; do
  if [[ -f "$f" ]]; then
    ARCHS="$(lipo -archs "$f" 2>/dev/null || ar -t "$f" 2>/dev/null | head -1 || echo '<unknown>')"
    SIZE="$(du -h "$f" | awk '{print $1}')"
    echo "  OK  $f  ($SIZE, archs: $ARCHS)"
  else
    echo "  MISSING  $f"
    MISSING=$((MISSING + 1))
  fi
done

if [[ "$MISSING" -gt 0 ]]; then
  echo ""
  echo "ERROR: $MISSING expected artifact(s) missing." >&2
  exit 1
fi

echo ""
echo "==> Done. Artifacts:"
echo "    hermesc:                $HERMES_BUILD_HOST/bin/hermesc"
echo "    shermes:                $HERMES_BUILD_HOST/bin/shermes"
echo "    libhermesvm.dylib:      $HERMES_BUILD_MACOS/lib/libhermesvm.dylib"
echo "    libhermesvmlean.dylib:  $HERMES_BUILD_MACOS/lib/libhermesvmlean.dylib"
echo "    libjsi.dylib:           $HERMES_BUILD_MACOS/jsi/libjsi.dylib"
echo "    libhermesvm_a.a:        $HERMES_BUILD_STATIC/lib/libhermesvm_a.a"
echo "    libjsi.a:               $HERMES_BUILD_STATIC/jsi/libjsi.a"
echo ""
echo "==> Building the JNI dylib (glue + Hermes + JSI statically linked) ..."

# Static libs are fat archives, so we build once per arch then lipo the results.
# Full build (with compileJS) for host JVM
GLUE_BUILD_X64="$JNI_BUILD_SRC/build_x86_64"
GLUE_BUILD_ARM="$JNI_BUILD_SRC/build_arm64"

for cfg in "x86_64;$GLUE_BUILD_X64" "arm64;$GLUE_BUILD_ARM"; do
  arch="${cfg%%;*}"
  dir="${cfg##*;}"
  cmake -S "$JNI_BUILD_SRC" -B "$dir" -G Ninja \
    -DCMAKE_BUILD_TYPE=MinSizeRel \
    -DCMAKE_OSX_ARCHITECTURES="$arch" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=10.15 \
    -DHERMESVM_LEAN=OFF \
    -DHERMES_SRC="$HERMES_SRC" \
    -DHERMES_STATIC_BUILD_DIR="$HERMES_BUILD_STATIC" \
    -DJAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}"
  cmake --build "$dir" --target hermesvm_jni -j "$JOBS"
done

GLUE_BUILD="$JNI_BUILD_SRC/build"
mkdir -p "$GLUE_BUILD"
lipo -create \
  "$GLUE_BUILD_X64/libhermesvm.dylib" \
  "$GLUE_BUILD_ARM/libhermesvm.dylib" \
  -output "$GLUE_BUILD/libhermesvm.dylib"

echo ""
echo "==> Packaging FULL libs into src/jvmMain/resources/jni/ (for host JVM) ..."
PREFIX="${PREFIX:-$SCRIPT_DIR/src/jvmMain/resources/jni}"
JNI_DYLIB="$GLUE_BUILD/libhermesvm.dylib"
if [[ ! -f "$JNI_DYLIB" ]]; then
  echo "ERROR: $JNI_DYLIB not produced by the JNI dylib build" >&2
  exit 1
fi
for pair in arm64:aarch64 x86_64:x86_64; do
  lipo_arch="${pair%%:*}"
  dir_name="${pair##*:}"
  mkdir -p "$PREFIX/$dir_name"
  lipo -thin "$lipo_arch" "$JNI_DYLIB" -output "$PREFIX/$dir_name/libhermesvm.dylib"
done
echo "    -> $PREFIX/{aarch64,x86_64}/libhermesvm.dylib (FULL - for host)"

###############################################################################
# Cross-build the Linux HOST libhermesvm.so used by Kotlin/Native cinterop on
# Linux (see src/nativeInterop/cinterop/hermes.def). Built on macOS so the
# resulting .so can be packaged for downstream Linux consumers alongside the
# macOS artifacts.
#
# This produces a non-lean libhermesvm.so (i.e. compile() works) so
# Kotlin/Native consumers on Linux can compile JS to bytecode on-device.
#
# Requires a Linux cross-toolchain. The host-build.sh script checks for
# x86_64-linux-gnu-gcc or a sysroot under /opt/homebrew/Cellar/. If neither
# is available, this step is skipped.
###############################################################################
echo ""
echo "==> Cross-building Linux HOST libhermesvm.so (non-lean, x86_64) ..."
LINUX_TOOLCHAIN="$JNI_BUILD_SRC/x86_64-linux-gnu-cross.cmake"
LINUX_BUILD_STATIC="$HERMES_SRC/build_linux_static"
LINUX_BUILD_GLUE="$JNI_BUILD_SRC/build_linux_x86_64_host"
LINUX_HOST_SO="$LINUX_BUILD_GLUE/libhermesvm.so"

LINUX_CROSS_TOOLCHAIN_AVAILABLE=0
if command -v x86_64-linux-gnu-gcc >/dev/null 2>&1; then
  LINUX_CROSS_TOOLCHAIN_AVAILABLE=1
elif command -v x86_64-unknown-linux-gnu-gcc >/dev/null 2>&1; then
  LINUX_CROSS_TOOLCHAIN_AVAILABLE=1
elif [[ -d "/opt/homebrew/Cellar/x86_64-unknown-linux-gnu" ]] || [[ -n "${LINUX_SYSROOT:-}" ]]; then
  LINUX_CROSS_TOOLCHAIN_AVAILABLE=1
elif clang --print-targets 2>/dev/null | grep -q "x86_64-linux"; then
  # Apple clang can target Linux directly when paired with a Linux sysroot
  # containing glibc + the C++ runtime. Without one the link step fails,
  # so this is a best-effort probe; we still try the cross build below.
  LINUX_CROSS_TOOLCHAIN_AVAILABLE=1
fi

if [[ "$LINUX_CROSS_TOOLCHAIN_AVAILABLE" == "1" ]]; then
  if [[ ! -f "$LINUX_TOOLCHAIN" ]]; then
    echo "    ERROR: toolchain file $LINUX_TOOLCHAIN not found" >&2
  else
    cmake -S "$HERMES_SRC" -B "$LINUX_BUILD_STATIC" -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE="$LINUX_TOOLCHAIN" \
      -DCMAKE_BUILD_TYPE=MinSizeRel \
      -DHERMES_ENABLE_DEBUGGER=OFF \
      -DHERMES_ENABLE_INTL=FALSE \
      -DHERMES_UNICODE_LITE=TRUE \
      -DHERMES_ENABLE_TEST_SUITE=OFF \
      -DHERMES_ENABLE_TOOLS=OFF \
      -DHERMES_BUILD_SHARED_JSI=OFF \
      -DHERMES_BUILD_APPLE_FRAMEWORK=OFF \
      -DJSI_DIR="$HERMES_SRC/API/jsi" \
      -DIMPORT_HOST_COMPILERS="$HERMES_BUILD_HOST/ImportHostCompilers.cmake"
    cmake --build "$LINUX_BUILD_STATIC" --target hermesvm hermesvmlean jsi -j "$JOBS" || \
      echo "    WARN: Linux static-lib build did not succeed (missing cross-toolchain?)"

    if [[ -f "$LINUX_BUILD_STATIC/lib/libhermesvm_a.a" ]]; then
      cmake -S "$JNI_BUILD_SRC" -B "$LINUX_BUILD_GLUE" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$LINUX_TOOLCHAIN" \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DHERMESVM_LEAN=OFF \
        -DHERMES_SRC="$HERMES_SRC" \
        -DHERMES_STATIC_BUILD_DIR="$LINUX_BUILD_STATIC" \
        -DJAVA_HOME="${JAVA_HOME:-}"
      cmake --build "$LINUX_BUILD_GLUE" --target hermesvm_jni -j "$JOBS" || \
        echo "    WARN: Linux host dylib build did not succeed"
    fi

    if [[ -f "$LINUX_HOST_SO" ]]; then
      file "$LINUX_HOST_SO" || true
      echo "    -> $LINUX_HOST_SO"
      # Stage next to the macOS artifacts under build/hermes-ios so the
      # Kotlin/Native cinterop directory layout is uniform across platforms.
      CINTEROP_DIR="$SCRIPT_DIR/build/hermes-ios"
      mkdir -p "$CINTEROP_DIR"
      cp "$LINUX_HOST_SO" "$CINTEROP_DIR/libhermesvm.so"
      echo "    -> $CINTEROP_DIR/libhermesvm.so (Linux HOST, non-lean)"
      # Also stage alongside the macOS dylib under jvmMain/resources/jni/amd64
      # so the JVM JAR on Linux can load /jni/amd64/libhermesvm.so from its
      # resources (see JsNativeLoader.kt). Linux os.arch is 'amd64' (not 'x86_64').
      mkdir -p "$PREFIX/amd64"
      cp "$LINUX_HOST_SO" "$PREFIX/amd64/libhermesvm.so"
      echo "    -> $PREFIX/amd64/libhermesvm.so (Linux HOST, for JAR)"
    else
      echo "    ERROR: Linux host libhermesvm.so not built"
      ls -la "$LINUX_BUILD_GLUE/" 2>/dev/null | head -10 || true
    fi
  fi
else
  echo "    skipped (no Linux cross-toolchain found; install x86_64-linux-gnu-gcc or set LINUX_SYSROOT)"
fi

echo ""
echo "==> Building LEAN libs for Android ..."
# Lean build (without compileJS) for Android
GLUE_BUILD_X64LEAN="$JNI_BUILD_SRC/build_x86_64_lean"
GLUE_BUILD_ARLEAN="$JNI_BUILD_SRC/build_arm64_lean"

for cfg in "x86_64;$GLUE_BUILD_X64LEAN" "arm64;$GLUE_BUILD_ARLEAN"; do
  arch="${cfg%%;*}"
  dir="${cfg##*;}"
  cmake -S "$JNI_BUILD_SRC" -B "$dir" -G Ninja \
    -DCMAKE_BUILD_TYPE=MinSizeRel \
    -DCMAKE_OSX_ARCHITECTURES="$arch" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=10.15 \
    -DHERMESVM_LEAN=ON \
    -DHERMES_SRC="$HERMES_SRC" \
    -DHERMES_STATIC_BUILD_DIR="$HERMES_BUILD_STATIC" \
    -DJAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}" \
    >/dev/null
  cmake --build "$dir" --target hermesvm_jni -j "$JOBS" >/dev/null
done

# Create fat dylib for lean too
GLUE_BUILD_LEAN="$JNI_BUILD_SRC/build_lean"
mkdir -p "$GLUE_BUILD_LEAN"
lipo -create \
  "$GLUE_BUILD_X64LEAN/libhermesvm.dylib" \
  "$GLUE_BUILD_ARLEAN/libhermesvm.dylib" \
  -output "$GLUE_BUILD_LEAN/libhermesvmlean.dylib"

echo ""
echo "==> Packaging LEAN libs into src/androidMain/resources/jniLibs/ (for Android) ..."
ANDROID_PREFIX="$SCRIPT_DIR/src/androidMain/resources/jniLibs"
for pair in arm64:arm64-v8a x86_64:x86_64; do
  lipo_arch="${pair%%:*}"
  abi="${pair##*:}"
  mkdir -p "$ANDROID_PREFIX/$abi"
  lipo -thin "$lipo_arch" "$GLUE_BUILD_LEAN/libhermesvmlean.dylib" \
    -output "$ANDROID_PREFIX/$abi/libhermesvmlean.so"
done
echo "    -> $ANDROID_PREFIX/{arm64-v8a,x86_64}/libhermesvmlean.so (LEAN - for Android)"

echo ""
echo "==> Copying libs and headers for Kotlin/Native cinterop ..."
CINTEROP_DIR="$SCRIPT_DIR/build/hermes-ios"
mkdir -p "$CINTEROP_DIR"
cp "$GLUE_BUILD/libhermesvm.dylib" "$CINTEROP_DIR/"
cp "$HERMES_IOS_SIM/libhermesvm-ios-simulator.dylib" "$CINTEROP_DIR/" 2>/dev/null || true
cp "$SCRIPT_DIR/native/hermes-ios/hermes-ios.h" "$CINTEROP_DIR/"
cp "$SCRIPT_DIR/native/hermes-core.h" "$CINTEROP_DIR/"
echo "    -> $CINTEROP_DIR/libhermesvm.dylib (macOS universal)"
echo "    -> $CINTEROP_DIR/libhermesvm-ios-simulator.dylib (iOS Simulator universal)"
echo "    -> $CINTEROP_DIR/hermes-ios.h"
echo "    -> $CINTEROP_DIR/hermes-core.h"
