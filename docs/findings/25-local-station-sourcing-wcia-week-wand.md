# 25 — Local-station sourcing: WCIA, WEEK and WAND

**Date:** 2026-09-25 UTC (the evening of 2026-09-24, local)
**Scope:** Sourcing research on three local broadcast stations: WCIA (CBS; Champaign), WEEK
(Peoria; web home 25newsnow.com) and WAND (NBC; Decatur). Two read-only research runs did the
work. **MYMTS-035** read each station's official web live page for a statically discoverable
manifest. **MYMTS-036** looked for each station's official YouTube channel and ran the
project's existing `youtube` resolver against each channel it established. Evidence is cited
by run and report section, for example (MYMTS-036 §5).

**Outcome: 0 admitted.** All three web players are **REFUSED**. On the YouTube route, WCIA is
**ADMISSIBLE VIA EXISTING RESOLVER, SCHEDULED**, with its live resolution **UNVERIFIED**; WEEK
is **OFFICIAL CHANNEL, NO LIVE EVIDENCE**; WAND is **NO OFFICIAL CHANNEL ESTABLISHED**. These
are research classifications, not admissions. Nothing was admitted, and no registry,
configuration, resolver or source was changed (MYMTS-035 §7, MYMTS-036 §7).

Prior records: `docs/BACKLOG.md` and `docs/findings/20-weather-feed-research.md` already cover
these stations. The BACKLOG records them as "login/auth-gated or YouTube-page-only — no public
keyless HLS", to be reconsidered only if a station ever exposes a public keyless HLS. It also
holds WCIA via Haystack / Roku channels as FREE-BUT-ToS-GRAY, pending the operator
(MYMTS-035 §2 B3).

---

## Criteria applied in MYMTS-035 and MYMTS-036

This section records the criteria these two runs applied, as they applied them. It does not
restate or amend the project's sourcing discipline.

- **Always-refused mechanisms.** Credentials or login; cookies or session state; `Referer` or
  `Origin` spoofing; DRM; entitlement or authentication tokens. Neither run used any of them
  (MYMTS-035 §11 F2, MYMTS-036 §11 F2).
- **Provenance.** A YouTube channel was treated as official only when the station's own website
  links to it, or an official social profile the site links does. MYMTS-036 applied the website
  rule as "any page of the station's own website links to it", established provenance before
  any probe, and probed no channel without it (MYMTS-036 §3, §10, §11 F2).
- **The existing `youtube` resolver.** It is the resolver behind the product's own YouTube
  channels, which are 31 of the 56 shipped lineup entries (MYMTS-035 §2 B1). MYMTS-036 read its
  extraction options (`quiet`, `no_warnings`, `skip_download`, `noplaylist`, `socket_timeout`)
  and found no cookie, credential, header, `Referer` or `Origin` option, so it uses no
  always-refused mechanism (MYMTS-036 §2 B1). It was the only mechanism extended, run with its
  own options unchanged, and only against officially established channels (MYMTS-036 §7).
- **No new mechanism.** No new extraction or token-minting path was built or run for any
  platform (MYMTS-036 §7). Neither run used a headless browser or executed JavaScript
  (MYMTS-035 §11 F2, MYMTS-036 §11 F2).
- **A withdrawn rule.** MYMTS-035 applied a structural rule refusing any URL that carries an
  expiry or signature parameter. Under it, a station reachable only through the `youtube`
  resolver could not be tested, because the googlevideo HLS URL that resolver obtains carries
  an `expire` parameter (MYMTS-035 §2 B5). MYMTS-036 withdrew that rule as over-broad, because
  it would have refused the product's own existing YouTube channels (MYMTS-036 §7).
- **Request profile and robots.** Official-surface reads used the helper's own request profile:
  User-Agent `mymts-helper/0.0`, https only, no cookie jar, no `Referer` or `Origin`, no
  credentials. Each host's `robots.txt` was read before any page on it, and every page read was
  allowed under its `*` group (MYMTS-035 §2 B2 and §3, MYMTS-036 §3).

---

## Results

| Station | Web player | Official YouTube channel | YouTube route |
|---|---|---|---|
| **WCIA** | **REFUSED**: Nexstar/Anvato player; stream via the Lura video API with an access key and a token | `@wcia3news` ("WCIA News"), established | **ADMISSIBLE VIA EXISTING RESOLVER, SCHEDULED**; live resolution **UNVERIFIED** |
| **WEEK** | **REFUSED**: Gray's client-side Quickplay player, given only catalog channel ids | `@25newsweek` ("25News WEEK-TV"), established, with a caveat | **OFFICIAL CHANNEL, NO LIVE EVIDENCE** |
| **WAND** | **REFUSED**: the same Gray client-side Quickplay player | none established | **NO OFFICIAL CHANNEL ESTABLISHED** |

MYMTS-035 classified all three web players **NO STATICALLY DISCOVERABLE MANIFEST**. MYMTS-036
recorded each as **REFUSED**, standing as MYMTS-035 found it, and did not touch any of them
(MYMTS-035 §5, MYMTS-036 §5 E1). No playlist, segment or key was fetched in either run
(MYMTS-035 §4, MYMTS-036 §11 F2).

---

## WCIA (CBS; Champaign) — web player REFUSED; YouTube ADMISSIBLE VIA EXISTING RESOLVER, SCHEDULED; live resolution UNVERIFIED

**Web player: REFUSED** (MYMTS-035 §3 and §5, MYMTS-036 §5 E1).

- The official site is `wcia.com`, a Nexstar property. Its live page, `wcia.com/watch-now/`,
  contains no occurrence of `m3u8`, `.mpd`, `manifest`, `hlsUrl`, `streamUrl` or `playbackUrl`.
- The live player is a Nexstar-skinned video.js element. Its configuration declares provider
  `anvato`, `isLivestream: true` and an Anvato video identifier (not recorded here), and
  contains no URL.
- The page's static settings route playback through the Lura video API. VOD items on the same
  page reference that API with query parameters named `anvack` (an access key) and `token`. No
  value is recorded here.
- The live player's wrapper carries a `data-has-auth` attribute. The page reads "Sign in to
  create your free My Nexstar account featuring exclusive livestreams".
- **Reason:** the player obtains the stream client-side from the Lura video API using the page's
  access key and a token. That is an entitlement-token path. Pursuing it would mean using that
  key and token, so it was not pursued.
- After nine reads of the live page in about six minutes during MYMTS-035, the site began
  answering with an HTTP 403 bot-protection page. It did not recur in MYMTS-036, where WCIA
  answered all four page reads with HTTP 200 (MYMTS-035 §3, MYMTS-036 §3).
- An aggregator route (Haystack News) seen in search results is not an official surface and was
  not contacted (MYMTS-035 §3 C4).

**Provenance: ESTABLISHED, handle `@wcia3news`** (MYMTS-036 §3).

1. The official live page `wcia.com/watch-now/` links the YouTube channel `@wcia3news` directly,
   in its "Social Media Header" section.
2. The site-wide footer links a YouTube channel in channel-ID form (the ID is not recorded). The
   channel's own metadata gives the handle `@wcia3news` and the title "WCIA News", and its
   channel ID equals the footer link's ID.

**YouTube route: ADMISSIBLE VIA EXISTING RESOLVER, SCHEDULED. Not admitted.** Evidence
(MYMTS-036 §4 and §5):

- At 2026-09-25T01:28:00Z the existing resolver, run as production runs it, returned its
  honest-offline result: `ok=False`, `is_live=False`, error category `not_live` ("The channel is
  not currently live"). No HLS URL was produced.
- The channel's Live tab, read to its first page only, lists 30 completed live streams, dated
  from "3 days ago" back to "3 months ago":
  - 17 titled as severe-weather or storm coverage ("LIVE: Severe Weather Update", "Severe
    Weather Coverage" or "Storm Update"), running from about 23 minutes to about 4 h 41 m;
  - 6 "Your Illini Nation" pregame and postgame shows;
  - 7 news conferences, hearings or community events.
- The first page showed no upcoming or scheduled item, and the listing showed no
  regular-newscast stream.
- The handle's `/live` source form passes the registry's `validate_youtube_url` (MYMTS-036 §2 B2,
  §5 E4).

**UNVERIFIED: live resolution.** The channel was offline at probe time, so no live resolution
was observed for it. No media-sequence advancement check ran in either run: MYMTS-035's
two-minute re-fetch never applied, and MYMTS-036's advancement check (D2) was not performed
because nothing resolved. Segment fetchability and the absence of DRM were not observed either
(MYMTS-035 §4; MYMTS-036 §4, §5 E4 and §10).

**Statements on the official surface** (closely paraphrased in MYMTS-036 §3; no legal
interpretation is offered). The Nexstar Terms of Use linked from wcia.com provide for personal,
non-commercial use; bar copying, distribution, transmission or display except as the terms
provide; bar framing; bar robots, spiders, scraping and data-mining; and bar retransmission or
redistribution without prior written consent. The terms do not mention YouTube, and whether
they extend to the station's YouTube channel is not stated.

---

## WEEK (Peoria; web home 25newsnow.com) — web player REFUSED; YouTube OFFICIAL CHANNEL, NO LIVE EVIDENCE

**Web player: REFUSED** (MYMTS-035 §3 and §5, MYMTS-036 §5 E1).

- The station's web home is `25newsnow.com`, not week.com: a Gray Local Media station whose
  footer links WEEK's FCC public inspection file.
- The official live page, `25newsnow.com/livestream/`, contains no `m3u8`. It is an Arc XP page
  whose live slot is Gray's client-side `QuickplayLivePlayer` feature.
- The page's server-rendered content cache holds four Quickplay live-channel catalog records,
  three of them inspected individually. They carry ids, catalog keys, schedules and thumbnail
  URLs, with no URL-valued field other than images and no playback endpoint.
- **Reason:** the page hands the player only catalog channel ids, and playback is resolved
  client-side at runtime. No extraction mechanism was built to reach it, and the player was not
  touched (MYMTS-036 §5 E1, §7).

**Provenance: ESTABLISHED, handle `@25newsweek`, with a caveat** (MYMTS-036 §3).

1. The official home page's Arc PageBuilder layout contains a `global/FlexFeature` image tile
   whose destination is the YouTube channel `@25newsweek`, set to open in a new window. None of
   its 36 custom fields is a hidden, disabled or visibility flag.
2. The channel's metadata gives the handle `@25newsweek` and the title "25News WEEK-TV".
   MYMTS-035 had seen this channel only in search results and had not established it as
   official (MYMTS-035 §3 C4).
3. **Caveat:** the link is delivered in the page's layout data for a lazy-loaded,
   client-rendered tile, not in server-rendered `<a>` markup. The tile was not rendered and no
   JavaScript was run. MYMTS-036 judged the link sufficient under the provenance rule, before
   any probe, and left the caveat for the operator to weigh.

No other surface read links the channel. The footer links Facebook, Instagram and X profiles but
has no YouTube icon, and the contact, terms and live pages have no YouTube reference.

**YouTube route: OFFICIAL CHANNEL, NO LIVE EVIDENCE. Not admitted.** Evidence (MYMTS-036 §4 and
§5):

- At 2026-09-25T01:28:31Z the existing resolver returned `not_live` ("The channel is not
  currently live"). No HLS URL was produced.
- The channel has no Live tab; its tabs are Home, Videos, Shorts and Playlists. The 48 items
  shown are uploaded clips only, and none is marked streamed, live or upcoming.

**Statements on the official surface** (closely paraphrased in MYMTS-036 §3; no legal
interpretation is offered). The live page says 25News streams more than 50 hours a week of live
local news, watchable on 25NewsNow.com, the 25News mobile app and the 25News Smart TV app on
Apple TV, Roku and Amazon Fire TV. It does not mention YouTube. The Gray Local Media Terms of
Service permit viewing, sharing, linking or caching for personal, non-commercial use with
notices intact; bar reproducing, distributing, transmitting, linking or caching for commercial
purposes without written permission; bar framing; and bar using any engine, software, tool or
agent to navigate or search the Services other than Gray's own search and generally available
web browsers, naming artificial intelligence and intelligent agents among them. They do not
mention YouTube.

---

## WAND (NBC; Decatur) — web player REFUSED; YouTube NO OFFICIAL CHANNEL ESTABLISHED

**Web player: REFUSED** (MYMTS-035 §3 and §5, MYMTS-036 §5 E1).

- The official site is `wandtv.com`, a Gray Local Media station whose footer links the station's
  FCC public inspection file. The home page's navigation links "Watch Live" and "Livestream" to
  `/livestream/`.
- The official live page, `wandtv.com/livestream/`, contains no `m3u8` and uses the same
  `QuickplayLivePlayer` feature. Its content cache lists three Quickplay live-channel records
  ("WAND News", "InvestigateTV" and "Local News Live"), none with a URL-valued field other than
  images.
- **Reason:** the same as WEEK's. The player is given only catalog channel ids and resolves
  playback client-side; it was not touched.

**Provenance: NOT ESTABLISHED** (MYMTS-036 §3 and §5).

- Five official pages were read, all HTTP 200: the home page, `/about-us/`,
  `/about-us/contact-us/`, `/terms-of-service/` and `/livestream/`. None contains a YouTube URL
  or the word "youtube".
- The footer links Facebook `wandtv`, Instagram `wandtv` and X `wandtvnews`. Those profiles
  could not be read: the `robots.txt` files at facebook.com, instagram.com and x.com each
  disallow `/` for `*`, and no profile page was fetched.
- No WAND channel was identified, no search was performed, and nothing was probed.

**YouTube route: NO OFFICIAL CHANNEL ESTABLISHED. Not admitted.**

**Statements on the official surface** (MYMTS-036 §3). The live page lists its live channels and
makes no statement about the stream or YouTube. The Terms of Service are the same Gray Local
Media text as WEEK's.

---

## Not determined

The two runs left these open:

1. **WCIA:** whether a live `@wcia3news` stream resolves through the existing resolver to an HLS
   playlist whose media sequence advances, with fetchable segments and no DRM (MYMTS-036 §10).
2. Whether yt-dlp 2026.03.17, running with no JavaScript runtime (the production image installs
   none), returns live HLS for these channels. This caveat came from general knowledge of recent
   yt-dlp YouTube changes and was not verified (MYMTS-036 §2 B3, §10).
3. Whether WAND has a YouTube channel that its official social profiles link (MYMTS-036 §10).
4. Whether WEEK's home-page tile linking `@25newsweek` is visibly rendered to visitors
   (MYMTS-036 §10).
5. Whether Nexstar/Anvato or Gray/Quickplay live playback carries DRM, and what Quickplay's
   playback authorization requires (MYMTS-035 §10).
6. Whether the Nexstar or Gray terms extend to use of the stations' YouTube channels
   (MYMTS-036 §10).
7. Whether the station sites' `robots.txt` groups that disallow named AI agents (`anthropic-ai`,
   `ClaudeBot`, `Claude-Web`) cover a user-directed, Claude-driven read such as these runs,
   which presented only the helper's User-Agent. That is the operator's call (MYMTS-035 §3,
   MYMTS-036 §3 and §10).
8. WCIA's Live tab was read to its first page only, 30 items (MYMTS-036 §10).
9. The deployed helper's database and any operator lineup override were not read. "Not
   admitted" is established from the shipped registry and tracked records (MYMTS-035 §2 B4,
   MYMTS-036 §10).
