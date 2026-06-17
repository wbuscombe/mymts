#!/usr/bin/env bash
# Sign + notarize MyMTS.app — GATED on credentials.
#
# With a Developer ID Application identity present (env CODESIGN_IDENTITY, or
# auto-detected from the keychain) the app is codesigned with the hardened
# runtime; with notarization credentials present it is also notarized + stapled
# (no Gatekeeper prompt for downloaders). With NEITHER, the app is left as the
# PyInstaller adhoc-signed build and the right-click -> Open caveat is printed —
# the build still succeeds. No secrets are printed.
#
# Credentials (all optional — absence => unsigned fallback):
#   CODESIGN_IDENTITY   "Developer ID Application: Name (TEAMID)"   (enables signing)
# Notarization (one set, enables notarize + staple):
#   AC_API_KEY_ID + AC_API_KEY_PATH + AC_API_ISSUER    (App Store Connect API key — preferred)
#   OR  APPLE_ID + APPLE_TEAM_ID + APPLE_APP_PASSWORD  (Apple-ID app-specific password)
set -euo pipefail

APP="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/dist/MyMTS.app}"
if [ ! -d "$APP" ]; then
    echo "no app bundle at: $APP (build it first)" >&2
    exit 1
fi

IDENTITY="${CODESIGN_IDENTITY:-}"
if [ -z "$IDENTITY" ]; then
    IDENTITY="$(security find-identity -v -p codesigning 2>/dev/null \
        | grep -oE 'Developer ID Application: .*\([A-Z0-9]+\)' | head -1 || true)"
fi

if [ -z "$IDENTITY" ]; then
    cat <<'EOF'
==> UNSIGNED build (no Developer ID Application identity found).
    The app is PyInstaller adhoc-signed — it runs locally, but a DOWNLOADED copy
    hits the macOS "unidentified developer" Gatekeeper prompt. First-run workaround
    for users: right-click the app -> Open -> Open (once).

    To produce SIGNED + NOTARIZED builds, provide (env or CI secrets):
      CODESIGN_IDENTITY = "Developer ID Application: <Name> (<TEAMID>)"
      and one notarization set:
        AC_API_KEY_ID + AC_API_KEY_PATH + AC_API_ISSUER   (App Store Connect API key)
        or APPLE_ID + APPLE_TEAM_ID + APPLE_APP_PASSWORD  (app-specific password)
EOF
    exit 0
fi

echo "==> codesign (hardened runtime) with a Developer ID identity"
codesign --deep --force --options runtime --timestamp --sign "$IDENTITY" "$APP"
codesign --verify --strict --verbose=2 "$APP"

# Notarization needs a zip of the .app — a TEMP file (not left in dist/, where the
# packaging step writes the real artifact). Cleaned up on exit.
ZIP="$(mktemp -t mymts-notarize).zip"
trap 'rm -f "$ZIP"' EXIT
ditto -c -k --keepParent "$APP" "$ZIP"

if [ -n "${AC_API_KEY_ID:-}" ] && [ -n "${AC_API_KEY_PATH:-}" ] && [ -n "${AC_API_ISSUER:-}" ]; then
    echo "==> notarize (App Store Connect API key) + staple"
    xcrun notarytool submit "$ZIP" --key "$AC_API_KEY_PATH" --key-id "$AC_API_KEY_ID" \
        --issuer "$AC_API_ISSUER" --wait
    xcrun stapler staple "$APP"
    echo "==> signed + notarized + stapled."
elif [ -n "${APPLE_ID:-}" ] && [ -n "${APPLE_TEAM_ID:-}" ] && [ -n "${APPLE_APP_PASSWORD:-}" ]; then
    echo "==> notarize (Apple-ID app-specific password) + staple"
    xcrun notarytool submit "$ZIP" --apple-id "$APPLE_ID" --team-id "$APPLE_TEAM_ID" \
        --password "$APPLE_APP_PASSWORD" --wait
    xcrun stapler staple "$APP"
    echo "==> signed + notarized + stapled."
else
    echo "==> SIGNED but NOT notarized (no notarization creds)."
    echo "    Gatekeeper may still warn on download until notarized. Provide AC_API_* or APPLE_*."
fi
