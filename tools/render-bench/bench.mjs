#!/usr/bin/env node
// render-bench collector — the per-stage smoothness instrument.
//
// Runs ON THE NAS HOST (needs `docker`; node >= 18). Over a warm window it captures
// EVERY stage of the wall pipeline at once, so frame loss can be attributed to a
// stage instead of guessed:
//
//   per-tile decode/present fps + drop-%   ← the render page's ?fpsmeter=1 telemetry
//   per-tile variant vs cell (overdrawX)   ← same telemetry (the prime suspect)
//   page paint rate                        ← telemetry rAF  +  an INDEPENDENT
//                                            x11grab+mpdecimate of :99 (unique frames
//                                            the browser actually painted)
//   encoder unique-frame rate              ← mpdecimate on the HLS output (the number
//                                            §31/the cleanup pass reported — kept so the
//                                            report can show it's output-side, not tile)
//   box load                               ← docker stats + per-process CPU (chromium
//                                            vs ffmpeg)
//
// The page must already be in fpsmeter mode (renderer pointed at ?render=1&fpsmeter=1);
// see README. This tool only READS — it never restarts anything.
//
//   Env: BENCH_WINDOW_S (default 60), BENCH_RENDERER (mymts-renderer),
//        BENCH_HELPER (mymts-helper), BENCH_HELPER_ORIGIN (https://127.0.0.1:8443),
//        BENCH_LABEL (free text, e.g. "news-before").
//   Out: a human table on stdout + a single BENCH_JSON:{...} line for capture.

import { execSync } from "node:child_process";
import { exec } from "node:child_process";

const WINDOW_S = Number(process.env.BENCH_WINDOW_S || 60);
const RENDERER = process.env.BENCH_RENDERER || "mymts-renderer";
const HELPER = process.env.BENCH_HELPER || "mymts-helper";
const HELPER_ORIGIN = process.env.BENCH_HELPER_ORIGIN || "https://127.0.0.1:8443";
const LABEL = process.env.BENCH_LABEL || "run";

const sh = (cmd) => execSync(cmd, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
// async variant so the 60s ffmpeg probes DON'T block the event loop — they must run
// concurrently, and CPU sampling + the telemetry-window clock must keep ticking.
const shA = (cmd) => new Promise((resolve) => {
  exec(cmd, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 }, (err, stdout) => resolve(stdout || ""));
});
const num = (x) => (typeof x === "number" && isFinite(x) ? x : null);
const f1 = (x) => (num(x) == null ? "  –  " : x.toFixed(1).padStart(5));

// --- read the render page telemetry (inside the helper container, over its own TLS) ---
function telemetry() {
  const py = [
    "import json,ssl,urllib.request",
    "c=ssl.create_default_context(); c.check_hostname=False; c.verify_mode=ssl.CERT_NONE",
    `r=urllib.request.urlopen('${HELPER_ORIGIN}/api/render/telemetry',timeout=5,context=c)`,
    "print(r.read().decode())",
  ].join("; ");
  try {
    const out = sh(`docker exec ${HELPER} python3 -c ${JSON.stringify(py)}`);
    return JSON.parse(out);
  } catch (e) {
    return { latest: null, age_s: null, _error: String(e).slice(0, 200) };
  }
}

// --- an ffmpeg probe inside the renderer container; returns {frames, unique} over W ---
async function ffprobeUnique(input, extraIn, windowS) {
  // -vf mpdecimate drops near-duplicate frames; the SURVIVING count / window = the
  // rate of genuinely-changed frames. A second plain count (no mpdecimate) gives the
  // CFR the encoder emits. Both from one container exec to keep the window aligned.
  // ffmpeg prints its `frame=` stats with \r (not \n), so translate CR→LF and take
  // the last stats line (-stats forces them even at -loglevel error).
  const base = `ffmpeg -hide_banner -nostdin -loglevel error -stats ${extraIn} -i ${input} -t ${windowS}`;
  const lastFrame = (pipe) => `${pipe} 2>&1 | tr '\\r' '\\n' | grep -a 'frame=' | tail -1`;
  const cmd =
    `docker exec ${RENDERER} sh -c ${JSON.stringify(
      `( ${lastFrame(`${base} -an -vf mpdecimate -f null -`)} ; echo '@@' ; ${lastFrame(`${base} -an -f null -`)} )`,
    )}`;
  const out = await shA(cmd);
  const [dec, cfr] = out.split("@@");
  const framesOf = (s) => {
    const m = (s || "").match(/frame=\s*(\d+)/);
    return m ? Number(m[1]) : null;
  };
  const uniqueFrames = framesOf(dec);
  const cfrFrames = framesOf(cfr);
  return {
    uniqueFps: uniqueFrames == null ? null : uniqueFrames / windowS,
    cfrFps: cfrFrames == null ? null : cfrFrames / windowS,
    uniqueFrames,
    cfrFrames,
  };
}

// --- CPU: sample docker stats + per-process a few times across the window ---
async function sampleLoad(windowS) {
  const samples = [];
  const perProc = { chromium: [], ffmpeg: [] };
  const n = Math.max(3, Math.min(10, Math.round(windowS / 6)));
  for (let i = 0; i < n; i++) {
    try {
      const s = sh(`docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' ${RENDERER}`).trim();
      const cpu = Number((s.split("%")[0] || "").trim());
      if (isFinite(cpu)) samples.push(cpu);
    } catch { /* skip */ }
    try {
      // busybox ps: sum %CPU by comm. Fall back silently if unavailable.
      const ps = sh(`docker exec ${RENDERER} sh -c "ps -eo pcpu,comm 2>/dev/null || ps -eo %cpu,comm"`);
      let ch = 0, fm = 0;
      for (const line of ps.split("\n")) {
        const m = line.trim().match(/^([\d.]+)\s+(.*)$/);
        if (!m) continue;
        const pct = Number(m[1]); const comm = m[2];
        if (/chrom/i.test(comm)) ch += pct;
        else if (/ffmpeg/i.test(comm)) fm += pct;
      }
      perProc.chromium.push(ch); perProc.ffmpeg.push(fm);
    } catch { /* skip */ }
    await new Promise((r) => setTimeout(r, (windowS * 1000) / n));
  }
  const avg = (xs) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null);
  return {
    containerCpuPctAvg: avg(samples),
    containerCpuPctMax: samples.length ? Math.max(...samples) : null,
    chromiumCpuPctAvg: avg(perProc.chromium),
    ffmpegCpuPctAvg: avg(perProc.ffmpeg),
    cores: 6, // renderer cpus cap (compose)
  };
}

// --- per-tile deltas between two telemetry reads over the window ---
// windowS here is the TRUE interval between the two telemetry snapshots (measured
// from the page's own elapsedS clock by the caller), NOT the nominal probe window —
// the ffmpeg probes can stretch wall-time, and dividing by the nominal window would
// inflate every fps. Ratios (drop-%) are interval-independent and unaffected.
function tileDeltas(t0, t1, windowS) {
  const a = (t0.latest && t0.latest.tiles) || [];
  const b = (t1.latest && t1.latest.tiles) || [];
  const byIdx = new Map(a.map((t) => [t.index, t]));
  return b.map((tb) => {
    const ta = byIdx.get(tb.index) || {};
    const dTotal = num(tb.totalVideoFrames) != null && num(ta.totalVideoFrames) != null
      ? tb.totalVideoFrames - ta.totalVideoFrames : null;
    const dDrop = num(tb.droppedVideoFrames) != null && num(ta.droppedVideoFrames) != null
      ? tb.droppedVideoFrames - ta.droppedVideoFrames : null;
    const dPres = num(tb.presentedFramesCum) != null && num(ta.presentedFramesCum) != null
      ? tb.presentedFramesCum - ta.presentedFramesCum : null;
    return {
      index: tb.index,
      label: tb.label || "",
      variant: tb.variant ? `${tb.variant.w}x${tb.variant.h}` : "—",
      cell: tb.cell ? `${tb.cell.deviceW}x${tb.cell.deviceH}` : "—",
      overdrawX: num(tb.overdrawX),
      decodedFps: dTotal == null ? null : dTotal / windowS,
      presentedFps: dPres == null ? null : dPres / windowS,
      dropPct: dTotal && dTotal > 0 ? (dDrop / dTotal) * 100 : (dTotal === 0 ? 0 : null),
      paused: tb.paused,
    };
  });
}

async function main() {
  const started = new Date().toISOString();
  console.log(`\n=== render-bench: ${LABEL} — ${WINDOW_S}s window @ ${started} ===`);

  const t0 = telemetry();
  if (!t0.latest) console.log(`! no fpsmeter telemetry (age=${t0.age_s}) — is the renderer on ?render=1&fpsmeter=1? ${t0._error || ""}`);

  // Run the two ffmpeg unique-frame probes + CPU sampling TRULY concurrently over the
  // window (async spawn, not blocking execSync — else they serialise and stretch the
  // telemetry window). The tile fps then divides by the page's OWN measured interval.
  const encoderP = ffprobeUnique("/stream/playlist.m3u8", "", WINDOW_S);
  const paintP = ffprobeUnique(":99", "-f x11grab -framerate 30 -video_size 1920x1080", WINDOW_S);
  const loadP = sampleLoad(WINDOW_S);
  const [encoder, paint, load] = await Promise.all([encoderP, paintP, loadP]);

  const t1 = telemetry();
  // TRUE window = the page's elapsedS delta (falls back to wall-clock, then nominal).
  const e0 = t0.latest && num(t0.latest.elapsedS);
  const e1 = t1.latest && num(t1.latest.elapsedS);
  const trueWindow = (e0 != null && e1 != null && e1 - e0 > 1) ? e1 - e0 : WINDOW_S;
  const tiles = tileDeltas(t0, t1, trueWindow);
  console.log(`  (telemetry window: ${trueWindow.toFixed(1)}s from the page clock)`);
  const pageRaf = t1.latest && t1.latest.page ? num(t1.latest.page.rafFps) : null;

  // ---- tables ----
  console.log(`\n-- per-tile (decode/present over the window) --`);
  console.log(`  # label        variant     cell        overdraw  decFps preFps  drop%`);
  for (const t of tiles) {
    console.log(
      `  ${String(t.index).padEnd(2)} ${(t.label || "").slice(0, 11).padEnd(11)} ` +
      `${t.variant.padEnd(11)} ${t.cell.padEnd(11)} ` +
      `${(t.overdrawX == null ? "—" : t.overdrawX.toFixed(1) + "x").padStart(7)}  ` +
      `${f1(t.decodedFps)} ${f1(t.presentedFps)}  ${t.dropPct == null ? "  – " : (t.dropPct.toFixed(0) + "%").padStart(4)}` +
      `${t.paused ? "  (paused)" : ""}`,
    );
  }
  const measured = tiles.filter((t) => num(t.presentedFps) != null);
  const minPres = measured.length ? Math.min(...measured.map((t) => t.presentedFps)) : null;
  const maxDrop = measured.length ? Math.max(...measured.map((t) => num(t.dropPct) || 0)) : null;
  const worstOver = tiles.map((t) => num(t.overdrawX)).filter((x) => x != null);

  console.log(`\n-- per-stage roll-up --`);
  console.log(`  tiles measured        : ${measured.length}/${tiles.length}`);
  console.log(`  slowest tile present  : ${f1(minPres)} fps   (bar: >=28)`);
  console.log(`  worst tile drop       : ${maxDrop == null ? "–" : maxDrop.toFixed(1) + "%"}   (bar: <5%)`);
  console.log(`  worst variant overdraw: ${worstOver.length ? Math.max(...worstOver).toFixed(1) + "x" : "–"}`);
  console.log(`  page paint (rAF)      : ${f1(pageRaf)} fps`);
  console.log(`  page paint (x11 uniq) : ${f1(paint.uniqueFps)} fps   [browser framebuffer, mpdecimate]`);
  console.log(`  encoder CFR out       : ${f1(encoder.cfrFps)} fps`);
  console.log(`  encoder unique out    : ${f1(encoder.uniqueFps)} fps   [mpdecimate — masks per-tile judder]`);
  console.log(`  container CPU         : ${f1(load.containerCpuPctAvg)}% avg / ${f1(load.containerCpuPctMax)}% max  (of ${load.cores * 100}% = ${load.cores} cores)`);
  console.log(`  per-proc CPU          : chromium ${f1(load.chromiumCpuPctAvg)}%  ffmpeg ${f1(load.ffmpegCpuPctAvg)}%`);

  const json = {
    label: LABEL, window_s: WINDOW_S, started,
    tiles, minPresentedFps: minPres, maxDropPct: maxDrop,
    worstOverdrawX: worstOver.length ? Math.max(...worstOver) : null,
    pageRafFps: pageRaf, pagePaintUniqueFps: paint.uniqueFps,
    encoderCfrFps: encoder.cfrFps, encoderUniqueFps: encoder.uniqueFps,
    load,
  };
  console.log(`\nBENCH_JSON:${JSON.stringify(json)}`);
}

main().catch((e) => { console.error("bench failed:", e); process.exit(1); });
