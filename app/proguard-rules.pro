# Release-build shrink/obfuscation rules.
#
# Stage 1 is debug-build only; this file is here for Stage 6 (operational
# hardening) when release builds + signed installs land. Keep minimal —
# adding rules without evidence is the path to shipping broken releases.

# Media3 / ExoPlayer keeps its own consumer-proguard-rules so we don't need
# to mirror them here. If a release build strips a Media3 internal that's
# loaded reflectively, add the rule with the SDK version in a comment so the
# next upgrade can revisit it.

# Kotlin metadata is preserved by AGP defaults — no rule needed.
