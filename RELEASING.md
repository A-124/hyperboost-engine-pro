# Releasing HyperBoost Engine Pro

This public repository is the main source repository, the official website source, and the public download location. The private `A-124/hyperboost-engine-pro-private` repository is kept as a private mirror/backup.

## One-time setup

Add these under **Settings → Secrets and variables → Actions** in this public repository:

**Secrets**
- `KEYSTORE_BASE64`: base64 of the release keystore file.
- `STORE_PASSWORD`: keystore password.
- `KEY_PASSWORD`: key password.

**Variables**
- `RELEASE_CERT_SHA256`: SHA-256 fingerprint of the release certificate, used to verify the built APK.
- `UNITY_GAME_ID`, `UNITY_TEST_MODE`, `UNITY_BANNER_ID`, `UNITY_INTERSTITIAL_ID`, `UNITY_REWARDED_ID` (optional ad configuration).

On Windows PowerShell, encode the keystore with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\my-upload-key.jks"))
```

> The release workflow **refuses to publish** when the signing secrets are missing. A debug-signed APK cannot update existing installs, so it must not be published on the public releases page. Keep a safe backup of the keystore and passwords; losing them means users must uninstall before installing a newer build.

## Release steps

1. Make sure the changes are tested and merged to `main`.
2. Add a section to `CHANGELOG.md`:

   ```markdown
   ## 1.6.1 - 2026-10-25

   - Short note about each change
   ```

   The section must contain at least one line of notes or the build stops.
3. Optionally update the default `versionName` in `app/build.gradle.kts`. CI sets it from the tag anyway.
4. Commit and push to `main`.
5. Create and push a version tag:

   ```bash
   git tag v1.6.1
   git push origin v1.6.1
   ```

6. Open **Actions → Release** and wait for the run to finish. It runs unit tests, builds and verifies the signed APK, and publishes a release here with `HyperBoost-Engine-Pro-<version>.apk` and `SHA256SUMS.txt`.

To rebuild an existing tag, run **Actions → Release → Run workflow** and enter the tag, for example `v1.6.1`. Move a wrong tag with `git tag -f` and `git push -f origin v1.6.1` only if you are sure.

## Asset names are part of the updater contract

The in-app updater in `app/src/main/java/com/hyperboost/engine/update/UpdateManager.kt` expects:

- release tag: `v<version>`
- APK asset: `HyperBoost-Engine-Pro-<version>.apk`
- checksum asset: `SHA256SUMS.txt`

Do not rename these. If the minimum supported version changes, update `update.json` as well.

## Website files

`index.html`, `assets/`, `robots.txt`, `sitemap.xml`, and `update.json` live in the repository root and are deployed by Vercel from `main`. Keep them in place; the app fetches `update.json` from the raw `main` branch.

## Alternative: build privately and publish manually

If you prefer to build in the private mirror, download the APK and `SHA256SUMS.txt` from the private release and attach them to a release here with the same tag, names, and notes. Keep the public releases page as the only download link users are given.
