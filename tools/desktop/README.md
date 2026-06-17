# MyMTS desktop — launchable helper executable

Package the MyMTS helper (the Python/FastAPI backend) as a **self-contained
desktop executable** so anyone can run the web wall **without cloning the repo,
installing Python, or running Docker**. The executable starts the helper on
`http://127.0.0.1:PORT`, serves the bundled web client at `/app/`, seeds a
default lineup into a per-user data dir, and opens the browser to the wall.

`localhost` is a browser **secure context**, so this runs over plain **HTTP with
no TLS** — and an `http://127.0.0.1` page is still allowed to load the channels'
`https://` video streams. No certificates, no trust prompts.

## Build (macOS)

Uses the helper's virtualenv (which already has FastAPI/uvicorn/yt-dlp + the
`mymts_helper` package). PyInstaller is the only extra build dep:

```sh
helper/.venv/bin/python -m pip install pyinstaller
tools/desktop/build.sh
```

Output (gitignored):
- macOS: `tools/desktop/dist/MyMTS.app`
- Linux/Windows (CI): `tools/desktop/dist/MyMTS/MyMTS[.exe]`

## Run

**macOS / Windows (tray app):** open `MyMTS.app` (macOS) or run `MyMTS.exe`
(Windows). It runs as a **menu-bar / system-tray** agent — no console window —
with a tray menu: **Open MyMTS** (opens the wall) and **Quit**. The browser
auto-opens to `http://127.0.0.1:8091/app/` once on launch.

```sh
open tools/desktop/dist/MyMTS.app      # macOS
```

**Linux / headless (no display) / `MYMTS_HEADLESS=1`:** the helper runs in the
foreground, prints the reachable URL, and does NOT open a browser or a tray —
the server-mode fallback for headless hosts. Open the printed URL yourself.

The seeded SQLite DB and all writable state live in the per-user data dir
(macOS `~/Library/Application Support/MyMTS/`, Windows `%LOCALAPPDATA%/MyMTS`,
Linux `~/.local/share/MyMTS`) — **never** inside the (read-only) bundle.

## What's bundled

- the launcher (`launch.py`) — reuses the helper's own `create_app()`; adds no
  server logic, just a localhost bind + a browser-open convenience;
- the helper package + its data (migrations, channel/feed seeds);
- the web client (served at `/app/`);
- FastAPI / uvicorn / **yt-dlp** + deps.

## Signing & notarization (gated on credentials)

Signing is **automatic when credentials are present** and a no-op otherwise (the
build still succeeds; the app is left PyInstaller adhoc-signed). `build.sh` runs
`sign-macos.sh`, which signs + notarizes + staples when these are set (env
locally / CI secrets), else prints the unsigned caveat:

```
CODESIGN_IDENTITY="Developer ID Application: <Name> (<TEAMID>)"   # enables signing
# and ONE notarization set:
AC_API_KEY_ID, AC_API_KEY_PATH, AC_API_ISSUER                     # App Store Connect API key (preferred)
# or
APPLE_ID, APPLE_TEAM_ID, APPLE_APP_PASSWORD                       # Apple-ID app-specific password
```

Set `CODESIGN_IDENTITY` **explicitly** if more than one Developer ID Application
identity is in the keychain (auto-detect picks the first). In **CI**, also provide
the cert itself as a base64-encoded `.p12` secret `MACOS_CERT_P12` (+
`MACOS_CERT_PASSWORD`) — the release workflow imports it into a temp keychain
before signing; without it the macOS build is unsigned (and the job still passes).

Windows (`sign-windows.ps1`): set `WINDOWS_CERT_PFX` (+ `WINDOWS_CERT_PASSWORD`)
to sign with `signtool`; otherwise unsigned (SmartScreen caveat).

**Unsigned-build first-run (users):** a downloaded *unsigned* app trips Gatekeeper
("unidentified developer") / SmartScreen. Once: macOS — right-click the app →
**Open** → **Open**; Windows — **More info** → **Run anyway**.

## Spike findings (macOS, Apple Silicon)

This started as a proof-of-concept to de-risk the approach. Validated:

- **It works.** Cold start ≈ 9 s to a healthy server (first run includes DB
  seeding + the heavy frozen import); the wall serves at `/app/`, the ~37 seed
  channels load, the DB lands in the user-data dir, clean shutdown.
- **Bundle size ≈ 53 MB** (`onedir`). yt-dlp (~12 MB of extractors) is the
  dominant contributor, then the Python runtime + `libcrypto`/`pydantic_core`.
- **yt-dlp survives freezing.** The YouTube channels resolve from inside the
  frozen bundle (live ones go live; not-currently-live ones report honest
  offline) — no missing-extractor breakage.
- **Launch UX / Gatekeeper:** a locally-built binary runs with no Gatekeeper
  prompt (no quarantine attribute); a *downloaded* unsigned/adhoc binary will
  hit the "unidentified developer" prompt until it is signed + notarized (or
  right-click → Open). PyInstaller adhoc-signs the arm64 binary so it runs at
  all.

## Caveats

- **Channel geo:** the default lineup was sourced from a US vantage; some streams
  may not resolve outside that region. The lineup is the helper's seed.
- **yt-dlp staleness:** a frozen yt-dlp can't self-update, so YouTube channels
  rot as YouTube changes extraction until a newer build ships. The ~30
  direct-HLS channels are unaffected. (A self-update path addresses this.)
