# Development notes

Technical details that don't need to be in the main README for someone just trying to
build or use the app, but are worth keeping around.

## Versioning

`versionName` / `versionCode` are **not** hardcoded in `app/build.gradle.kts`:
- **Our own CI** (GitHub Actions) passes them explicitly at build time:
  - On a tag push (`v1.2.3`): `versionName = "1.2.3"` (matches the GitHub Release name
    exactly), `versionCode` = the workflow run number (strictly increasing).
  - On a plain push/PR (no tag): `versionName = "0.0.0-dev.<run number>"`, so a dev
    build can never be mistaken for a tagged release.
- **Any other build environment** (F-Droid's build server in particular, which checks
  out a tagged commit and runs a plain `gradle assembleRelease` with none of the flags
  above) falls back to deriving the version straight from git: `versionName` from the
  nearest tag, `versionCode` from the total commit count (monotonic — it only grows).
  This means F-Droid's reproducible build doesn't depend on our CI at all.
- With no git history available at all (e.g. a bare source zip), it falls back further
  to `versionName = "0.0.0-dev"`, `versionCode = 1`.

## Fixed debug signing key — why it matters here

An **uninstall** (and therefore a reinstall) wipes the Android Keystore encryption key
along with the rest of the app's data — already-encrypted files then become unreadable
forever (see the README, "File encryption"). The risk is that **an ordinary update can
silently turn into a forced uninstall**: every GitHub Actions run happens on a
brand-new machine, and without explicit configuration, AGP signs each debug build with
a debug key generated on the fly on that machine (`~/.android/debug.keystore`,
different every run). Two APKs signed with different keys can't update one another —
Android requires a full uninstall before installing the next one, which destroys the
Keystore key and, with it, access to any already-encrypted file.

**The fix**: a fixed debug key, `app/debug.keystore`, generated once and committed to
the repo (password `android`, alias `androiddebugkey` — deliberately AGP's own
defaults, nothing secret to protect here: it's a *debug* key, never used for a Play
Store release). `app/build.gradle.kts` references it explicitly for the `debug` build
type, so **every build — local and CI — now shares the same signature**. A new version
then installs normally over the previous one, no uninstall needed, and the Keystore
key (and thus access to already-encrypted files) survives from one version to the
next.

## Encryption: how it works, and its security review history

**On-disk format**: `SNPD` (4-byte magic) + version (1 byte) + IV (12 bytes) +
ciphertext + 16-byte GCM authentication tag. The header (magic + version) is passed
to AES-256-GCM as AAD, so tampering with it — not just the ciphertext — is detected
too. Implementation: `data/CryptoManager.kt`; opening goes through `data/DocumentCodec.kt`
(bounded read, encrypted-vs-legacy classification), wired into
`NotepadViewModel.loadDocument()` / `.save()`.

This design was reviewed by a second AI (ChatGPT) across four rounds — the first three
focused on `CryptoManager.kt` itself, the fourth on the integration code around it
(`NotepadViewModel.kt`, `PreferencesRepository.kt`). Kept for anyone who wants the
reasoning behind a specific decision, or is doing their own review and wants to avoid
re-litigating settled points.

### Round 1 — `CryptoManager.kt`, first pass
Fixed: key created as a side effect of a failed decrypt (`getExistingKey()` vs.
`getOrCreateKey()` split); no size limit (added `MAX_PLAINTEXT_BYTES`); IV length
unchecked; key creation not thread-safe; `android:allowBackup="true"` risked an
encrypted file being backed up without its (never-backed-up) key. Declined:
requiring user authentication (biometric/PIN) on every open/save — a deliberate
product choice, not an oversight; documented in the README's guarantees/limits list.

### Round 2 — `CryptoManager.kt`, second pass
Fixed: the size limit wasn't actually enforced *inside* `decrypt()` itself, only by
its one caller; decrypted bytes weren't strictly validated as UTF-8 (silent `�`
replacement could mask corruption as garbled text). Declined: a key/format version
byte (nothing to version yet — no second key or algorithm exists) and anti-rollback
protection (replaying an old, still-authentic ciphertext) — for a personal notes app,
worst case is reading a stale draft, not worth a separately-tracked protected counter.

### Round 3 — `CryptoManager.kt`, third pass
Fixed: Keystore access inside `decrypt()` could throw outside its `runCatching`,
contradicting the documented "never throws" contract; `looksEncrypted()` accepted
anything at least header+IV long, when a real GCM container can never be shorter than
header+IV+tag; a doc comment overstated "no other device" when the accurate claim is
"not without the corresponding key". Declined: binding ciphertext to a specific note's
identity via AAD (an attacker able to swap the raw bytes of two encrypted files would
have both decrypt successfully, each showing the other's content under the wrong
filename) — would need a stable per-note ID independent of the file's URI/name
(renaming a note via an external file manager must not break decryption), which this
app has no concept of today; exploiting this already requires raw write access to the
encrypted files, at which point the attacker gains no content they didn't already
control. Also declined: the file size revealing an approximation of the plaintext
length — a standard, inherent property of unpadded authenticated encryption, not
specific to this implementation.

### Round 4 — the integration code (`NotepadViewModel.kt`, `PreferencesRepository.kt`)
With `CryptoManager` itself judged solid, this round found real functional bugs, not
just crypto hygiene:
- **A failed save could silently lose data.** `save()` didn't report success/failure,
  so "Save first" (in the unsaved-changes dialog) proceeded to discard the buffer or
  open another document even if the write itself had failed. `save()` now returns a
  `Boolean`; callers that chain further actions check it.
- **`completeSaveAs()` could point the UI at a file that was never written** — it
  updated `uri`/`filename`/`filePath` in state, then wrote. Reordered: writes first,
  only commits the new document into state once that write actually succeeded.
- **Wrong size bound on open.** Incoming raw bytes were compared against
  `MAX_PLAINTEXT_BYTES`, up to 33 bytes smaller than a real encrypted container — a
  legitimately maximal note of our own could be wrongly rejected on reopen. Now
  compared against `CryptoManager.MAX_CONTAINER_BYTES` (exposed publicly for this).
- **Size checked after loading the whole file into memory.** Used to call
  `readBytes()` before checking size at all. Now reads in 8 KB chunks and aborts as
  soon as the limit is exceeded, never buffering an oversized file fully.
- **A dead `lastUri` was retried forever.** If the last-opened document became
  inaccessible (deleted, permission revoked, moved), the app silently failed to
  restore it on every launch. It now clears the stored reference after one failed
  restore.

Declined, with reasoning:
- **Non-atomic writes.** `save()` still opens in truncate mode (`"wt"`) and writes in
  one step; a write that fails partway (disk full, provider error) can leave the file
  empty or partial rather than restoring the previous content. True atomic
  write-temp-then-rename would need reliable `DocumentsContract.renameDocument`
  support, which varies by SAF provider, for a narrow failure window on files capped
  at 100 KB.
- **The legacy plain-text path stays lenient UTF-8**, unlike the strict decoder used
  for our own encrypted format. Deliberate: the encrypted path only ever contains
  bytes this app itself produced (always valid UTF-8), while the plain-text fallback
  handles arbitrary external `.txt` files that may be in any encoding — rejecting
  those outright would break opening legitimate legacy notes.
- **`LAST_URI` in DataStore isn't itself encrypted** and can reveal a filename/path.
  Acceptable for a personal app, same spirit as the file listing already showing
  size/modified date in plain sight.
- **A possible external-Intent attack surface was raised and checked — none exists.**
  `MainActivity` has only a `MAIN`/`LAUNCHER` intent-filter (a `VIEW`/`EDIT` handler
  was prototyped once and deliberately removed) and `openDocument(uri)` is only ever
  called from in-app UI, never from externally-supplied Intent data.
- **`Uri.fromFile()`** in the in-app file browser is only ever used locally via
  `ContentResolver`, never passed to another app through an `Intent`.
- **DataStore vs. Android Backup** was already resolved in round 1
  (`android:allowBackup="false"`).

### Round 5 — `NotepadScreen.kt`
Fixed: **"Save first" on a never-saved buffer closed the unsaved-changes dialog and
cleared the pending New/Open action the moment the `CreateDocument` picker returned a
URI — before knowing whether the write would actually succeed.** A destination chosen
by the picker isn't the same as a successful write. `completeSaveAs()` now resumes the
pending action itself (via a `resumePendingAction()` helper shared with
`onDialogSaveFirst()`), only after a confirmed successful write; on failure, the
dialog/pending action are left untouched and the existing failure toast is shown. This
had been a known, documented limitation ("Save first" not auto-resuming — see the
README's git history) rather than a bug fix; round 4's save-result plumbing made it
straightforward to close properly. Declined: `save()` reporting its result to
`NotepadScreen` for the Screen to react to — already unnecessary, since `save()`
already surfaces failure via a toast (round 4), which the Screen doesn't need to
duplicate; explicit tracking of whether `takePersistableUriPermission()` succeeded —
already effectively covered by round 4's dead-`lastUri` self-healing, which absorbs
the practical consequence without needing a separate signal; switching the
`OpenDocument` picker's MIME filter from `text/plain` to `*/*` — our own files keep
the `text/plain` MIME declared at creation regardless of their now-encrypted content,
so this isn't currently a real gap, and a broader filter would make the picker less
useful for a text-notes app.

### Round 6 — `NotepadViewModel.kt`, closer look
Fixed: **`openDocument(uri)` ignored `loadDocument()`'s success/failure result** —
even when opening failed (too large, corrupted, key missing), it still closed the
Open-document overlay and persisted the URI as `lastUri`, so the next launch would
retry (and then discard) a reference that had already failed once. Now returns early
on failure, leaving the overlay open so the person can pick a different file, and
never remembers a URI that didn't actually load. Also fixed: **the legacy plain-text
path wasn't checked against the actual 100 KB limit** — the initial bounded read uses
the looser `MAX_CONTAINER_BYTES` (correct for not-yet-classified incoming bytes, since
an encrypted container is up to 33 bytes larger than its plaintext), but once a file
is confirmed *not* to be one of ours, it's now checked again against the tighter
`MAX_PLAINTEXT_BYTES` so the 100 KB limit is enforced uniformly regardless of a file's
origin. Also fixed: `completeSaveAs()` took the persistable URI permission *before*
confirming the write succeeded; reordered so a failed Save-As doesn't leave a
permission grant persisted to a document that was never actually written.

Raised, and left as-is on purpose: the app's plaintext necessarily lives in memory as
a plain Kotlin `String` while editing (in `NotepadUiState` and the text field) — this
is inherent to how any text editor works, and encryption here protects data *at rest*,
not the in-memory content of an actively open, unlocked device (already stated in the
README's guarantees/limits list). One concrete consequence worth naming: Android's
Recents/app-switcher can show a screenshot thumbnail of the last visible screen,
which could include note content. `WindowManager.LayoutParams.FLAG_SECURE` is the
standard fix (blocks both that thumbnail and the person's own ability to screenshot
the app), but it wasn't added without being asked for, since it removes a real
capability (no more screenshots at all, including deliberate ones) rather than being
a free hardening step — worth adding if that trade-off is wanted.

### Round 7 — `PreferencesRepository.kt` + `app/build.gradle.kts`
`PreferencesRepository` was judged clean: only UI prefs and the last-opened URI are
stored, never note content or key material; `LAST_URI` can reveal a filename via the
URI string, accepted as low-priority metadata exposure (same spirit as the file
listing already showing size/date). `app/build.gradle.kts` got two fixes:
- **Explicit documentation that `release` must never sign with `app/debug.keystore`.**
  In fact, `release` already has no `signingConfig` at all — `./gradlew
  assembleRelease` currently produces an unsigned APK — so this was already
  structurally impossible, not just unlikely; a comment now says so directly, and
  spells out that a real release needs its own private, never-committed keystore.
- **R8 + resource shrinking enabled for `release`** (`isMinifyEnabled` /
  `isShrinkResources = true`, previously `false`). Standard practice, low risk for a
  reflection-free Compose app; reduces attack surface and ease of reverse-engineering,
  though — worth repeating — it does nothing to protect the AES key, which was never
  in the code to begin with (it lives in the Android Keystore).

Raised, not acted on: bumping `compileSdk`/`targetSdk` past 34 and refreshing pinned
dependency versions. Both are legitimate maintenance items (Android 16/API 36 is
Google Play's current target as of August 2026), but a blind bump risks a build break
that can't be verified without a real Android SDK/build environment, which isn't
available here — left as an explicit decision for whoever does the next release pass,
rather than guessed at.

### Round 8 — SDK bump, and closing the loop
`compileSdk`/`targetSdk` bumped 34 → 36, AGP 8.5.2 → 8.13.0 (stays on the AGP 8.x line
— AGP 9.x requires Gradle 9 and drops the classic DSL entirely, a much bigger,
higher-risk migration than this app needs right now), Gradle wrapper 8.7 → 8.13.
Kotlin (1.9.24), the Compose compiler extension version, and all other dependency
versions were left untouched to keep this a narrow, single-purpose change — a full
dependency refresh is a separate task, not bundled in here.

Asked directly whether the last two rounds' worth of findings still held up given
this is "just a small personal notes app," the second AI recalibrated: the two
concrete fixes from round 6 (`openDocument()` ignoring `loadDocument()`'s result, and
the plaintext path not enforcing the same 100 KB limit) were confirmed as the last
real bugs; everything raised after that — anti-rollback, key versioning, granular
error states, encrypting `last_uri`, `FLAG_SECURE`, R8, exhaustive dependency
auditing — was reclassified as optional hardening for a *different, higher* threat
model (a compromised device, a malicious keyboard, forensic tools), not gaps in the
one this app actually targets: keep a `.txt` file unreadable to another app or another
device, and don't lose or corrupt data through careless error handling. Against that
narrower, honest threat model, the review's own conclusion was that the architecture
— `CryptoManager` + `NotepadViewModel` + SAF + DataStore — is "already pretty clean."
That's a reasonable place to stop iterating on this particular audit thread.

### v1.0.0 — stabilization pass
Fixed: **files opened from the in-app document list showed up as `untitled.txt`** —
`queryDisplayName()` queried a `ContentProvider`, which `file://` URIs don't have; the
name now comes from the path. **Save-As of an over-100 KB buffer** reported a generic
"Could not save" (it hit `encrypt()`'s last-resort guard); it now shows the same
"File too large" toast as `save()`. Fastlane descriptions corrected: they claimed the
key "never leaves secure hardware" (not guaranteed, see `CryptoManager`), that
renaming the app invalidates the key (only the `applicationId` matters), and that an
undecryptable file "opens as-is" (it is refused with a warning).

Known and accepted: if Android kills the app process while the system Save-As picker
is open (rare, low-memory devices), the unsaved buffer is lost — the text isn't kept
in saved instance state, since a 100 KB note would risk exceeding the Binder
transaction limit.

## Tests

`./gradlew testDebugUnitTest` runs plain JVM tests (no device, no Robolectric):
- `CryptoManagerTest` — via the internal `encryptWith`/`decryptWith`, which take the
  key as a parameter, so a software AES key stands in for the Keystore one.
- `DocumentCodecTest` — bounded read and encrypted/legacy classification.
- `NotepadUiStateTest` — derived counts, find/replace match counting.
- `StringResourcesTest` — same keys and placeholders in `values`, `values-fr`,
  `values-es`; no unescaped apostrophe.

The ViewModel/SAF/Keystore glue isn't unit-tested (it would need an emulator). Before
tagging a release, run through this on a real device, **installing over the previous
version** (never uninstall — that destroys the key):
1. Notes saved by the previous version still open.
2. New → type → Save → pick a location → reopen via Open → Browse device storage.
3. Edit → Save (no picker) → relaunch the app: the last note is restored.
4. Edit → New → "Save first" on an untitled buffer, then cancel the picker: the
   dialog stays up, nothing is lost.
5. Open a plain `.txt` from another app: opens as text; saving it encrypts it.
6. Find/replace, wrap and line-number toggles, system Back closing each layer.

## Releasing
1. Merge to `main` with CI green, run the checklist above.
2. Tag and push: `git tag v1.0.0 && git push origin v1.0.0`. CI builds, tests and
   publishes the GitHub Release with `angerona-nota.apk`.
