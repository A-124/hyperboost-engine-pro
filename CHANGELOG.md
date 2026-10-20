# Changelog

## Unreleased

_No unreleased changes yet._

## 1.3.6 - 2026-10-20

### In-app updates
- Automatically checks for a newer public release when the app starts.
- Adds a manual update check from the dashboard.
- Reads the minimum supported version policy and prompts users to update when required.
- Downloads the APK, verifies its SHA-256 digest when provided by GitHub Releases, and opens Android's package installer for user confirmation.

## 1.3.5 - 2026-10-20

### Ads
- Banner now stays hidden until an ad actually fills, so the empty black strip is gone.
- Banner retries on no-fill, refreshes every 60 seconds while visible, and is destroyed cleanly.
- Interstitials now appear after every 2nd feature action (boost toggles, Clear Background, Storage Boost, Trim Caches), at most once per 90 seconds.
- Interstitial and rewarded placements retry loading automatically instead of staying dead after a failed load.
- Ad initialization retries if it fails.

### Shizuku setup
- Setup guide rewritten as short numbered steps for Wireless debugging, with no computer needed. See docs/SHIZUKU_SETUP.md.

### Release process
- Release workflow publishes the APK to this private repository only. The public repository is updated manually.
- Release signing secrets are optional. Without them the APK is debug-signed for private testing.

## 1.3.4 - 2026-10-20

### UI redesign
- Dashboard split into four tabs (Boost, Monitor, Tools, Games) so each screen shows only what it needs, instead of one long scroll.
- Shizuku status moved into the top bar as a chip and stays visible on every tab.
- Master Boost hero card redesigned with live module count, status, and progress; the whole card is tappable.
- Refined dark palette with a deeper base, clearer surface levels, and consistent corner radii.
- New shared components (`HbCard`, `SectionHeader`, `StatChip`, `StatTile`) for future screens.

### Build & maintainability
- Release workflow rewritten into preflight, build, and publish stages with secret checks, signature verification, and changelog-based release notes.
- Removed template theme files that were not used by the app.
- Expanded the README with setup requirements, build instructions, telemetry limitations, and permission/safety notes.

### Added: "More Rootless Tools" (feature set inspired by Benimaru Tool, re-implemented; no code copied)
- **Disable Window Blurs** (Android 12+): turns off real-time background blur. Restored from the settings snapshot.
- **Disable Thermal Throttling**, with a temperature guard: it turns itself off at 60°C CPU or 65°C GPU, and it turns off when you close the app. A leftover override from an earlier session is reverted on launch.
- **Gyro Check**: turns off Battery Saver, then measures the gyroscope's real sample rate.
- **Auto Tap**: a floating ■ button that repeats a tap or swipe through Shizuku (no Accessibility permission). Minimum interval 300 ms. Stops after 30 minutes, after 5 failed inputs in a row, or when you tap ■.
- **Freeze Apps**: disables user-installed apps you don't use. Their data is kept. Protected system apps are never listed or frozen, and Restore My Original Settings unfreezes everything.

### Safety
- Display size changes (720p) now need confirmation. Without a reply within 6 seconds, the display goes back to native.
- Restore My Original Settings also turns off thermal override, unfreezes apps, and stops Auto Tap.
- The booster counter now covers 12 tweaks.

## 1.2.8
### Telemetry is now real (was partly simulated)
- Removed the random "58-60 FPS" fallback. FPS was previously made up whenever SurfaceFlinger returned nothing
  (it was queried without a layer name, so it nearly always did) and the gfxinfo fallback used `frames / 2`.
- New `TelemetryHub`: one shared sampler for dashboard + HUD (previously two independent shell loops).
- FPS from real SurfaceFlinger present timestamps of the foreground app's layer; gfxinfo framestats fallback.
- Exact rate estimator (steady 30/60/120 FPS read exactly; stalls are reported, not hidden).
- Jank judged against the app's own cadence; new 1% low.
- Real CPU load, CPU/GPU temperature, power (uA/mA auto-detect, smoothed, charge vs draw).
- Panel-aware: refresh chips show only rates the display supports; "good FPS" is relative to the panel.
- Sampling only runs while the dashboard is visible or the HUD is on.

### Engine
- Sustained Performance and Do Not Disturb are verified by reading the system back; sustained state is read from
  the power service instead of assumed.
- `wm size` / `wm density` parse failures no longer invent a 1080x2400 / 420 dpi "native" size.
- Master Turbo re-reads the real device state and reports "N/8 verified active"; no longer runs `pm trim-caches`
  (slow, and it cold-starts every app).

### Games
- Game detection uses the system game category/flag and known publishers (the old `contains("cod")` matched
  unrelated apps) and no longer pads the list with random apps.
- Per-game Auto-Boost choice is remembered; a profile is applied once per session (not on every window event),
  works with Shizuku alone (accessibility optional), clamps to supported refresh rates, and gives the refresh rate
  back when you leave the game.

### HUD
- Crosshair-only mode keeps a foreground notification so Android doesn't kill it.
- Shows `--` and the reason when no frame data is available.

### Tests
- JVM unit tests for all parsers (`FrameParsersTest`).
