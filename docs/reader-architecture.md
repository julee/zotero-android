# Reader architecture (Route B)

Reference for the custom reader. Operational rules live in `../CLAUDE.md`.

## Overview

This fork replaces the commercial **PSPDFKit** viewer with the in-development **web
Zotero Reader** (PDF.js/EPUB.js in a WebView), so PDF/HTML/EPUB all open through one
engine. On top of it is a custom **crop reading mode**: one cropped page per screen
(whitespace margins trimmed to maximize content), preserved across page turns, with
per-file odd/even crop rects.

- New reader: `app/src/main/java/org/zotero/android/screens/reader/`
- Old PSPDFKit reader (kept as a reference, e.g. for sidebar state-hoisting):
  `app/src/main/java/org/zotero/android/pdf/`

## Bundle pipeline (why editing `reader/src` does nothing on its own)

The reader is a git **submodule** at `reader/` (local branch `crop-reading-mode`). The app
does not load submodule source; it loads a built bundle `app/src/main/assets/reader/reader.zip`.

- `reader.zip` and most `app/src/main/assets/*` bundled dirs are **gitignored**, generated
  locally by `scripts/bundle_*.{sh,py}`. The app builds fine without the others
  (translation/citation/styles features just go absent); only `reader/` is needed for PDF.
- Rebuild after editing reader JS: `bash scripts/rebuild_reader_fast.sh` (webpack the
  android bundle, repackage `reader.zip`, bump `reader_hash.txt`). Then reinstall.
- On device, `ReaderLoader._updateFromBundle` extracts `reader.zip` → `files/reader`
  **only when `reader_hash.txt` differs** from the stored hash (hash-gated). The fast
  rebuild bumps the hash each run, so rebuild+reinstall re-extracts. If hash and content
  ever drift (stale extracted files), delete `files/reader`/`files/runningReader` to force
  re-extraction (`checkFolderIntegrity` resets the stored hash when the dir is empty).
- Per document open, `ReaderViewModel` (~line 433) copies `files/reader` →
  `files/runningReader`; the WebView loads `runningReader/view.html` via a
  `WebViewAssetLoader` `/local/` path handler (`ReaderWebViewHandler`).

CI vendors `reader.zip` into the repo (force-added past gitignore) so it can build a
working APK without initializing submodules.

## `reader/src/pdf/pdf-view.js` (most-customized file)

- **Crop reading mode**: `_cropReadingMode`, `_cropConfig {mode, odd, even}`, `_applyCrop`,
  `_applyCropRectNormalized` (scale + direct scroll + transform residual for centering).
  Content bbox autodetection: `_getContentBBoxPdf` (canvas pixel scan) with per-page memo
  `_autoCropCache`; `_getContentBBoxNorm`. Smooth turns: `_handlePageChanging` +
  `_beginCropTransition`/`_endCropTransition` (hide `#viewer` during relayout).
- **Manual crop editor**: `enterCropEdit` / `_buildCropOverlay` (4 draggable edges, save
  per page parity).
- **Tap navigation**: `_handleTapNavStart`/`_handleTapNavEnd` — left/right thirds =
  prev/next page, middle = toggle UI.
- **Swipe navigation**: `_handleSwipeStart`/`_handleSwipeEnd` — horizontal flick turns
  pages. Uses **touch** events, not pointer: a drag is reported as `pointercancel` (not
  `pointerup`), so the tap handler never sees it. Requires fast (<600ms), clearly
  horizontal gesture; clears any accidental text selection.
- **Native selection sync**: `_handleNativeSelectionChange` (debounced) → `_syncNativeSelection`.
  Android finger selection is handled natively by the browser and bypasses the reader's
  pointer selection, leaving `_selectionRanges` empty (no popup/highlight). This bridges
  the native DOM selection into the reader's selection model.
- **Crop-mode navigation**: `navigate()` resolves a target to a page via
  `_resolveLocationPageIndex` (annotationID / position / pageIndex) → `_cropNavigateToPage`
  (sets `currentPageNumber`, which re-crops via `_handlePageChanging`). Makes sidebar
  annotation/outline taps land on the right page in crop mode.

## Kotlin ↔ JS bridge

- `ReaderWebViewHandler` — WebMessage channel + `evaluateJavascript`; serves runningReader.
- `ReaderWebCallChainExecutor` — issues JS calls (`select`, `navigate`, `setTool`, crop
  config, …) and handles inbound events.
- `ReaderViewModel` — state, Realm DB, EventBus. `setViewStats` consumes the reader's view
  stats (`pageIndex`, `outlinePath`) and stores `currentPageIndex` for outline sync. Date
  parsing uses `iso8601WithFractionalSeconds` (mismatch here crashed highlight save).
  Per-file crop config persists via `Defaults` (key prefix `readerCropConfig_`).

## Sidebar

- `ReaderScreen` hoists `annotationsLazyListState` / `outlineLazyListState` /
  `thumbnailsLazyListState` so scroll positions survive the sidebar being closed/reopened
  (it is recreated via `AnimatedContent`, not hidden — same approach as the old PSPDFKit
  reader). Thumbnail bitmaps are cached in `ReaderThumbnailsViewModel`.
- Outline auto-scrolls to the current page's section: `ReaderOutlineSidebar` flattens the
  visible (non-collapsed) outline rows and best-matches by `location.position.pageIndex`
  ≤ `viewState.currentPageIndex`; `LaunchedEffect` scrolls there (also follows live page
  changes). Page-before-first-section → scroll to top.

## Full-bleed layout

PDF/HTML draw into the display-cutout and nav-bar areas
(`shouldIncludeTopBarAndNavBarPaddings = false`); the top app bar floats over content.
Because the content has no top inset, the **sidebar** must inset itself below the status
bar **and** the top app bar (`statusBars + TopAppBarDefaults.TopAppBarExpandedHeight`) or
its tabs hide behind the bar. EPUB keeps normal system-bar insets.
