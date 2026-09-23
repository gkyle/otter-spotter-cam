#!/usr/bin/env bash
# ==============================================================================
# build_bundle.sh - Generate, Sign, and Verify OtterSpotter Android App Bundle (.aab)
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/android"

BUILD_TYPE="release"
CLEAN_BUILD=false
if [ -f "$REPO_ROOT/.env" ]; then
    # shellcheck disable=SC1091
    set -a
    source "$REPO_ROOT/.env"
    set +a
fi

KEYSTORE="${KEYSTORE_PATH:-$REPO_ROOT/keystore.jks}"
KEY_ALIAS="${KEY_ALIAS:-UPLOAD_KEY}"
KEYSTORE_PASS="${KEYSTORE_PASS:-}"
KEY_PASS="${KEY_PASS:-}"

while [ $# -gt 0 ]; do
    case "$1" in
        --release)
            BUILD_TYPE="release"
            shift
            ;;
        --debug)
            BUILD_TYPE="debug"
            shift
            ;;
        --clean)
            CLEAN_BUILD=true
            shift
            ;;
        --keystore)
            KEYSTORE="$2"
            shift 2
            ;;
        --alias)
            KEY_ALIAS="$2"
            shift 2
            ;;
        --storepass)
            KEYSTORE_PASS="$2"
            shift 2
            ;;
        --keypass)
            KEY_PASS="$2"
            shift 2
            ;;
        --help|-h)
            echo "Usage: $(basename "$0") [options]"
            echo ""
            echo "Options:"
            echo "  --release           Build release App Bundle (default: release)"
            echo "  --debug             Build debug App Bundle"
            echo "  --clean             Run gradle clean before bundling"
            echo "  --keystore <path>   Path to signing keystore (default: keystore.jks)"
            echo "  --alias <alias>     Key alias (default: UPLOAD_KEY)"
            echo "  --storepass <pass>  Keystore password (prompted if omitted)"
            echo "  --keypass <pass>    Key password (defaults to storepass)"
            echo "  -h, --help          Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            echo "Run '$(basename "$0") --help' for usage." >&2
            exit 1
            ;;
    esac
done

# Locate jarsigner binary
JARSIGNER_BIN=""
if command -v jarsigner >/dev/null 2>&1; then
    JARSIGNER_BIN="jarsigner"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jarsigner" ]; then
    JARSIGNER_BIN="$JAVA_HOME/bin/jarsigner"
else
    for candidate in \
        /usr/lib/jvm/java-21-openjdk-amd64/bin/jarsigner \
        /usr/lib/jvm/java-17-openjdk-amd64/bin/jarsigner \
        /usr/lib/jvm/default-java/bin/jarsigner; do
        if [ -x "$candidate" ]; then
            JARSIGNER_BIN="$candidate"
            break
        fi
    done
fi

if [ -z "$JARSIGNER_BIN" ]; then
    echo "Error: jarsigner binary not found. Please ensure a JDK is installed." >&2
    exit 1
fi

if [ ! -f "$KEYSTORE" ]; then
    echo "Error: Keystore not found at $KEYSTORE" >&2
    exit 1
fi

# Prompt interactively for keystore password if not provided and attached to a terminal
if [ -z "$KEYSTORE_PASS" ] && [ -t 0 ]; then
    read -rsp "Enter keystore password for $KEYSTORE ($KEY_ALIAS): " KEYSTORE_PASS
    echo ""
fi

echo "==> Generating OtterSpotter Android App Bundle ($BUILD_TYPE)..."
cd "$ANDROID_DIR"

if [ "$CLEAN_BUILD" = true ]; then
    echo "==> Cleaning previous build outputs..."
    ./gradlew clean
fi

if [ "$BUILD_TYPE" = "release" ]; then
    ./gradlew bundleRelease
    BUNDLE_SRC="$ANDROID_DIR/app/build/outputs/bundle/release/app-release.aab"
else
    ./gradlew bundleDebug
    BUNDLE_SRC="$ANDROID_DIR/app/build/outputs/bundle/debug/app-debug.aab"
fi

if [ ! -f "$BUNDLE_SRC" ]; then
    echo "Error: Expected App Bundle not found at $BUNDLE_SRC" >&2
    exit 1
fi

echo "==> Signing Android App Bundle ($KEY_ALIAS)..."
SIGN_ARGS=(
    -sigalg SHA256withRSA
    -digestalg SHA-256
    -keystore "$KEYSTORE"
)

if [ -n "$KEYSTORE_PASS" ]; then
    SIGN_ARGS+=(-storepass "$KEYSTORE_PASS")
    SIGN_ARGS+=(-keypass "${KEY_PASS:-$KEYSTORE_PASS}")
    "$JARSIGNER_BIN" "${SIGN_ARGS[@]}" "$BUNDLE_SRC" "$KEY_ALIAS" >/dev/null
else
    "$JARSIGNER_BIN" "${SIGN_ARGS[@]}" "$BUNDLE_SRC" "$KEY_ALIAS"
fi

echo "==> Verifying Android App Bundle signature..."
VERIFY_OUTPUT="$("$JARSIGNER_BIN" -verify "$BUNDLE_SRC" 2>&1)"
if ! echo "$VERIFY_OUTPUT" | grep -q "jar verified"; then
    echo "Error: App Bundle signature verification failed!" >&2
    echo "$VERIFY_OUTPUT" >&2
    exit 1
fi
echo "==> Signature verified successfully."

echo "==> Updating static download distribution assets..."
mkdir -p "$REPO_ROOT/static"
cp "$BUNDLE_SRC" "$REPO_ROOT/static/otterspotter.aab"

echo ""
echo "=============================================================================="
echo " BUNDLE GENERATED, SIGNED & VERIFIED"
echo "=============================================================================="
echo " Output AAB:   $BUNDLE_SRC"
echo " Size:         $(du -h "$BUNDLE_SRC" | cut -f1)"
echo " Keystore:     $KEYSTORE"
echo " Key Alias:    $KEY_ALIAS"
echo " Verification: VALID (jar verified by jarsigner)"
echo " Static copy:  $REPO_ROOT/static/otterspotter.aab"
echo "=============================================================================="
