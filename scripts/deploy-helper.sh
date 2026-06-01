#!/usr/bin/env bash
set -euo pipefail

# MyMTS helper deploy — Stage 2.
#
# Builds the image with BUILD_SHA + BUILD_VERSION from the current git
# state, copies the artifact set to the NAS, and runs the full
# pull → rebuild → restart cycle. Verifies /health responds with the
# new SHA before declaring success.
#
# The deploy follows the standing standard: a bare restart never picks
# up new code; this script does the full cycle and verifies.
#
# Hard rule: NEVER touches the unrelated host container. The compose file
# defines its own internal network and references nothing else on the
# NAS.

usage() {
    cat <<EOF
Usage: $0 [--host <user@nas>] [--remote-path <path>]

Defaults:
  --host         cargo@cargo.local        (override via MYMTS_NAS_HOST env)
  --remote-path  /srv/docker/mymts-helper

This script:
  1. Computes BUILD_SHA + BUILD_VERSION from git.
  2. rsyncs helper/ to <host>:<remote-path>/_src/
  3. Renders deploy/docker-compose.nas.yml into <remote-path>/compose.yml.
  4. Ensures data/ exists.
  5. SSHes to host and runs:
        docker compose build --pull
        docker compose up -d
  6. Polls /health from the NAS itself until it returns the new SHA
     (or fails after a timeout).

Pre-reqs on the NAS:
  - docker + docker compose v2
  - the operator's standard /srv/docker layout
  - unrelated host container untouched (this script never references it)
EOF
    exit 2
}

HOST="${MYMTS_NAS_HOST:-<HOST>}"   # SSH alias resolves to cargo@<LAN_IP>
REMOTE_PATH="/srv/docker/mymts-helper"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --host) HOST="$2"; shift 2 ;;
        --remote-path) REMOTE_PATH="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "unknown arg: $1" >&2; usage ;;
    esac
done

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

BUILD_SHA=$(git rev-parse --short HEAD)
BUILD_VERSION=$(git describe --tags --abbrev=0 2>/dev/null || echo "0.0.0-dev")
BUILD_VERSION="${BUILD_VERSION#v}"
# Docker tags reject '+'; semver build-metadata ('+dirty') would break the
# image tag. We track dirty-ness via a '-dirty' suffix instead, which is a
# valid Docker tag character. The /health build_sha stays exact.
if ! git diff --quiet || ! git diff --cached --quiet; then
    BUILD_VERSION="${BUILD_VERSION}-dirty"
fi

echo "==> deploying helper"
echo "    host=$HOST  remote-path=$REMOTE_PATH"
echo "    build_sha=$BUILD_SHA  build_version=$BUILD_VERSION"

echo "==> ensuring remote layout"
ssh "$HOST" "mkdir -p '$REMOTE_PATH/_src'"
# Note: container state lives in the named volume `mymts-helper-data`
# (not a bind mount), so the host directory layout doesn't need to be
# permissioned for uid 10001. To inspect state from the NAS host:
#   docker volume inspect mymts-helper-data
#   docker exec mymts-helper ls -la /data
# To back up:
#   docker run --rm -v mymts-helper-data:/data alpine tar -czf - -C / data > backup.tgz

echo "==> rsyncing helper source"
rsync -az --delete \
    --exclude '.venv/' \
    --exclude '__pycache__/' \
    --exclude '.pytest_cache/' \
    --exclude '.ruff_cache/' \
    --exclude 'data/' \
    --exclude '.env' \
    helper/ "$HOST:$REMOTE_PATH/_src/"

echo "==> rendering compose.yml on the NAS"
ssh "$HOST" "cp '$REMOTE_PATH/_src/deploy/docker-compose.nas.yml' '$REMOTE_PATH/compose.yml'"

echo "==> writing/refreshing .env (build identity + defaults)"
ssh "$HOST" "cat > '$REMOTE_PATH/.env'" <<EOF
BUILD_SHA=$BUILD_SHA
BUILD_VERSION=$BUILD_VERSION
PHANTOM_MODE=0
LOG_LEVEL=info
FEED_POLL_INTERVAL_SECONDS=300
FEED_RETENTION_DAYS=14
CHANNEL_PROBE_INTERVAL_SECONDS=1800
EOF

echo "==> docker compose build --pull + up -d (the full cycle)"
ssh "$HOST" "cd '$REMOTE_PATH' && docker compose -f compose.yml --env-file .env build --pull && docker compose -f compose.yml --env-file .env up -d"

echo "==> waiting for /health to report the new SHA"
ssh "$HOST" "bash -s" <<EOF
for i in \$(seq 1 30); do
    SHA=\$(docker exec mymts-helper curl -fsS http://127.0.0.1:8091/health 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin).get("build_sha","?"))' 2>/dev/null || echo "")
    if [[ "\$SHA" == "$BUILD_SHA" ]]; then
        echo "    /health build_sha matches: \$SHA"
        exit 0
    fi
    sleep 1
done
echo "    /health never reported build_sha=$BUILD_SHA (last saw: \$SHA)" >&2
exit 1
EOF

echo "==> done"
