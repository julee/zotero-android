#!/bin/bash
# Fast reader rebuild: webpack the android bundle only (skips the slow pdf.js gulp build,
# which is unchanged), then repackage into app assets. Run pdf.js build via
# bundle_reader_local.sh only when pdf.js itself changes.
set -eo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
READER="$ROOT/reader"
DEST="$ROOT/app/src/main/assets/reader"

cd "$READER"
NODE_OPTIONS=--openssl-legacy-provider npx webpack --config-name android

mkdir -p "$DEST"
rm -f "$DEST/reader.zip"
cd "$READER/build/android"
zip -rq "$DEST/reader.zip" .
echo "reader_$(date +%s)" > "$DEST/reader_hash.txt"
echo "Reader bundle repackaged into $DEST/reader.zip"
