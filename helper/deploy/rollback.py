#!/usr/bin/env python3
"""Pre-deploy capture and full restore of every component a helper deploy changes.

Runs ON THE HELPER HOST: scripts/deploy-helper.sh copies this file and db_backup.py
into a fresh evidence directory and runs `capture` over ssh before it changes
anything. stdlib only (the host's python3), and it drives the docker CLI.

Components (the MYMTS-024 findings) and what capture writes to the evidence dir:

  helper_image    the running helper's image id, held under
                  mymts-helper:predeploy-<id>, a tag no deploy step moves
  renderer_image  the same for mymts-renderer
  renderer_state  the renderer container record (image, StartedAt, restarts, build);
                  restored by recreating the renderer on its held image, so Chromium
                  reloads the restored assets instead of keeping a stale page
  web_assets      _web as a tar, with the tar's sha256 and a tree digest
  source_state    _src and _renderer as tars; compose.yml and .env as copies
  database        a consistent SQLite online backup of the data volume, taken inside
                  the running helper, copied out and checksum-verified

`restore` changes nothing until every artifact checksum and held image is verified,
and refuses an incomplete capture outright (a partial rollback is never offered).
Then it stops both services; moves each changed tree and file aside as
`<name>.superseded-<utc>` and puts the captured one in place; retags the held images
to the captured version; checkpoints and restores the database in a one-shot,
network-less container (the prior database is preserved, not deleted); recreates
both services; and verifies images, trees, files, the renderer's recreation and the
helper serving the captured build.

Nothing is ever deleted: no prune, no rm, no cleanup, no retention count.

Usage:
  rollback.py capture --deploy-dir DIR --evidence-dir DIR
  rollback.py restore --deploy-dir DIR --evidence-dir DIR

Exit codes: 0 ok, 1 restore ran but did not verify, 2 usage, 4 refused or failed,
5 capture incomplete while something is deployed (a deploy must not proceed).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tarfile
import time
from datetime import datetime, timezone
from pathlib import Path

SCHEMA_VERSION = 1
COMPONENTS = (
    "helper_image",
    "renderer_image",
    "renderer_state",
    "web_assets",
    "source_state",
    "database",
)

HELPER_CONTAINER = "mymts-helper"
RENDERER_CONTAINER = "mymts-renderer"
HELPER_REPO = "mymts-helper"
RENDERER_REPO = "mymts-renderer"
HOLD_TAG_PREFIX = "predeploy-"

WEB_TREES = ("_web",)
SOURCE_TREES = ("_src", "_renderer")
SOURCE_FILES = ("compose.yml", ".env")

DATA_MOUNT = "/data"
CONTAINER_DB = "/data/mymts-helper.db"
CONTAINER_BACKUP_ROOT = "/data/_rollback"
BACKUP_NAME = "mymts-helper.db"
BACKUP_SIDECAR_SUFFIX = ".sha256"
CONTAINER_USER = "10001:10001"
DB_BACKUP_SCRIPT = "db_backup.py"
DB_NOT_FOUND_MARKER = b"database not found"

HEALTH_URL = "https://127.0.0.1:8443/health"
HEALTH_ATTEMPTS = 30
HEALTH_INTERVAL_SECONDS = 1.0

MANIFEST_NAME = "manifest.json"
RENDERER_STATE_NAME = "renderer-state.json"
SUPERSEDED_MARKER = ".superseded-"
EVIDENCE_DIR_MODE = 0o700
# The one-shot restore container runs as the helper's uid and must read this dir.
DATABASE_DIR_MODE = 0o755
DATABASE_FILE_MODE = 0o644
HASH_CHUNK_BYTES = 1 << 20
STDERR_EXCERPT_CHARS = 500

EXIT_OK = 0
EXIT_UNVERIFIED = 1
EXIT_USAGE = 2
EXIT_FAILED = 4
EXIT_INCOMPLETE = 5


class RollbackError(Exception):
    """Refused or failed; the message says whether anything was changed."""


def run_command(argv, *, cwd=None, input=None):  # noqa: A002 (mirrors subprocess.run)
    return subprocess.run(  # noqa: S603 (fixed docker argv, never a shell)
        argv, cwd=cwd, input=input, capture_output=True, check=False
    )


def log(message: str) -> None:
    sys.stderr.write(f"    [rollback] {message}\n")


def utc_stamp() -> str:
    # timezone.utc rather than datetime.UTC: the host's python3 may be older.
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")  # noqa: UP017


def sha256_file(path) -> str:
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for chunk in iter(lambda: f.read(HASH_CHUNK_BYTES), b""):
            h.update(chunk)
    return h.hexdigest()


def tree_digest(root) -> str:
    """Digest of a tree's structure and bytes: files by sha256, symlinks by target,
    empty directories by name. Symlinks are never followed."""
    root = Path(root)
    entries = []
    for dirpath, dirnames, filenames in os.walk(root):
        base = Path(dirpath)
        for name in dirnames + filenames:
            p = base / name
            rel = p.relative_to(root).as_posix()
            if p.is_symlink():
                entries.append(f"L {os.readlink(p)} {rel}")
            elif p.is_dir():
                entries.append(f"D {rel}")
            else:
                entries.append(f"F {sha256_file(p)} {rel}")
    return hashlib.sha256("\n".join(sorted(entries)).encode("utf-8")).hexdigest()


def _check(proc, what: str):
    if proc.returncode != 0:
        err = proc.stderr.decode("utf-8", "replace").strip()[:STDERR_EXCERPT_CHARS]
        raise RollbackError(f"{what} failed (exit {proc.returncode}): {err}")
    return proc


def _inspect(run, container: str):
    proc = run(["docker", "inspect", container])
    if proc.returncode != 0:
        if b"No such object" in proc.stderr:
            return None
        _check(proc, f"docker inspect {container}")
    data = json.loads(proc.stdout or b"[]")
    return data[0] if data else None


def _env_of(info, key: str):
    for item in (info.get("Config") or {}).get("Env") or []:
        if item.startswith(key + "="):
            return item.split("=", 1)[1]
    return None


def _parse_env(text: str) -> dict:
    out = {}
    for line in text.splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def _copy_new(src: Path, dest: Path) -> None:
    with src.open("rb") as fin, dest.open("xb") as fout:
        shutil.copyfileobj(fin, fout, HASH_CHUNK_BYTES)
    shutil.copymode(src, dest)


def _make_tar(src: Path, dest: Path) -> None:
    with tarfile.open(dest, "x") as tar:
        tar.add(str(src), arcname=".")


def _extract_tar(tar_path: Path, dest: Path) -> None:
    dest.mkdir()
    with tarfile.open(tar_path, "r") as tar:
        if hasattr(tarfile, "tar_filter"):
            tar.extractall(dest, filter="tar")  # noqa: S202 (own checksum-verified tar)
            return
        for member in tar.getmembers():
            name = Path(member.name)
            if name.is_absolute() or ".." in name.parts:
                raise RollbackError(f"unsafe path in {tar_path.name}: {member.name}")
        tar.extractall(dest)  # noqa: S202 (own checksum-verified tar, paths checked)


def _write_json_new(path: Path, data) -> None:
    with path.open("x", encoding="utf-8") as f:
        json.dump(data, f, indent=2, sort_keys=True)
        f.write("\n")


# ---- capture ----


def _hold_image(run, info, container: str, repo: str, rollback_id: str) -> dict:
    if info is None:
        return {"captured": False, "reason": f"container {container} is not present"}
    image_id = info["Image"]
    tag = f"{repo}:{HOLD_TAG_PREFIX}{rollback_id}"
    _check(run(["docker", "tag", image_id, tag]), f"docker tag {tag}")
    log(f"held {container} image {image_id} as {tag}")
    return {"captured": True, "container": container, "image_id": image_id, "hold_tag": tag}


def _capture_renderer_state(info, evidence: Path) -> dict:
    if info is None:
        return {"captured": False, "reason": f"container {RENDERER_CONTAINER} is not present"}
    record = {
        "image_id": info["Image"],
        "started_at": (info.get("State") or {}).get("StartedAt"),
        "restart_count": info.get("RestartCount"),
        "env_build_sha": _env_of(info, "BUILD_SHA"),
    }
    _write_json_new(evidence / RENDERER_STATE_NAME, record)
    return {
        "captured": True,
        "record": RENDERER_STATE_NAME,
        "sha256": sha256_file(evidence / RENDERER_STATE_NAME),
        **record,
    }


def _capture_trees(deploy: Path, evidence: Path, trees, files) -> dict:
    out = {"captured": True, "trees": {}, "files": {}}
    missing = []
    for name in trees:
        src = deploy / name
        if not src.is_dir():
            missing.append(name)
            continue
        (evidence / "trees").mkdir(exist_ok=True)
        rel = f"trees/{name}.tar"
        digest = tree_digest(src)
        _make_tar(src, evidence / rel)
        out["trees"][name] = {
            "tar": rel,
            "tar_sha256": sha256_file(evidence / rel),
            "tree_digest": digest,
        }
        log(f"captured {name} (tree {digest[:12]})")
    for name in files:
        src = deploy / name
        if not src.is_file():
            missing.append(name)
            continue
        (evidence / "files").mkdir(exist_ok=True)
        rel = f"files/{name}"
        _copy_new(src, evidence / rel)
        out["files"][name] = {"copy": rel, "sha256": sha256_file(evidence / rel)}
        log(f"captured {name}")
    if missing:
        out["captured"] = False
        out["reason"] = "absent from the deploy dir: " + ", ".join(missing)
    return out


def _capture_database(run, helper, evidence: Path, rollback_id: str, script: bytes) -> dict:
    if helper is None:
        return {"captured": False, "reason": f"container {HELPER_CONTAINER} is not present"}
    if not (helper.get("State") or {}).get("Running"):
        return {"captured": False, "reason": f"{HELPER_CONTAINER} is not running"}
    volume = next(
        (
            m.get("Name")
            for m in helper.get("Mounts") or []
            if m.get("Destination") == DATA_MOUNT and m.get("Type") == "volume"
        ),
        None,
    )
    if not volume:
        return {"captured": False, "reason": f"no named volume mounted at {DATA_MOUNT}"}
    in_container = f"{CONTAINER_BACKUP_ROOT}/{rollback_id}"
    proc = _check(
        run(
            [
                "docker", "exec", "-i", HELPER_CONTAINER, "python", "-", "backup",
                "--db", CONTAINER_DB, "--out-dir", in_container, "--name", BACKUP_NAME,
            ],
            input=script,
        ),
        "online database backup inside the helper",
    )
    reported = json.loads(proc.stdout)["sha256"]
    dbdir = evidence / "database"
    dbdir.mkdir()
    os.chmod(dbdir, DATABASE_DIR_MODE)
    for name in (BACKUP_NAME, BACKUP_NAME + BACKUP_SIDECAR_SUFFIX):
        _check(
            run(["docker", "cp", f"{HELPER_CONTAINER}:{in_container}/{name}", str(dbdir / name)]),
            f"docker cp {name}",
        )
        os.chmod(dbdir / name, DATABASE_FILE_MODE)
    local = sha256_file(dbdir / BACKUP_NAME)
    sidecar = (dbdir / (BACKUP_NAME + BACKUP_SIDECAR_SUFFIX)).read_text(encoding="utf-8").split()
    if local != reported or not sidecar or sidecar[0] != local:
        raise RollbackError("database backup checksum changed between container and host")
    log(f"captured database from volume {volume} (sha256 {local[:12]})")
    return {
        "captured": True,
        "volume": volume,
        "backup": f"database/{BACKUP_NAME}",
        "sha256": local,
        "in_volume_copy": f"{in_container}/{BACKUP_NAME}",
    }


def capture(deploy_dir, evidence_dir, *, run=run_command, tools_dir=None) -> dict:
    deploy = Path(deploy_dir)
    evidence = Path(evidence_dir)
    tools = Path(tools_dir) if tools_dir is not None else Path(__file__).resolve().parent
    script = (tools / DB_BACKUP_SCRIPT).read_bytes()
    if (evidence / MANIFEST_NAME).exists():
        raise RollbackError(f"{evidence} already holds a capture; use a new evidence dir")
    if not deploy.is_dir():
        raise RollbackError(f"deploy dir not found: {deploy}")
    evidence.mkdir(parents=True, exist_ok=True)
    os.chmod(evidence, EVIDENCE_DIR_MODE)
    rollback_id = evidence.name

    helper = _inspect(run, HELPER_CONTAINER)
    renderer = _inspect(run, RENDERER_CONTAINER)
    components = {
        "helper_image": _hold_image(run, helper, HELPER_CONTAINER, HELPER_REPO, rollback_id),
        "renderer_image": _hold_image(
            run, renderer, RENDERER_CONTAINER, RENDERER_REPO, rollback_id
        ),
        "renderer_state": _capture_renderer_state(renderer, evidence),
        "web_assets": _capture_trees(deploy, evidence, WEB_TREES, ()),
        "source_state": _capture_trees(deploy, evidence, SOURCE_TREES, SOURCE_FILES),
        "database": _capture_database(run, helper, evidence, rollback_id, script),
    }
    manifest = {
        "schema_version": SCHEMA_VERSION,
        "rollback_id": rollback_id,
        "captured_at": utc_stamp(),
        "deploy_dir": str(deploy),
        "complete": all(c["captured"] for c in components.values()),
        "nothing_deployed": helper is None and renderer is None,
        "components": components,
    }
    _write_json_new(evidence / MANIFEST_NAME, manifest)
    return manifest


# ---- restore ----


def _verify_evidence(evidence: Path, comps: dict) -> None:
    artifacts = []
    for comp in ("web_assets", "source_state"):
        for rec in comps[comp]["trees"].values():
            artifacts.append((rec["tar"], rec["tar_sha256"]))
        for rec in comps[comp]["files"].values():
            artifacts.append((rec["copy"], rec["sha256"]))
    artifacts.append((comps["renderer_state"]["record"], comps["renderer_state"]["sha256"]))
    artifacts.append((comps["database"]["backup"], comps["database"]["sha256"]))
    bad = []
    for rel, want in artifacts:
        path = evidence / rel
        if not path.is_file():
            bad.append(f"{rel} (missing)")
        elif sha256_file(path) != want:
            bad.append(rel)
    if bad:
        raise RollbackError(
            "evidence checksum mismatch, nothing was changed: " + ", ".join(bad)
        )


def _set_aside(path: Path, stamp: str, superseded: list) -> None:
    if path.exists() or path.is_symlink():
        aside = path.with_name(f"{path.name}{SUPERSEDED_MARKER}{stamp}")
        path.rename(aside)
        superseded.append(str(aside))


def restore(
    deploy_dir,
    evidence_dir,
    *,
    run=run_command,
    tools_dir=None,
    sleep=time.sleep,
    health_attempts: int = HEALTH_ATTEMPTS,
) -> dict:
    deploy = Path(deploy_dir)
    evidence = Path(evidence_dir)
    tools = Path(tools_dir) if tools_dir is not None else Path(__file__).resolve().parent
    script = (tools / DB_BACKUP_SCRIPT).read_bytes()
    manifest_path = evidence / MANIFEST_NAME
    if not manifest_path.is_file():
        raise RollbackError(f"no capture manifest in {evidence}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    comps = manifest["components"]

    # ---- verify everything before changing anything ----
    missing = [n for n in COMPONENTS if not (comps.get(n) or {}).get("captured")]
    if missing:
        raise RollbackError(
            f"capture is incomplete ({', '.join(missing)}): refusing a partial rollback, "
            "nothing was changed"
        )
    _verify_evidence(evidence, comps)
    for comp in ("helper_image", "renderer_image"):
        image_id = comps[comp]["image_id"]
        if run(["docker", "image", "inspect", image_id]).returncode != 0:
            raise RollbackError(
                f"held image {image_id} for {comp} no longer exists, nothing was changed"
            )
    env = _parse_env((evidence / comps["source_state"]["files"][".env"]["copy"]).read_text())
    version, build_sha = env.get("BUILD_VERSION"), env.get("BUILD_SHA")
    if not version or not build_sha:
        raise RollbackError("captured .env lacks BUILD_VERSION or BUILD_SHA, nothing was changed")
    before = _inspect(run, RENDERER_CONTAINER)
    renderer_started_before = ((before or {}).get("State") or {}).get("StartedAt")

    stamp = utc_stamp()
    compose = ["docker", "compose", "-f", "compose.yml", "--env-file", ".env"]
    superseded: list = []
    log("verified; stopping helper and renderer")
    _check(run([*compose, "stop"], cwd=str(deploy)), "docker compose stop")

    # ---- files: move the deployed ones aside, put the captured ones in place ----
    for comp, names in (("web_assets", WEB_TREES), ("source_state", SOURCE_TREES)):
        for name in names:
            _set_aside(deploy / name, stamp, superseded)
            _extract_tar(evidence / comps[comp]["trees"][name]["tar"], deploy / name)
            log(f"restored {name}")
    for name in SOURCE_FILES:
        _set_aside(deploy / name, stamp, superseded)
        _copy_new(evidence / comps["source_state"]["files"][name]["copy"], deploy / name)
        log(f"restored {name}")

    # ---- images: the captured version tag points at the held images again ----
    for comp, repo in (("helper_image", HELPER_REPO), ("renderer_image", RENDERER_REPO)):
        ref = f"{repo}:{version}"
        _check(run(["docker", "tag", comps[comp]["image_id"], ref]), f"docker tag {ref}")
        log(f"{ref} -> {comps[comp]['image_id']}")

    # ---- database: checkpoint, then restore with the prior file preserved ----
    db = comps["database"]
    helper_image = comps["helper_image"]["image_id"]
    one_shot = [
        "docker", "run", "--rm", "-i", "--network", "none", "--user", CONTAINER_USER,
        "-v", f"{db['volume']}:{DATA_MOUNT}",
    ]
    proc = run(
        [*one_shot, "--entrypoint", "python", helper_image, "-", "checkpoint",
         "--db", CONTAINER_DB],
        input=script,
    )
    if proc.returncode != 0 and DB_NOT_FOUND_MARKER not in proc.stderr:
        _check(proc, "database checkpoint")
    proc = _check(
        run(
            [*one_shot, "-v", f"{evidence / 'database'}:/in:ro", "--entrypoint", "python",
             helper_image, "-", "restore", "--backup", f"/in/{BACKUP_NAME}",
             "--target", CONTAINER_DB],
            input=script,
        ),
        "database restore",
    )
    restored_db = json.loads(proc.stdout)
    log(f"restored database (sha256 {restored_db['sha256'][:12]})")

    _check(run([*compose, "up", "-d", "--force-recreate"], cwd=str(deploy)), "docker compose up")
    log("recreated helper and renderer; verifying")

    # ---- verify the result ----
    helper = _inspect(run, HELPER_CONTAINER)
    renderer = _inspect(run, RENDERER_CONTAINER)
    checks = {}
    for comp, info in (("helper_image", helper), ("renderer_image", renderer)):
        want = comps[comp]["image_id"]
        got = (info or {}).get("Image")
        checks[comp] = {"ok": got == want, "detail": f"running {got}, captured {want}"}
    state = (renderer or {}).get("State") or {}
    checks["renderer_state"] = {
        "ok": bool(
            renderer
            and renderer.get("Image") == comps["renderer_state"]["image_id"]
            and state.get("Running")
            and state.get("StartedAt") != renderer_started_before
        ),
        "detail": f"renderer started {state.get('StartedAt')} "
        f"(before restore {renderer_started_before}) on {(renderer or {}).get('Image')}",
    }
    for comp, trees, files in (
        ("web_assets", WEB_TREES, ()),
        ("source_state", SOURCE_TREES, SOURCE_FILES),
    ):
        recorded = comps[comp]
        wrong = [n for n in trees if tree_digest(deploy / n) != recorded["trees"][n]["tree_digest"]]
        wrong += [n for n in files if sha256_file(deploy / n) != recorded["files"][n]["sha256"]]
        checks[comp] = {
            "ok": not wrong,
            "detail": "matches the capture" if not wrong else "differs: " + ", ".join(wrong),
        }
    checks["database"] = {
        "ok": restored_db.get("sha256") == db["sha256"],
        "detail": f"restored {db['sha256'][:12]}; prior database kept in the volume as "
        f"{Path(restored_db.get('preserved') or '').name or '(none existed)'}",
    }
    served = None
    for _ in range(max(1, health_attempts)):
        proc = run(["docker", "exec", HELPER_CONTAINER, "curl", "-fsSk", HEALTH_URL])
        if proc.returncode == 0:
            try:
                served = json.loads(proc.stdout).get("build_sha")
            except ValueError:
                served = None
            if served == build_sha:
                break
        sleep(HEALTH_INTERVAL_SECONDS)
    checks["helper_health"] = {
        "ok": served == build_sha,
        "detail": f"/health build_sha {served}, captured {build_sha}",
    }

    report = {
        "rollback_id": manifest["rollback_id"],
        "restored_at": stamp,
        "complete": all(c["ok"] for c in checks.values()),
        "checks": checks,
        "superseded": superseded,
        "preserved_database": restored_db.get("preserved"),
    }
    _write_json_new(evidence / f"restore-{stamp}.json", report)
    return report


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="rollback.py", description=__doc__.split("\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("capture", "restore"):
        p = sub.add_parser(name)
        p.add_argument("--deploy-dir", required=True)
        p.add_argument("--evidence-dir", required=True)
    try:
        args = parser.parse_args(argv)
    except SystemExit as e:
        return EXIT_USAGE if e.code else EXIT_OK
    try:
        if args.command == "capture":
            manifest = capture(args.deploy_dir, args.evidence_dir)
            summary = {k: v["captured"] for k, v in manifest["components"].items()}
            sys.stdout.write(json.dumps({"complete": manifest["complete"],
                                         "nothing_deployed": manifest["nothing_deployed"],
                                         "components": summary}, sort_keys=True) + "\n")
            if manifest["complete"] or manifest["nothing_deployed"]:
                return EXIT_OK
            return EXIT_INCOMPLETE
        report = restore(args.deploy_dir, args.evidence_dir)
        sys.stdout.write(json.dumps(report, indent=2, sort_keys=True) + "\n")
        return EXIT_OK if report["complete"] else EXIT_UNVERIFIED
    except (RollbackError, OSError, ValueError, KeyError) as e:
        sys.stderr.write(f"ROLLBACK {args.command.upper()} STOPPED: {type(e).__name__}: {e}\n")
        return EXIT_FAILED


if __name__ == "__main__":
    sys.exit(main())
