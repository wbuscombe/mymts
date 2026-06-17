#!/usr/bin/env bash
# Build the MyMTS desktop app (PyInstaller, windowed onedir + macOS .app).
#
# Uses the helper venv (fastapi/uvicorn/yt-dlp + mymts_helper). The extra build
# deps are PyInstaller + the tray stack (pystray/Pillow). Output is gitignored:
#   macOS:          tools/desktop/dist/MyMTS.app
#   Linux/Windows:  tools/desktop/dist/MyMTS/MyMTS[.exe]
#
# Signing/notarization is post-build and GATED on credentials — see sign-macos.sh
# (run it after this, or it is invoked by CI when secrets are present).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV="$HERE/../../helper/.venv"
PY="$VENV/bin/python"

if [ ! -x "$PY" ]; then
    echo "helper venv not found at $VENV"
    exit 1
fi

# Ensure the build deps are present (idempotent).
"$PY" -m pip install --quiet pyinstaller pystray Pillow

cd "$HERE"
echo "==> building (windowed onedir) ..."
"$VENV/bin/pyinstaller" mymts-helper.spec --noconfirm --clean

case "$(uname -s)" in
    Darwin) echo "==> built: $HERE/dist/MyMTS.app   (open it: open '$HERE/dist/MyMTS.app')" ;;
    *)      echo "==> built: $HERE/dist/MyMTS/MyMTS" ;;
esac
