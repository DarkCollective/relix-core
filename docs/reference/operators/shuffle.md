# Name: Shuffle (SHUFFLE)

# Syntax:
SHUFFLE (Relation)
SHUFFLE SEED <integer> (Relation)

SHUFFLE (Deck)            -- a random permutation (non-deterministic)
SHUFFLE SEED 7 (Deck)     -- a reproducible permutation

# Description:
SHUFFLE returns every row of its input exactly once, in a uniformly random order —
a random permutation. It is the random-ordering sibling of SORT (τ): where sort
imposes a deterministic key order, shuffle imposes a random one.

The row count never changes — that is the difference from `SAMPLE n ROWS`, which
also returns rows out of input order but takes a random *subset*. SHUFFLE keeps
every row; it only reorders them.

The optional `SEED <integer>` clause makes the permutation reproducible: two runs
with the same seed and the same input produce the identical order every time —
useful for a stable randomised fixture, a reproducible shuffle in a test, or any
workflow you need to replay exactly. Without a seed the order differs run to run.

Keyword-only, with no glyph — like the `SAMPLE` family it is spelled out, because
the random operators are rarer and read more clearly as words. (SORT keeps its `τ`
glyph because it is deterministic and pervasive.)

# Technical Description:
SHUFFLE buffers the whole input and permutes it with a Fisher–Yates (Knuth)
shuffle. The optional SEED clause initialises a seeded `java.util.Random`; without
it, `ThreadLocalRandom.current()` supplies fresh randomness — exactly the contract
`SAMPLE n ROWS [SEED k]` documents. The output schema equals the input schema and
the cardinality is unchanged; the result carries no ordering guarantee. [bag]
materialisation; it never pushes down to a backend, because no portable SQL "order
by random, reproducibly" matches a seeded Fisher–Yates permutation.

Because a full permutation must hold every row, SHUFFLE is a **blocking** operator:
like SORT, GROUP and `SAMPLE n ROWS`, it is rejected at plan time over a provably
unbounded input.

# Examples:
A random running order for a playlist:
  SHUFFLE (Tracks)

A reproducible shuffle — the same order every run:
  SHUFFLE SEED 2026 (Tracks)

Shuffle, then take five — a reproducible random five:
  LIMIT 5 (SHUFFLE SEED 7 (Deck))

Shuffle within a filtered subset:
  SHUFFLE (σ region = "EMEA" (Customers))

# Worked Example:
Five cards, dealt in a reproducible random order:

```relix
Deck := [
| pos | card |
|-----|------|
| 1   | A    |
| 2   | K    |
| 3   | Q    |
| 4   | J    |
| 5   | T    |
];

query { SHUFFLE SEED 7 (Deck) };
```

```
 pos  card
 ───  ────
   5  T
   4  J
   1  A
   3  Q
   2  K
(5 rows)
```

Every row comes back, exactly once — only the order is random. Run it again with
the same seed and you get the identical order; drop the seed and each run deals a
different one. Contrast `SAMPLE 3 ROWS SEED 7 (Deck)`, which would return only
three of the five.

# Limitations:
SHUFFLE buffers its whole input, so — like SORT — it cannot be pushed to the data
source and it is rejected over a provably unbounded input (bound it first with a
`LIMIT`, or shuffle a finite relation). Different seeds produce different
(independent) permutations; the same seed always produces the same permutation for
a given input. The result carries no ordering, so a downstream operator that needs
one must establish it itself.

# Alternatives:
SORT (τ) for a deterministic key order. SAMPLE n ROWS for a random *subset* (fewer
rows) rather than a permutation. `LIMIT n (SHUFFLE SEED k (R))` for a reproducible
random *n* rows in random order — whereas `SAMPLE n ROWS SEED k` is a reproducible
random *n* drawn via a reservoir.

# See Also:
[sort](sort.md), [sample-reservoir](../advanced/sample-reservoir.md),
[limit](limit.md)

# Notes:
The SEED value is an integer literal written in the script; it applies to the
Fisher–Yates permutation decisions only, so the same seed with different input data
produces a different order.
