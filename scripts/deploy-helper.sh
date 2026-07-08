#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ -f "$SCRIPT_DIR/deploy.local.env" ]] && source "$SCRIPT_DIR/deploy.local.env"

# MyMTS helper deploy — Stage 2.
#
# Builds the image with BUILD_SHA + BUILD_VERSION from the current git
# state, copies the artifact set to the helper host, and runs the full
# pull → rebuild → restart cycle. Verifies /health responds with the
# new SHA before declaring success.
#
# The deploy follows the standing standard: a bare restart never picks
# up new code; this script does the full cycle and verifies.
#
# Hard rule: never touches unrelated services/containers sharing the
# helper's host; the helper runs isolated on its own network. The compose
# file defines its own internal network and references nothing else on
# the host.

usage() {
    cat <<EOF
Usage: $0 [--host <user@host>] [--remote-path <path>] [--only <service>]

  --only <service>  Scope build + up to ONE compose service (e.g. \`helper\`),
                    leaving the sibling \`renderer\` untouched (it shares this
                    compose since the headless-container version). Default: all.

Defaults:
  --host         my-helper-host           (set in scripts/deploy.local.env)
  --remote-path  /srv/mymts-helper        (set in scripts/deploy.local.env)

This script:
  1. Computes BUILD_SHA + BUILD_VERSION from git.
  2. rsyncs helper/ to <host>:<remote-path>/_src/
  3. Renders deploy/docker-compose.nas.yml into <remote-path>/compose.yml.
  4. Ensures data/ exists.
  5. SSHes to host and runs:
        docker compose build --pull
        docker compose up -d
  6. Polls /health from the helper host itself until it returns the new
     SHA (or fails after a timeout).

Pre-reqs on the helper host:
  - docker + docker compose v2
  - the operator's standard docker layout
  - unrelated services/containers untouched (this script never references
    them; the helper runs isolated on its own network)
EOF
    exit 2
}

HOST="${MYMTS_NAS_HOST:-my-helper-host}"   # SSH alias resolves to your helper host
REMOTE_PATH="${MYMTS_HELPER_REMOTE_PATH:-/srv/mymts-helper}"
export MYMTS_HELPER_REMOTE_PATH="$REMOTE_PATH"
# --only <service>: scope the build + up to ONE compose service (e.g. `helper`)
# so a helper-only change does NOT rebuild/recreate the sibling `renderer`
# (which shares this compose since the headless-container version). Empty =
# all services (the prior behavior). The /health verify always targets helper.
ONLY=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --host) HOST="$2"; shift 2 ;;
        --remote-path) REMOTE_PATH="$2"; export MYMTS_HELPER_REMOTE_PATH="$2"; shift 2 ;;
        --only) ONLY="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "unknown arg: $1" >&2; usage ;;
    esac
done

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

BUILD_SHA=$(git rev-parse --short HEAD)
# Only SEMVER release tags (v*) drive the image version — NEVER a
# `pre-professionalization-*` rollback/snapshot tag, which `git describe` would
# otherwise pick when it's the closest tag, tagging the running image with an
# incident-era name (the §5 advisory). So the image is always `mymts-helper:<semver>`.
BUILD_VERSION=$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || echo "0.0.0-dev")
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
# permissioned for uid 10001. To inspect state from the helper host:
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

# LAN web client static files (repo-root web/, a sibling of helper/ so
# it isn't in the image build context). The helper compose bind-mounts
# _web/ read-only at /app/web; WEB_CLIENT_DIR (set in .env below) turns
# the mount on. Inert static files only — no secrets, credential-free.
echo "==> rsyncing web client static files"
ssh "$HOST" "mkdir -p '$REMOTE_PATH/_web'"
rsync -az --delete \
    --exclude '.DS_Store' \
    web/ "$HOST:$REMOTE_PATH/_web/"

# Renderer build context (headless-container version): the renderer/ tree
# (Dockerfile + supervisor + run.py) is the build context for the mymts-renderer
# service in the compose. Rsynced like the helper source; it has no secrets.
echo "==> rsyncing renderer build context"
ssh "$HOST" "mkdir -p '$REMOTE_PATH/_renderer'"
rsync -az --delete \
    --exclude '.DS_Store' \
    --exclude '__pycache__/' \
    renderer/ "$HOST:$REMOTE_PATH/_renderer/"

# Discord Activity static app (repo-root discord-activity/, a sibling of helper/
# so it isn't in the image build context). The helper compose bind-mounts
# _activity/ read-only at /app/activity; the dedicated public app serves it (with
# /api/discord/* + the /api/stream passthrough) behind the operator's CF tunnel.
# Inert static files only (HTML/CSS/JS + vendored SDK/hls.js) — no secrets.
echo "==> rsyncing discord activity static app"
ssh "$HOST" "mkdir -p '$REMOTE_PATH/_activity'"
rsync -az --delete \
    --exclude '.DS_Store' \
    discord-activity/ "$HOST:$REMOTE_PATH/_activity/"

echo "==> rendering compose.yml on the helper host"
ssh "$HOST" "cp '$REMOTE_PATH/_src/deploy/docker-compose.nas.yml' '$REMOTE_PATH/compose.yml'"

echo "==> snapshotting current helper for rollback (DEPLOY-3)"
# Record the currently-running build_sha and tag its image as the rollback handle, so a
# failed deploy can revert (the rebuild reuses the same BUILD_VERSION tag and
# would otherwise overwrite the prior good image in place with nothing to roll
# back to). Scoped to mymts-helper:* only — never touches other containers and
# never the named data volume (mymts-helper-data).
PREV_SHA="$(ssh "$HOST" "docker exec mymts-helper curl -fsSk https://127.0.0.1:8443/health 2>/dev/null | python3 -c 'import json,sys;print(json.load(sys.stdin).get(\"build_sha\",\"\"))' 2>/dev/null" || true)"
ssh "$HOST" "img=\$(docker inspect --format '{{.Image}}' mymts-helper 2>/dev/null || true); if [[ -n \"\$img\" ]]; then docker tag \"\$img\" mymts-helper:rollback && echo '    tagged mymts-helper:rollback (prev build_sha=$PREV_SHA)'; else echo '    (no running helper to snapshot — first deploy?)'; fi"

echo "==> writing/refreshing .env (build identity + defaults)"
# MYMTS_HELPER_REMOTE_PATH MUST be in the .env: the compose file bind-mounts
# the TLS certs (`${MYMTS_HELPER_REMOTE_PATH:-/srv/mymts-helper}/_secrets`) and
# the web client (`.../_web`) using this var. `docker compose` runs on the NAS
# over ssh, where this var is NOT in the shell env — so without it in .env, the
# mounts silently fall back to the `/srv/mymts-helper` DEFAULT, docker creates
# an empty dir there, and the helper crash-loops on a missing cert
# (`load_cert_chain: FileNotFoundError`). Write the real path so the mounts
# resolve to the actual deploy dir.
ssh "$HOST" "cat > '$REMOTE_PATH/.env'" <<EOF
BUILD_SHA=$BUILD_SHA
BUILD_VERSION=$BUILD_VERSION
MYMTS_HELPER_REMOTE_PATH=$REMOTE_PATH
PHANTOM_MODE=0
LOG_LEVEL=info
FEED_POLL_INTERVAL_SECONDS=300
FEED_RETENTION_DAYS=14
CHANNEL_PROBE_INTERVAL_SECONDS=1800
WEB_CLIENT_DIR=/app/web
STREAM_DIR=/stream
STREAM_HTTP_PORT=8082
# Discord Activity output (2026-06-28). The dedicated public app (Activity +
# /api/discord/* + the /api/stream passthrough) starts on DISCORD_PUBLIC_PORT once
# the _activity mount is present. CLIENT ID is public; the SECRET is a secret — set
# both (and the public origin) as exports in the gitignored scripts/deploy.local.env
# when the operator registers the Discord app; default EMPTY → the card sits in
# needs-setup and posts NOTHING to Discord. Never hard-code the secret here.
DISCORD_CLIENT_ID=${DISCORD_CLIENT_ID:-}
DISCORD_CLIENT_SECRET=${DISCORD_CLIENT_SECRET:-}
DISCORD_ACTIVITY_PUBLIC_ORIGIN=${DISCORD_ACTIVITY_PUBLIC_ORIGIN:-}
DISCORD_ACTIVITY_DIR=${DISCORD_ACTIVITY_DIR:-/app/activity}
DISCORD_PUBLIC_PORT=${DISCORD_PUBLIC_PORT:-8084}
EOF

echo "==> docker compose build --pull + up -d (the full cycle)"
ssh "$HOST" "cd '$REMOTE_PATH' && docker compose -f compose.yml --env-file .env build --pull $ONLY && docker compose -f compose.yml --env-file .env up -d $ONLY"

echo "==> waiting for /health to report the new SHA"
# HTTPS-only since the TLS cutover (2026-06-04): the helper serves
# 8443 and no longer listens on 8091. Poll the in-container HTTPS
# endpoint with -k (loopback, self-fetch — cert verification is moot;
# we only want liveness + the SHA). Previously this hit :8091 and would
# report a false failure on an otherwise-good deploy.
if ssh "$HOST" "bash -s" <<EOF
for i in \$(seq 1 30); do
    SHA=\$(docker exec mymts-helper curl -fsSk https://127.0.0.1:8443/health 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin).get("build_sha","?"))' 2>/dev/null || echo "")
    if [[ "\$SHA" == "$BUILD_SHA" ]]; then
        echo "    /health build_sha matches: \$SHA"
        exit 0
    fi
    sleep 1
done
echo "    /health never reported build_sha=$BUILD_SHA (last saw: \$SHA)" >&2
exit 1
EOF
then
    echo "==> done"
else
    echo "==> DEPLOY VERIFY FAILED — attempting rollback to the prior (rollback) image (DEPLOY-3)" >&2
    # Auto-revert: restore the rollback image under the deploy tag, restore its
    # build_sha in .env, bring it up, and confirm it serves. The data volume is
    # never touched; only mymts-helper:* tags are moved. If anything is
    # uncertain, the manual recovery command is always printed.
    rolled_back=0
    if [[ -n "$PREV_SHA" ]] && ssh "$HOST" "docker image inspect mymts-helper:rollback >/dev/null 2>&1"; then
        if ssh "$HOST" "docker tag mymts-helper:rollback mymts-helper:$BUILD_VERSION && sed -i.bak 's/^BUILD_SHA=.*/BUILD_SHA=$PREV_SHA/' '$REMOTE_PATH/.env' && cd '$REMOTE_PATH' && docker compose -f compose.yml --env-file .env up -d && for i in \$(seq 1 20); do docker exec mymts-helper curl -fsSk https://127.0.0.1:8443/health >/dev/null 2>&1 && exit 0; sleep 1; done; exit 1"; then
            echo "==> ROLLED BACK to the rollback image (build_sha=$PREV_SHA); helper is serving again." >&2
            rolled_back=1
        fi
    fi
    if [[ "$rolled_back" != "1" ]]; then
        echo "!! Auto-rollback could not confirm a healthy helper. MANUAL RECOVERY:" >&2
        echo "   ssh $HOST 'docker tag mymts-helper:rollback mymts-helper:$BUILD_VERSION && cd $REMOTE_PATH && docker compose -f compose.yml --env-file .env up -d'" >&2
        echo "   (or: git checkout a known-good helper sha and re-run scripts/deploy-helper.sh)" >&2
    fi
    exit 1
fi
