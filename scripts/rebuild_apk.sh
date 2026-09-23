#!/usr/bin/env bash
# ==============================================================================
# rebuild_apk.sh - Rebuild Otter Spotter APK
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/android"

BUILD_TYPE="debug"
CLEAN_BUILD=false

for arg in "$@"; do
    case "$arg" in
        --release)
            BUILD_TYPE="release"
            ;;
        --clean)
            CLEAN_BUILD=true
            ;;
        --help|-h)
            echo "Usage: $(basename "$0") [--release] [--clean]"
            echo "  --release    Build release APK (default: debug)"
            echo "  --clean      Run gradle clean before assembling"
            exit 0
            ;;
        *)
            echo "Unknown argument: $arg"
            echo "Usage: $(basename "$0") [--release] [--clean]"
            exit 1
            ;;
    esac
done

echo "==> Building Otter Spotter APK ($BUILD_TYPE)..."
cd "$ANDROID_DIR"

if [ "$CLEAN_BUILD" = true ]; then
    echo "==> Cleaning previous build outputs..."
    ./gradlew clean
fi

if [ "$BUILD_TYPE" = "release" ]; then
    ./gradlew assembleRelease
    APK_SRC="$ANDROID_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
else
    ./gradlew assembleDebug
    APK_SRC="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
fi

if [ ! -f "$APK_SRC" ]; then
    echo "Error: Expected APK not found at $APK_SRC" >&2
    exit 1
fi

echo "==> Updating static download distribution assets..."
mkdir -p "$REPO_ROOT/static"
cp "$APK_SRC" "$REPO_ROOT/static/otterspotter.apk"

echo ""
echo "=============================================================================="
echo " BUILD SUCCESSFUL"
echo "=============================================================================="
echo " Output APK:   $APK_SRC"
echo " Size:         $(du -h "$APK_SRC" | cut -f1)"
echo " Static copy:  $REPO_ROOT/static/otterspotter.apk"
echo "=============================================================================="
