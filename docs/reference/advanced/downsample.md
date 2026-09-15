# Name: DOWNSAMPLE

# Syntax:
```
DOWNSAMPLE <tsCol> BY '<interval>' USING <fn> (Relation)
DOWNSAMPLE <tsCol> BY '<interval>' USING <fn> PER <key> [, <key> …] (Relation)
DOWNSAMPLE <tsCol> BY '<interval>' USING <fn> PER <key> [, …] FOR <n> ROWS (Relation)
```

`fn` is one of: `AVG`, `SUM`, `MIN`, `MAX`, `COUNT`

Interval formats:
- Shorthand: `5m`, `1h`, `1d`, `30s`, `1w` (seconds / minutes / hours / days / weeks)
- ISO-8601: `PT5M`, `PT1H`, `P1D`, `P7D`

# Description:
DOWNSAMPLE is the time-series aggregation operator. It groups rows into
fixed-width time windows (buckets) and consolidates columns within each window
using the chosen function. It answers questions like "give me the average CPU
per 5-minute window" or "total sales per hour, per region".

Each output row represents one bucket. The `bucket` column holds the aligned
start time of the window (a `TIMESTAMP`).

Which columns are consolidated depends on the function. `AVG` and `SUM` are
arithmetic and consolidate the NUMBER columns only; every other column that is
not a grouping key or the timestamp is dropped. `MIN` and `MAX` compare rather
than compute, and rank a date, a time or a string exactly as `γ MIN(...)` does,
so they consolidate every scalar column — `min_host` is the alphabetically first
host in the bucket. Nested (array/struct) columns are never consolidated.

The optional `PER` clause adds grouping dimensions — one output bucket row per
distinct combination of (grouping keys + bucket start). The optional `FOR n ROWS`
clause keeps only the N most recent buckets (sorted by bucket descending), useful
for "last N windows" queries.

# Output schema:
For `AVG / SUM` (numeric columns only):
  `<key_col_1>`, …, `bucket: TIMESTAMP`, `<fn>_<numeric col>: NUMBER`, …

For `MIN / MAX` (every scalar column, keeping its type):
  `<key_col_1>`, …, `bucket: TIMESTAMP`, `<fn>_<col>: <type of col>`, …

For `COUNT`:
  `<key_col_1>`, …, `bucket: TIMESTAMP`, `count: NUMBER`

Column order: grouping keys (original types), then `bucket`, then consolidated
columns in input-schema order.

# Technical Description:
DOWNSAMPLE maps each input TIMESTAMP to a bucket start via
`floor(epoch_seconds / interval_seconds) * interval_seconds`. Rows are then
grouped by (groupingKeys, bucket) and the eligible non-key, non-timestamp
columns are consolidated with the chosen function.

The consolidation is performed by the same aggregate a `γ` would use, so the two
agree: NULL values are skipped in AVG/SUM/MIN/MAX (SQL-style), a bucket with no
non-NULL value for a column yields NULL there, and COUNT counts every row
including those whose columns are all NULL. MIN/MAX order values the way an
ascending `τ` does, which is what lets them consolidate timestamps and strings.

Materialisation: `[bag]` — all input rows are buffered before any output row is
emitted. Never pushes down to SQL or MongoDB.

# Examples:

## Basic: average CPU per 5-minute window
```
source Metrics from csv("metrics.csv") {
    schema: { ts: TIMESTAMP, host: STRING, cpu: NUMBER, mem: NUMBER }
};
query { DOWNSAMPLE ts BY '5m' USING AVG (Metrics) };
```

Rows are assigned to the bucket their timestamp falls in — seconds 0–299 to the
`00:00` bucket, 300–599 to `00:05` — and AVG is computed independently per numeric
column. Worked through with real output below.

## With PER grouping: total events per hour per region
```
source Events from csv("events.csv") {
    schema: { ts: TIMESTAMP, region: STRING, amount: NUMBER }
};
query { DOWNSAMPLE ts BY '1h' USING SUM PER region (Events) };
```

With `PER`, bucketing happens within each group: four rows in the same hour but
two regions give two output rows, one per region, each summing only its own.

## MIN over columns that are not numbers
```
source Spans from csv("spans.csv") {
    schema: { ts: TIMESTAMP, host: STRING, started: TIMESTAMP, latency: NUMBER }
};
query { DOWNSAMPLE ts BY '1h' USING MIN (Spans) };
```

Every scalar column is consolidated, each keeping its own type: the alphabetically
first host, the earliest start time, the smallest latency. `ts` is the bucketing
column and is replaced by `bucket`. The same query `USING AVG` would produce
`avg_latency` alone — averaging a hostname means nothing, so `AVG` and `SUM` take
the numeric columns only. Worked through with real output below.

## FOR n ROWS: last 24 one-hour buckets
```
query { DOWNSAMPLE ts BY '1h' USING AVG PER host FOR 24 ROWS (Metrics) };
```
Keeps only the 24 most recent hour buckets (most recent first in the ordering
applied for selection; output is otherwise unordered).

## COUNT: how many events per day
```
query { DOWNSAMPLE ts BY '1d' USING COUNT (Events) };
```

Output schema: `bucket: TIMESTAMP`, `count: NUMBER`.  Each row counts how many
input rows fall in that 24-hour UTC window.


# Worked Example:
Four CPU readings three minutes apart, bucketed into five-minute windows. An
inline table's columns are STRING or NUMBER, so the timestamp is converted first —
`DOWNSAMPLE` needs a real `TIMESTAMP` to align buckets against:

```relix
Readings := [
| host | at                   | cpu |
|------|----------------------|-----|
| web1 | 2026-06-01T10:00:00Z | 20  |
| web1 | 2026-06-01T10:03:00Z | 40  |
| web1 | 2026-06-01T10:07:00Z | 60  |
| web1 | 2026-06-01T10:09:00Z | 80  |
];

Typed := { π host, to_timestamp(at) → at, cpu (Readings) };

query { DOWNSAMPLE at BY '5m' USING AVG PER host (Typed) };
```

```
 host  bucket                avg_cpu
 ────  ────────────────────  ───────
 web1  2026-06-01T10:00:00Z       30
 web1  2026-06-01T10:05:00Z       70
(2 rows)
```

Two buckets out of four rows. The `bucket` column holds the **aligned start** of
each window — 10:00 and 10:05, not the timestamp of the first row that fell into
it — so buckets from different hosts or different queries line up. The 10:00
bucket averages 20 and 40; the 10:05 bucket averages 60 and 80.

Note what `AVG` did to `host`: it survived because it is a `PER` key. Had it not
been, an arithmetic consolidation would have dropped it — there is no meaningful
average of a hostname.

**`MIN` and `MAX` are the other kind.** They compare rather than compute, so they
consolidate *every* scalar column, not just the numeric ones:

```relix
Spans := [
| at                   | host | started              | latency |
|----------------------|------|----------------------|---------|
| 2026-06-01T10:00:00Z | web3 | 2026-06-01T10:00:10Z | 12      |
| 2026-06-01T10:20:00Z | web1 | 2026-06-01T10:19:55Z | 30      |
| 2026-06-01T10:40:00Z | web2 | 2026-06-01T10:39:58Z | 7       |
];

TypedSpans := {
    π to_timestamp(at) → at, host, to_timestamp(started) → started, latency (Spans)
};

query { DOWNSAMPLE at BY '1h' USING MIN (TypedSpans) };
```

```
 bucket                min_host  min_started           min_latency
 ────────────────────  ────────  ────────────────────  ───────────
 2026-06-01T10:00:00Z  web1      2026-06-01T10:00:10Z            7
(1 row)
```

One bucket, and three consolidated columns that came from three different rows:
the alphabetically first host, the earliest start, the smallest latency. Nothing
here is a row that existed — a bucket is an aggregate, and `MIN` is applied per
column exactly as `γ MIN(…)` would.

# Limitations:
- The timestamp column must be of type TIMESTAMP (or ANY); other types are a
  validation error.
- `AVG` and `SUM` are defined over numbers only; a column of another type is
  dropped rather than consolidated. `MIN`/`MAX` have no such restriction.
- Nested (array or struct) columns are never consolidated — the engine defines
  no ordering over them.
- DOWNSAMPLE does not push down to SQL or MongoDB — it always runs in-engine.
- The `FOR n ROWS` limit applies globally (not per grouping key). If you have
  multiple PER keys, the N most-recent buckets across all key combinations are
  kept.
- Week intervals (`1w`) are counted as 7 × 86 400 seconds from the Unix epoch
  (UTC) and are not calendar-aware.

# See Also:
[group](../operators/group.md), [sort](../operators/sort.md), [limit](../operators/limit.md),
[TIMESTAMP literal](../literals/timestamp-literal.md), [DATE_TRUNC](../functions/datetime/date_trunc.md)
