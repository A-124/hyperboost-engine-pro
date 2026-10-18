# HyperBoost Engine Pro

Rootless Android performance engine: one-tap boost, live telemetry, floating FPS HUD / crosshair
and per-game profiles. Works **without root** through [Shizuku](https://shizuku.rikka.app/)
(wireless ADB) plus an accessibility service.

## Features

| Area | What it does |
|------|--------------|
| Master Turbo | Applies every safe tweak independently; one unsupported tweak never aborts the rest |
| Display | Refresh-rate lock, 720p downscale + DPI |
| GPU | Skia-Vulkan pipeline (`skiavk`), SurfaceFlinger instant latching |
| Input | Fast pointer, touch prediction, quick long-press, zero animations |
| Network | Wi-Fi low-latency mode, no Wi-Fi sleep, Private DNS |
| **Power / thermal** | Sustained-performance mode, Battery Saver + Adaptive Battery off |
| **Background** | Phantom-process shield (Android 12+), clear cached apps, game Do-Not-Disturb |
| **Storage** | On-demand TRIM + background dexopt |
| Per game | AOT speed-profile compile, Game Mode, Doze whitelist |
| **Safety** | Snapshots your original values and restores them exactly ("Restore My Original Settings") |

## Setup on the phone

1. Install the APK from the Releases page.
2. Install **Shizuku**, start it with *Wireless debugging* (Developer options), and grant HyperBoost access.
3. Open HyperBoost → tap **Master Turbo**.

> Tweaks that rely on hidden OEM/AOSP flags are best-effort and behave differently per device and
> Android version. Unsupported ones are skipped and reported.

## Build locally

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug
./gradlew assembleRelease    # signed with debug key unless release secrets are set
```
Requires JDK 17+ and the Android SDK (Android Studio sets both up).

## Release an APK (GitHub Actions)

See [RELEASING.md](RELEASING.md).
