# CLAUDE.md

Personal fork of `zotero/zotero-android`. It replaces PSPDFKit with the web Zotero Reader
(PDF.js/EPUB.js in a WebView) plus a custom crop reading mode. New reader code lives in
`app/src/main/java/org/zotero/android/screens/reader/`.

## Build / install / run

- Dev variant is **devDebug** (applicationId `org.zotero.android.debug`).
- Install to a connected device: `./gradlew :app:installDevDebug`
- Build APK only: `./gradlew :app:assembleDevDebug`
  → `app/build/outputs/apk/dev/debug/app-dev-debug.apk`
- JDK **17**. Debug builds sign with the tracked `debug.keystore`; devDebug needs no extra
  keys (`pspdfkit-key.txt` etc. are optional, the build degrades gracefully).
- If a gradle command fails with "Operation not permitted" on `~/.gradle/...`, that's the
  sandbox blocking the wrapper lock — re-run with the sandbox disabled.

## Editing the reader (read before touching `reader/`)

`reader/` is a submodule, but the app loads a **built bundle**
`app/src/main/assets/reader/reader.zip` (gitignored, generated). Editing `reader/src/**`
has no effect until you rebuild:

1. `bash scripts/rebuild_reader_fast.sh`  (rebuilds reader.zip, bumps reader_hash.txt)
2. reinstall the app.

If the device still shows stale reader behavior, force re-extraction:
`adb shell run-as org.zotero.android.debug rm -rf files/reader files/runningReader`, then
relaunch. See `docs/reader-architecture.md` for why.

## Releasing

Fork remote `fork` = `julee/zotero-android`; work branch `official-latest`. CI
`.github/workflows/release-apk.yml` builds devDebug and attaches the APK to a GitHub
Release on a **`v*` tag** push. To cut a release: bump `versionCode` in
`buildSrc/src/main/kotlin/BuildConfig.kt` (needed to install over an existing build),
commit, then `git tag -a vX.Y.Z -m "…" && git push fork vX.Y.Z`. The inherited upstream
`android.yml` is disabled on the fork (it needs secrets the fork doesn't have).

## Conventions

- The APK is ~170 MB (4 ABIs + PSPDFKit) — expected, not a bug.
- There is **DEBUG-only diagnostic code** (crash/freeze watchdog in `ZoteroApplication`,
  `DebugFileLog`, an adb-broadcast debug receiver wired through the reader VM/executor,
  `scripts/cdp_*.js`). Harmless at runtime; strip it before any clean/public distribution.
- Verify reader behavior on a real device — synthetic touches can't reproduce PDF.js text
  selection. See `docs/debugging.md`.

## Further docs

- `docs/reader-architecture.md` — reader engine map, bundle pipeline internals, sidebar,
  full-bleed layout.
- `docs/debugging.md` — on-device CDP debugging and device-specific notes.
