#!/usr/bin/env bash
# Quick check that your phone is connected and ready for tests.
#   ./check-device.sh
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"

echo "Looking for a connected device..."
"$ADB" devices -l

state=$("$ADB" get-state 2>/dev/null || true)
if [ "$state" = "device" ]; then
  model=$("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  rel=$("$ADB" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
  echo
  echo "READY: $model (Android $rel) is connected and authorized."
  echo "You can now run:  ./gradlew connectedAndroidTest"
else
  echo
  echo "NOT READY. Check the list above:"
  echo "  - empty list      -> phone not plugged in, or USB debugging is off, or run setup-udev.sh"
  echo "  - 'unauthorized'  -> tap 'Allow USB debugging' on the phone screen"
  echo "  - 'no permissions'-> run:  sudo ./setup-udev.sh   (then unplug/replug)"
fi
