# A random, representative subset

> **Grain:** one row per sampled row · **Class:** Sampling · **Signals:** a random sample, a representative subset, N at random, a preview, reproducible sample · **Operators:** `SAMPLE … ROWS`, `SAMPLE p`

## The problem

*"Give me 3 random events for a test fixture — the same 3 every time so the test is
stable. Or roughly 40% of the rows as a quick preview. And let me sample within one
region."*

## How to recognise it

The question asks for a **random** or **representative** subset, not a filtered or
ranked one — *a random sample*, *N at random*, *a representative subset*, *a preview*,
*a spot check*. The tell is that **which** rows come back is not determined by their
values: any row is as eligible as any other. That rules out σ (which picks by a
condition) and `TOP` (which picks by rank).

Two forms, and the tell is whether you want an **exact count** or a **proportion**:

- **Exactly N rows** — `SAMPLE n ROWS` (reservoir sampling): returns exactly `n`
  (or all rows if there are fewer).
- **A percentage** — `SAMPLE p` (Bernoulli): each row is kept with probability `p`, so
  the count varies around `p × |input|`.

`SEED` makes either one **reproducible** — the same seed and input always pick the
same rows — which is what turns a random sample into a stable test fixture.

## The data

```relix
Events := [
| id | region |
|----|--------|
| 1  | EMEA   |
| 2  | EMEA   |
| 3  | APAC   |
| 4  | EMEA   |
| 5  | APAC   |
| 6  | EMEA   |
| 7  | APAC   |
| 8  | EMEA   |
| 9  | APAC   |
| 10 | EMEA   |
];
```

## Recipe 1: exactly N random rows (SAMPLE n ROWS)

`SAMPLE n ROWS` returns exactly `n` rows drawn uniformly. Pin the draw with `SEED` so a
fixture or benchmark replays the same rows:

```relix
query { SAMPLE 3 ROWS SEED 7 (Events) };
```

<!-- output: paste from a run. Expected: ids 2, 3, 4 — and the same three on every run with SEED 7. Rows come back in reservoir order, not input order. -->

Ask for more than the relation holds and you get all of it, not an error:

```relix
query { SAMPLE 50 ROWS SEED 7 (Events) };
```

<!-- output: paste from a run. Expected: all 10 rows. -->

## Recipe 2: a percentage (SAMPLE p)

`SAMPLE p` keeps each row with probability `p` — a streaming sample whose size varies.
Use it for a rough preview of a large input where the exact count does not matter:

```relix
query { SAMPLE 0.4 SEED 42 (Events) };
```

<!-- output: paste from a run. Expected: about 40% of the rows — ids 3, 4, 7, 8 at SEED 42 (4 of 10). The count is approximate, not exactly 4. -->

## Recipe 3: sample within a stratum

To sample *within* a subset — a stratum — filter first, then sample the result. The
sample is drawn from just that subset:

```relix
query { SAMPLE 2 ROWS SEED 1 (σ region = "APAC" (Events)) };
```

<!-- output: paste from a run. Expected: 2 of the APAC rows — ids 7 and 9 at SEED 1. -->

For a sample of each stratum, run this per region (or union the per-region samples).

## Variations

- **Bernoulli reproducibly** — `SAMPLE p SEED k` is the streaming, percentage cousin of
  the seeded reservoir.
- **A deterministic prefix**, not a random sample, is `λ` (LIMIT / `TOP n`) — the first
  `n` rows in input order, not a uniform draw.
- **Without a seed**, both forms are non-deterministic — the rows differ run to run,
  which is what you want for a genuine spot check but not for a fixture.

## Pitfalls

- **A sample is not a filter and not a prefix.** `SAMPLE` picks at random; σ picks by a
  condition; `λ`/`TOP` picks a deterministic slice. Using `TOP 100` as a "sample" biases
  toward whatever the input order favours.
- **No seed means no reproducibility.** For a test fixture, a benchmark, or anything you
  need to replay, always `SEED` it — otherwise the "sample" changes under you.
- **The reservoir count is a cap, not a promise of exactly N.** `SAMPLE n ROWS` returns
  all rows when the input has fewer than `n`; it never pads.
- **`SAMPLE p` gives a proportion, not a count.** If you need *exactly* `k` rows, use the
  `ROWS` form — the Bernoulli form's size varies around `p × |input|`.
- **`SAMPLE` buffers/streams and does not push down**, so the draw happens in-engine over
  what the source returns.

## Check it

- Run the seeded query **twice** and confirm the rows are identical — that is the
  reproducibility guarantee, and the reason to seed a fixture.
- Ask for **more rows than exist** and confirm you get all of them, not an error.
- For `SAMPLE p`, run it over a larger input and confirm the count sits *around* `p ×
  |input|`, not exactly at it — that is the difference from the `ROWS` form.

## Related

- [Which rows, and which columns](selection.md) — σ picks by a condition; sampling picks
  at random.
- [The most, the top N, the latest](ranking.md) — `TOP` is a *deterministic* slice by
  rank, the thing a sample is often mistaken for.
- Reference pages (`docs/reference`): `sample-reservoir`, `sample-bernoulli`,
  `limit`.
