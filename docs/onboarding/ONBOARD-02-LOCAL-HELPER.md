# Claude — MyMTS: Local Helper on Real Public Data (ONBOARD-02, optional)

> **You are helping a new collaborator run the MyMTS helper LOCALLY in NORMAL mode — fetching REAL data from PUBLIC, KEYLESS sources (Yahoo Finance, ESPN, RSS, CoinGecko) and public HLS streams — entirely on THEIR own machine, with NO NAS, NO secrets, NO credentials, and NO connection to anyone else's infrastructure. This is the "fuller experience" after the ONBOARD-01 demo. Same hard rules: work ONLY on the collaborator's machine; never obtain, fabricate, or work around any secret; never reference the project author's real IPs/hosts/deployment as a target. Prerequisite: ONBOARD-01 demo mode already works.**

## 0. Why this is safe with no secrets
Every upstream the helper uses is **public and keyless** — there is nothing to authenticate. The NAS in the author's setup is only *where they host* the helper; the helper itself just makes outbound calls to public APIs and reads seed files that are already in the repo (`helper/src/mymts_helper/channels/seed.json`, `feeds/seed.json`). So a collaborator can run the exact same helper locally and get real headlines, real market quotes, and real scores — no private anything.

## 1. Run the helper in NORMAL (live) mode
Stop the phantom helper if it's running. Then:
```bash
cd helper
PORT=8091 uv run python -m mymts_helper      # PHANTOM_MODE unset/0 → live public data
```
It starts the RSS feed poller, the markets/sports pollers, and the channel prober — all hitting public endpoints. Give it ~30–60s to populate.

**Verify:**
```bash
curl -fsS http://localhost:8091/health                 # ready=true once pollers warm up
curl -fsS http://localhost:8091/api/feed | head -c 400  # REAL recent headlines
curl -fsS "http://localhost:8091/api/ticker?mode=markets" # REAL quotes (Yahoo/CoinGecko)
```
Some channels may show offline depending on the collaborator's network/region — that's the honest play-what-works behaviour, not a bug.

## 2. Point the app at the local helper
The app already defaults to `http://localhost:8091`, so on an **emulator** just pass the host alias:
```bash
adb shell am start -n com.mymts/.MainActivity --es helper "http://10.0.2.2:8091"
```
If the collaborator wants the build itself to default to a specific local URL (e.g. a device on their LAN reaching their dev machine), set it in the **gitignored** `local.properties` at the repo root:
```
MYMTS_HELPER_BASE_URL=http://<THEIR-DEV-MACHINE-LAN-IP>:8091
```
Use the collaborator's OWN machine's IP — never anyone else's. `local.properties` is gitignored; it never gets committed.

## 3. What they get
Real headlines in the feed, real market quotes + sports scores in the ticker, and live video tiles for whatever public channels resolve on their network — the full MyMTS experience, self-hosted on their machine.

## 4. Verification + troubleshooting
After this, the feed/ticker show **real** content (not the phantom fixtures).
- **Feed/ticker empty after a minute:** the pollers need outbound internet; confirm the machine has network access and isn't behind a proxy that blocks the public APIs.
- **Many tiles offline:** normal — some public streams are geo/network-dependent. The honest-degradation design shows this truthfully.
- **App still shows mock data:** you're still pointing at a phantom helper, or the app cached the phantom URL — confirm the helper above is running in normal mode and the app's `--es helper` URL matches.

## Standards (for you, the assistant)
- Collaborator's machine ONLY. Never connect to or reference the project author's NAS/boxes/real deployment — they're irrelevant here; the helper runs locally on public data.
- Keyless public sources only — there is no key to add, fabricate, or request. Never introduce one.
- Any real config value (like a LAN IP) goes in the **gitignored** `local.properties`, never a committed file.
