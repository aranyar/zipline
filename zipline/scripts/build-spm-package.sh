#!/bin/bash
#
# Build a Swift Package containing the hermesvm.xcframework and zip it for
# Maven distribution. This is invoked from build.gradle.kts after the iOS
# framework has been built.
#
# Output:
#   build/outputs/spm-packages/hermesvm-spm-<version>.zip
#
# The zip contains a Swift package with a single binaryTarget pointing at the
# hermesvm.xcframework. SPM consumers add it via URL+checksum in their
# Package.swift.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
XCFRAMEWORK_DIR="$ROOT_DIR/build/hermesvm.xcframework"
STAGING_DIR="$ROOT_DIR/build/hermes-spm-stage"
OUTPUT_DIR="$ROOT_DIR/build/outputs/spm-packages"
VERSION="${VERSION_NAME:-unknown}"
OUTPUT_ZIP="$OUTPUT_DIR/hermesvm-spm-${VERSION}.zip"

if [[ ! -d "$XCFRAMEWORK_DIR" ]]; then
  echo "ERROR: hermesvm.xcframework not found at $XCFRAMEWORK_DIR" >&2
  echo "       Run ./zipline/host-build.sh first" >&2
  exit 1
fi

echo "==> Building hermesvm Swift Package (version $VERSION) ..."

# Clean and create staging directory.
rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR"

# Place the xcframework at the root of the package, NOT inside Sources/.
# SPM binaryTarget's `path` will point at the xcframework directly.
cp -R "$XCFRAMEWORK_DIR" "$STAGING_DIR/hermesvm.xcframework"

# Write the Package.swift manifest. The path is just "hermesvm.xcframework"
# (relative to the package root) because we placed it at the root.
cat > "$STAGING_DIR/Package.swift" <<EOF
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "hermesvm",
    platforms: [
        .iOS(.v14)
    ],
    products: [
        .library(
            name: "hermesvm",
            targets: ["hermesvmTarget"]
        )
    ],
    targets: [
        .binaryTarget(
            name: "hermesvmTarget",
            path: "hermesvm.xcframework"
        )
    ]
)
EOF

# Zip up the SPM package (the xcframework directory + Package.swift).
mkdir -p "$OUTPUT_DIR"
rm -f "$OUTPUT_ZIP"
(cd "$STAGING_DIR" && zip -qr "$OUTPUT_ZIP" Package.swift hermesvm.xcframework)

# Print the SHA256 for reference (useful for SPM URL+checksum consumers).
SHA256=$(shasum -a 256 "$OUTPUT_ZIP" | awk '{print $1}')
echo "    -> $OUTPUT_ZIP"
echo "    -> SHA256: $SHA256"

# Clean up the staging directory.
rm -rf "$STAGING_DIR"
