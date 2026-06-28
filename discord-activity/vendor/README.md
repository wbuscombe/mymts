# Vendored third-party bundles (own-origin, no CDN)

The Discord Activity follows the same posture as the LAN web client: **all
third-party JS is vendored and served same-origin** (`script-src 'self'`, no CDN).
Two files live here:

| File | What | Source |
| --- | --- | --- |
| `hls.min.js` | hls.js player (UMD → `window.Hls`) | identical byte-for-byte copy of `web/vendor/hls.min.js` (the pinned, already-audited bundle the wall uses) |
| `discord-embedded-app-sdk.mjs` | `@discord/embedded-app-sdk` v2.5.0, esbuild-bundled into a **self-contained ESM** file (all transitive deps inlined; zero bare imports) | npm `@discord/embedded-app-sdk` |

## Why bundle the SDK?

The published `@discord/embedded-app-sdk` ESM entry (`output/index.mjs`) has bare
imports (`decode-uri-component`, etc.) that a browser can't resolve without a
bundler. We have **no build step** for static assets, so we pre-bundle once into a
single own-origin ESM file. The bundle exports exactly what the Activity uses:
`DiscordSDK`, `patchUrlMappings`, `Events`, `Common`.

## Regenerate (pin/refresh the SDK)

```sh
TMP="$(mktemp -d)"; cd "$TMP"
npm init -y >/dev/null
npm install @discord/embedded-app-sdk@<version> esbuild
printf 'export { DiscordSDK, patchUrlMappings, Events, Common } from "@discord/embedded-app-sdk";\n' > entry.mjs
./node_modules/.bin/esbuild entry.mjs --bundle --format=esm --platform=browser \
  --target=es2020 --legal-comments=none \
  --outfile=<repo>/discord-activity/vendor/discord-embedded-app-sdk.mjs
# sanity: no residual bare imports, exports present
grep -nE '^\s*import[^"]*"[^./]' <repo>/discord-activity/vendor/discord-embedded-app-sdk.mjs   # → none
```

`hls.min.js` is refreshed by copying `web/vendor/hls.min.js` (keep the two in lockstep
so the Activity and the wall play with the same engine).
