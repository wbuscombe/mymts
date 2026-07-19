# MyMTS convenience targets — the "lazy one-command" entry points. The real
# logic lives in the scripts; this is just a thin, discoverable wrapper.
# (App build/test = ./gradlew … ; helper = uv run … ; web = node --test … —
#  see ONBOARDING.md / CONTRIBUTING.md. These targets cover the repo tooling.)

.PHONY: help record-demo test-capture

help:
	@echo "MyMTS make targets:"
	@echo "  make record-demo    Record a demo of the live wall on your configured Android TV device (scrcpy)."
	@echo "                      You drive the walkthrough; Ctrl-C stops. See tools/capture/README.md."
	@echo "                      Pass flags via ARGS, e.g.  make record-demo ARGS=\"--h265 --native\""
	@echo "  make test-capture   Test record-demo.sh's preflight guards (no device needed)."

# Record the wall. ARGS passes flags through to the script (e.g. --h265, --mp4).
record-demo:
	@tools/capture/record-demo.sh $(ARGS)

# Verify the recorder's bulletproof guards (stubbed scrcpy/adb; no real device).
test-capture:
	@tools/capture/test-preflight.sh
