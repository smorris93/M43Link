# M43Link

An Android app to **remote-control and transfer photos from Panasonic Lumix and OM System
(Olympus) Micro Four Thirds cameras** over the camera's own Wi-Fi hotspot.

## Why

The official Panasonic app (Image App / LUMIX Sync) connects unreliably: modern Android detects the
camera's hotspot has no internet, reroutes app traffic over cellular, and tears the Wi-Fi link down.
LumiLink fixes exactly this — it uses a `WifiNetworkSpecifier` with the internet capability removed
and **binds every socket to the camera network**, so the connection holds.

## Features

- **Connect** — reliable, self-healing connection to the camera hotspot (the whole point).
- **Photos** — browse all photos on the camera (DLNA), sort, full-image preview, multi-select, and
  download JPG/RAW to the phone.
- **Control** — remote shutter, video record start/stop, and a live camera status readout.
- **Live view** — real-time MJPEG viewfinder with **tap-to-focus** and a live **ISO** read-out.

## Requirements

- An Android phone running **Android 10 (API 29) or newer** (developed on a Xiaomi 14T Pro).
- A Panasonic Lumix (verified on the GX80) or an OM System camera (verified on the PEN,
  Wi-Fi set to Private mode). The protocols are per-brand, so most bodies from either maker
  should work; model-specific quirks live in the camera backends.
- A build machine with the toolchain below (no Android Studio required — CLI builds).

## Tech stack

Kotlin · Jetpack Compose · Material 3 · single-module (`:app`) · manual dependency injection ·
OkHttp (HTTP control + DLNA + downloads) · Coil (thumbnails) · min SDK 29 / target SDK 34.

## Build & install

**Toolchain:** Temurin **JDK 17**, Android **cmdline-tools** SDK (platform-tools, android-34,
build-tools 34), Gradle **8.9** (via the included wrapper), AGP 8.5.2, Kotlin 2.0.20.

```bash
# Set up (once): point JAVA_HOME at a JDK 17 and ANDROID_HOME at your SDK.
export JAVA_HOME=/path/to/temurin-17
export ANDROID_HOME=/path/to/android-sdk

# Build the debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug

# Or build and install straight to a connected device (adb):
./gradlew installDebug

# Run the unit tests (pure-JVM parser tests):
./gradlew testDebugUnitTest
```

## Using it

1. On the camera: **Wi-Fi → New Connection → Remote Shooting & View**.
2. In LumiLink's **Connect** tab, enter that hotspot's Wi-Fi name (leave the password blank if the
   camera doesn't show one) and tap **Connect**. Accept the one-time Android Wi-Fi prompt.
3. Accept the connection on the camera screen the first time it asks.
4. Use the **Control** and **Photos** tabs.

## Status & notes

Working end-to-end on real hardware (GX80 + Xiaomi 14T Pro). The camera's HTTP/UDP protocol is
unofficial (reverse-engineered), so it's specific to this camera family. In Manual mode the GX80
doesn't expose aperture/shutter over Wi-Fi, so only ISO is shown.

Full design notes, protocol details, and on-device findings live in
[`docs/PROJECT_CONTEXT.md`](docs/PROJECT_CONTEXT.md).
