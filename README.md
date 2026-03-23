# AirPods Pro 2 Controller for Android

A real Android app that brings AirPods Pro 2 features to Android using BLE passive scanning and iAP2 protocol reverse engineering.

## Features
- 🔋 Battery status (Left, Right, Case) via BLE advertisement parsing
- 👂 Ear detection — pause audio when buds removed
- 🔌 Auto-disconnect Bluetooth when both buds taken out
- 🎧 ANC control (Off / Noise Cancel / Transparency / Adaptive) via iAP2 packets
- 📍 Find My Buds — last known GPS location + alarm sound
- 🖐 Gesture customization

## How to build
Push this repo to GitHub. The GitHub Actions workflow will automatically build and upload the APK.
Go to Actions tab → download artifact → install on your Android phone.

## Technical approach
- BLE passive scanning reads Apple manufacturer data (company ID 0x004C) from AirPods advertisements
- iAP2/AACP packet spoofing via RFCOMM BluetoothSocket impersonates an Apple device to send ANC commands
- Foreground service keeps BLE scanner alive in background
