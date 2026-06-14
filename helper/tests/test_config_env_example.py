"""Guard: helper/.env.example documents every env var config.py reads.

Config scaffolding (DX standard): the `.env.example` template must stay
COMPLETE — every knob the code reads is discoverable there, with a safe
placeholder/comment — so a collaborator never hits an undocumented env var.
This test makes that an enforced check instead of an audit-time catch (it was
manually reconciled on 2026-06-14; this stops it drifting again): if a future
change adds an `os.environ` read to config.py without an `.env.example` entry,
this fails.

A var may be listed COMMENTED in the example (optional knobs default safely);
"documented" means the name appears in the file, commented or not.
"""

from __future__ import annotations

import re
from pathlib import Path

_HELPER_ROOT = Path(__file__).resolve().parent.parent
_CONFIG_PY = _HELPER_ROOT / "src" / "mymts_helper" / "config.py"
_ENV_EXAMPLE = _HELPER_ROOT / ".env.example"

# Env vars read by config.py that are deliberately NOT MyMTS .env knobs and so
# are intentionally absent from .env.example (with the reason). Keep this list
# tight — every entry is an explicit, justified exception.
_INTENTIONALLY_UNDOCUMENTED = {
    # Standard XDG base-dir var, read ONLY as an OS-level fallback for the DB
    # location when DATA_DIR is unset. Not a MyMTS-specific knob; documenting it
    # in .env.example would wrongly imply MyMTS owns it. DATA_DIR (which IS
    # documented) is the MyMTS override.
    "XDG_DATA_HOME",
}

# Matches the env-var access patterns config.py uses:
#   os.environ.get("X"...) / os.environ["X"] / _opt_int("X") / _opt_str("X")
_ENV_REF = re.compile(
    r'(?:os\.environ(?:\.get)?\(\s*"([A-Z_][A-Z0-9_]*)"'
    r'|os\.environ\[\s*"([A-Z_][A-Z0-9_]*)"\s*\]'
    r'|_opt_(?:int|str)\(\s*"([A-Z_][A-Z0-9_]*)")'
)


def _env_vars_read_by_config() -> set[str]:
    src = _CONFIG_PY.read_text(encoding="utf-8")
    names: set[str] = set()
    for m in _ENV_REF.finditer(src):
        names.add(next(g for g in m.groups() if g))
    return names


def test_every_config_env_var_is_in_env_example() -> None:
    read = _env_vars_read_by_config()
    assert read, "regex found no env vars in config.py — the matcher is stale"

    documented = _ENV_EXAMPLE.read_text(encoding="utf-8")
    expected = read - _INTENTIONALLY_UNDOCUMENTED

    missing = sorted(name for name in expected if name not in documented)
    assert not missing, (
        "helper/.env.example is missing entries for env vars config.py reads: "
        f"{missing}. Add each (commented + a default is fine for optional knobs) "
        "so the template stays complete, or add to _INTENTIONALLY_UNDOCUMENTED "
        "with a reason if it is genuinely not a MyMTS knob."
    )


def test_exclusion_list_is_actually_read_by_config() -> None:
    # Keep the exclusion list honest: every name we claim to deliberately omit
    # must actually be read by config.py (else it's stale and should be dropped).
    read = _env_vars_read_by_config()
    stale = sorted(name for name in _INTENTIONALLY_UNDOCUMENTED if name not in read)
    assert not stale, f"_INTENTIONALLY_UNDOCUMENTED has names config.py no longer reads: {stale}"
