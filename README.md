# Angerona Nota (Android)

A native Kotlin + Jetpack Compose implementation of the design in
`design_handoff_android_notepad/README.md` — a local-only, encrypted, plain-text notepad
in the "Classical" editorial design language (warm near-white ground, gold accent used
only as stroke/text, hairline dividers, serif chrome with monospace editor content).

### Why "Angerona"?

Angerona was a minor Roman goddess of silence and secrecy. Her statue was shown with a
bandaged mouth, or a finger held to her lips. She was the keeper of Rome's *secret
name* — a name the city deliberately never spoke aloud, since revealing it was believed
to leave the city vulnerable to enemies invoking it in a curse. A fitting, appropriately
understated namesake for an app whose entire job is to keep written words unreadable to
anyone but their author.

> **AI authorship disclosure.** Every line of code, configuration, and documentation in
> this repository — the app itself, the Gradle setup, the CI/CD workflow, and this
> README — was designed and written by an AI (Claude, Anthropic), working from the
> design handoff and iterating on human feedback (bug reports, CI fixes, build
> troubleshooting). No line was hand-written by a person; a human reviewed the result,
> ran it, and reported back issues that were then fixed by the same AI.

## Requirements
- JDK 17
- Android SDK 36 (minSdk 26)
- Android Studio Koala (2024.1) or newer — optional, see "Building without Android
  Studio" below.

## Getting started
Open this folder (`android-simple-notepad/`) directly in Android Studio — it's a
self-contained Gradle project (Gradle 8.13, AGP 8.13.0, Kotlin 1.9.24) — and hit Run.
No API keys, accounts, or network access are required; the app never touches the network.

## Building without Android Studio
No local Android SDK is required. Every push to `main` (and every pull request) builds
a debug APK via GitHub Actions — see `.github/workflows/build.yml`:
- **Every push/PR**: builds `angerona-notepad.apk` and uploads it as a workflow artifact
  (Actions tab → the run → Artifacts).
- **Pushing a tag starting with `v`** (e.g. `v1.0.0`, created from the Releases page or
  `git tag v1.0.0 && git push origin v1.0.0`): additionally publishes a public GitHub
  Release with `angerona-notepad.apk` attached, downloadable by anyone if the repo is
  public.

## Versioning
See [`DEVELOPMENT.md`](DEVELOPMENT.md) for how `versionName`/`versionCode` are derived
— not hardcoded, and works whether built by our CI, F-Droid, or locally.

## License
WTFPL — see [`LICENSE`](LICENSE). Do what you want with it.

## F-Droid readiness
This repo includes what F-Droid's inclusion process expects beyond the code itself:
- [`LICENSE`](LICENSE) — a FOSS license (FSF-approved) is a hard requirement.
- `fastlane/metadata/android/{en-US,fr-FR,es-ES}/` — store listing text (title, short
  and full description) in each supported language, plus a `changelogs/` folder (add
  one `<versionCode>.txt` file per release going forward).
- Self-contained versioning from git (see "Versioning" above) — no external CI needed
  to produce a correct, real version number.
- No Google Play Services, no analytics, no ads, no network permission at all — nothing
  in the anti-features list applies.

Not yet done: actually opening the inclusion request (a merge request against
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) with a recipe describing this repo,
license, and build steps) and adding a few screenshots under
`fastlane/metadata/android/<locale>/images/phoneScreenshots/`.

## File encryption

**Every file saved by Angerona Nota is encrypted on disk** — there is no way to save in
plain text. Only this app, on this specific device, can read the content back, using
an AES-256-GCM key held in the Android Keystore.

**Guarantees and limits — read this before trusting it with anything sensitive:**
- ✅ A `.txt` file produced by Angerona Nota is unreadable by any other app on the phone, a
  computer it gets copied to, or anyone who picks it up via Drive/email/USB.
- ✅ Tampering with an encrypted file (or an attempt to feed it a corrupted one) is
  detected and surfaced with a warning, not silently accepted or shown as garbled text.
- ⚠️ **The key survives neither an app uninstall, nor a factory reset, nor a device
  change.** Already-encrypted files then become **permanently unreadable** — no
  recovery password, no way back. Not a portable encrypted-backup system, only a
  "this device, this app" protection.
- ⚠️ This is **not** password-protected encryption: as long as the phone is unlocked
  and the app installed, opening is automatic and transparent — no lock inside the app
  itself.
- ⚠️ Files over 100 KB are refused on both open and save, to avoid crashes on huge
  files — nobody is editing a note that size here anyway.

Opening an older, unencrypted `.txt` file works with no friction — it's just read as
plain text. Implementation details and the full security-review history are in
[`DEVELOPMENT.md`](DEVELOPMENT.md).

## Localization

The app ships in **English, French, and Spanish** using Android's native string
resources (`res/values/strings.xml`, `res/values-fr/strings.xml`,
`res/values-es/strings.xml`) — no library, no in-app language switcher. Android picks
the matching resource file automatically based on the phone's system language, and
falls back to English for any other language.

All user-facing text (button labels, dialog copy, toasts, status bar counts) goes
through `stringResource()` / `pluralStringResource()` in Compose, or
`Context.getString()` / `Resources.getQuantityString()` in `NotepadViewModel` for
toasts, which are generated outside a `@Composable` scope. Plurals (line/lines,
char/chars, match/matches replaced) use Android's `<plurals>` resource so each language
handles singular/plural correctly rather than concatenating English-shaped fragments.

Two things are deliberately **not** translated: the app name, and the default filename
`untitled.txt` — kept invariant so files stay portable and unambiguous regardless of the
phone's language.

To add a fourth language: add `res/values-<code>/strings.xml` with the same keys as
`res/values/strings.xml` (Android Studio's Translations Editor lists any missing keys
for you); no code changes needed.


## What's implemented
- **Editor** — top app bar (New / Open / Save / Find / More) with a dirty-state dot,
  monospace editor with an optional line-number gutter (hidden while wrap is on), and a
  bottom status bar with file path + live line/char counts.
- **Open document** — full-screen overlay listing `.txt` files from the app's own
  documents directory (the design's stand-in for the system picker), plus a "Browse
  device storage…" row that launches `ACTION_OPEN_DOCUMENT` for anything else.
- **Find & replace** — literal, case-sensitive substring search, match counter (`3 / 7`
  / `none`), replace-all with a toast (`7 matches replaced` / `1 match replaced` /
  `No matches`), previous/next with wraparound.
- **Overflow menu** — Word wrap / Line numbers toggles; the Line numbers row shows
  "Wrap is on" and goes inert while wrap is enabled, per spec. Word wrap defaults to
  **on**.
- **Unsaved changes dialog** — Cancel / Discard / Save first, with the exact copy from
  the handoff, gating New and Open when the buffer is dirty.
- **Toast** — bottom-anchored, monospace, 2200ms auto-dismiss.
- **State** — a single `NotepadViewModel` (`StateFlow`) matching the handoff's state
  table exactly, with `wrap` / `lineNumbers` / last-opened URI persisted via DataStore
  and restored on relaunch.
- **File access** — Storage Access Framework: `ACTION_OPEN_DOCUMENT` for Open,
  `ACTION_CREATE_DOCUMENT` for a new buffer's first Save, `ContentResolver` streams after
  that, with persistable URI permissions taken so re-opened files survive restarts.
- **Edge-to-edge display** — content is padded with `WindowInsets.safeDrawing` so the
  top app bar and bottom status bar/toast clear the status and navigation bars, while
  the background still paints edge-to-edge behind them.
- **System Back button** — only intercepted when something is open to close first
  (overflow menu, find strip, Open overlay, unsaved-changes dialog); otherwise it falls
  through to the system default so the app exits/minimizes normally.
- **Encryption** — every save is encrypted (AES-256-GCM, Android Keystore-backed);
  every open tries to decrypt and falls back to plain text if that fails. See
  "File encryption" above.

## Design tokens
All colors, type sizes, spacing, and radii are transcribed from the handoff into
`ui/theme/Color.kt`, `ui/theme/TextStyles.kt`, and used directly by each component —
there's no separate "port to Material3 defaults" step; the handoff's numbers are the
source of truth.

Fonts: **Cormorant Garamond** and **Lora** (both OFL) are bundled as actual font files
under `res/font/` per the handoff's "bundle" instruction, rather than fetched at runtime
via the Downloadable Fonts API. License texts are included under
`app/src/main/assets/font_licenses/`.

## Known adaptations / simplifications
- **Icons**: the eight required glyphs (`file-plus`, `folder-open`, `save`, `search`,
  `more-vertical`, `arrow-left`, `chevron-up`, `chevron-down`) are recreated as Android
  vector drawables directly from Lucide's own SVG source (ISC licensed), tinted at
  runtime to match `ink` (`#201f1d`).
- **Line-number gutter**: implemented with a shared `ScrollState` between the gutter and
  the text field so numbers track the text vertically. This works well for the common
  case; extremely large documents may want a virtualized editor for best performance —
  out of scope for this handoff-fidelity pass.
- No dark theme: the handoff is explicitly light-only ("warm near-white ground"), so
  none is offered.

## Not implemented (explicitly out of scope per the handoff)
- Save-as dialog / confirmation step on Save.
- Save on `onStop`.
- Any network access, accounts, or formatting.
