"""Tests for /health/deep (MYMTS-025).

`/health` stays a liveness snapshot whose `ok`/`ready` are constants; its shape is
a consumer contract and is not touched. `/health/deep` is the signal a deploy gate
can trust: every subsystem is evaluated from real state, the response names each
failed subsystem, and the status code is 503 whenever any subsystem fails.

The module under test is imported inside each test (via `_dh`) so that, before it
existed, every case failed on its own instead of as a single collection error.
"""

from __future__ import annotations

import os
import shutil
import sqlite3
import time
from datetime import UTC, datetime, timedelta
from pathlib import Path

from fastapi.testclient import TestClient

from mymts_helper.app import create_app
from mymts_helper.config import Config

REPO_WEB = Path(__file__).resolve().parents[2] / "web"

# A Config value for the fixture helper, not a health threshold.
POLL_INTERVAL_SECONDS = 300

HEALTH_KEYS = {
    "schema_version", "ok", "ready", "phantom", "build_sha", "version",
    "uptime_seconds", "feeds", "channels",
}


def _dh():
    from mymts_helper import deep_health

    return deep_health


async def _block_all_resolver(host: str) -> list[str]:
    return ["127.0.0.1"]


def _iso(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S.000Z")


class World:
    """A helper app over a temp data dir, stream dir and web dir.

    The TestClient is used without `with`, so the lifespan (and its pollers) never
    starts: the database holds exactly the state each test writes.
    """

    def __init__(
        self,
        root: Path,
        *,
        web_dir: Path | None = REPO_WEB,
        stream: bool = True,
        build_sha: str = "abc1234",
    ) -> None:
        root.mkdir(parents=True, exist_ok=True)
        self.stream = root / "stream"
        self.stream.mkdir()
        cfg = Config(
            phantom_mode=False,
            port=8091,
            log_level="warning",
            build_sha=build_sha,
            build_version="1.2.3",
            data_dir=str(root / "data"),
            feed_poll_interval_seconds=POLL_INTERVAL_SECONDS,
            channel_probe_interval_seconds=3600,
            markets_poll_interval_seconds=3600,
            sports_poll_interval_seconds=3600,
            web_client_dir=str(web_dir) if web_dir is not None else None,
            stream_dir=str(self.stream) if stream else None,
        )
        self.db = root / "data" / "mymts-helper.db"
        self.client = TestClient(create_app(cfg, resolver=_block_all_resolver))

    def _write(self, sql: str, params: tuple = ()) -> None:
        conn = sqlite3.connect(self.db)
        try:
            conn.execute(sql, params)
            conn.commit()
        finally:
            conn.close()

    def set_ingest_age(self, seconds: float) -> None:
        stamp = _iso(datetime.now(UTC) - timedelta(seconds=seconds))
        self._write("UPDATE sources SET last_success_at=? WHERE enabled=1", (stamp,))

    def set_live_channels(self, n: int) -> None:
        self._write("UPDATE channels SET status='unavailable'")
        self._write(
            "UPDATE channels SET status='live' WHERE id IN "
            "(SELECT id FROM channels WHERE enabled=1 ORDER BY id LIMIT ?)",
            (n,),
        )

    def write_stream(self, sequence: int, segment_age_seconds: float = 0.0) -> None:
        names = [f"seg_{sequence + i:05d}.ts" for i in range(3)]
        body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n"
        body += f"#EXT-X-MEDIA-SEQUENCE:{sequence}\n"
        body += "".join(f"#EXTINF:4.0,\n{n}\n" for n in names)
        for n in names:
            (self.stream / n).write_bytes(b"\x47" * 188)
        (self.stream / "playlist.m3u8").write_text(body, encoding="utf-8")
        stamp = time.time() - segment_age_seconds
        for n in [*names, "playlist.m3u8"]:
            os.utime(self.stream / n, (stamp, stamp))

    def deep(self) -> tuple[int, dict]:
        r = self.client.get(_dh().DEEP_HEALTH_PATH)
        return r.status_code, r.json()


def _healthy(root: Path, **kwargs) -> World:
    w = World(root, **kwargs)
    w.set_ingest_age(0)
    w.set_live_channels(3)
    w.write_stream(sequence=100)
    return w


def _foreign_web(root: Path) -> Path:
    served = root / "served-web"
    shutil.copytree(REPO_WEB, served)
    target = served / "js" / "app.mjs"
    target.write_bytes(target.read_bytes() + b"\n// assets from another build\n")
    return served


# ---- a. all subsystems healthy ----


def test_all_subsystems_healthy_reports_healthy(tmp_path: Path) -> None:
    w = _healthy(tmp_path)
    status, body = w.deep()
    assert status == 200
    assert body["healthy"] is True
    assert body["failed"] == []
    assert list(body["checks"]) == list(_dh().SUBSYSTEMS)
    assert all(c["ok"] is True for c in body["checks"].values())


# ---- b. ingest stale beyond the threshold ----


def test_stale_ingest_is_unhealthy(tmp_path: Path) -> None:
    dh = _dh()
    limit = dh.ingest_max_age_seconds(POLL_INTERVAL_SECONDS)
    assert limit == max(
        dh.INGEST_MAX_AGE_FLOOR_SECONDS,
        dh.INGEST_MAX_AGE_POLL_INTERVALS * POLL_INTERVAL_SECONDS,
    )
    w = _healthy(tmp_path)

    w.set_ingest_age(limit - 60)
    assert w.deep()[0] == 200

    w.set_ingest_age(limit + 60)
    status, body = w.deep()
    assert status == 503
    assert body["healthy"] is False
    assert body["failed"] == ["ingest"]


# ---- c. zero live channels ----


def test_zero_live_channels_is_unhealthy(tmp_path: Path) -> None:
    w = _healthy(tmp_path)
    w.set_live_channels(0)
    status, body = w.deep()
    assert status == 503
    assert body["failed"] == ["channels"]
    assert body["checks"]["channels"]["live_count"] == 0
    assert _dh().MIN_LIVE_CHANNELS >= 1


# ---- d. renderer stream absent or not advancing ----


def test_absent_stream_is_unhealthy(tmp_path: Path) -> None:
    w = World(tmp_path)
    w.set_ingest_age(0)
    w.set_live_channels(3)
    status, body = w.deep()
    assert status == 503
    assert body["failed"] == ["stream"]


def test_stream_whose_segments_stopped_is_unhealthy(tmp_path: Path) -> None:
    dh = _dh()
    w = World(tmp_path)
    w.set_ingest_age(0)
    w.set_live_channels(3)
    w.write_stream(sequence=100, segment_age_seconds=dh.STREAM_MAX_SEGMENT_AGE_SECONDS + 30)
    status, body = w.deep()
    assert status == 503
    assert body["failed"] == ["stream"]


def test_stream_whose_media_sequence_stops_advancing_is_unhealthy(tmp_path: Path) -> None:
    dh = _dh()
    w = _healthy(tmp_path)
    now = [1000.0]
    deep = dh.DeepHealth(
        db_path=w.db,
        web_dir=REPO_WEB,
        stream_dir=w.stream,
        feed_poll_interval_seconds=POLL_INTERVAL_SECONDS,
        monotonic=lambda: now[0],
    )
    assert deep.evaluate()["checks"]["stream"]["ok"] is True

    # Segments freshly written, but the media sequence never moves.
    now[0] += dh.STREAM_MAX_SEQUENCE_STALL_SECONDS + 1
    w.write_stream(sequence=100)
    body = deep.evaluate()
    assert body["healthy"] is False
    assert body["failed"] == ["stream"]

    now[0] += 1
    w.write_stream(sequence=101)
    assert deep.evaluate()["healthy"] is True


# ---- e. served web assets not matching the running build ----


def test_served_web_assets_not_matching_running_build_is_unhealthy(tmp_path: Path) -> None:
    w = _healthy(tmp_path, web_dir=_foreign_web(tmp_path))
    status, body = w.deep()
    assert status == 503
    assert body["failed"] == ["web_assets"]
    assert "js/app.mjs" in body["checks"]["web_assets"]["changed"]


# ---- f. database unreachable or failing a trivial read ----


def test_unreadable_database_is_unhealthy(tmp_path: Path) -> None:
    w = _healthy(tmp_path)
    with w.db.open("r+b") as f:
        f.write(b"this is not an sqlite database header")
    status, body = w.deep()
    assert status == 503
    assert body["healthy"] is False
    assert body["failed"][0] == "database"
    assert body["checks"]["database"]["ok"] is False


def test_missing_database_is_unhealthy_and_never_created(tmp_path: Path) -> None:
    dh = _dh()
    missing = tmp_path / "absent" / "mymts-helper.db"
    stream = tmp_path / "stream"
    stream.mkdir()
    deep = dh.DeepHealth(
        db_path=missing,
        web_dir=REPO_WEB,
        stream_dir=stream,
        feed_poll_interval_seconds=POLL_INTERVAL_SECONDS,
    )
    body = deep.evaluate()
    assert body["failed"][0] == "database"
    assert not missing.parent.exists()


# ---- g. the response names WHICH subsystem failed ----


def test_response_names_each_failed_subsystem(tmp_path: Path) -> None:
    w = World(tmp_path, web_dir=_foreign_web(tmp_path))
    w.set_ingest_age(0)
    w.set_live_channels(3)
    status, body = w.deep()
    assert status == 503
    assert body["failed"] == ["stream", "web_assets"]
    for name in ("database", "ingest", "channels"):
        assert body["checks"][name]["ok"] is True
    for name in body["failed"]:
        assert body["checks"][name]["ok"] is False
        assert isinstance(body["checks"][name]["detail"], str)
        assert body["checks"][name]["detail"]


# ---- guards around the new endpoint ----


def test_unconfigured_stream_and_web_are_failures_not_skips(tmp_path: Path) -> None:
    w = World(tmp_path, web_dir=None, stream=False)
    w.set_ingest_age(0)
    w.set_live_channels(3)
    status, body = w.deep()
    assert status == 503
    assert body["failed"] == ["stream", "web_assets"]


def test_deep_failure_leaves_health_contract_unchanged(tmp_path: Path) -> None:
    w = World(tmp_path)
    assert w.deep()[0] == 503
    r = w.client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert set(body) == HEALTH_KEYS
    assert body["schema_version"] == 1
    assert body["ok"] is True
    assert body["ready"] is True


def test_build_identity_comes_from_running_code_not_env(tmp_path: Path) -> None:
    a = _healthy(tmp_path / "a", build_sha="aaaaaaa")
    b = _healthy(tmp_path / "b", build_sha="bbbbbbb")
    body_a, body_b = a.deep()[1], b.deep()[1]
    assert body_a["build"]["code_digest"] == body_b["build"]["code_digest"]
    assert len(body_a["build"]["code_digest"]) == 64
    assert "aaaaaaa" not in a.client.get(_dh().DEEP_HEALTH_PATH).text
    assert "bbbbbbb" not in b.client.get(_dh().DEEP_HEALTH_PATH).text


def test_committed_web_manifest_matches_repo_web_tree() -> None:
    dh = _dh()
    assert dh.load_web_manifest() == dh.tree_digest(REPO_WEB), (
        "web/ changed without regenerating the helper's expected-asset manifest: "
        "cd helper && uv run python -m mymts_helper.deep_health web-manifest ../web "
        "> src/mymts_helper/web_manifest.json"
    )
