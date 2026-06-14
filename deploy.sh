#!/usr/bin/env bash
set -euo pipefail

echo "🚀 Starting Android App Deployment..."

# 0. Refresh the BUNDLED adult-domain blocklist (NEW STEP) — runs on THIS machine.
ASSET_DIR="app/src/main/assets/blocklist"
ASSET="$ASSET_DIR/adult_hosts.txt.gz"
echo "🧱 Refreshing bundled blocklist..."
if command -v python3 &>/dev/null; then
    if python3 tools/build_adult_blocklist.py --gz-only --out "$ASSET_DIR"; then
        echo "✅ Blocklist refreshed: $ASSET ($(wc -c < "$ASSET") bytes)"
    elif [[ -f "$ASSET" ]]; then
        echo "⚠️  Refresh failed (offline?). Building with the existing $ASSET."
    else
        echo "❌ Refresh failed and no existing blocklist asset. Aborting."; exit 1
    fi
else
    [[ -f "$ASSET" ]] && echo "⚠️  python3 missing; using existing $ASSET." \
        || { echo "❌ python3 missing and no asset."; exit 1; }
fi


# 1. Verify adb is available
if ! command -v adb &>/dev/null; then
    echo "❌ Error: 'adb' not found in PATH."
    echo "   Install it: sudo apt install android-sdk-platform-tools"
    exit 1
fi

# 2. Check for connected & authorized device
echo "📱 Checking for connected devices..."
DEVICE_COUNT=$(adb devices | tail -n +2 | grep -c "device$" || true)
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
    echo "❌ Error: No Android device connected or authorized."
    echo "   Ensure USB debugging is enabled, cable is connected, and you've allowed the PC prompt on the phone."
    exit 1
fi

# 3. Build the debug APK
echo "🔨 Building debug APK..."
./gradlew assembleDebug

# 4. Locate the output APK
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK_PATH" ]]; then
    echo "❌ Error: APK not found at $APK_PATH"
    exit 1
fi
echo "✅ APK built: $APK_PATH"

# 5. Install/Update on device
echo "📦 Installing APK on device (replacing existing debug version)..."
adb install -r "$APK_PATH"

echo "🎉 Deployment complete! App is now running on your device."

