#!/usr/bin/env bash
# Run ONCE with sudo to let your normal user talk to an Android phone over USB.
#   sudo ./setup-udev.sh
# This installs Android's USB device rules and reloads udev. Safe to re-run.
set -e

if [ "$(id -u)" -ne 0 ]; then
  echo "Please run with sudo:  sudo ./setup-udev.sh"
  exit 1
fi

echo "Installing Android USB device rules..."
apt-get update -qq
apt-get install -y android-sdk-platform-tools-common

echo "Reloading udev..."
udevadm control --reload-rules
udevadm trigger

echo
echo "Done. Now UNPLUG and RE-PLUG your phone, then accept the"
echo "'Allow USB debugging?' prompt on the phone screen."
