# Name: Bernoulli Sampling (SAMPLE p)

# Syntax:
SAMPLE <probability> (Relation)
SAMPLE <probability> SEED <integer> (Relation)

SAMPLE 0.1 (Events)           -- keep roughly 10% of the rows (non-deterministic)
SAMPLE 0.1 SEED 42 (Events)  -- reproducible 10% sample

# Description:
SAMPLE p keeps each row independently with the given probability (between 0 and
1). With `SAMPLE 0.1` each row has a 1-in-10 chance of being kept, so you get
roughly 10% of the data — handy for quick exploration of a huge table without
processing all of it. The exact number of rows varies run to run because it is
random.

The optional `SEED <integer>` clause makes the sample reproducible: two runs with
the same seed and the same input produce identical row selections. This is useful
for stable test fixtures, benchmarks, or any workflow where the same random sample
must be replayed exactly.

Without a seed the sample uses fresh randomness each run.

# Technical Description:
SAMPLE p keeps each input row with independent probability p ∈ [0, 1]. The
optional SEED clause initialises a seeded `java.util.Random`; without it,
`ThreadLocalRandom.current()` supplies fresh randomness. A real
`BernoulliSample` physical operator is emitted (formerly desugared to
`σ Rand() < p`); the STREAM materialisation mode and cost-scaling-by-p are
unchanged. Never pushed down to a backend.

# Examples:
Roughly 10% of an events table for a quick look:
  SAMPLE 0.1 (Events)

Half of the rows, randomly:
  SAMPLE 0.5 (Survey)

Reproducible 30% sample — same rows every run:
  SAMPLE 0.3 SEED 2026 (Survey)

Sample then aggregate for an approximate count:
  γ region, COUNT(id) → approx (SAMPLE 0.05 (Clicks))

Stable test fixture — pin the random rows:
  SAMPLE 0.2 SEED 99 (LargeTable)


# Worked Example:
Ten rows, each kept with probability 0.5, under a fixed seed so the result can be
written down at all:

```relix
Events := [
| id | kind  |
|----|-------|
| 1  | click |
| 2  | view  |
| 3  | click |
| 4  | view  |
| 5  | click |
| 6  | view  |
| 7  | click |
| 8  | view  |
| 9  | click |
| 10 | view  |
];

query { SAMPLE 0.5 SEED 42 (Events) };
```

```
 id  kind
 ──  ─────
  3  click
  4  view
  7  click
  8  view
  9  click
(5 rows)
```

Five rows out of ten — and that is luck, not arithmetic. Each row was decided
independently, so the count is a random variable: with this data and seed it lands
on the average, and with another seed it would as easily be four or seven. Over a
large relation the count is close to `p × n`; over a small one it can be anything,
including zero. If you need an exact count, that is reservoir sampling, not this.

Change the seed and a different subset comes back; keep it and this exact subset
comes back every time, which is what makes a sampled fixture usable in a test.

# Limitations:
The output size is approximate, not exact — for a fixed count use SAMPLE n ROWS.
Different seeds produce different (independent) samples; the same seed always
produces the same sample for a given input.

# Alternatives:
SAMPLE n ROWS for an exact-count uniform sample. LIMIT for a deterministic prefix
(not random). SAMPLE n ROWS SEED k for a reproducible fixed-count sample.

# See Also:
[sample-reservoir](sample-reservoir.md), [limit](../operators/limit.md), [rand](../functions/math/rand.md)

# Notes:
The SEED value is an integer literal. To drive it from an environment variable,
use `${SEED_VAR}` substitution before parsing. The seed applies to the
per-row Bernoulli coin flips only; the same seed with different input data
produces a different set of output rows.
