# HyperBoost Engine Pro

**Rootless Android performance engine** for gamers: one-tap boost, live telemetry, a floating FPS HUD, a screen-center crosshair and per-game profiles. It works **without root** through [Shizuku](https://shizuku.rikka.app/) (wireless ADB) plus an accessibility service.

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Root](https://img.shields.io/badge/Root-not%20required-00BCD4)

---

## Features

| Area | What it does |
|------|--------------|
| **Master Turbo** | Applies every safe tweak independently; one unsupported tweak never aborts the rest |
| **Display** | Refresh-rate lock, 720p downscale + DPI |
| **GPU** | Skia-Vulkan pipeline (`skiavk`), SurfaceFlinger instant latching |
| **Input** | Fast pointer, touch prediction, quick long-press, zero animations |
| **Network** | Wi-Fi low-latency mode, no Wi-Fi sleep, Private DNS ("Gaming DNS") |
| **Power / thermal** | Sustained-performance mode, Battery Saver + Adaptive Battery off |
| **Background** | Phantom-process shield (Android 12+), clear cached apps, game Do-Not-Disturb |
| **Storage** | On-demand TRIM + background dexopt |
| **Per-game profiles** | Auto-boost refresh rate, AOT speed-profile pre-compile, Game Mode, Doze whitelist, one-tap Launch |
| **Floating Telemetry HUD** | Draggable live FPS, frametime curve, temperature and memory usage |
| **Crosshair overlay** | Screen-center crosshair with custom reticle color, size and thickness |
| **Safety** | Snapshots your original values and restores them exactly with **Restore My Original Settings** |

> Tweaks that rely on hidden OEM/AOSP flags are best-effort and behave differently per device and Android version. Unsupported ones are skipped and reported.

## Requirements

- Android 8.0+
- [Shizuku](https://shizuku.rikka.app/) installed and running
- Developer options → **Wireless debugging** enabled (Android 11+)
- Permissions you will be asked for: **Display over other apps** (HUD and crosshair), **Notifications**, and **Accessibility** (Macro Service)

## Install

1. Download the latest APK from the [Releases](../../releases) page.
2. Install it (allow "Install unknown apps" for your browser or file manager if asked).
3. Install **Shizuku** and start it using *Wireless debugging*.
4. Open HyperBoost, grant Shizuku access, then tap **Master Turbo**.

The in-app **Setup Guide** walks you through the Shizuku step.

## Using the app

- **Master Turbo**: applies all tweaks at once.
- **Restore My Original Settings**: undoes every tweak back to the exact values you had before.
- **Game profiles**: switch a game on for Auto-Boost, use **Pre-compile AOT** to speed up game loading, then tap **Launch**.
- **Floating Telemetry HUD** and **Screen Center Crosshair**: grant **Overlay Perms** first, then turn the switch on. The crosshair has a customizer for color, size and thickness.
- **Macro Service**: enable it in Android's accessibility settings. The status dot shows whether it is active.

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Tweaks do nothing | Make sure Shizuku is running (restart it with Wireless debugging after every reboot) and that HyperBoost has access |
| HUD or crosshair doesn't appear | Grant **Overlay Perms** in the app |
| Macro Service shows a red dot | Enable HyperBoost under Settings → Accessibility |
| Something feels off after boosting | Tap **Restore My Original Settings** |
| "No video available" on the rewarded ad | Ad availability varies; try again later |

## Ads

The free version shows ads: a banner, an occasional interstitial, and an optional rewarded video that turns ads off for **1 hour**.

## Disclaimer

HyperBoost changes system settings through Shizuku. Results vary by device, and some tweaks may be unsupported on your ROM. Use **Restore My Original Settings** to revert. Provided as is, without warranty.