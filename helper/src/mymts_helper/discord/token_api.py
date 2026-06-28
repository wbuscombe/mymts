"""/api/discord — the Discord Activity's server-side OAuth + public config.

Two tiny endpoints, served ONLY on the dedicated public app (``create_public_app``),
NEVER on the LAN API / ``/control``:

  GET  /api/discord/config  → {"client_id": "<public id>"}
        The Discord application's client id is PUBLIC (it identifies the app, it is
        not a credential). Serving it lets the static Activity skip any build-time
        templating — it fetches its own client id from its origin at boot.

  POST /api/discord/token   → {"access_token": "..."}
        Exchanges the OAuth ``code`` the Activity got from ``authorize`` for a
        Discord access token, using the CLIENT SECRET server-side. Discord's
        Embedded-App flow requires this exchange to happen off-client (the secret
        must never reach the browser).

Secret discipline (hard rules):
  - The client SECRET lives ONLY in the gitignored ``.env`` (read into config by var
    name); it is used solely to sign the request to Discord and is NEVER returned,
    logged, or echoed.
  - Only the ``access_token`` field is returned to the Activity — never the refresh
    token, scope, or the upstream error body (which could leak request details).
  - Inputs are validated; missing creds / bad code / upstream failure all FAIL
    CLOSED with a generic message (no token, no internals).
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable

from fastapi import APIRouter, Body, HTTPException

# Discord's OAuth2 token endpoint (a FIXED, trusted host — the only outbound call
# this surface makes; the sole user input is the `code`, a form value, never a URL,
# so there is no SSRF surface here).
DISCORD_TOKEN_URL = "https://discord.com/api/oauth2/token"  # noqa: S105 — a public URL, not a secret

# A defensive cap. Real Discord authorization codes are short; this only stops an
# abusive client from posting a megabyte "code". The real validation is Discord
# rejecting anything that isn't a live code it issued.
MAX_CODE_LEN = 1024

# An injected exchanger: async (code) -> access_token|None. The browser entry uses
# the httpx-backed default; tests inject a fake to assert wiring + secret-safety
# with zero network.
TokenExchanger = Callable[[str], Awaitable[str | None]]


def build_token_form(client_id: str, client_secret: str, code: str) -> dict[str, str]:
    """The exact ``application/x-www-form-urlencoded`` body for Discord's token
    exchange (Embedded-App flow: no ``redirect_uri``). Pure + testable — kept
    separate so a unit test can assert the field set WITHOUT performing the network
    call or touching a real secret."""
    return {
        "client_id": client_id,
        "client_secret": client_secret,
        "grant_type": "authorization_code",
        "code": code,
    }


def make_httpx_exchanger(
    client_id: str, client_secret: str, *, timeout: float = 10.0
) -> TokenExchanger:
    """The production exchanger: POST the form to Discord with httpx and return ONLY
    the ``access_token``. Raises on any transport / HTTP / shape failure so the
    router can fail closed with a generic message (the caller never sees the
    upstream body). Closes over the secret; never logs it."""

    async def _exchange(code: str) -> str | None:
        import httpx

        async with httpx.AsyncClient(timeout=timeout) as client:
            resp = await client.post(
                DISCORD_TOKEN_URL,
                data=build_token_form(client_id, client_secret, code),
                headers={"Content-Type": "application/x-www-form-urlencoded"},
            )
        resp.raise_for_status()
        body = resp.json()
        token = body.get("access_token") if isinstance(body, dict) else None
        return token if isinstance(token, str) and token else None

    return _exchange


def get_router(
    *,
    client_id: str | None,
    client_secret: str | None,
    token_exchanger: TokenExchanger | None = None,
) -> APIRouter:
    """Build the `/api/discord` router. ``token_exchanger`` overrides the network
    call (tests). When omitted AND both creds are present, the httpx exchanger is
    used; otherwise the token route fails closed as "not configured"."""
    router = APIRouter(prefix="/api/discord", tags=["discord"])

    exchanger: TokenExchanger | None = token_exchanger
    if exchanger is None and client_id and client_secret:
        exchanger = make_httpx_exchanger(client_id, client_secret)

    @router.get("/config")
    def config() -> dict[str, str]:
        # PUBLIC, non-secret. Empty string when unset → the Activity shows an honest
        # "not configured yet" rather than handshaking with a bogus id.
        return {"client_id": client_id or ""}

    @router.post("/token")
    async def token(code: str = Body(..., embed=True, max_length=MAX_CODE_LEN)) -> dict[str, str]:
        c = code.strip()
        if not c:
            raise HTTPException(status_code=400, detail="missing or invalid 'code'")
        if exchanger is None:
            # Surface is up but the operator hasn't supplied the client id/secret.
            raise HTTPException(status_code=503, detail="discord token exchange not configured")
        try:
            access_token = await exchanger(c)
        except Exception as e:  # noqa: BLE001 — never leak the upstream error/secret
            raise HTTPException(status_code=502, detail="token exchange with Discord failed") from e
        if not access_token:
            raise HTTPException(status_code=502, detail="token exchange returned no access_token")
        # ONLY the access token — never refresh_token / scope / the raw upstream body.
        return {"access_token": access_token}

    return router
