# tools/capture — record a demo of the wall

One command records the **live MyMTS wall** (the native app on your configured Android TV device)
to a video file, using [**scrcpy**](https://github.com/Genymobile/scrcpy) — a
free, open-source tool that captures the device **framebuffer** over the existing
adb connection. Native quality, no camera, no new hardware, no paid service.

> scrcpy **READS the screen only** — it does not deploy, install, or change
> anything on the box (it pushes an ephemeral server to `/data/local/tmp` that it
> removes on exit). Safe to run any time the wall is up.

The **script** is bulletproof (fails fast on every precondition). The
**performance** — driving the menu walkthrough — is yours: you perform the demo
with the remote (or the scrcpy mirror window) while it records.

## Run it

```bash
make record-demo                 # or: tools/capture/record-demo.sh
```

That's it. It records to `tools/capture/output/mymts-demo-<timestamp>.mkv`
(gitignored — large binaries are never committed). Perform the walkthrough, then
press **Ctrl-C** (or close the scrcpy window) to stop; the file finalizes on stop.

Useful flags (`tools/capture/record-demo.sh --help` for all):

| flag | effect |
|---|---|
| `--h265` | H.265 (better quality / smaller) instead of the H.264 default |
| `--mp4` | record `.mp4` instead of `.mkv` (universal, but a Ctrl-C'd mp4 can corrupt) |
| `--native` | record at native device res (no downscale; larger files) |
| `--bitrate 24M` | bump the bitrate (default 16M) |
| `--no-control` | pure recorder — the window can't drive the box (drive with the remote) |
| `--audio` | also capture device audio (default: off) |

`make record-demo ARGS="--h265 --native"` passes flags through make.

## Prerequisites

- **scrcpy installed** — `brew install scrcpy` (macOS) / your distro's package
  (Linux). scrcpy **2.0+** (the script checks and tells you if it's too old).
- **Your configured device is on** and reachable over adb (the same transport deploys use;
  the script reconnects it for you, or tells you how if it can't).
- **MyMTS is running on the box** — the *live native wall*, not the demo/phantom
  web client. The live wall is where the real channels play and the per-sport
  cards show. (Make sure the **helper is up** so the wall has real feed/ticker
  data.)
- The target serial comes from `MYMTS_DEPLOY_DEVICE` in the gitignored
  `scripts/deploy.local.env` — the same one the deploy uses. The
  script **hard-targets** it via scrcpy `-s`, so it can never grab the `.182` /
  `.158` boxes by accident.

## The shot list (a tight ~60–90s demo)

Drive this while it records — a consistent flow each time:

1. **The wall (~20s).** Sit on the main wall. Let the **video tiles play**, the
   **ticker crawl** across the top, and the markets→sports→news modes cycle.
   This is the money shot — live news video + the live ticker.
2. **Settings menu (~20s).** Open the menu and walk the sections: **Display & Fit**,
   **Layout & Feed** (grid rows × cols), the **sports-leagues** picker, and the
   **Ticker** controls (incl. the new **ticker-motion** flip/crawl toggle once
   it's on the box).
3. **Channel picker (~15s).** Open it — show the sectioned channel categories and
   the honest **live / on-the-TV-wall / offline** badges.
4. **A showcase beat (~15s).** Cycle a ticker mode, or land on a **per-sport card**
   (PGA / UFC / Tennis / F1) if one is live; let a SAMPLE/STALE pill be visible to
   show the honest-degradation discipline.

Keep it tight and deliberate — pause a beat on each view so it reads on playback.

## How to drive it

- **Physical remote** (recommended for D-pad menu navigation — it feels natural):
  just use the remote; scrcpy records the screen. Add `--no-control` so the
  mirror window can't send stray input.
- **The computer**: scrcpy forwards your keyboard/mouse to the box — click the
  mirror window, use arrow keys for the D-pad. Handy if the remote isn't nearby.

## The transport caveat (Wi-Fi vs USB)

scrcpy holds a **continuous** adb connection. On the flaky **Wi-Fi**
transport it can stutter or drop mid-recording. Mitigations:

- **USB cable** (best): plug the computer into the box if it's physically
  reachable — a rock-solid connection and the best quality, using a cable you
  already have (no new hardware). `adb` picks up the USB device; pass that serial
  via `--device` if needed.
- **Smooth Wi-Fi jitter**: `RECORD_VIDEO_BUFFER=200 make record-demo` adds a
  200 ms buffer (smoother capture at the cost of a little latency).

Recommend **USB if convenient**; Wi-Fi works with a buffer otherwise.

## ⚠️ Verify the output

scrcpy captures the framebuffer, so a **DRM / hardware-protected** video tile can
record as **black**. MyMTS plays free news streams (likely fine), but **don't
assume** — after recording, open the file and confirm the tiles show a picture:

```bash
open tools/capture/output/mymts-demo-<timestamp>.mkv   # macOS
vlc  tools/capture/output/mymts-demo-<timestamp>.mkv    # any
```

A black tile means that one channel is protected; the rest of the demo is still
good. The script prints this reminder + the file size after every run (and warns
if the file came out suspiciously small).

## Verify the script itself (no device needed)

```bash
make test-capture          # or: tools/capture/test-preflight.sh
```

Stubs scrcpy/adb to prove every preflight guard fires (scrcpy-missing → install
guidance, too-old → upgrade, placeholder/wrong-box → refused, not-connected →
reconnect guidance) and that it never `kill-server`s or backgrounds adb. The
scripts are `shellcheck`-clean.
