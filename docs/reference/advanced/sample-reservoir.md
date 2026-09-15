# Name: Reservoir Sampling (SAMPLE n ROWS)

# Syntax:
SAMPLE <count> ROWS (Relation)
SAMPLE <count> ROWS SEED <integer> (Relation)

SAMPLE 100 ROWS (Events)           -- keep a uniform 100-row sample (non-deterministic)
SAMPLE 100 ROWS SEED 42 (Events)  -- reproducible 100-row sample

# Description:
SAMPLE n ROWS keeps exactly n rows chosen uniformly at random (or all rows when
the relation has fewer than n). Unlike SAMPLE p, where the count varies, this
gives you a precise sample size — the fixed-count version of "give me 100 random
rows". Perfect for building a representative fixed-size sample for testing or
preview.

The optional `SEED <integer>` clause makes the sample reproducible: two runs with
the same seed and the same input always pick the same n rows in the same order.
This is invaluable for stable test data, reproducible benchmarks, or any workflow
where you need to replay the exact same random sample.

Without a seed the chosen rows differ run to run.

# Technical Description:
SAMPLE n ROWS returns exactly min(n, |input|) rows drawn uniformly via Vitter's
Algorithm R — a single streaming pass holding at most n rows in a reservoir. The
optional SEED clause initialises a seeded `java.util.Random`; without it,
`ThreadLocalRandom.current()` supplies fresh randomness. [bag] materialisation;
never pushes down to a backend. Cost estimate = min(n, |input|).

# Examples:
Exactly 100 random rows for a test fixture:
  SAMPLE 100 ROWS (Events)

A uniform 1000-row preview of a large import:
  SAMPLE 1000 ROWS (RawImport)

Reproducible 50-row sample — same rows every run:
  SAMPLE 50 ROWS SEED 2026 (Events)

Sample within a filtered subset:
  SAMPLE 50 ROWS (σ region = "EMEA" (Customers))

Reproducible filtered sample for a stable benchmark:
  SAMPLE 200 ROWS SEED 42 (σ status = "active" (Users))


# Worked Example:
The same ten rows, asked for exactly three:

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

query { SAMPLE 3 ROWS SEED 7 (Events) };
```

```
 id  kind
 ──  ─────
  4  view
  2  view
  3  click
(3 rows)
```

Exactly three, every time — that is the difference from `SAMPLE p`, where the
count varies. Note that they do not come back in input order: a reservoir fills
with the first n rows and then replaces slots at random, so the surviving rows sit
in whichever slots they landed in. The draw is uniform, so no position is
favoured — the last row of a stream is as likely to be kept as the first, which is
the property that lets the algorithm work without knowing the row count in
advance.

Asking for more rows than the relation holds returns all of them rather than
failing:

```relix
query { SAMPLE 50 ROWS SEED 7 (Events) };
```

```
 id  kind
 ──  ─────
  1  click
  2  view
  3  click
  4  view
  5  click
  6  view
  7  click
  8  view
  9  click
 10  view
(10 rows)
```

# Limitations:
It buffers a reservoir, so — unlike SAMPLE p — it cannot be pushed to the data
source. If the input has fewer than n rows you simply get all of them. Different
seeds produce different (independent) selections; the same seed always produces
the same selection for a given input.

# Alternatives:
SAMPLE p (Bernoulli) for an approximate-percentage sample that streams. LIMIT for
a deterministic (non-random) prefix. SAMPLE p SEED k for a reproducible
approximate-percentage sample.

# See Also:
[sample-bernoulli](sample-bernoulli.md), [limit](../operators/limit.md)

# Notes:
The disambiguation from Bernoulli SAMPLE is the `ROWS` keyword after an integer
count. The SEED value is an integer literal. To drive it from an environment
variable, use `${SEED_VAR}` substitution before parsing. The seed applies to the
Algorithm R reservoir-replacement decisions only; the same seed with different
input data produces a different set of output rows.
