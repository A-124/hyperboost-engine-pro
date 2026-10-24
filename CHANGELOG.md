# Changelog

## 1.7.1 - 2026-10-25

### Fixes
- Per-game resolution is now a render scale (0.2× / 0.4× / 0.6× / 0.8× / 1×) applied only while the selected game is in front. The previous display is restored when you leave the game or close the app. The global resolution preset card was removed.
- Lowered the display and density bounds so 0.2× can work on 1080p panels.
- Fixed switches flipping back on after being disabled. Read-back now requires both the system value and HyperBoost's applied flag, so a pre-existing device setting is never mistaken for a tweak the app applied.
- In-app updates open Android's system installer through a granted `FileProvider` URI. The withdrawn v1.7.0 build has been removed from the releases page.
- This patch is the first build with the corrected render-scale and toggle-state behavior.

## 1.7.0 - 2026-10-24

### Truthful state
- Touch Boost reports success only after `touch_prediction_status` is written and read back. State read-back no longer infers touch boost from the mouse `pointer_speed` setting.
- Wi-Fi low-latency reports whether the extra framework command was accepted instead of assuming it worked.
- Thermal override is checked through the thermal service where the ROM exposes it. Controls show **Verified**, **Requested (unverifiable)**, or **Not active** instead of always appearing engaged.
- Core toggles re-read the device after each action, so the switches reflect the phone rather than the last request.
- Compiled-game state now persists across app restarts.

### More gaming tuning
- Resolution presets for Native, 1080p, 900p and 720p, with density scaled to keep the UI proportional and the existing 6-second safety revert.
- Cached Apps Freezer (Android 11+), Adaptive Brightness Off and Show Refresh Rate modules on the Boost tab.
- Per-game display profiles: choose a resolution per game; it is applied when the game comes to the front and the previous display is restored when you leave.

### Updates and storage
- In-app updates now hand the verified APK to Android's system installer through a granted `FileProvider` URI instead of using `PackageInstaller` sessions. This fixes install failures reported as `Flags = ...` on some OEM builds.
- Removed the install-result receiver; the system installer now owns the confirmation step.
- Signature-conflict APK export uses `MediaStore.Downloads` on Android 10+ and legacy storage on older versions, with a share-sheet fallback. The dialog shows the real saved location instead of assuming Downloads.
- Device-specific setting snapshots are excluded from cloud backup and device transfer.

### Cleanup
- Removed the unused `FrametimeAnalyzer`, `SectionHeader`, `UpdateInstallReceiver`, template tests, unused usage-stats permission, and unused accessibility gesture APIs.
- The accessibility service is now documented as optional foreground-game detection; Auto Tap continues to use Shizuku.
- Expanded third-party notices for Firebase, Shizuku, AndroidX/Compose, Kotlin and test libraries.
- Added `EngineStateParser` and `FeedbackRepository` unit tests.

### Website and docs
- Website refreshed with three real app screenshots, a responsive screenshot grid, generated hero/OG artwork and a lightbox.
- Privacy policy updated for anonymous Firebase feedback.
- README trimmed and feature copy aligned with the implementation.

### Release status
- CI builds and publishes the signed APK with `SHA256SUMS.txt`.
- Device-specific behavior still varies; controls that a ROM rejects show as unsupported instead of silently succeeding.

## 1.7.0-rc.1 - 2026-10-24

Release candidate for v1.7.0. See the 1.7.0 notes below for the full change list.

## 1.6.0 - 2026-10-24

### Reliability and compatibility
- Gyro Check uses a compatible game-rate sensor request instead of requesting a zero-microsecond sampling period that can fail on Android devices.
- Touch Boost no longer hard-codes an unsupported mouse pointer speed; optional touch tweaks are attempted only where Android permits them.
- Tool failures use plain-language guidance instead of exposing raw Android exception stack traces.
- In-app update-check failures now show a clear retry message instead of internal API/JSON errors.
- Improved frametime telemetry so unavailable measurements are not replaced with synthetic FPS/jank values.
- Thermal override requires both CPU and GPU temperature readings and fails safe when readings disappear or limits are exceeded.
- Safer settings restoration avoids deleting a setting when no original snapshot could be read.

### Release status
- The v1.6.0 source changes passed the private GitHub Actions unit-test, lint, and debug-APK build workflow.
- **Public APK release is pending production signing and installation/update verification.** The available private-test APK is debug-signed and is not being advertised as a production-ready public update.


## 1.5.0 - 2026-10-22

### Deep system customization
- Keep More Apps Cached: raises the cached-app limit from 32 to 64 (Android 12+), verified by read-back and undone by Restore.
- Stay Awake While Charging, Screen Timeout presets (1 / 5 / 15 min), Landscape Lock and Quiet Radios (background Bluetooth scans and always-on cellular off).
- Touch Overlay: touch dots and live X/Y coordinates for picking Auto Tap positions.

### Visual tweaks
- Force Dark Theme (Android 10+; your original day/night mode is remembered and restored), Night Light, Grayscale Display.
- Extra Dim presets (25 / 50 / 75%, Android 12+) and Font Size presets (0.85x / 1.15x / 1.3x).

### Stability and automation
- Stability Guard: caps the refresh rate to 60 Hz when CPU/GPU/battery temperature is high or battery is under 15%, then restores the previous rate. Clears background apps during a game when free RAM drops below 10% (2-minute cooldown).
- Game Session Automation: per-session Do Not Disturb, RAM clean, Stay Awake and Landscape Lock for games with Optimize on; only what the session switched on is undone when you leave the game.
- Copy Device Report: device, display, RAM, temperature and active-tweak summary for bug reports.

### Notes
- All new tweaks snapshot the original value, verify the write where possible, and are covered by Restore.
- Support varies by Android version and ROM; unsupported tweaks report why instead of failing silently.

## 1.4.3 - 2026-10-21

### In-app updates
- Install downloaded updates through Android's PackageInstaller session API so the app receives the install result.
- Show a clear in-app error with Android's installer details when an update is cancelled or rejected.

## 1.4.2 - 2026-10-21

### Rootless tools
- Added a Shizuku network ping check that measures average latency to Cloudflare.
- Added a per-app background restriction switch for user apps; HyperBoost remembers each app's previous standby policy and restores it when switched off or when using Restore.

### Release
- Correct release signing fingerprint extraction and normalize its formatting before comparison.

## 1.4.0 - 2026-10-21

### Updates
- Update checks now show the GitHub API's error message, including rate-limit details, instead of only a generic HTTP status.
- Kept SHA-256 verification, signature checks, and the guided reinstall flow for incompatible update signatures.

### Release notes
- The in-app updater reads releases from the public `A-124/hyperboost-engine-pro` repository; the APK must be published there as well as in this private source repository.
- The previous release was debug-signed. Android may require uninstalling v1.3.9 before installing v1.4.0; consistent in-place updates need a persistent release keystore configured in GitHub Actions.

## 1.3.9 - 2026-10-21

### In-app feedback
- Added an in-app feedback form with Idea, Bug, and Other categories.
- Sends feedback to a write-only Firestore collection after anonymous Firebase sign-in; includes app and Android versions and device model.
- Added Firestore security rules and Firebase setup instructions.
- Connected the Android app to the HyperBoost Firebase project.

## 1.3.8 - 2026-10-21

### In-app updates & installer improvements
- **Package conflict fix**: Added pre-install signature verification (`checkSignatureCompatibility`) to detect signing certificate mismatches before invoking Android's package installer, avoiding the cryptic "App not installed as package conflicts with an existing package" error.
- **Guided update export**: When a signature mismatch is detected (e.g. updating between a local/debug build and a release build), the APK is automatically exported to the public `Downloads` directory and guides the user through a clean reinstall without lost downloads.
- **Zero-OOM checksum streaming**: Computes SHA-256 on the fly during network download using 8 KB buffers, completely eliminating out-of-memory errors on large APK files.
- **Live download progress**: Displays download percentage, downloaded MB, and total MB directly in an update dialog.
- **SHA-256 fallback**: Automatically parses `SHA256SUMS.txt` if GitHub release asset metadata lacks the `digest` field.
- **Rate-limit protection**: Throttles cold-start background update checks to a 6-hour interval in `SharedPreferences` to protect against GitHub API HTTP 403 limits.
- **Resumable permissions**: Automatically resumes download when returning from the system "Install unknown apps" settings screen.
- **Cache cleanup**: Automatically cleans up older cached update APKs before downloading a new release.
- **Deterministic debug keystore**: Added a committed repository `debug.keystore` so local and CI fallback builds share identical signatures.
- **Unit tests**: Added `UpdateManagerTest` verifying version comparisons and compatibility status models.

## 1.3.7 - 2026-10-20

### Private APK build check
- Bumps the local fallback app version to 1.3.7 so the version is consistent for local testing.
- No feature changes; this patch is intended to verify the private release workflow can run tests, build the APK, and attach the APK and SHA-256 checksum.

## 1.3.6 - 2026-10-20

### In-app updates
- Automatically checks for a newer public release when the app starts.
- Adds a manual update check from the dashboard.
- Reads the minimum supported version policy and prompts users to update when required.
- Downloads the APK, verifies its SHA-256 digest when provided by GitHub Releases, and opens Android's package installer for user confirmation.

## 1.3.5 - 2026-10-20

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
