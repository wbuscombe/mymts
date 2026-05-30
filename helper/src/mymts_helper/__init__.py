"""MyMTS helper — minimal NAS-side service.

Owns only the two jobs the TV must not do itself:
  - Aggregate news (stage 2)
  - Resolve live-stream addresses (stage 2)

Stage 1 is health-only. The doors are wired; the rooms behind them are empty.
"""

__version__ = "0.0.0"
