#!/bin/sh
# Synthetic HIGH-MOTION, MULTI-VARIANT live HLS — the render-bench control source.
#
# WHY: to separate "the wall judders because the content is still" from "the wall
# judders because the pipeline drops frames", the control run needs a source whose
# motion is KNOWN by construction: every frame differs (a full-frame testsrc2 field
# + a per-frame timecode), at a deterministic 30fps. And it is MULTI-VARIANT
# (1080p/720p/480p) so the capLevelToPlayerSize fix has lower renditions to pick —
# exactly like the operator's real (multi-bitrate CDN) news streams, so the fix's
# decode-load reduction is measured on a faithful analog.
#
# Runs INSIDE the renderer container (it has ffmpeg + python3 + the DejaVu font).
# Serves the stream on 0.0.0.0:$PORT so BOTH the render Chrome and the helper's
# prober reach it by the container's compose DNS name (mymts-renderer). Ephemeral:
# everything lives under /tmp (tmpfs) and is torn down by `stop`.
#
#   Usage (from the NAS host):
#     docker exec -d mymts-renderer sh /app/render-bench/make-clock-hls.sh start
#     docker exec    mymts-renderer sh /app/render-bench/make-clock-hls.sh stop
#
# (The bench README copies this script into the container; it is NOT baked into the
# image — it is a measurement tool, not a runtime dependency.)

set -eu

DIR="${BENCH_CLOCK_DIR:-/tmp/bench-clock}"
PORT="${BENCH_CLOCK_PORT:-8099}"
FONT="/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
[ -f "$FONT" ] || FONT="/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
PIDDIR="$DIR/.pids"

start() {
  mkdir -p "$DIR" "$PIDDIR"
  # A full-frame moving test field guarantees per-frame change (high motion by
  # construction); the centered running timecode makes every frame provably unique
  # and human-readable in the captured tile. split into 3, scale to the ladder,
  # encode 3 renditions + a shared tone, emit a live master + rolling segments.
  ffmpeg -hide_banner -loglevel error -nostdin \
    -f lavfi -i "testsrc2=size=1920x1080:rate=30" \
    -f lavfi -i "sine=frequency=1000:sample_rate=48000" \
    -filter_complex "\
[0:v]drawtext=fontfile=${FONT}:text='f=%{n}':fontsize=120:fontcolor=white:borderw=6:bordercolor=black:x=(w-tw)/2:y=(h-th)/2,\
split=3[v0][v1][v2];\
[v1]scale=1280:720[v1o];\
[v2]scale=854:480[v2o]" \
    -map "[v0]" -map 1:a \
    -map "[v1o]" -map 1:a \
    -map "[v2o]" -map 1:a \
    -c:v libx264 -preset veryfast -tune zerolatency -pix_fmt yuv420p -g 60 -sc_threshold 0 \
    -b:v:0 6M -maxrate:v:0 6M -bufsize:v:0 12M \
    -b:v:1 3M -maxrate:v:1 3M -bufsize:v:1 6M \
    -b:v:2 1M -maxrate:v:2 1M -bufsize:v:2 2M \
    -c:a aac -b:a 128k -ar 48000 \
    -f hls -hls_time 2 -hls_list_size 6 \
    -hls_flags delete_segments+append_list+independent_segments \
    -hls_segment_type mpegts \
    -master_pl_name master.m3u8 \
    -var_stream_map "v:0,a:0 v:1,a:1 v:2,a:2" \
    "$DIR/stream_%v.m3u8" >"$DIR/ffmpeg.log" 2>&1 &
  echo $! > "$PIDDIR/ffmpeg.pid"

  # A dead-simple static server for the segments (no CORS needed — the tiles fetch
  # same-scheme http; hls.js handles it). Bound to all interfaces so the helper
  # prober + render Chrome reach it via the container DNS name.
  ( cd "$DIR" && python3 -m http.server "$PORT" --bind 0.0.0.0 >"$DIR/http.log" 2>&1 & echo $! > "$PIDDIR/http.pid" )

  # Wait for the master to appear so the caller can wire the channel immediately.
  i=0
  while [ ! -f "$DIR/master.m3u8" ] && [ "$i" -lt 30 ]; do i=$((i+1)); sleep 0.5; done
  if [ -f "$DIR/master.m3u8" ]; then
    echo "clock up: http://mymts-renderer:${PORT}/master.m3u8 (dir=$DIR)"
  else
    echo "clock FAILED to start — see $DIR/ffmpeg.log" >&2
    tail -5 "$DIR/ffmpeg.log" >&2 || true
    exit 1
  fi
}

stop() {
  for p in ffmpeg http; do
    if [ -f "$PIDDIR/$p.pid" ]; then kill "$(cat "$PIDDIR/$p.pid")" 2>/dev/null || true; fi
  done
  # belt-and-suspenders: kill any stray clock ffmpeg / server on our dir/port
  pkill -f "master_pl_name master.m3u8" 2>/dev/null || true
  pkill -f "http.server ${PORT}" 2>/dev/null || true
  rm -rf "$DIR"
  echo "clock stopped + cleaned ($DIR)"
}

case "${1:-start}" in
  start) start ;;
  stop)  stop ;;
  *) echo "usage: $0 start|stop" >&2; exit 2 ;;
esac
