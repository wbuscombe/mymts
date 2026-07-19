// docs-hygiene — ENFORCED docs leakage gate (MAINTENANCE-CHARTER.md).
//
// Greps the PUBLIC docs for two leak classes our audits actually caught and
// FAILS the build on a non-allowlisted match:
//   - SENSITIVE topology — full IPv4, MAC, absolute personal home paths.
//   - PERSONAL-CONFIG — the operator's specific deployment/location/instance
//     framing ("the office Onn", "my wall", a named box instance, etc.).
//
// The check is only as good as its ALLOWLIST: without it, the check
// false-positives on the things we DELIBERATELY keep (the last-octet box
// aliases, loopback/emulator IPs, the `<LAN_IP>`/`<MAC>` placeholders) and
// gets disabled. Two exemption mechanisms:
//   - GLOBAL: tools/docs-hygiene/allowlist.txt — literal strings that are OK
//     wherever they appear (one reason per line).
//   - INLINE: a line containing `docs-hygiene:allow` (e.g. an HTML comment) is
//     exempt — for one-off legit cases like a CHANGELOG entry that QUOTES a
//     removed phrase to document the cleanup.
//
//   Run:  node tools/docs-hygiene/check.mjs        (scans the repo's public docs)
//   Test: node --test tools/docs-hygiene/check.test.mjs

import { execSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, "../..");

// The operator's real apex/brand is NOT committed (it is the very thing this
// class protects). Provide it out-of-band: the `DOCS_HYGIENE_APEX` env var (set
// it as a CI variable so the gate still catches the real brand), or a gitignored
// `tools/docs-hygiene/apex.local` file for local runs. A public fork sets its own.
// Absent → a placeholder token that matches nothing real, so the gate still runs
// (it just can't catch a brand it was never told about). See apex.local.example.
export function readApexBrand() {
  const env = process.env.DOCS_HYGIENE_APEX?.trim();
  if (env) return env;
  try {
    const line = readFileSync(path.join(HERE, "apex.local"), "utf8")
      .split(/\r?\n/).map((l) => l.replace(/#.*$/, "").trim()).find(Boolean);
    if (line) return line;
  } catch { /* no local override — use the placeholder below */ }
  return "your-brand";
}

/** Build the apex-domain regex for a brand token (e.g. "acme" matches
 *  sub.acme.com / acme.io). The brand is regex-escaped. */
export function apexPattern(brand) {
  const esc = brand.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(String.raw`\b(?:[a-z0-9-]+\.)*${esc}\.[a-z]{2,}\b`, "gi");
}

// Each pattern is global (`g`) so matchAll yields every hit on a line. `id`
// names the leak class; `why` is shown in the failure so a human knows the fix.
// Built via a function so the apex pattern can take an injected brand (tests do this).
export function buildPatterns(apexBrand = readApexBrand()) {
  return [
  { id: "full-ipv4", why: "a full IPv4 looks like real topology — use a <LAN_IP> placeholder",
    re: /\b(?:\d{1,3}\.){3}\d{1,3}\b/g },
  { id: "mac-address", why: "a MAC address is device-identifying — use a <MAC> placeholder",
    re: /\b(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b/g },
  { id: "abs-home-path", why: "an absolute personal home path leaks the operator's filesystem",
    re: /(?:\/Users\/|\/home\/)[A-Za-z0-9._-]+/g },
  { id: "apex-domain", why: "the operator's real domain/brand leaks the deployment host — use a wall.your-domain.example placeholder",
    re: apexPattern(apexBrand) },
  { id: "device-alias", why: "a last-octet box alias — keep only the deliberate ones (allowlist)",
    re: /(?<![\d.])\.(?:92|158|182)\b/g },
  { id: "location-onn", why: "a location-qualified box instance ('the office Onn') — describe the platform generically",
    re: /\b(?:office|basement|upstairs|downstairs|garage|bedroom|kitchen|den|living[ -]?room)\s+Onn\b/gi },
  { id: "named-box-instance", why: "the operator's named box instance — generalize to 'the dedicated MyMTS box'",
    re: /\bOffice ONN Box\b/gi },
  { id: "possessive-deployment", why: "a personal-deployment possessive — describe the project, not the operator's rig",
    re: /\b(?:my|our)\s+(?:wall|TV|NAS|box|setup|rig|house|panel)\b/gi },
  { id: "ambient-room-deployment", why: "room-as-deployment framing — say 'an ambient wall display' (not the operator's room)",
    re: /\bambient in the room\b/gi },
  { id: "location-session", why: "a location-tagged session label — generalize (e.g. 'hands-on session')",
    re: /\b(?:upstairs|downstairs|basement)\s+session\b/gi },
  { id: "legacy-room-filename", why: "the office-in-situ.png filename encoded a room — it is now in-situ.png",
    re: /\boffice-in-situ\b/gi },
  ];
}

export const PATTERNS = buildPatterns();

const INLINE_ALLOW = "docs-hygiene:allow";

/** Parse allowlist.txt → a Set of literal exempt strings (strip `#` comments). */
export function parseAllowlist(text) {
  const out = new Set();
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.replace(/#.*$/, "").trim();
    if (line) out.add(line);
  }
  return out;
}

/** The IANA ranges RESERVED for documentation/examples — RFC 5737 (192.0.2.0/24,
 *  198.51.100.0/24, 203.0.113.0/24). These ARE the sanctioned placeholders (what the
 *  full-ipv4 `why` tells authors to use), so the topology check must never flag them —
 *  else the placeholder convention fails its own gate. A real leak is a routable IP. */
export function isDocumentationIp(s) {
  return /^192\.0\.2\.\d{1,3}$/.test(s)
    || /^198\.51\.100\.\d{1,3}$/.test(s)
    || /^203\.0\.113\.\d{1,3}$/.test(s);
}

/** Scan one document's text. Returns [{line, id, match, why}] for every
 *  non-exempt hit. A hit is exempt if its matched string is in `allowSet`
 *  (GLOBAL), its line carries the INLINE marker, or (for full-ipv4) it is a
 *  reserved documentation IP. */
export function scanText(text, allowSet = new Set(), patterns = PATTERNS) {
  const violations = [];
  const lines = text.split(/\r?\n/);
  lines.forEach((line, i) => {
    if (line.includes(INLINE_ALLOW)) return; // line-level exemption
    for (const p of patterns) {
      for (const m of line.matchAll(p.re)) {
        if (allowSet.has(m[0])) continue;     // global exemption
        if (p.id === "full-ipv4" && isDocumentationIp(m[0])) continue; // RFC 5737 placeholder
        violations.push({ line: i + 1, id: p.id, match: m[0], why: p.why });
      }
    }
  });
  return violations;
}

/** Tracked scan targets: public-doc files (markdown + .phantom.yml) AND the committed
 *  CONFIG TEMPLATES (`*.env.example`, compose files). The config templates are in scope
 *  because a real topology leak (the LiveKit tailnet IP, 2026-07-08 audit) slipped past a
 *  docs-only scan — it lived in `.env.example` + the NAS compose. Excludes the check's own
 *  fixtures + the gitignored ops-local notes. (Benign infra IPs in compose — 127.0.0.1,
 *  0.0.0.0 — are covered by the allowlist.) */
export function listDocFiles() {
  const out = execSync(
    "git ls-files -z -- '*.md' '.phantom.yml' '*.env.example' '*compose*.yml' '*compose*.yaml'",
    { cwd: REPO },
  )
    .toString("utf8")
    .split("\0")
    .filter(Boolean)
    .filter((f) => !f.startsWith("tools/docs-hygiene/") && !f.startsWith("docs/ops-local/"));
  return out;
}

function main() {
  const allowSet = parseAllowlist(readFileSync(path.join(HERE, "allowlist.txt"), "utf8"));
  const files = listDocFiles();
  const all = [];
  for (const rel of files) {
    const text = readFileSync(path.join(REPO, rel), "utf8");
    for (const v of scanText(text, allowSet)) all.push({ file: rel, ...v });
  }
  if (all.length === 0) {
    console.log(`docs-hygiene: clean — ${files.length} public docs scanned, 0 leaks.`);
    return;
  }
  console.error(`docs-hygiene: ${all.length} leak(s) in public docs — FAIL:\n`);
  for (const v of all) {
    console.error(`  ${v.file}:${v.line}  [${v.id}]  "${v.match}"  → ${v.why}`);
  }
  console.error(
    `\nFix the doc, or — if the match is intentional — exempt it: add the literal to` +
    `\ntools/docs-hygiene/allowlist.txt (with a reason) for a global keep, or put` +
    `\n"${INLINE_ALLOW}" on the line (e.g. an HTML comment) for a one-off.`,
  );
  process.exitCode = 1;
}

if (import.meta.url === `file://${process.argv[1]}`) main();
