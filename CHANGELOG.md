# Changelog

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

### Ads
- Failed banner requests stay completely hidden, with no error strip or dialog; automatic retries continue with capped backoff.

### Rootless tools
- Added a Shizuku network ping check that measures average latency to Cloudflare.
- Added a per-app background restriction switch for user apps; HyperBoost remembers each app's previous standby policy and restores it when switched off or when using Restore.

### Release
- Correct release signing fingerprint extraction and normalize its formatting before comparison.

## 1.4.0 - 2026-10-21

### Ads and updates
- Corrected the Unity Ads Game ID to HyperBoost's registered Android app ID (`800403406`); the previous ID belonged to another app in the Unity account.
- Added a compact banner-ad unavailable message with a one-tap retry, so a no-fill or placement error is visible instead of looking like a broken blank area.
- Banner ads now retry indefinitely with a capped backoff, and successful loads reset the retry delay.
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
