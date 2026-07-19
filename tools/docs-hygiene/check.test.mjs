// Regression guard for the docs-hygiene check's own logic. Pins that planted
// leaks FAIL, that the allowlist + inline marker exempt, and — critically —
// that the deliberate KEEPS ("Onn 4K box" platform fact, "anyone in the room"
// use-case) are NOT false-positived.
//
//   Run:  node --test tools/docs-hygiene/check.test.mjs

import { test } from "node:test";
import assert from "node:assert/strict";
import { scanText, parseAllowlist, buildPatterns } from "./check.mjs";

const ids = (text, allow) => scanText(text, allow).map((v) => v.id);

test("planted SENSITIVE leaks fail", () => {
  assert.deepEqual(ids("the box is at 192.168.1.45 on the LAN"), ["full-ipv4"]);  // routable LAN IP
  assert.deepEqual(ids("MAC aa:bb:cc:dd:ee:ff"), ["mac-address"]);
  assert.deepEqual(ids("see /Users/realname/secrets/key"), ["abs-home-path"]);
  assert.deepEqual(ids("backed up to /home/operator/keys"), ["abs-home-path"]);
});

test("planted PERSONAL-CONFIG leaks fail", () => {
  assert.deepEqual(ids("the full wall on the office Onn 4K"), ["location-onn"]);
  assert.deepEqual(ids("runs on the basement Onn"), ["location-onn"]);
  // "Office ONN Box" trips both location-onn (the "Office ONN" prefix) and the
  // named-box-instance rule — either way it's a caught leak.
  assert.ok(ids("MyMTS now runs on Office ONN Box - MyMTS").includes("named-box-instance"));
  assert.deepEqual(ids("the real thing running on my wall"), ["possessive-deployment"]);
  assert.deepEqual(ids("running ambient in the room"), ["ambient-room-deployment"]);
  assert.deepEqual(ids("Operator feedback (2026-06-04 upstairs session)"), ["location-session"]);
  assert.deepEqual(ids("docs/screenshots/device/office-in-situ.png"), ["legacy-room-filename"]);
});

test("RFC 5737 documentation IPs are NOT flagged (they ARE the sanctioned placeholder)", () => {
  // the config templates + tests use 192.0.2.x/198.51.100.x/203.0.113.x as example IPs
  assert.deepEqual(ids("--device 192.0.2.10:5555"), []);
  assert.deepEqual(ids("NODE_IP=198.51.100.7"), []);
  assert.deepEqual(ids("the gateway 203.0.113.1"), []);
  // a REAL routable IP still fails (the leak class this protects) — use a GENERIC
  // CGNAT/LAN address, never the operator's actual tailnet host, in the fixture itself.
  assert.deepEqual(ids("a tailnet-class host 100.64.1.1"), ["full-ipv4"]);
  assert.deepEqual(ids("LAN box 192.168.1.42"), ["full-ipv4"]);
});

test("the operator apex domain / brand is flagged (use a placeholder)", () => {
  // Inject a TEST brand so the operator's real apex is never written into the
  // repo (not even as a fixture). Production reads DOCS_HYGIENE_APEX / apex.local.
  const p = buildPatterns("example-brand");
  const idsB = (t) => scanText(t, new Set(), p).map((v) => v.id);
  assert.deepEqual(idsB("served from wall.example-brand.com over the tunnel"), ["apex-domain"]);
  assert.deepEqual(idsB("purge the example-brand.io zone"), ["apex-domain"]);
  assert.deepEqual(idsB("even foo.bar.example-brand.com is caught"), ["apex-domain"]);
  // the neutral placeholder convention is NOT flagged
  assert.deepEqual(idsB("served from wall.your-domain.example"), []);
  // precision: a token that merely CONTAINS the brand substring (no dotted TLD) isn't flagged
  assert.deepEqual(idsB("the example-brandish label is unrelated"), []);
});

test("DELIBERATE KEEPS are NOT flagged (precision — no crying wolf)", () => {
  assert.deepEqual(ids("Native Android TV on an Onn 4K box"), []);   // platform fact
  assert.deepEqual(ids("safe for anyone in the room"), []);           // ambient-audience use-case
  assert.deepEqual(ids("the wall is on by default"), []);             // "the wall" is the product concept
  assert.deepEqual(ids("hands-on session feedback"), []);             // the generalized form
  assert.deepEqual(ids("the dedicated MyMTS box"), []);               // the generalized box reference
  assert.deepEqual(ids("relative path docs/screenshots/web/"), []);   // relative paths are fine
});

test("GLOBAL allowlist exempts a literal match", () => {
  const allow = parseAllowlist("10.0.2.2  # emulator\n.200  # box alias");
  assert.deepEqual(ids("the helper at 10.0.2.2", allow), []);          // emulator IP exempt
  assert.deepEqual(ids("never disrupt the .200 box", allow), []);      // alias exempt
  // but a NON-allowlisted IP still fails even with the allowlist loaded
  assert.deepEqual(ids("a real one 172.16.9.9", allow), ["full-ipv4"]);  // routable, non-allowlisted, non-doc
});

test("INLINE marker exempts that line only", () => {
  assert.deepEqual(ids("office Onn <!-- docs-hygiene:allow quoting the removed phrase -->"), []);
  // the marker is line-scoped: a leak on a different line still fails
  assert.deepEqual(
    ids("office Onn <!-- docs-hygiene:allow -->\nbut the basement Onn here is not exempt"),
    ["location-onn"],
  );
});

test("parseAllowlist strips comments + blanks", () => {
  const s = parseAllowlist("# header\n10.0.2.2   # reason\n\n.200\n   # only a comment\n");
  assert.deepEqual([...s].sort(), [".200", "10.0.2.2"]);
});
