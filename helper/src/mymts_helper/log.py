"""Structured JSON logging to stdout.

Captured by Docker's json-file driver. Includes a redaction pass that strips
known secret-shaped substrings (Bearer tokens, Authorization headers, and
internal/loopback/link-local IPv4s) as a guard against any code path that
might log them. Stage 6 hardens this further; Stage 1 ships the pass so we
never start logging secrets in the first place.
"""

from __future__ import annotations

import json
import logging
import re
import sys
import time
from typing import Any

_SECRET_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"(?i)(bearer\s+)\S+"),
    # Header patterns are greedy to end-of-line because Basic / Digest auth
    # have token+payload pairs ("Basic dXNlcjpwYXNz") — stripping only the
    # first token would leak the credential body.
    re.compile(r"(?i)(authorization:\s*).*"),
    re.compile(r"(?i)(x-api-key:\s*).*"),
    # Internal RFC1918 + loopback + link-local — never useful in a log line and
    # never safe to leak across a tunnel.
    re.compile(r"\b(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3})\b"),
    re.compile(r"\b(?:192\.168\.\d{1,3}\.\d{1,3})\b"),
    re.compile(r"\b(?:172\.(?:1[6-9]|2\d|3[0-1])\.\d{1,3}\.\d{1,3})\b"),
    re.compile(r"\b127\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"),
    re.compile(r"\b169\.254\.\d{1,3}\.\d{1,3}\b"),
)


def redact(s: str) -> str:
    out = s
    for pattern in _SECRET_PATTERNS:
        out = pattern.sub(r"\1<REDACTED>" if pattern.groups else "<REDACTED>", out)
    return out


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "ts": time.time(),
            "level": record.levelname.lower(),
            "logger": record.name,
            "msg": redact(record.getMessage()),
        }
        if record.exc_info:
            payload["exc"] = redact(self.formatException(record.exc_info))
        for k, v in record.__dict__.items():
            if k in {
                "name", "msg", "args", "levelname", "levelno", "pathname",
                "filename", "module", "exc_info", "exc_text", "stack_info",
                "lineno", "funcName", "created", "msecs", "relativeCreated",
                "thread", "threadName", "processName", "process", "message",
                "taskName",
            }:
                continue
            payload[k] = v
        return json.dumps(payload, default=str)


def configure_logging(level: str) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level.upper())
