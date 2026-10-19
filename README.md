# HyperBoost Engine Pro

> **Proprietary software — All Rights Reserved.** This repository is public for viewing only. No open-source license is granted. Reuse, modification, redistribution, or commercial use of the original project requires prior written permission from A-124. Third-party components retain their own licenses.

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
| **Live telemetry** | Real FPS / frametime / 1% low / jank of the app in front (SurfaceFlinger present times, gfxinfo fallback), CPU load, CPU/GPU/battery temps, real power draw. Never simulated: unmeasurable values show `--` with the reason |
| **Safety** | Snapshots your original values and restores them exactly ("Restore My Original Settings") |

## How the numbers are measured

* **FPS / frametime / jank** - Shizuku runs `dumpsys SurfaceFlinger --latency <layer>` for the layer of the
  foreground app (SurfaceView layers first, which is where games draw) and reads the real *present* timestamps.
  Apps that draw through HWUI fall back to `dumpsys gfxinfo <pkg> framestats` completion times.
  Jank is relative to the app's own cadence, so a locked 30 FPS game on a 120 Hz panel is 0% jank.
* **1% low** - 99th-percentile frame interval over the last ~10 s.
* **CPU / GPU temp, CPU load** - `/sys/class/thermal` and `/proc/stat` through Shizuku.
* **Power** - `BATTERY_PROPERTY_CURRENT_NOW` x voltage, smoothed, with unit auto-detection (uA vs mA).
* FPS is measured for the app in front, so it shows `--` on the HyperBoost screen itself; use the floating HUD in-game.

## Setup on the phone

1. Install the APK from the Releases page.
2. Install **Shizuku**, start it with *Wireless debugging* (Developer options), and grant HyperBoost access.
3. Open HyperBoost → tap **Master Turbo**.

> Tweaks that rely on hidden OEM/AOSP flags are best-effort and behave differently per device and
> Android version. Unsupported ones are skipped and reported.

## Local build information

Build instructions are not provided as a grant of permission to reuse or redistribute the source. The project is proprietary and all rights are reserved. Any use beyond viewing the public repository requires prior written permission from A-124.

## Release an APK (GitHub Actions)

See [RELEASING.md](RELEASING.md).
