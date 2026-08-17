# MotionSound

An on-device AI music player, optimized for listening while riding — inspired by
Mercedes SoundDrive, but driven entirely by the phone's own sensors instead of
car data. Every song is
separated into vocals, bass, drums and melody on the phone itself — no cloud —
then the mix is reshaped by how you ride: acceleration, braking, cornering and
speed from the phone's sensors. Accelerate and the layers build; glide into a
corner and the sound opens up; stop, and it settles back into a quiet bed of bass
and drums.

**v1.0.0** · Android 14+ · 100% on-device AI · [Apache 2.0](LICENSE)

Get the APK from [Releases](https://github.com/ngocthanhgl/MotionSound/releases)
(arm64-v8a / armeabi-v7a). The AI model (~160 MB) downloads automatically on first use.

## Screenshots

<img src="docs/screenshots/img001.png" width="260" alt="Drive dashboard"/>

## Usage

1. Mount the phone securely on the handlebar — the app reads movement from its sensors. It only works when the phone is fixed on the vehicle and the vehicle is moving: shaking the phone or jogging won't trigger it.
2. Grant the requested permissions on first launch.
3. Create a playlist and add songs. Stems separate in the background and are cached, so every later play starts instantly.
4. Press play. Above 5 km/h the app switches to a fullscreen drive dashboard; tap the gauge to enter or exit it manually.
5. Sound Drive keeps working with the screen off — GPS keeps reporting speed in the background.

## Permissions

- **Music & audio** — reads your song library
- **Notifications** — media playback controls and drive status
- **Location** — GPS speed for Sound Drive; "Allow all the time" enables screen-off driving
- **Battery optimization exemption** (recommended) — prevents the OS from killing playback in the background

## Drive safely

- Never touch the app while riding. Set your playlist before you go.
- The dashboard is designed to be glanced at, not read. Keep your eyes on the road.
- The audio mix is a feature, not a safety device. Obey traffic rules and speed limits.

## Requirements

- **Android 14 or newer** (arm64 recommended)
- **Strong chipset and at least 6 GB RAM** — stem separation runs on-device and is heavy
- **High storage** — each song needs ~250 MB of cached stems (4 lossless float32 tracks); a 10-song playlist is about 2.5 GB
- Low-end devices separate much slower or may not finish at all — use short playlists and pre-separate songs in advance

## Build

```
git clone https://github.com/ngocthanhgl/MotionSound
```

Open in Android Studio (compileSdk 35, Java 17) and run on an arm64 device.
Releases are built automatically on tag push.

## Disclaimer

This app is inspired by Mercedes SoundDrive — it does not copy or infringe any
copyright. It is independent, experimental software built for fun. Use responsibly.

## License

This project is licensed under the Apache License 2.0 — see [LICENSE](LICENSE).
