# On-device debugging

Reference for verifying reader behavior. Operational rules live in `../CLAUDE.md`.

## Why CDP

Real text selection and many gestures only behave correctly in a real WebView, and
synthetic `adb` touches don't reliably reproduce PDF.js native selection. Drive/inspect
the live reader WebView with the **Chrome DevTools Protocol**.

```sh
PID=$(adb shell pidof org.zotero.android.debug | tr -d '\r')
adb forward tcp:9222 localabstract:webview_devtools_remote_$PID
# list pages (the reader is titled "Zotero Reader View"):
curl -s http://localhost:9222/json
# eval JS in a page; if it times out, the JS main thread is blocked:
node scripts/cdp_eval.js "<wsUrl>" "<jsExpr>"
```

Entry points in the page:
- live PDFView instance: `window._view._view`
- PDF.js app: `window._view._view._iframeWindow.PDFViewerApplication`
  (e.g. `.pdfViewer.currentPageNumber`, `.pdfDocument.getOutline()`)

`scripts/cdp_*.js` are dev-only helpers (not shipped):
- `cdp_eval.js` — evaluate a JS expression (timeout = blocked thread).
- `cdp_longpress.js`, `cdp_repro.js` — dispatch synthetic touch sequences.

For the **native Compose UI** (sidebar, toolbars), use `adb shell uiautomator dump` +
`adb shell screencap`. Note synthetic `adb input swipe` does fire touch events the swipe
handler reads (the swipe page-turn was verified this way), but cannot trigger native PDF
text selection.

## Device notes

- Primary test device: Honor/Huawei foldable **LRA-AN00** (folded = overlay sidebar mode,
  unfolded = side-by-side mode).
- Some Huawei/Honor devices ship **encrypted logcat** (HKS/HKE lines). Enable full logging
  via the device's logging dial code before `adb logcat` output is readable.
- The reader runs the WebView in a child process; `run-as` can land in that process's cwd,
  so prefer absolute paths and re-check if `run-as ls`/`cd` behaves oddly.
