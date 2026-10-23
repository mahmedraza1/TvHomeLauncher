# TvHomeLauncher

A small, fast home-screen launcher for Android TV boxes, written in Kotlin with Jetpack Compose.
It replaces the stock launcher with a clean screen: a big clock, local weather, a live memory
readout, four large tiles and an "All apps" drawer. It is intentionally light: no recommendation
rows, no background services.

> Built and tested on a Google "AX-550" Android TV box (Allwinner SoC, Android 15, 1280x720).
> It uses only standard Android APIs, but other devices have not been tested.

## Features

- **Clock and date** at the top left, updated every minute.
- **Weather** for a city of your choice, from [Open-Meteo](https://open-meteo.com) (no API key).
- **RAM and system load** pill, refreshed every few seconds.
- **Four rectangular tiles:** YouTube, Play Store, Chrome and **Apps**.
- **Apps drawer:** a full-screen grid of every launchable app. Press **Back** to close it.
- **D-pad friendly:** focus glow and scale animation, focus returns to the Apps tile when the
  drawer closes.
- **Wallpaper** behind a soft scrim so text stays readable.

## Requirements

- JDK 17
- Android SDK with platform 36 and build-tools (set `sdk.dir` in `local.properties`, or let
  Android Studio create it)
- An Android TV / Google TV device on Android 8.0 (API 26) or newer, with ADB enabled

## Build

```bash
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install and set as the home app

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk

# make it the default Home screen
adb shell cmd package set-home-activity com.mark.tvhome/.MainActivity
```

To go back to the stock launcher:

```bash
adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
```

(The stock launcher's package name differs between devices; use `adb shell pm list packages
launcher` to find yours.)

## Customise

| What | Where |
| --- | --- |
| Weather city | `WEATHER_CITY` in `app/src/main/java/com/mark/tvhome/MainActivity.kt` |
| Pinned tiles (order and apps) | `Pinned` list in the same file (package names) |
| Wallpaper | Put a 1280x720 `wallpaper.jpg` in `app/src/main/res/drawable-nodpi/`. It is git-ignored, so your personal photo is never committed. Without it the app uses the gradient in `res/drawable/wallpaper.xml`. |
| Colours and card sizes | `Card` and `HomeScreen` composables in `MainActivity.kt` |

## Notes and limitations

- The system-load figure reads `/proc/loadavg`. Some firmware builds block that for normal apps, in
  which case the load part of the pill is simply left out.
- Weather needs internet access; if it cannot load, the line shows "Weather unavailable".
- The launcher lists apps through `PackageManager`, so it only shows apps that declare a launcher or
  leanback-launcher activity.

## Project layout

```
app/src/main/
  AndroidManifest.xml            HOME intent filter and permissions
  java/com/mark/tvhome/          MainActivity.kt: all UI and logic
  res/drawable/wallpaper.xml     default gradient wallpaper
```

## Related

[TvRemoteApp](https://github.com/mahmedraza1/TvRemoteApp) is a companion phone app that controls an
Android TV over the local network.
