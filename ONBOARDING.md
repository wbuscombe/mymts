# MyMTS — Onboarding (clone → running, fast)

Welcome. This gets you from a fresh `git clone` to **MyMTS running locally in
demo mode** with **no access to anything private** — no NAS, no keystore, no
TLS cert, no `.env`, no API keys. The operator's real deployment is not needed
or referenced; you run your own local copy on mock data.

> Prefer your AI assistant to do the setup? Feed it the prompts in
> [`docs/onboarding/`](docs/onboarding/) — `ONBOARD-01-SETUP.md` (demo) and
> `ONBOARD-02-LOCAL-HELPER.md` (fuller, real public data). They're written for
> *your* Claude to follow on *your* machine.

> **⚠️ Cloned before 2026-06-12?** The git history was rewritten once on that
> date (a one-time cleanup of internal addresses from old commits). Your old
> clone has diverged and **`git pull` will not reconcile**. Re-clone fresh:
> `git clone https://github.com/wbuscombe/mymts.git`. If you have local work on
> the old clone, save it as a patch first (`git diff > /tmp/mywork.patch`) and
> re-apply it on the fresh clone. This is a one-time event; normal pulls work
> from here on.

## What MyMTS is (1 line)
A native **Android TV** ambient news wall (Kotlin / Compose for TV / Media3) +
a minimal **Python/FastAPI helper** that aggregates feeds and resolves live
streams. See [`ARCHITECTURE.md`](ARCHITECTURE.md) and the foundation docs.

## Prerequisites (per component)
- **Helper** (Python): Python 3.13 + [`uv`](https://github.com/astral-sh/uv) (`brew install uv` / `pipx install uv`).
- **App** (Android): JDK 17+ and the Android SDK — Android Studio is easiest (it bundles a JBR + SDK + an emulator). An Android **TV** emulator (API 33+) or a real Android TV device.
- Git. That's it — no secrets to obtain.

---

## Fast path — demo mode (mock data, zero network egress)

**1. Run the helper in phantom mode** (mock channels + feed + ticker, no NAS, no outbound):
```bash
cd helper
PHANTOM_MODE=1 PORT=8091 uv run python -m mymts_helper
# → serving on http://localhost:8091  (HTTP, no cert needed)
```
Verify in another shell:
```bash
curl -fsS http://localhost:8091/health      # 200 + build info
curl -fsS http://localhost:8091/api/feed     # preloaded mock feed items
curl -fsS http://localhost:8091/api/channels # all 49 seeded channels (direct-HLS + YouTube + 2 C-SPAN/.gov)
```
You can also open the **web client** at <http://localhost:8091/app>.

**2. Build + run the TV app**, pointed at your local helper.
The app's helper URL defaults to `http://localhost:8091`, so no config is needed
for the demo. *(A **stock** build with no configured URL instead shows a one-time
first-run setup screen to enter + reachability-test a helper address — you can also
change it any time under Settings → "Helper URL"; no rebuild needed.)* On an
**emulator**, the host's `localhost` is `10.0.2.2`, so pass:
```bash
# from repo root, with an Android TV emulator running:
./gradlew :app:installDebug
adb shell am start -n com.mymts/.MainActivity --es helper "http://10.0.2.2:8091"
```
(On a real device on the same Wi-Fi as your dev machine, use your machine's LAN
IP instead of `10.0.2.2`.)

You should see the wall: a 2×2 video grid (mock channels), a left feed pane
(mock headlines), and the top ticker — all on synthetic data.

---

## Fuller path — a local helper on REAL public data (optional)

The helper's live sources are all **keyless public** (Yahoo Finance, ESPN, RSS,
CoinGecko) and the channel HLS streams are public — so you can run the helper in
**normal** mode locally and get real feed/ticker/scores with **no NAS and no
secrets**:
```bash
cd helper
PORT=8091 uv run python -m mymts_helper     # PHANTOM_MODE unset → live public data
```
Or run it as the hardened container — a one-command **clone-to-running** path that
brings up the helper (migrations + seed on a fresh DB; `/health` + `/api/channels` +
`/app/` all serve):
```bash
cd helper && cp .env.example .env && docker compose up --build   # → http://localhost:8091
```
Point the app at it the same way. See
[`docs/onboarding/ONBOARD-02-LOCAL-HELPER.md`](docs/onboarding/ONBOARD-02-LOCAL-HELPER.md)
for details (and to override the helper URL via `local.properties`).

---

## Run the tests
```bash
cd helper && uv run pytest          # helper suite
./gradlew :app:testReleaseUnitTest  # app suite
```

## What you can play with
- The whole wall UI, the menu (D-pad / arrow keys on the emulator), the channel
  picker, the settings, the ticker — all on mock or real-public data.
- The helper API + the static web client.
- The full source of both components.

## What you can't do (by design)
- Connect to the operator's NAS, boxes, or real deployment — you don't have (and
  don't need) their config, keystore, cert, or `.env`. Everything above runs on
  **your** machine only.
- Sign a release build — that needs the operator's keystore (gitignored, never
  shared). Debug builds (`installDebug`) need no signing config.

If demo mode doesn't boot, see the troubleshooting at the end of
[`docs/onboarding/ONBOARD-01-SETUP.md`](docs/onboarding/ONBOARD-01-SETUP.md).
