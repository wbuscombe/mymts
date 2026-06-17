# PyInstaller spec — MyMTS helper desktop spike (macOS, onedir).
#
# Bundles the launcher (tools/desktop/launch.py) + the helper package + its
# data (migrations, seeds) + the web client into a runnable onedir app. onedir
# (not onefile) keeps the build fast and the contents inspectable for a spike.
#
# Build from this directory:  pyinstaller mymts-helper.spec --noconfirm
# (build/ and dist/ land here and are gitignored.)

import os

from PyInstaller.utils.hooks import collect_all, collect_data_files, collect_submodules

# SPECPATH is injected by PyInstaller = the dir holding this spec (tools/desktop).
REPO_ROOT = os.path.abspath(os.path.join(SPECPATH, "..", ".."))
WEB_DIR = os.path.join(REPO_ROOT, "web")

datas = []
binaries = []
hiddenimports = []

# The web client, served by the helper at /app/ — bundle the whole tree at <bundle>/web.
datas += [(WEB_DIR, "web")]

# Helper package DATA read via importlib.resources at runtime: migrations/*.sql
# and channels/feeds seed.json. collect_data_files grabs the non-.py files.
datas += collect_data_files("mymts_helper")
hiddenimports += collect_submodules("mymts_helper")

# uvicorn loads its protocol/lifespan/loop implementations dynamically, so a
# plain import-scan misses them — collect everything.
u_datas, u_bins, u_hidden = collect_all("uvicorn")
datas += u_datas
binaries += u_bins
hiddenimports += u_hidden

# yt-dlp dynamically imports its (many hundred) site extractors — the YouTube
# resolver needs them. collect_all is the only reliable way to freeze it; it is
# the dominant contributor to bundle size (surfaced in the spike report).
y_datas, y_bins, y_hidden = collect_all("yt_dlp")
datas += y_datas
binaries += y_bins
hiddenimports += y_hidden

# certifi ships a CA bundle httpx needs at runtime.
datas += collect_data_files("certifi")

a = Analysis(
    ["launch.py"],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["tkinter", "PyInstaller"],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="mymts-helper",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,          # console executable is fine for the spike
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,      # native arch (this Mac); not a universal2 build
    codesign_identity=None,  # unsigned spike — Gatekeeper friction is reported
    entitlements_file=None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="mymts-helper",
)
