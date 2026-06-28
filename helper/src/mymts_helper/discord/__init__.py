"""Discord Activity output — the server-side OAuth/config endpoints + the public
checklist helpers.

Discord is the THIRD MyMTS output (alongside HLS/VLC + the Mercury stub). Its only
ToS-legitimate surface for shared in-call video is an Embedded-App-SDK *Activity*
(a viewer iframe a user launches in a voice channel) that plays the SAME HLS render
the renderer already produces — so it adds NO encode. A bot cannot broadcast video
unattended; a user-token self-bot is forbidden. Hence "launch-to-start" is a
platform rule, not a limitation we chose.

This package is intentionally small and self-contained:
  - :mod:`token_api`   the `/api/discord/config` + `/api/discord/token` router
                       (served ONLY on the dedicated public app, never the LAN API).
  - :mod:`status`      the helper-computed `disabled | needs_setup | ready` state +
                       setup checklist for the `/control/` Discord card.
"""
