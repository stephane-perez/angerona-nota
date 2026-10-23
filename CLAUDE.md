# Angerona Nota — project rules

Local-only, encrypted plain-text notepad for Android (Kotlin + Jetpack Compose).
Sister app of Angerona Fasti (encrypted calendar). Personal-use app: keep security
measures proportionate, don't over-engineer. The person you work with speaks French;
talk to them in French, but all repo docs and code comments stay in English.

## Hard constraints (do not break)
- **Never change `applicationId` / package (`com.stephaneperez.notepad`).** Encryption
  keys live in the Android Keystore, tied to the app identity. Changing it orphans every
  encrypted file permanently. Only the display name (`app_name`) may change.
- **Never change the debug signing key** (`app/debug.keystore`). A signature change
  forces an uninstall, which destroys the Keystore key and with it all encrypted notes.
- `release` build type must never reference the debug keystore.
- Do NOT add a `VIEW`/`EDIT` intent-filter ("Open with" for text files). Explicitly
  rejected: the app must never encrypt arbitrary external files by accident.
- Do NOT add `FLAG_SECURE` (explicitly declined).
- `android:allowBackup="false"` must stay (encrypted file without its Keystore key is
  useless).
- **Never change the Keystore alias (`simple_notepad_file_key`) or the container header
  (`SNPD` magic + version byte 1)** in `CryptoManager`, even though they carry the old
  "Simple Notepad" name: existing files would become unreadable. Same for the
  `notepad_prefs` DataStore name (would reset preferences and the last-opened note).

## Encryption rules (see `data/CryptoManager.kt`)
- Reading: if the file looks encrypted, decrypt it; if decryption fails, show an error
  and never display raw bytes as text. If it does not look encrypted, open it as legacy
  plaintext. Every save encrypts.
- AES-256-GCM, Keystore key, GCM AAD over the format header, 100 KB plaintext cap,
  container size bounds enforced inside `CryptoManager` itself, strict UTF-8 decoding
  (legacy plaintext stays lenient UTF-8). Open-file classification lives in
  `data/DocumentCodec.kt`.
- Save paths must report real success/failure; never clear `dirty` or close a dialog
  before the write is confirmed.
- Deliberately declined as disproportionate (see DEVELOPMENT.md): anti-rollback, key
  versioning, binding ciphertext to note identity, granular error types.

## Conventions
- **Always update `README.md` when changing source code.** README stays in English and
  short; technical detail goes in `DEVELOPMENT.md`.
- Strings: English (`values/`), French (`values-fr/`), Spanish (`values-es/`). Every new
  string needs all three. **Apostrophes in `strings.xml` must be escaped as `\'`**, or
  the CI build breaks (AAPT error). `StringResourcesTest` enforces both.
- Tests: plain JVM unit tests in `app/src/test/` (`./gradlew testDebugUnitTest`), no
  Robolectric. Keystore-dependent code is tested through the internal
  `CryptoManager.encryptWith`/`decryptWith` (key passed in). Add tests with logic changes.
- Do not import `androidx.compose.foundation.layout.weight` explicitly (it caused a
  compile error); it resolves inside `Row`/`Column` scope.
- Signing config: use `signingConfigs.getByName("debug")`, never `create("debug")`.
- Edge-to-edge: content uses `windowInsetsPadding(WindowInsets.safeDrawing)`.
  `BackHandler` must be enabled only when something needs closing.
- Version comes from git tags (`vX.Y.Z`); CI passes `-PversionNameOverride` /
  `-PversionCodeOverride` (run number). Without them (F-Droid, local) it falls back to
  `git describe` / commit count. Never hardcode versions.
- CI: `.github/workflows/build.yml`, default branch `main`. Runs on pushes to `main`,
  PRs to `main`, `v*` tags and manual dispatch (not on plain feature-branch pushes):
  `testDebugUnitTest lintDebug assembleDebug assembleRelease`. The published APK is the
  debug-signed build, `angerona-nota.apk`. Work on feature branches, merge via PR; a
  `v*` tag triggers the GitHub Release (a `-suffix` tag is a pre-release).
- License: WTFPL. F-Droid metadata lives in `fastlane/metadata/android/`.
- Build needs JDK 17, compileSdk/targetSdk 36 (minSdk 26), AGP 8.13.0, Gradle 8.13,
  Kotlin 1.9.24 with Compose compiler extension 1.5.14 (pre-Kotlin-2.0 setup: no
  `org.jetbrains.kotlin.plugin.compose` plugin).
