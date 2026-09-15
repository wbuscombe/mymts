"""Tests for helper/deploy/rollback.py (MYMTS-025).

rollback.py runs on the helper host and drives the docker CLI. Here the CLI is a
fake over a temp "host": volumes are directories, and `docker exec` / `docker run`
of `python -` really execute the piped db_backup.py against them. No container,
host or network is touched.

The scenario every restore test builds on: capture a running 0.5.0 deployment,
apply everything a full deploy changes (new images, new web assets, new source and
renderer trees, rewritten compose.yml and .env, a database written by migrations
and pollers), then restore and require every component back.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import sqlite3
import subprocess
import sys
from pathlib import Path

import pytest

DEPLOY_DIR = Path(__file__).resolve().parents[1] / "deploy"
MODULE_PATH = DEPLOY_DIR / "rollback.py"

OLD = {"sha": "0967970", "version": "0.5.0"}
NEW = {"sha": "8605885", "version": "0.6.1"}


def _rb():
    spec = importlib.util.spec_from_file_location("mymts_rollback", MODULE_PATH)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def _env(build: dict) -> str:
    return (
        f"BUILD_SHA={build['sha']}\nBUILD_VERSION={build['version']}\n"
        "PHANTOM_MODE=0\nLOG_LEVEL=info\nWEB_CLIENT_DIR=/app/web\nSTREAM_DIR=/stream\n"
    )


def _db_rows(path: Path) -> list[tuple]:
    conn = sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro&immutable=1", uri=True)
    try:
        cols = [r[1] for r in conn.execute("PRAGMA table_info(feed_items)")]
        rows = conn.execute("SELECT * FROM feed_items ORDER BY id").fetchall()
        version = conn.execute("SELECT value FROM meta WHERE key='schema_version'").fetchone()
        return [("columns", *cols), ("schema", version[0]), *rows]
    finally:
        conn.close()


def _files(root: Path) -> dict[str, bytes]:
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob("*") if p.is_file()}


class FakeDocker:
    """Enough of the docker CLI for rollback.py over a temp host."""

    def __init__(self, host: Path, deploy: Path) -> None:
        self.deploy = deploy
        self.volumes = {"mymts-helper-data": host / "volumes" / "mymts-helper-data"}
        for v in self.volumes.values():
            v.mkdir(parents=True)
        self.images = {"sha256:helper-old", "sha256:renderer-old"}
        self.tags = {
            "mymts-helper:0.5.0": "sha256:helper-old",
            "mymts-renderer:0.5.0": "sha256:renderer-old",
        }
        self.starts = 0
        self.health_sha_override: str | None = None
        self.calls: list[list[str]] = []
        self.containers: dict[str, dict] = {}
        self.recreate(OLD)

    # -- state helpers --

    def _container(self, name: str, image: str, sha: str, mounts: list[dict]) -> dict:
        self.starts += 1
        return {
            "Name": "/" + name,
            "Image": image,
            "State": {"Running": True, "StartedAt": f"2026-09-15T00:00:{self.starts:02d}Z"},
            "RestartCount": 0,
            "Mounts": mounts,
            "Config": {"Env": [f"BUILD_SHA={sha}"]},
        }

    def recreate(self, build: dict) -> None:
        data = [{"Type": "volume", "Name": "mymts-helper-data", "Destination": "/data"}]
        self.containers["mymts-helper"] = self._container(
            "mymts-helper", self.tags[f"mymts-helper:{build['version']}"], build["sha"], data
        )
        self.containers["mymts-renderer"] = self._container(
            "mymts-renderer", self.tags[f"mymts-renderer:{build['version']}"], build["sha"], []
        )

    def mutations_since(self, index: int) -> list[list[str]]:
        mutating = []
        for argv in self.calls[index:]:
            cmd = argv[1:]
            if (
                cmd[0] in ("tag", "run")
                or (cmd[0] == "compose" and ("stop" in cmd or "up" in cmd))
                or (cmd[0] == "exec" and "restore" in cmd)
            ):
                mutating.append(argv)
        return mutating

    # -- the CLI --

    @staticmethod
    def _result(argv: list[str], rc: int = 0, out: bytes = b"", err: bytes = b""):
        return subprocess.CompletedProcess(argv, rc, out, err)

    def _python(self, argv: list[str], args: list[str], mounts: dict[str, Path], script: bytes):
        mapped = []
        for a in args:
            for dest, src in sorted(mounts.items(), key=lambda kv: -len(kv[0])):
                if a == dest or a.startswith(dest + "/"):
                    a = str(src) + a[len(dest):]
                    break
            mapped.append(a)
        p = subprocess.run(
            [sys.executable, "-", *mapped], input=script, capture_output=True, check=False
        )
        return self._result(argv, p.returncode, p.stdout, p.stderr)

    def __call__(self, argv: list[str], *, cwd: str | None = None, input: bytes | None = None):
        self.calls.append(list(argv))
        assert argv[0] == "docker"
        cmd = argv[1:]
        if cmd[0] == "inspect":
            c = self.containers.get(cmd[-1])
            if c is None:
                return self._result(argv, 1, b"[]", b"Error: No such object")
            return self._result(argv, 0, json.dumps([c]).encode())
        if cmd[:2] == ["image", "inspect"]:
            ref = cmd[-1]
            ok = ref in self.images or self.tags.get(ref) in self.images
            return self._result(argv, 0 if ok else 1)
        if cmd[0] == "tag":
            image = self.tags.get(cmd[1], cmd[1])
            if image not in self.images:
                return self._result(argv, 1, b"", b"No such image")
            self.tags[cmd[2]] = image
            return self._result(argv, 0)
        if cmd[0] == "exec":
            rest = [a for a in cmd[1:] if a != "-i"]
            name, rest = rest[0], rest[1:]
            c = self.containers.get(name)
            if c is None or not c["State"]["Running"]:
                return self._result(argv, 1, b"", b"container not running")
            if rest[:2] == ["python", "-"]:
                data = {"/data": self.volumes["mymts-helper-data"]}
                return self._python(argv, rest[2:], data, input)
            if rest[0] == "curl":
                env_sha = c["Config"]["Env"][0].split("=", 1)[1]
                sha = self.health_sha_override or env_sha
                return self._result(argv, 0, json.dumps({"ok": True, "build_sha": sha}).encode())
        if cmd[0] == "cp":
            container, path = cmd[1].split(":", 1)
            assert container == "mymts-helper" and path.startswith("/data/")
            src = self.volumes["mymts-helper-data"] / path[len("/data/"):]
            Path(cmd[2]).write_bytes(src.read_bytes())
            return self._result(argv, 0)
        if cmd[0] == "compose":
            assert Path(cwd) == self.deploy
            if "stop" in cmd:
                for c in self.containers.values():
                    c["State"]["Running"] = False
                return self._result(argv, 0)
            if "up" in cmd:
                env = dict(
                    line.split("=", 1)
                    for line in (self.deploy / ".env").read_text().splitlines()
                    if "=" in line
                )
                self.recreate({"sha": env["BUILD_SHA"], "version": env["BUILD_VERSION"]})
                return self._result(argv, 0)
        if cmd[0] == "run":
            mounts: dict[str, Path] = {}
            i = 1
            image = None
            while i < len(cmd):
                a = cmd[i]
                if a in ("--rm", "-i"):
                    i += 1
                elif a in ("--network", "--user", "--entrypoint"):
                    assert a != "--network" or cmd[i + 1] == "none"
                    i += 2
                elif a == "-v":
                    src, dest = cmd[i + 1].split(":")[:2]
                    mounts[dest] = self.volumes.get(src, Path(src))
                    i += 2
                else:
                    image = a
                    break
            assert image in self.images
            assert cmd[i + 1] == "-"
            return self._python(argv, cmd[i + 2:], mounts, input)
        raise AssertionError(f"unexpected docker call: {argv}")


def _host(tmp_path: Path) -> tuple[Path, FakeDocker]:
    deploy = tmp_path / "host" / "mymts-helper"
    (deploy / "_src" / "src").mkdir(parents=True)
    (deploy / "_src" / "src" / "app.py").write_text("print('helper 0.5.0')\n")
    (deploy / "_src" / "src" / "retired.py").write_text("print('only in 0.5.0')\n")
    (deploy / "_src" / ".dockerignore").write_text(".venv/\n")
    (deploy / "_web" / "js").mkdir(parents=True)
    (deploy / "_web" / "index.html").write_text("<title>wall 0.5.0</title>\n")
    (deploy / "_web" / "js" / "app.mjs").write_text("export const v = '0.5.0';\n")
    (deploy / "_renderer").mkdir()
    (deploy / "_renderer" / "run.py").write_text("RENDERER = '0.5.0'\n")
    (deploy / "_secrets").mkdir()
    (deploy / "_secrets" / "untouched.txt").write_text("not ours\n")
    (deploy / "compose.yml").write_text("services: {helper: {}, renderer: {}}\n")
    (deploy / ".env").write_text(_env(OLD))
    fake = FakeDocker(tmp_path / "host", deploy)
    db = fake.volumes["mymts-helper-data"] / "mymts-helper.db"
    conn = sqlite3.connect(db)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    conn.execute("INSERT INTO meta VALUES ('schema_version', '5')")
    conn.execute("CREATE TABLE feed_items (id INTEGER PRIMARY KEY, title TEXT)")
    conn.executemany(
        "INSERT INTO feed_items (title) VALUES (?)", [(f"old {i}",) for i in range(20)]
    )
    conn.commit()
    conn.close()
    return deploy, fake


def _deploy_new_build(tmp_path: Path, deploy: Path, fake: FakeDocker) -> None:
    """Everything a full deploy-helper.sh run changes."""
    (deploy / "_src" / "src" / "app.py").write_text("print('helper 0.6.1')\n")
    (deploy / "_src" / "src" / "retired.py").rename(tmp_path / "rsync-deleted-retired.py")
    (deploy / "_src" / "src" / "added.py").write_text("print('new in 0.6.1')\n")
    (deploy / "_web" / "js" / "app.mjs").write_text("export const v = '0.6.1';\n")
    (deploy / "_web" / "js" / "extra.mjs").write_text("export {};\n")
    (deploy / "_renderer" / "run.py").write_text("RENDERER = '0.6.1'\n")
    (deploy / "compose.yml").write_text("services: {helper: {new: 1}, renderer: {}}\n")
    (deploy / ".env").write_text(_env(NEW))
    fake.images |= {"sha256:helper-new", "sha256:renderer-new"}
    fake.tags["mymts-helper:0.6.1"] = "sha256:helper-new"
    fake.tags["mymts-renderer:0.6.1"] = "sha256:renderer-new"
    fake.tags["mymts-helper:rollback"] = "sha256:helper-old"
    fake.recreate(NEW)
    conn = sqlite3.connect(fake.volumes["mymts-helper-data"] / "mymts-helper.db")
    conn.execute("ALTER TABLE feed_items ADD COLUMN published_at TEXT")
    conn.execute("UPDATE meta SET value='6' WHERE key='schema_version'")
    conn.executemany("INSERT INTO feed_items (title) VALUES (?)", [(f"new {i}",) for i in range(5)])
    conn.commit()
    conn.close()


def _capture(tmp_path: Path, deploy: Path, fake: FakeDocker, name: str = "20260915T000000Z"):
    rb = _rb()
    evidence = tmp_path / "host" / "mymts-helper" / "_rollback" / name
    manifest = rb.capture(deploy, evidence, run=fake, tools_dir=DEPLOY_DIR)
    return rb, evidence, manifest


def _no_sleep(_seconds: float) -> None:
    return None


# ---- capture ----


def test_capture_records_every_component_a_deploy_changes(tmp_path: Path) -> None:
    deploy, fake = _host(tmp_path)
    before = {n: _files(deploy / n) for n in ("_src", "_web", "_renderer")}
    rows = _db_rows(fake.volumes["mymts-helper-data"] / "mymts-helper.db")

    rb, evidence, manifest = _capture(tmp_path, deploy, fake)

    assert manifest["complete"] is True
    assert set(manifest["components"]) == set(rb.COMPONENTS)
    assert all(c["captured"] for c in manifest["components"].values())
    helper = manifest["components"]["helper_image"]
    renderer = manifest["components"]["renderer_image"]
    assert helper["image_id"] == "sha256:helper-old"
    assert renderer["image_id"] == "sha256:renderer-old"
    assert fake.tags[helper["hold_tag"]] == "sha256:helper-old"
    assert fake.tags[renderer["hold_tag"]] == "sha256:renderer-old"
    db = manifest["components"]["database"]
    assert _sha(evidence / db["backup"]) == db["sha256"]
    assert _db_rows(evidence / db["backup"]) == rows
    assert (evidence / rb.MANIFEST_NAME).is_file()
    # capture is read-only toward everything a deploy changes
    assert {n: _files(deploy / n) for n in ("_src", "_web", "_renderer")} == before
    assert fake.tags["mymts-helper:0.5.0"] == "sha256:helper-old"


def test_capture_refuses_to_reuse_an_evidence_dir(tmp_path: Path) -> None:
    deploy, fake = _host(tmp_path)
    rb, evidence, _ = _capture(tmp_path, deploy, fake)
    with pytest.raises(rb.RollbackError):
        rb.capture(deploy, evidence, run=fake, tools_dir=DEPLOY_DIR)


# ---- restore ----


def test_restore_after_a_deploy_returns_every_component(tmp_path: Path) -> None:
    deploy, fake = _host(tmp_path)
    db_path = fake.volumes["mymts-helper-data"] / "mymts-helper.db"
    before = {n: _files(deploy / n) for n in ("_src", "_web", "_renderer")}
    before_compose = (deploy / "compose.yml").read_bytes()
    before_env = (deploy / ".env").read_bytes()
    before_rows = _db_rows(db_path)
    rb, evidence, manifest = _capture(tmp_path, deploy, fake)
    renderer_started = fake.containers["mymts-renderer"]["State"]["StartedAt"]

    _deploy_new_build(tmp_path, deploy, fake)
    after_deploy = {n: _files(deploy / n) for n in ("_src", "_web", "_renderer")}
    deployed_rows = _db_rows(db_path)
    assert after_deploy != before and deployed_rows != before_rows

    report = rb.restore(deploy, evidence, run=fake, tools_dir=DEPLOY_DIR, sleep=_no_sleep)

    assert report["complete"] is True, report
    assert set(rb.COMPONENTS) <= set(report["checks"])
    assert all(c["ok"] for c in report["checks"].values()), report
    # helper image and renderer image
    assert fake.containers["mymts-helper"]["Image"] == "sha256:helper-old"
    assert fake.containers["mymts-renderer"]["Image"] == "sha256:renderer-old"
    # renderer state: recreated on the restored image, so its page reloads
    assert fake.containers["mymts-renderer"]["State"]["StartedAt"] != renderer_started
    # web assets and source state
    assert {n: _files(deploy / n) for n in ("_src", "_web", "_renderer")} == before
    assert (deploy / "compose.yml").read_bytes() == before_compose
    assert (deploy / ".env").read_bytes() == before_env
    # database, including undoing the migration
    assert _db_rows(db_path) == before_rows
    assert _db_rows(Path(report["preserved_database"])) == deployed_rows
    # nothing deleted: every superseded tree is kept beside the restored one
    superseded = [Path(p) for p in report["superseded"]]
    assert {p.name.split(rb.SUPERSEDED_MARKER)[0] for p in superseded} == {
        "_src", "_web", "_renderer", "compose.yml", ".env"
    }
    for p in superseded:
        name = p.name.split(rb.SUPERSEDED_MARKER)[0]
        if name in after_deploy:
            assert _files(p) == after_deploy[name]
    assert (deploy / "_secrets" / "untouched.txt").read_text() == "not ours\n"


def test_restore_refuses_tampered_evidence_before_changing_anything(tmp_path: Path) -> None:
    deploy, fake = _host(tmp_path)
    rb, evidence, manifest = _capture(tmp_path, deploy, fake)
    _deploy_new_build(tmp_path, deploy, fake)
    tar = evidence / manifest["components"]["web_assets"]["trees"]["_web"]["tar"]
    raw = bytearray(tar.read_bytes())
    raw[len(raw) // 2] ^= 0xFF
    tar.write_bytes(bytes(raw))
    deployed = {n: _files(deploy / n) for n in ("_src", "_web", "_renderer")}
    mark = len(fake.calls)

    with pytest.raises(rb.RollbackError, match="checksum"):
        rb.restore(deploy, evidence, run=fake, tools_dir=DEPLOY_DIR, sleep=_no_sleep)

    assert fake.mutations_since(mark) == []
    assert {n: _files(deploy / n) for n in ("_src", "_web", "_renderer")} == deployed
    assert fake.containers["mymts-helper"]["Image"] == "sha256:helper-new"


def test_restore_refuses_an_incomplete_capture(tmp_path: Path) -> None:
    deploy, fake = _host(tmp_path)
    del fake.containers["mymts-renderer"]
    rb, evidence, manifest = _capture(tmp_path, deploy, fake)
    assert manifest["complete"] is False
    assert manifest["components"]["renderer_image"]["captured"] is False
    mark = len(fake.calls)

    with pytest.raises(rb.RollbackError, match="renderer_image"):
        rb.restore(deploy, evidence, run=fake, tools_dir=DEPLOY_DIR, sleep=_no_sleep)

    assert fake.mutations_since(mark) == []


def test_restore_refuses_when_a_held_image_is_gone(tmp_path: Path) -> None:
    deploy, fake = _host(tmp_path)
    rb, evidence, _ = _capture(tmp_path, deploy, fake)
    _deploy_new_build(tmp_path, deploy, fake)
    fake.images.discard("sha256:renderer-old")
    mark = len(fake.calls)

    with pytest.raises(rb.RollbackError, match="sha256:renderer-old"):
        rb.restore(deploy, evidence, run=fake, tools_dir=DEPLOY_DIR, sleep=_no_sleep)

    assert fake.mutations_since(mark) == []


def test_restore_reports_incomplete_when_the_helper_never_serves_the_captured_build(
    tmp_path: Path,
) -> None:
    deploy, fake = _host(tmp_path)
    rb, evidence, _ = _capture(tmp_path, deploy, fake)
    _deploy_new_build(tmp_path, deploy, fake)
    fake.health_sha_override = "fffffff"

    report = rb.restore(
        deploy, evidence, run=fake, tools_dir=DEPLOY_DIR, sleep=_no_sleep, health_attempts=2
    )

    assert report["complete"] is False
    assert report["checks"]["helper_health"]["ok"] is False
