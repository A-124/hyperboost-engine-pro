# Changelog

## 1.8.0 - 2026-10-28

### Fixed
- Feedback could not be sent at all. Release builds were missing their messaging configuration; they now include it, and if sending still fails the dialog offers **Send on GitHub** instead.
- Feedback errors now say what actually went wrong.
- A failed update no longer leaves you stuck: every failure offers **Open download page**.
- Updates are also checked when you return to the app, and a declined update stops asking.

### New
- **Required updates and notices:** a build that must not keep running shows a prompt that cannot be dismissed.
- **Optional device compatibility sharing**, off by default: shares which settings your phone accepts or rejects, never values, app names or messages.
- **Android 16 support.**

### Smaller
- Smaller download and faster install: unused code and resources are removed from release builds.
- No longer asks for permission to see every installed app.
- Updated Compose, Lifecycle, Activity and Unity Ads.

## 1.7.3 - 2026-10-25

> Re-issued build. If you are on an older version, install this APK manually once: those builds stop in-app downloads at the first progress update.

- New: per-game Android Game Mode (Performance / Standard / Battery), and a **HyperBoost Boost** Quick Settings tile that runs Master Boost without opening the app.
- Fixed: in-app downloads failed immediately with `Update failed: Flags = '('` because a `%` in the progress line was not escaped.
- Updates: `version.json` is published with each release, so a re-issued build is detected even when the version name is unchanged.

---

Older release notes (1.7.2 and earlier): [docs/CHANGELOG_ARCHIVE.md](docs/CHANGELOG_ARCHIVE.md).
