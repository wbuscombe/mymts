# PyInstaller spec — MyMTS desktop app (tray launcher, cross-platform onedir).
#
# Bundles the launcher + the helper package + its data (migrations, seeds) + the
# web client + uvicorn/yt-dlp + the tray stack (pystray/Pillow) into a windowed
# (no-console) onedir app. On macOS it is wrapped into MyMTS.app (a menu-bar
# agent). The SAME spec is run by CI on Windows/Linux to produce native output.
#
# Build:  pyinstaller mymts-helper.spec --noconfirm        (from this dir)
# (build/ and dist/ land here and are gitignored.)

import sys

from PyInstaller.utils.hooks import collect_all, collect_data_files, collect_submodules

import os

# SPECPATH = the dir holding this spec (tools/desktop).
REPO_ROOT = os.path.abspath(os.path.join(SPECPATH, "..", ".."))
WEB_DIR = os.path.join(REPO_ROOT, "web")

datas = []
binaries = []
hiddenimports = []

# The web client (served at /app/) and the helper package data (migrations +
# seeds, read via importlib.resources).
datas += [(WEB_DIR, "web")]
datas += collect_data_files("mymts_helper")
hiddenimports += collect_submodules("mymts_helper")

# uvicorn loads its protocol/lifespan/loop impls dynamically.
for pkg in ("uvicorn", "yt_dlp", "pystray"):
    d, b, h = collect_all(pkg)
    datas += d
    binaries += b
    hiddenimports += h

datas += collect_data_files("certifi")  # CA bundle httpx needs at runtime

# The launcher's sibling modules (yt-dlp self-update) live next to launch.py.
hiddenimports += ["ytdlp_update"]

# Tray backend imports (resolved dynamically by pystray's backend selector).
hiddenimports += ["PIL.Image", "PIL.ImageDraw"]
if sys.platform == "darwin":
    hiddenimports += ["pystray._darwin", "AppKit", "Foundation", "objc", "PyObjCTools.MachSignals"]
elif sys.platform == "win32":
    hiddenimports += ["pystray._win32"]
else:
    # Linux: appindicator/gtk/xorg backends — best-effort; the launcher falls
    # back to HEADLESS mode when no tray backend / display is available.
    hiddenimports += ["pystray._xorg", "pystray._appindicator", "pystray._gtk"]

a = Analysis(
    ["launch.py"],
    pathex=[SPECPATH],   # so the sibling ytdlp_update module is importable
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
    name="MyMTS",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,            # --windowed: no console window
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,   # signing is done post-build (gated) by build.sh / CI
    entitlements_file=None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="MyMTS",
)

# macOS: wrap the onedir into a proper .app. LSUIElement = a menu-bar agent (no
# Dock icon); the tray icon is the UI.
if sys.platform == "darwin":
    app = BUNDLE(
        coll,
        name="MyMTS.app",
        icon=None,            # default icon for now (a branded .icns is follow-up polish)
        bundle_identifier="com.mymts.desktop",
        info_plist={
            "LSUIElement": True,
            "CFBundleName": "MyMTS",
            "CFBundleDisplayName": "MyMTS",
            "NSHighResolutionCapable": True,
        },
    )
