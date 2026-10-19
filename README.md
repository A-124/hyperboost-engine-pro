# ⚡ HyperBoost Engine Pro

<p align="center">
  <strong>A rootless Android gaming performance toolkit.</strong><br>
  Tune supported system settings, monitor real-time performance, and manage per-game optimizations — without rooting your phone.
</p>

<p align="center">
  <a href="https://hyperboost-engine-pro.vercel.app"><strong>🌐 Official Website</strong></a>
  ·
  <a href="https://github.com/A-124/hyperboost-engine-pro/releases/latest"><strong>⬇️ Download APK</strong></a>
  ·
  <a href="https://shizuku.rikka.app/"><strong>🛠 Shizuku</strong></a>
</p>

---

## 🎮 About

HyperBoost Engine Pro is an Android performance utility designed to make supported gaming and system tweaks easier to manage. It uses **Shizuku** to access supported ADB-level operations without requiring root access, alongside an accessibility service for supported features.

> **Important:** Results depend on your phone manufacturer, Android version, game, and available system permissions. HyperBoost cannot guarantee higher FPS on every device, and unsupported tweaks may be skipped.

## ✨ Highlights

| Feature | What it offers |
| --- | --- |
| 🚀 **Master Turbo** | Applies supported optimizations independently, so one unavailable tweak does not stop the rest. |
| 🖥️ **Display tuning** | Options for refresh-rate selection, resolution scaling, and DPI where supported. |
| 🎯 **Input responsiveness** | Supported pointer, touch, long-press, and animation settings. |
| 🌐 **Network options** | Wi-Fi and Private DNS settings where the device supports them. |
| 🌡️ **Power and thermal options** | Supported performance and battery-related controls. Availability varies by device. |
| 🧹 **Background management** | Tools for supported background-process, cached-app, and game interruption controls. |
| 💾 **Storage maintenance** | On-demand TRIM and dex optimization where available. |
| 🎮 **Per-game profiles** | Supported game mode, compilation, and Doze-related options. |
| 📊 **Live telemetry** | FPS, frame time, 1% low, jank, CPU load, temperatures, and power readings when measurable. |
| ↩️ **Restore original settings** | Saves original values for supported settings and provides a way to restore them. |

## 📊 Real measurements, not simulated stats

HyperBoost uses Android diagnostic sources through Shizuku when available:

- **FPS, frame time, and jank:** SurfaceFlinger presentation timestamps, with a `gfxinfo` fallback for supported apps.
- **1% low:** Estimated from frame intervals over a recent sampling window.
- **CPU load and temperatures:** Read from available system interfaces.
- **Power draw:** Estimated from battery current and voltage readings when the device exposes them.

Some manufacturers restrict these interfaces. When a value cannot be measured reliably, HyperBoost may display `--` rather than invent a number. FPS telemetry measures the app in the foreground, so use the floating HUD while a game is active.

## 📱 Getting started

1. **Download HyperBoost Engine Pro** from the [GitHub Releases page](https://github.com/A-124/hyperboost-engine-pro/releases/latest).
2. Install **[Shizuku](https://shizuku.rikka.app/)** from its official site and start it using Wireless debugging in Android Developer options, following Shizuku's instructions for your Android version.
3. Open HyperBoost and grant the requested permissions.
4. Start with **Master Turbo**, then test your games and settings.
5. If a change causes an unwanted result, use **Restore My Original Settings** for supported settings.

### Requirements and compatibility

- Android device with support for the required Android features and permissions.
- Shizuku running and authorized for features that need it.
- Wireless debugging / Developer options for the Shizuku setup method.
- Some features may be unavailable on certain OEM builds or Android versions.

## 🛡️ Safety notes

- Review each permission before granting it.
- Apply changes gradually and test stability, temperature, battery use, and game compatibility.
- OEM-specific flags and hidden Android settings are best-effort; they may behave differently or be ignored.
- Restoring saved values applies to settings HyperBoost can track; it cannot guarantee reversal of every device-specific side effect.
- HyperBoost does not bypass hardware limits. Actual performance depends on the device, cooling, power mode, and the game.

## 📥 Downloads and updates

Get the latest published APK and version notes here:

**[➡️ View all HyperBoost Engine Pro releases](https://github.com/A-124/hyperboost-engine-pro/releases)**

For the latest version, use the [latest release page](https://github.com/A-124/hyperboost-engine-pro/releases/latest). Always download from the official project links.

## 🔒 Project and licensing

HyperBoost Engine Pro is **proprietary software**. This public repository is intended for project information and download links; publishing this README does not grant permission to copy, modify, redistribute, or commercially use the application or its source. All rights are reserved by **A-124**, except for third-party components governed by their own licenses.

For project updates and downloads, visit the [official website](https://hyperboost-engine-pro.vercel.app).

---

<p align="center">
  <sub>Built for Android gaming · Rootless workflow · Device-dependent features</sub>
</p>
