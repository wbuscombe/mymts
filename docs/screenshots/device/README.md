# Native-TV hero shots — operator drop-in slots

These are the **"this is the real thing running on my wall"** credibility shots — the
native Android TV app on the office Onn 4K, driving a real TV. An automated tool can
capture the LAN **web** wall (see [`../web/`](../web/)), but **only the operator can
capture the native panel** — Claude Code can't see the TV.

The files here are **labeled placeholders**. Replace each one with your real capture,
**keeping the same filename**, and it appears automatically in the top-level
[`README.md`](../../../README.md) gallery.

## The shots the README expects

| File | What to capture |
|---|---|
| `wall-hero.png` | The **full wall** on the TV — feed + video grid + ticker, with live channels playing and the ticker mid-stride. The money shot. |
| `cards-closeup.png` | A **close-up of the ticker on LIVE data** — real market quotes **and the bespoke per-sport cards (PGA leaderboard / UFC fight / Tennis match / F1 race)**. These are the headline live-data states the automated demo gallery **cannot** show. |
| `office-in-situ.png` | The TV **on the wall, in the room** — a phone photo showing it running ambient. The "it's actually mounted and on" shot. |

> Optional per-sport breakouts: if you want individual cards, add `ticker-pga-live.png` / `ticker-ufc-live.png` / `ticker-tennis-live.png` / `ticker-f1-live.png` (or a combined `ticker-sports-live.png`) and reference them in the top README. Same live-data, same drop-in rule.

## Why these are device/live shots (the demo can't produce them)

The automated `../web/` gallery runs against **demo/phantom mode**, which serves clearly-labeled **SAMPLE** markets, three **team** games (MLB/NBA/NHL), and a fixture feed — deliberately no live upstreams. So the demo gallery covers the **structure** (the menu, the three ticker modes, the news-expand interaction, the honest SAMPLE pills), but it **cannot** show:

- the **bespoke per-sport cards** (PGA/UFC/Tennis/F1) — they only render on **live ESPN data**;
- **real market quotes** (live, no SAMPLE pill);
- **real channels actually playing** in the grid.

Those are exactly the live-data credibility shots above — yours to capture on the real wall.

## How to capture

- **A phone photo of the TV** — easiest for `wall-hero` and `office-in-situ` (gets the real panel + room).
- **An on-device screencap** — pixel-perfect for `wall-hero` / `cards-closeup` (no bezel/glare):
  ```bash
  # from a machine paired to the box (the operator's deploy host):
  adb -s <DEVICE> exec-out screencap -p > wall-hero.png
  ```
  (`<DEVICE>` is the box's adb target — a placeholder; never commit the real address.)

## Before you commit

- **Keep the filenames** above so the README picks them up (or update the paths in the top README if you prefer different names).
- **Topology-clean:** the shot must not show any real LAN IP, hostname, or the operator's
  domain (a wall/room photo is fine; if a settings/diagnostic overlay with an address is on
  screen, dismiss it first). Same audience-aware-docs rule as the rest of the repo.
- PNG or JPG both fine — if you save as `.jpg`, just update the extension in the top README's
  image paths (3 lines) to match.

Until you drop the real shots in, the placeholders render in their place so the README is
complete-shaped immediately and gets richer when you add them.
