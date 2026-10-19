# Changelog

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
