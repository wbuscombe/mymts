// Generate clean, labeled placeholder images for the operator's MANUAL
// native-TV hero shots (the real wall on the office Onn). Claude Code can't
// photograph the panel, so these stand in until the operator drops the real
// captures over them (same filenames → the top-level README picks them up).
//
//   Usage:  node make-placeholders.mjs

import { chromium } from "playwright";
import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.resolve(HERE, "../../docs/screenshots/device");

const SHOTS = [
  {
    file: "wall-hero.png",
    title: "Wall hero",
    sub: "The full wall on the office Onn 4K driving a real TV — the money shot.",
  },
  {
    file: "cards-closeup.png",
    title: "Ticker cards close-up",
    sub: "Live markets + the bespoke per-sport cards (PGA / UFC / Tennis / F1) on real data.",
  },
  {
    file: "office-in-situ.png",
    title: "In situ",
    sub: "The TV on the wall, running ambient in the room — the “this is the real thing” shot.",
  },
];

function html(title, sub, file) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    html,body{margin:0;height:100%}
    .frame{box-sizing:border-box;width:1600px;height:900px;background:#0b0e13;color:#9fb3c8;
      display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;
      font-family:-apple-system,'Segoe UI',Roboto,sans-serif;border:3px dashed #2b3a4a}
    .cam{font-size:120px;opacity:.45;margin-bottom:6px}
    .title{font-size:46px;font-weight:700;color:#cde1f2;letter-spacing:.5px}
    .sub{font-size:24px;margin-top:16px;max-width:1120px;line-height:1.55}
    .file{margin-top:34px;font-family:ui-monospace,Menlo,monospace;font-size:20px;color:#7d97b0;
      background:#121821;border:1px solid #243140;border-radius:8px;padding:9px 16px}
    .badge{margin-top:30px;font-size:17px;color:#86c389;letter-spacing:2px}
    .hint{margin-top:10px;font-size:17px;color:#6f8499}
  </style></head><body><div class="frame">
    <div class="cam">\u{1F4F7}</div>
    <div class="title">${title} — placeholder</div>
    <div class="sub">${sub}</div>
    <div class="file">docs/screenshots/device/${file}</div>
    <div class="badge">NATIVE-TV HERO SHOT · REAL DEVICE · DROP-IN SLOT</div>
    <div class="hint">Operator to capture (a phone photo of the TV, or an Android screencap) and replace this file — keep the name.</div>
  </div></body></html>`;
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
await mkdir(OUT, { recursive: true });
for (const s of SHOTS) {
  await page.setContent(html(s.title, s.sub, s.file), { waitUntil: "load" });
  await page.screenshot({ path: path.join(OUT, s.file) });
  console.log("placeholder", s.file);
}
await browser.close();
console.log("done →", OUT);
