# Download & Run MyMTS — no Docker, no clone, no Python

MyMTS is a calm news wall: live news/sports/weather video tiles, a headline feed,
and a markets-and-scores ticker, all on one screen in your browser. This guide
gets it running on your own computer in a couple of minutes. **You don't need to
be a developer** — no Terminal, no installing anything else.

## 1. Download the app for your computer

Go to the project's **Releases** page and download the file for your operating
system:

| Your computer | Download |
|---|---|
| **Mac** | `MyMTS-macos.zip` |
| **Windows** | `MyMTS-windows.zip` |
| **Linux** | `MyMTS-linux.tar.gz` |

Double-click the downloaded file to unzip it.

## 2. Open it

- **Mac:** open **MyMTS.app**.
- **Windows:** open the unzipped folder and run **MyMTS.exe**.
- **Linux (desktop):** run the **MyMTS** program. (On a headless server, run it in
  a terminal — it prints a web address to open; see "Running on a server" below.)

MyMTS starts quietly in your **menu bar (Mac)** or **system tray (Windows)** — a
small icon, no big window. Your browser opens automatically to the wall. To open
it again later, or to quit, click the tray icon: **Open MyMTS** / **Quit**.

That's it. The wall is running at **http://127.0.0.1:8091/app/** on your own
computer.

## "Unidentified developer" / "Windows protected your PC"

These early builds aren't code-signed yet, so your computer shows a one-time
caution before running an app it doesn't recognize. It's safe to allow it:

- **Mac:** right-click **MyMTS.app** → **Open** → **Open**. (Just the first time.)
- **Windows:** on the blue "protected your PC" box, click **More info** → **Run
  anyway**. (Just the first time.)

## What it does on your computer

- Runs entirely **on your own machine** — the wall is served at `127.0.0.1`
  (your computer only), not exposed to the internet or your network.
- Keeps its small database of channels/feeds in your user folder
  (Mac `~/Library/Application Support/MyMTS`, Windows `%LOCALAPPDATA%\MyMTS`,
  Linux `~/.local/share/MyMTS`). Delete that folder to reset.
- Plays public live streams directly from their sources; it doesn't sign you in
  to anything and has no account.

## Good to know

- **Where you are matters for some channels.** The built-in channel list was put
  together from a US vantage point — a handful of streams may not play outside
  that region. Most do.
- **YouTube channels stay current automatically.** The app quietly keeps its
  YouTube support up to date (downloading the latest helper from the official
  Python package index) so those channels keep working over time. If you're
  offline it just uses what it shipped with. The non-YouTube channels don't need
  this.
- **Quitting:** click the tray/menu-bar icon → **Quit**. Closing the browser tab
  does **not** quit it (the tray icon is still there) — that's so you can reopen
  the wall without restarting.

## Running on a server (headless)

If you run it on a machine with no screen (a home server), MyMTS detects there's
no desktop and runs in **server mode**: no tray, no auto-open — it just prints the
web address to open from another device on your network. You can also force this
with the `MYMTS_HEADLESS=1` environment variable.

## Want the full TV-wall experience?

This desktop app runs the **web** wall. The Android TV app (the 10-foot couch
experience on a TV) and self-hosting the backend as a service are separate paths —
see the main `README.md`.
