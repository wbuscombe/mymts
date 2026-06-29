"""Unit tests for the Mercury LiveKit publisher — the pure pieces + the status state
machine, WITHOUT launching Chrome or touching the network. Stdlib unittest (the
renderer's convention: `python3 -m unittest discover -s renderer`).
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import unittest
from unittest import mock

import mercury


def _decode_jwt(token: str) -> tuple[dict, dict, bytes, str]:
    h_b64, c_b64, s_b64 = token.split(".")

    def _pad(s: str) -> bytes:
        return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))

    header = json.loads(_pad(h_b64))
    claims = json.loads(_pad(c_b64))
    sig = _pad(s_b64)
    signing_input = f"{h_b64}.{c_b64}"
    return header, claims, sig, signing_input


class TokenGrant(unittest.TestCase):
    def test_grant_is_exactly_publish_only(self):
        # Mercury note E/K/L: canSubscribe + canPublishData MUST be false (a default
        # grant would pull every participant's mic to the NAS).
        g = mercury.video_grant("channel-abc")
        self.assertEqual(g, {
            "room": "channel-abc", "roomJoin": True, "canPublish": True,
            "canSubscribe": False, "canPublishData": False,
        })

    def test_minted_token_claims_and_signature(self):
        token = mercury.mint_livekit_token(
            "APIkey123", "thesecret", room="channel-xyz",
            identity="mymts-wall-bot", name="MyMTS News Wall", ttl_seconds=86400, now=1000,
        )
        header, claims, sig, signing_input = _decode_jwt(token)
        self.assertEqual(header, {"alg": "HS256", "typ": "JWT"})
        self.assertEqual(claims["iss"], "APIkey123")
        self.assertEqual(claims["sub"], "mymts-wall-bot")            # note K: fixed bot identity
        self.assertEqual(claims["name"], "MyMTS News Wall")          # note L
        self.assertEqual(claims["nbf"], 1000)
        self.assertEqual(claims["exp"], 1000 + 86400)               # note 18: 24h TTL
        self.assertEqual(claims["video"], {
            "room": "channel-xyz", "roomJoin": True, "canPublish": True,
            "canSubscribe": False, "canPublishData": False,
        })
        # HS256 signature verifies against the secret.
        expected = hmac.new(b"thesecret", signing_input.encode(), hashlib.sha256).digest()
        self.assertEqual(sig, expected)
        # the API SECRET never appears in the token.
        self.assertNotIn("thesecret", token)

    def test_remint_changes_token(self):
        t1 = mercury.mint_livekit_token("k", "s", room="r", identity="i", name="n", now=1000)
        t2 = mercury.mint_livekit_token("k", "s", room="r", identity="i", name="n", now=1001)
        self.assertNotEqual(t1, t2)

    def test_room_name_from_guid(self):
        self.assertEqual(mercury.room_name("deadbeef"), "channel-deadbeef")


class Simulcast(unittest.TestCase):
    def test_cap_resolution_at_1080p(self):
        self.assertEqual(mercury.cap_resolution("2160p"), "1080p")
        self.assertEqual(mercury.cap_resolution("720p"), "720p")
        self.assertEqual(mercury.cap_resolution("bogus"), "1080p")

    def test_layers_top_plus_lower_only(self):
        # 1080p top → 1080 + 720 + 360 (bits/s; top uses the configured kbps).
        layers = mercury.build_simulcast_layers("1080p", 8000, 30)
        self.assertEqual([(l["w"], l["h"]) for l in layers], [(1920, 1080), (1280, 720), (640, 360)])
        self.assertEqual(layers[0]["bitrate"], 8000 * 1000)         # kbps → bps
        self.assertEqual(layers[1]["bitrate"], 1500 * 1000)
        # 720p top → only 360 below it (no equal-size 720 layer).
        layers720 = mercury.build_simulcast_layers("720p", 3000, 30)
        self.assertEqual([(l["w"], l["h"]) for l in layers720], [(1280, 720), (640, 360)])
        # 360p-ish small top → no lower layers (none strictly smaller).
        self.assertEqual(len(mercury.build_simulcast_layers("720p", 3000, 15)), 2)

    def test_publisher_config_shape(self):
        cfg = mercury.build_publisher_config(
            ws_url="wss://x/livekit-proxy", token="tok", room="channel-g",
            identity="mymts-wall-bot", name="MyMTS News Wall", audio=False, fps=30,
            resolution="1080p", bitrate_kbps=8000,
            hls_url="http://mymts-helper:8082/api/stream/playlist.m3u8",
        )
        self.assertEqual(cfg["wsUrl"], "wss://x/livekit-proxy")
        self.assertEqual(cfg["room"], "channel-g")
        self.assertEqual(cfg["topBitrate"], 8000 * 1000)
        self.assertEqual(len(cfg["layers"]), 3)
        self.assertFalse(cfg["audio"])
        # render-once: the publisher captures the wall's single HLS render.
        self.assertEqual(cfg["hlsUrl"], "http://mymts-helper:8082/api/stream/playlist.m3u8")


class HostResolver(unittest.TestCase):
    def test_prod_host_maps_to_node_ip(self):
        env = {
            mercury.ENV_API_KEY: "k", mercury.ENV_API_SECRET: "s",
            mercury.ENV_HOST: "wss://chat.mercurychat.net:7095/livekit-proxy",
            mercury.ENV_NODE_IP: "100.99.182.50",
        }
        pub = mercury.RealMercuryPublisher(env=env, log=lambda *_: None)
        pub.configure({"enabled": True, "channel_guid": "g"})
        self.assertEqual(
            pub._host_resolver_arg(),
            "--host-resolver-rules=MAP chat.mercurychat.net 100.99.182.50",
        )

    def test_dev_host_no_mapping(self):
        env = {mercury.ENV_API_KEY: "k", mercury.ENV_API_SECRET: "s",
               mercury.ENV_HOST: "ws://127.0.0.1:7880", mercury.ENV_NODE_IP: "100.99.182.50"}
        pub = mercury.RealMercuryPublisher(env=env, log=lambda *_: None)
        pub.configure({"enabled": True, "channel_guid": "g"})
        self.assertIsNone(pub._host_resolver_arg())

    def test_probe_dials_node_ip_for_prod(self):
        seen = {}

        def fake_conn(addr, timeout=None):
            seen["host"] = addr[0]
            raise OSError("refused")     # we only care WHICH host it dials

        with mock.patch("socket.create_connection", side_effect=fake_conn):
            mercury._tcp_probe("wss://chat.mercurychat.net:7095/x", node_ip="100.99.182.50")
        self.assertEqual(seen["host"], "100.99.182.50")


class StatusStateMachine(unittest.TestCase):
    def _pub(self, env=None, cfg=None):
        env = env or {mercury.ENV_API_KEY: "k", mercury.ENV_API_SECRET: "s",
                      mercury.ENV_HOST: "ws://127.0.0.1:7880"}
        pub = mercury.RealMercuryPublisher(env=env, log=lambda *_: None,
                                           probe=lambda *_a, **_k: True)
        pub.configure(cfg if cfg is not None else {"enabled": True, "channel_guid": "g"})
        return pub

    def test_disabled_when_off(self):
        pub = self._pub(cfg={"enabled": False})
        self.assertEqual(pub.status()["state"], mercury.STATE_DISABLED)

    def test_needs_setup_without_channel(self):
        pub = self._pub(cfg={"enabled": True})            # no channel_guid
        st = pub.status()
        self.assertEqual(st["state"], mercury.STATE_NEEDS_SETUP)
        self.assertIn("channel", st["detail"])

    def test_needs_setup_without_creds_no_probe(self):
        probed = []
        pub = mercury.RealMercuryPublisher(
            env={mercury.ENV_HOST: "ws://x"}, log=lambda *_: None,
            probe=lambda *a, **k: probed.append(a) or True,
        )
        pub.configure({"enabled": True, "channel_guid": "g"})
        st = pub.status()
        self.assertEqual(st["state"], mercury.STATE_NEEDS_SETUP)
        self.assertEqual(probed, [])                       # zero egress without creds
        self.assertFalse(st["checklist"]["key_present"])

    def test_publishing_reflects_browser_state(self):
        pub = self._pub()
        # simulate a live publisher Chrome reporting "publishing"
        pub._proc = mock.Mock(); pub._proc.poll.return_value = None    # alive
        pub.on_browser_status({"state": mercury.STATE_PUBLISHING, "viewers": 3})
        st = pub.status()
        self.assertEqual(st["state"], mercury.STATE_PUBLISHING)
        self.assertEqual(st["viewer_count"], 3)
        self.assertEqual(st["room"], "channel-g")

    def test_dynacast_paused_is_a_normal_state_not_error(self):
        pub = self._pub()
        pub._proc = mock.Mock(); pub._proc.poll.return_value = None
        pub.on_browser_status({"state": mercury.STATE_DYNACAST_PAUSED, "viewers": 0})
        st = pub.status()
        self.assertEqual(st["state"], mercury.STATE_DYNACAST_PAUSED)   # NOT error
        self.assertIsNone(st["last_error"])

    def test_dead_process_is_error(self):
        pub = self._pub()
        pub._proc = mock.Mock(); pub._proc.poll.return_value = 1       # exited
        pub.on_browser_status({"state": mercury.STATE_PUBLISHING})
        st = pub.status()
        self.assertEqual(st["state"], mercury.STATE_ERROR)
        self.assertTrue(st["last_error"])

    def test_current_config_carries_grant_and_guid_from_config(self):
        pub = self._pub(cfg={"enabled": True, "channel_guid": "abc-123",
                             "resolution": "1080p", "bitrate_kbps": 8000})
        raw = pub.current_config_json()
        self.assertIsNotNone(raw)
        cfg = json.loads(raw)
        self.assertEqual(cfg["room"], "channel-abc-123")              # note J: GUID from config
        self.assertTrue(cfg["hlsUrl"])                                # render-once capture source
        _, claims, _, _ = _decode_jwt(cfg["token"])
        self.assertFalse(claims["video"]["canSubscribe"])
        self.assertEqual(len(cfg["layers"]), 3)


class Factory(unittest.TestCase):
    def test_real_when_creds_present(self):
        pub = mercury.make_publisher(env={mercury.ENV_API_KEY: "k", mercury.ENV_API_SECRET: "s"})
        self.assertIsInstance(pub, mercury.RealMercuryPublisher)

    def test_stub_when_no_creds(self):
        pub = mercury.make_publisher(env={})
        self.assertIsInstance(pub, mercury.StubMercuryPublisher)

    def test_stub_zero_egress_without_creds(self):
        probed = []
        stub = mercury.StubMercuryPublisher(env={}, log=lambda *_: None,
                                            probe=lambda *a, **k: probed.append(a) or True)
        stub.configure({"enabled": True, "channel_guid": "g"})
        st = stub.status()
        self.assertEqual(st["state"], mercury.STATE_NEEDS_SETUP)
        self.assertEqual(probed, [])                                  # never probes without a key


if __name__ == "__main__":
    unittest.main()
