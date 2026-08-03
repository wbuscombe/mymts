# MyMTS convenience targets — the "lazy one-command" entry points. The real
# logic lives in the scripts; this is just a thin, discoverable wrapper.
# (App build/test = ./gradlew … ; helper = uv run … ; web = node --test … —
#  see ONBOARDING.md / CONTRIBUTING.md. These targets cover the repo tooling.)

.PHONY: help test test-app record-demo test-capture

help:
	@echo "MyMTS make targets:"
	@echo "  make test           Run every suite that needs no device/NAS/secrets:"
	@echo "                      helper pytest, web node:test, renderer unittest,"
	@echo "                      docs-hygiene (+ its self-test), the cross-surface"
	@echo "                      parity contracts, and the shell gates. Works from a"
	@echo "                      clean clone; reports SKIP (never a silent pass) for"
	@echo "                      any missing prerequisite."
	@echo "  make test-app       Native Android suite (needs JDK 17 + Android SDK)."
	@echo "  make record-demo    Record a demo of the live wall on your configured Android TV device (scrcpy)."
	@echo "                      You drive the walkthrough; Ctrl-C stops. See tools/capture/README.md."
	@echo "                      Pass flags via ARGS, e.g.  make record-demo ARGS=\"--h265 --native\""
	@echo "  make test-capture   Test record-demo.sh's preflight guards (no device needed)."

# Everything green in one command. The real logic is in the script (see the
# file header for what is deliberately excluded and why).
test:
	@bash scripts/run-tests.sh

# The native suite is separate because it needs a JDK + the Android SDK, which a
# clean clone of a Python/web contributor's machine won't have. Fails loudly and
# actionably rather than cryptically when the JDK is absent.
# NOTE: the probe RUNS java rather than testing `command -v java`. macOS ships a
# /usr/bin/java SHIM that exists on PATH but errors with "Unable to locate a Java
# Runtime" — so a presence check passes and the user still gets the cryptic message.
test-app:
	@java -version >/dev/null 2>&1 || { \
	  echo "FATAL: no working java — the native suite needs JDK 17."; \
	  echo "       macOS with Android Studio installed:"; \
	  echo "         export JAVA_HOME=\"/Applications/Android Studio.app/Contents/jbr/Contents/Home\""; \
	  echo "       otherwise install a JDK 17 (e.g. brew install openjdk@17)."; \
	  exit 2; }
	@./gradlew :app:testReleaseUnitTest

# Record the wall. ARGS passes flags through to the script (e.g. --h265, --mp4).
record-demo:
	@tools/capture/record-demo.sh $(ARGS)

# Verify the recorder's bulletproof guards (stubbed scrcpy/adb; no real device).
test-capture:
	@tools/capture/test-preflight.sh
