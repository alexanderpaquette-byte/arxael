#!/usr/bin/env bash
# Bundle the engine's bash CLI into the npm package. Run automatically on `npm pack`/`npm publish` (prepack),
# and by hand during dev. The npm `arxael` launcher delegates the daily commands (up/status/stop/logs/pull) to
# these; dev commands (verify/bench) are intentionally NOT shipped — they build from source.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/.."
mkdir -p "$HERE/scripts"
cp "$SRC/scripts/arxael" "$HERE/scripts/arxael"
cp "$SRC/scripts/reap-daemons.sh" "$HERE/scripts/reap-daemons.sh"
[ -f "$SRC/LICENSE" ] && cp "$SRC/LICENSE" "$HERE/LICENSE"   # npm shows it on the package page
chmod +x "$HERE/scripts/arxael" "$HERE/scripts/reap-daemons.sh" "$HERE/bin/arxael.js"
echo "bundled: scripts/arxael, scripts/reap-daemons.sh, LICENSE"
