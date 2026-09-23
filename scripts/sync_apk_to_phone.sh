#!/usr/bin/env bash
# ==============================================================================
# sync_apk_to_phone.sh - Install and sync Otter Spotter APK to connected phone
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/android"

PACKAGE_NAME="com.kylescholz.otterspotter"
LAUNCH_AFTER=true
DEVICE_SERIAL="${ANDROID_SERIAL:-}"
BUILD_IF_MISSING=true
APK_PATH=""

# Parse flags
while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--serial)
            DEVICE_SERIAL="$2"
            shift 2
            ;;
        --no-launch)
            LAUNCH_AFTER=false
            shift
            ;;
        --no-build)
            BUILD_IF_MISSING=false
            shift
            ;;
        --apk)
            APK_PATH="$2"
            shift 2
            ;;
        --help|-h)
            echo "Usage: $(basename "$0") [-s <serial>] [--no-launch] [--no-build] [--apk <path>]"
            echo "  -s, --serial <id>   Target specific ADB device serial"
            echo "  --no-launch         Do not automatically launch app after install"
            echo "  --no-build          Do not automatically build APK if missing"
            echo "  --apk <path>        Path to custom APK file to install"
            exit 0
            ;;
        *)
            echo "Unknown argument: $1"
            echo "Usage: $(basename "$0") [-s <serial>] [--no-launch] [--no-build] [--apk <path>]"
            exit 1
            ;;
    esac
done

# Check ADB
if ! command -v adb &>/dev/null; then
    echo "Error: adb command not found. Please ensure Android SDK platform-tools are in PATH." >&2
    exit 1
fi

# Detect device if not explicitly provided
if [ -z "$DEVICE_SERIAL" ]; then
    mapfile -t DEVICES < <(adb devices | awk '$2=="device" {print $1}')
    DEVICE_COUNT=${#DEVICES[@]}

    if [ "$DEVICE_COUNT" -eq 0 ]; then
        echo "Error: No connected Android devices detected via adb." >&2
        echo "Please connect a device via USB/Wi-Fi and ensure USB debugging is enabled." >&2
        exit 1
    elif [ "$DEVICE_COUNT" -eq 1 ]; then
        DEVICE_SERIAL="${DEVICES[0]}"
    else
        # Multiple devices attached: prioritize physical hardware (exclude emulator-*)
        PHYSICAL_DEVICES=()
        for d in "${DEVICES[@]}"; do
            if [[ ! "$d" =~ ^emulator- ]]; then
                PHYSICAL_DEVICES+=("$d")
            fi
        done

        if [ ${#PHYSICAL_DEVICES[@]} -eq 1 ]; then
            DEVICE_SERIAL="${PHYSICAL_DEVICES[0]}"
            echo "==> Multiple devices attached; auto-targeting physical device: $DEVICE_SERIAL"
        elif [ ${#PHYSICAL_DEVICES[@]} -gt 1 ]; then
            DEVICE_SERIAL="${PHYSICAL_DEVICES[0]}"
            echo "==> Multiple physical devices attached; targeting first: $DEVICE_SERIAL"
        else
            DEVICE_SERIAL="${DEVICES[0]}"
            echo "==> Auto-targeting device: $DEVICE_SERIAL"
        fi
    fi
fi

ADB_CMD=("adb" "-s" "$DEVICE_SERIAL")

# Locate APK
if [ -z "$APK_PATH" ]; then
    DEBUG_APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
    RELEASE_APK="$ANDROID_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"

    if [ -f "$DEBUG_APK" ]; then
        APK_PATH="$DEBUG_APK"
    elif [ -f "$RELEASE_APK" ]; then
        APK_PATH="$RELEASE_APK"
    elif [ "$BUILD_IF_MISSING" = true ]; then
        echo "==> No existing APK found. Building debug APK first..."
        "$SCRIPT_DIR/rebuild_apk.sh"
        APK_PATH="$DEBUG_APK"
    else
        echo "Error: No APK found. Run ./scripts/rebuild_apk.sh first." >&2
        exit 1
    fi
fi

if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK file not found at $APK_PATH" >&2
    exit 1
fi

DEVICE_MODEL="$("${ADB_CMD[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "$DEVICE_SERIAL")"
echo "==> Target Device: $DEVICE_MODEL ($DEVICE_SERIAL)"
echo "==> Installing $(basename "$APK_PATH") ($(du -h "$APK_PATH" | cut -f1))..."

"${ADB_CMD[@]}" install -r "$APK_PATH"

echo "==> APK successfully synced to $DEVICE_MODEL!"

if [ "$LAUNCH_AFTER" = true ]; then
    echo "==> Starting Otter Spotter ($PACKAGE_NAME)..."
    "${ADB_CMD[@]}" shell am start -n "$PACKAGE_NAME/.MainActivity" >/dev/null 2>&1 || \
    "${ADB_CMD[@]}" shell am start -n "$PACKAGE_NAME/.SignInActivity" >/dev/null 2>&1 || true
    echo "==> App is running on $DEVICE_MODEL"
fi
