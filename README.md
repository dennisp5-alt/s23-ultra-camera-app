# Colour Profiles

Sets Samsung's display mode for each app on the Galaxy S23 Ultra. Lightroom starts on Basic (sRGB) and every other app on Vivid.

## Setup

1. Install and start [Shizuku](https://shizuku.rikka.app/download/).
2. Open Colour Profiles and tap **Allow** next to Shizuku.
3. Tap **Turn on** next to App detection.
4. Open **Test display modes** and tap each mode.
5. Add the Colour Profiles Quick Settings tile if you want an on/off switch.

Shizuku must be started again after a reboot unless you use a fork that does it automatically.

## How it works

- An Accessibility service reports which app is open. It does not read screen content.
- Shizuku runs a small service as the ADB shell user.
- That service writes `screen_mode_setting` and can also call Samsung's mDNIe colour service.

Modes: 0 Cinema, 1 Photo, 2 Basic, 3 Natural, 4 Vivid.

## If the screen doesn't change

Run **Run full test and copy report**. It records whether each method was accepted and what the system reported.

## Build

GitHub Actions builds the APK on every push to `main`.

A signed release needs these repo secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Without them, CI makes a debug APK.
