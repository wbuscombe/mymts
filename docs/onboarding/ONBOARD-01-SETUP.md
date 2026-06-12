# Claude — MyMTS: Local Setup + Demo Mode (ONBOARD-01)

> **You are helping a new collaborator stand up MyMTS LOCALLY on THEIR machine, from a fresh clone, in DEMO MODE. Demo mode runs on mock data with ZERO secrets, ZERO network egress, and NO connection to anyone else's infrastructure. The end state: the collaborator has the MyMTS wall UI running locally. Work ONLY on the collaborator's own machine. They have NONE of the project author's secrets, keystore, TLS cert, `.env`, NAS, or deployment — and they need none. Never try to obtain, fabricate, guess, or work around any secret or credential. Never connect to (or reference as a target) any host that isn't the collaborator's own localhost. Use the placeholder/default config in the repo.**

## 0. Orient
- Confirm you're at the root of a fresh `mymts` clone (`ls` shows `app/`, `helper/`, `web/`, `ARCHITECTURE.md`, `ONBOARDING.md`).
- Read `ONBOARDING.md` and `.phantom.yml` — they define the demo contract.
- **MyMTS is multi-component:** a Kotlin/Compose **Android TV app** (Gradle) + a Python/FastAPI **helper** (uv) + a static **web client** (served by the helper). Detect the collaborator's OS (macOS / Linux / Windows) and tailor commands.

## 1. Prerequisites (install only what's missing)
- **Python 3.13 + `uv`** for the helper. Check `python3 --version` and `uv --version`; if `uv` is missing, install it (`brew install uv`, `pipx install uv`, or the official installer) — confirm with the collaborator before installing anything system-wide.
- **JDK 17+ and the Android SDK** for the app. The simplest path is **Android Studio** (bundles a JBR, the SDK, and an emulator). If they already have it, find `JAVA_HOME` and the SDK. They'll want an **Android TV emulator (API 33+)** or a real Android TV device.
- Do NOT install anything that requires the project author's accounts/keys — none are needed.

## 2. Config — templates only (no real secrets exist or are needed)
- The repo ships templates: `helper/.env.example` and `app/keystore.properties.example`. **For demo mode you don't need either** — demo mode needs no `.env` and no signing (debug builds aren't signed). If you copy `helper/.env.example` → `helper/.env`, leave it at defaults (`PHANTOM_MODE` will be set on the command line anyway). NEVER fill in real secret values — there are none to fill.
- The app's helper URL defaults to `http://localhost:8091` (the local helper). No config change is needed for demo. (If you ever want to override it, that's `MYMTS_HELPER_BASE_URL` in a gitignored `local.properties` — not needed here.)

## 3. Start the helper in PHANTOM (demo) mode
Phantom mode serves deterministic mock data and refuses ALL outbound network calls (a hard contract). Run:
```bash
cd helper
PHANTOM_MODE=1 PORT=8091 uv run python -m mymts_helper
```
The first `uv run` resolves the locked dependencies (from `uv.lock`) — let it. It should log that it's serving on `http://localhost:8091`.

**Verify (in a second terminal):**
```bash
curl -fsS http://localhost:8091/health       # expect HTTP 200 + build info
curl -fsS http://localhost:8091/api/feed       # expect mock feed items (non-empty)
curl -fsS http://localhost:8091/api/channels   # expect all ~21 seeded channels, status live
```
Open <http://localhost:8091/app> in a browser to see the **web client** on the mock data — that alone proves the helper demo works.

## 4. Build + run the TV app against the local helper
With an Android TV emulator running (or a device on the same network):
```bash
# from the repo root:
./gradlew :app:installDebug
# emulator: the host's localhost is 10.0.2.2 inside the emulator
adb shell am start -n com.mymts/.MainActivity --es helper "http://10.0.2.2:8091"
```
(Real device on the same Wi-Fi → use the dev machine's LAN IP instead of `10.0.2.2`. Still the collaborator's own machine — never anyone else's host.)

**End state:** the wall shows a 2×2 grid (mock channels), a feed pane (mock headlines), and the ticker — all synthetic.

## 5. Run the tests (optional, confirms a healthy setup)
```bash
cd helper && uv run pytest            # helper suite
./gradlew :app:testReleaseUnitTest    # app suite
```

## 6. Verification + troubleshooting
After this, **demo mode should boot and show the wall**. If not:
- **Helper won't start:** ensure `uv` is installed and you're in `helper/`; re-run `uv run pytest` to confirm the env resolves. Port busy → change `PORT` (and the app's `--es helper` URL to match).
- **`/api/feed` or `/api/channels` empty:** confirm `PHANTOM_MODE=1` was set on the command line (it preloads the fixtures).
- **App shows "helper unreachable" / empty feed:** the app can't reach the helper URL. On an emulator use `http://10.0.2.2:8091` (NOT `localhost`, which is the emulator itself). On a device use the dev machine's LAN IP. Confirm the helper is still running.
- **Anything asks for a secret/keystore/cert/NAS:** you don't have them and don't need them for demo — you're off the happy path. Debug builds need no signing; demo needs no `.env`. Re-read steps 2–4. Do NOT fabricate or hunt for credentials.

## Standards (for you, the assistant)
- Touch ONLY the collaborator's own machine. Never connect to, or reference as a target, any host that isn't their localhost/their own device.
- Never obtain, fabricate, guess, or work around any secret, key, cert, or credential. There are none in this repo and none are needed for demo mode.
- Use the repo's placeholder/default config. Don't invent IPs or hostnames.
- When done, tell the collaborator demo mode is up and point them at `ONBOARD-02-LOCAL-HELPER.md` if they want the fuller real-public-data experience.
