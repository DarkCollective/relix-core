# Name: Rand (random number)

# Syntax:
Rand()

π id, Rand() → r (Users)

# Description:
Rand returns a fresh random number between 0 (inclusive) and 1 (exclusive) each
time it is called. Use it to shuffle, assign random buckets, or sample rows —
`Rand() < 0.1` keeps roughly 10% of rows.

# Technical Description:
Rand() → NUMBER in [0, 1). Zero-argument. It reads the system RNG, so it is
neither PURE nor DETERMINISTIC — the optimizer never folds or dedupes it, and it
produces a new value per evaluation. No seed (not reproducible). Underpins the
SAMPLE p operator (which desugars to `σ Rand() < p`).

# Examples:
Randomly keep about 10% of rows:
  σ Rand() < 0.1 (Events)

Assign a random sort key (shuffle):
  τ r ASC (π id, Rand() → r (Deck))

Random bucket 0–9:
  π id, Int(Rand() * 10) → bucket (Users)

# Limitations:
Non-deterministic and unseeded — results differ every run and are not
reproducible. Because it is impure it is never constant-folded.

# Alternatives:
SAMPLE p for probabilistic sampling and SAMPLE n ROWS for an exact-count sample
express the common cases more directly.

# See Also:
[sample-bernoulli](../../advanced/sample-bernoulli.md), [sample-reservoir](../../advanced/sample-reservoir.md), [int](int.md)

# Notes:
Use Rand() with τ for a random shuffle, or with σ for ad-hoc sampling.
