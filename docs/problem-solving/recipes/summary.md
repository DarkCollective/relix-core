# How many, how much, per what

> **Grain:** one row per group · **Class:** Summary · **Signals:** how many, how much, total, average, per, running total, per interval · **Operators:** γ, `ROLLING`, `DOWNSAMPLE`

## The problem

*"How many orders and how much money per region? What is each region's running
total through the morning? And how much came in per five-minute window?"*

## How to recognise it

The question says **how many**, **how much**, **total**, **average**, or carries a
**per** — *per region*, *per day*, *per customer*. The answer has **fewer rows than
the input**: many rows go in, one row per group comes out. That collapse is the
signature of a summary, and the group is the grain.

Three shapes, and the *per what* tells them apart:

- **Per a key** — one row per region, per customer. That is **γ**.
- **A running value, keeping every row** — a running total, a moving average. That
  keeps the grain and adds a column: **`ROLLING`**.
- **Per a time interval** — per hour, per five minutes. That is **`DOWNSAMPLE`**.

## The data

Order events through one morning. An inline table's columns are STRING or NUMBER, so
the timestamp is converted to a real `TIMESTAMP` first — `DOWNSAMPLE` needs one to
align its windows.

```relix
Orders := [
| at                   | region | amount |
|----------------------|--------|--------|
| 2026-06-01T09:02:00Z | East   | 100    |
| 2026-06-01T09:05:00Z | East   | 120    |
| 2026-06-01T09:11:00Z | West   | 80     |
| 2026-06-01T09:13:00Z | East   | 90     |
| 2026-06-01T09:14:00Z | West   | 95     |
];

Typed := { π to_timestamp(at) → at, region, amount (Orders) };
```

## Recipe 1: one row per group (γ)

`γ` lists the grouping keys, then the aggregates, each named. `COUNT(*)` counts rows;
`SUM`/`AVG`/`MIN`/`MAX` skip NULLs.

```relix
query { γ region, COUNT(*) → orders, SUM(amount) → total, AVG(amount) → mean (Typed) };
```

<!-- output: paste from a run. Expected: East orders 3 total 310 mean ≈103.33; West orders 2 total 175 mean 87.5. -->

**The keys are the grain.** `γ region` gives one row per region; add a key and the
grain gets finer — one row per region *and* something else. Decide *"one row per
___"* before you write the γ, and the keys follow.

## Recipe 2: a running total, keeping every row (ROLLING)

A running total is not a collapse — every order stays, and each carries the total so
far. `ROLLING … OVER ALL ROWS` is the cumulative frame; `SORT` orders it and `PER`
restarts it per group.

```relix
query { ROLLING SUM(amount) OVER ALL ROWS SORT at ASC PER region AS running (Typed) };
```

<!-- output: paste from a run. Expected: five rows; East accumulates 100, 220, 310; West 80, 175. -->

`OVER n ROWS` is the other frame — a trailing window of `n` rows, for a moving
average.

## Recipe 3: per time interval (DOWNSAMPLE)

*How much per five minutes?* is a group whose key is a **time bucket**. `DOWNSAMPLE`
floors each timestamp to the start of its window and consolidates within it.

```relix
query { DOWNSAMPLE at BY '5m' USING SUM (Typed) };
```

<!-- output: paste from a run. Expected: 09:00 → 100, 09:05 → 120, 09:10 → 265 (the 09:10 window holds three orders). -->

`AVG`/`SUM` keep the numeric columns only — there is no average of a region name — so
`region` is dropped unless it is a grouping key. Add `PER` to keep it and bucket
within each region:

```relix
query { DOWNSAMPLE at BY '5m' USING SUM PER region (Typed) };
```

<!-- output: paste from a run. Expected: East 09:00 → 100, East 09:05 → 120, East 09:10 → 90, West 09:10 → 175. -->

## Variations

- **`MIN`/`MAX` in `DOWNSAMPLE`** consolidate *every* scalar column, not just numbers,
  because they compare rather than compute — the alphabetically first host, the
  earliest time.
- **Having** — to keep only the groups whose aggregate passes a test, put a `σ` above
  the γ: `σ total > 200 (…)`.
- **The row at the maximum**, not the maximum value, is a Ranking question (`ARGMAX`),
  not a summary.

## Pitfalls

- **The grain is the keys, and it is easy to key on too much.** An extra column in the
  γ list silently splits every group. If the answer has more rows than you expected,
  count the distinct keys.
- **`COUNT(*)` and `COUNT(col)` differ on NULLs.** `COUNT(*)` counts rows; `COUNT(col)`
  counts rows where `col` is not NULL. When some rows have a missing value, they are
  different numbers and only one answers the question.
- **`AVG` ignores NULLs, it does not treat them as zero.** The average of `10, NULL`
  is `10`, not `5`. If a missing value should count as zero, `Nz(col, 0)` it first.
- **A `DOWNSAMPLE` bucket with no rows produces no row.** The series has gaps where
  nothing happened, so a `ROLLING … OVER 3 ROWS` over the buckets spans present
  buckets, not consecutive intervals.
- **`ROLLING` needs a total order to be deterministic.** Two rows with equal `SORT`
  keys can fall either way in the frame; add a tie-breaker key when it matters.

## Check it

- **The grain is one row per group.** `COUNT` of a γ's output equals `COUNT` of `δ` of
  its key columns over the input — if not, a key is finer than you think.
- The totals must **reconcile**: `SUM` of the per-group totals equals the grand
  `SUM(amount)` over the whole input.
- Put a **NULL** amount in and watch `COUNT(*)`, `COUNT(amount)` and `AVG` diverge on
  tiny data.

## Related

- [Which rows, and which columns](selection.md) — when the answer keeps the input's
  grain instead of collapsing it.
- [The most, the top N, the latest](ranking.md) — *the most* / *top N* wants the row,
  not the total (`TOP`, `ARGMAX`).
- [In a row, per visit, gaps](sequence.md) — *gaps between events* is `SESSIONIZE`, a
  cousin of the time bucket.
- Reference pages (`docs/reference`): `group`, `ROLLING`, `DOWNSAMPLE`.
