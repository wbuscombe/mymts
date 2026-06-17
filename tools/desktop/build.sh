#!/usr/bin/env bash
# Build the MyMTS helper desktop spike (macOS, PyInstaller onedir).
#
# Uses the helper venv (which already has fastapi/uvicorn/yt-dlp + the
# mymts_helper package installed). Output is tools/desktop/dist/mymts-helper/
# (gitignored). This is a SPIKE build — unsigned, single-arch (this Mac).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV="$HERE/../../helper/.venv"

if [ ! -x "$VENV/bin/pyinstaller" ]; then
    echo "PyInstaller not found in $VENV"
    echo "Install it first:  $VENV/bin/python -m pip install pyinstaller"
    exit 1
fi

cd "$HERE"
echo "==> building (onedir) ..."
"$VENV/bin/pyinstaller" mymts-helper.spec --noconfirm --clean

BIN="$HERE/dist/mymts-helper/mymts-helper"
echo "==> built: $BIN"
echo "==> run:   $BIN   (then a browser opens http://127.0.0.1:8091/app/)"
