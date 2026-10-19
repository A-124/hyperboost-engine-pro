<div align="center">

# ⚡ HyperBoost Engine Pro

**A Rootless Android Gaming Performance & System Optimization Utility**

[![Latest Release](https://img.shields.io/github/v/release/A-124/hyperboost-engine-pro?style=for-the-badge&color=7c3aed)](https://github.com/A-124/hyperboost-engine-pro/releases/latest)
[![Android Version](https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)
[![Shizuku Framework](https://img.shields.io/badge/Requires-Shizuku-007ACC?style=for-the-badge&logo=android&logoColor=white)](https://shizuku.rikka.app/)
[![License](https://img.shields.io/badge/License-Proprietary-red?style=for-the-badge)](https://hyperboost-engine-pro.vercel.app)

[🌐 Official Website](https://hyperboost-engine-pro.vercel.app) • [⬇️ Download Latest APK](https://github.com/A-124/hyperboost-engine-pro/releases/latest) • [🛠️ Shizuku Setup Guide](https://shizuku.rikka.app/)

---

</div>

## 📌 Overview

**HyperBoost Engine Pro** is a lightweight, rootless Android performance utility designed for gamers and power users. By leveraging **Shizuku** to execute elevated ADB-level commands alongside target accessibility integrations, HyperBoost allows you to fine-tune system scheduling, display parameters, and game behavior **without rooting your device**.

> ⚠️ **Notice:** Feature availability and performance gains vary depending on device OEM (ColorOS, MIUI, One UI, OxygenOS, stock Android), SoC capabilities, Android version, and system permissions.

---

## 📑 Table of Contents
- [✨ Key Features](#-key-features)
- [📊 Telemetry & Metric Measurement](#-telemetry--metric-measurement)
- [🚀 Getting Started](#-getting-started)
- [📋 System Requirements](#-system-requirements)
- [🛡️ Safety & Restoration](#️-safety--restoration)
- [📥 Downloads & Releases](#-downloads--releases)
- [🔒 License & Ownership](#-license--ownership)

---

## ✨ Key Features

| Feature | Description |
| :--- | :--- |
| 🚀 **Master Turbo** | Executes multi-layered optimizations independently — ensuring unavailable tweaks do not block supported routines. |
| 🖥️ **Display Tuning** | On-the-fly refresh rate forcing, resolution scaling overrides, and density (DPI) adjustments where supported. |
| 🎯 **Input Optimization** | Adjust pointer speed, touch latency parameters, long-press delay, and window animation scales. |
| 🌐 **Network Optimization** | Low-latency Wi-Fi toggles and automated Private DNS switching support. |
| 🌡️ **Thermal & Power Controls** | Tweak supported background battery restrictions, power profiles, and thermal throttling behaviors. |
| 🧹 **Process Management** | Smart background app management, cached process cleaning, and game distraction locks. |
| 💾 **Storage Maintenance** | Execute manual filesystem TRIM operations and speed up app loading using system `dexopt` compilation. |
| 🎮 **Per-Game Profiles** | Custom game space configurations, Doze mode overrides, and per-app performance rules. |
| 📊 **Live Performance Overlay** | In-game floating HUD featuring real-time FPS, frame timing, 1% lows, jank spikes, CPU load, and battery power draw. |
| ↩️ **1-Click Settings Restore** | Automatically backs up modified system flags and provides a seamless restore function. |

---

## 📊 Telemetry & Metric Measurement

Unlike standard apps that show mock or simulated performance stats, HyperBoost Engine Pro pulls telemetry directly from native Android diagnostic APIs via Shizuku:

* **FPS & Frame Times:** Direct SurfaceFlinger presentation timestamp parsing, with `gfxinfo` diagnostics as fallback.
* **1% Low FPS:** Estimated dynamically over a scrolling frame-interval sampling window.
* **CPU Load & Thermal Zones:** Extracted from active system kernel hardware sensors.
* **Power Draw:** Calculated using system battery current ($I$) and voltage ($V$) interfaces where exposed by the OEM.

*Note: If a device manufacturer restricts access to a specific thermal or hardware path, HyperBoost displays `--` instead of guessing metrics.*

---

## 🚀 Getting Started

1. **Download the APK:** Get the latest release from the official [Releases Page](https://github.com/A-124/hyperboost-engine-pro/releases/latest).
2. **Setup Shizuku:** Install and start **[Shizuku](https://shizuku.rikka.app/)** using Wireless Debugging or PC ADB.
3. **Grant Permissions:** Launch HyperBoost Engine Pro and authorize Shizuku and required system access.
4. **Apply Optimizations:** Run **Master Turbo** or customize per-game configurations in the dashboard.
5. **Revert Changes:** Use **Restore My Original Settings** at any time to revert modified parameters.

---

## 📋 System Requirements

* **Android OS:** Android 8.0 (Oreo) or higher.
* **Shizuku Framework:** Active and authorized service.
* **Developer Options:** Enabled (for Wireless Debugging setup).
* **Hardware:** Compatible with Qualcomm Snapdragon, MediaTek Dimensity, Samsung Exynos, and Google Tensor SoCs.

---

## 🛡️ Safety & Restoration

* **Non-Destructive:** HyperBoost modifies soft Android software parameters via official APIs and ADB flags — it does not modify system partitions.
* **Reversible:** Built-in backup tables allow you to safely restore original values for modified settings.
* **Safe Operations:** Always apply tweaks gradually and test for temperature, battery usage, and overall game stability.

---

## 📥 Downloads & Releases

Always download official releases directly from verified sources:

* 📦 **[Latest Production APK](https://github.com/A-124/hyperboost-engine-pro/releases/latest)**
* 🌐 **[Official Web Portal](https://hyperboost-engine-pro.vercel.app)**
* 📜 **[Version Changelogs](https://github.com/A-124/hyperboost-engine-pro/releases)**

---

## 🔒 License & Ownership

**HyperBoost Engine Pro** is **proprietary software** developed and maintained by **A-124**. 

All rights reserved. Access to this public repository is provided strictly for project documentation, bug reporting, and official download links. Redistribution, reverse engineering, unauthorized mirroring, or commercial use without explicit permission is prohibited.

---

<div align="center">
  <sub>Developed by <strong>A-124</strong> • Built for Android Gaming Performance</sub>
</div>
