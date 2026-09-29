# In a row, per visit, gaps

> **Grain:** one row per event (labelled), or one per session · **Class:** Sequence · **Signals:** in a row, per visit, consecutive, gaps, since last, streak, the next one · **Operators:** `SESSIONIZE`, `WINDOW LAG`/`LEAD`

## The problem

*"Group these page views into visits that end after 30 minutes idle, and count each
visit. And by how much did each daily meter reading change from the one before?"*

## How to recognise it

The question is about a row's **position in an ordered stream** and its **neighbours**
— *in a row*, *consecutive*, *per visit*, *gaps between*, *since last time*, *streak*,
*the next one*. It is not about a key you can group on directly; the grouping *emerges*
from the order. Two operators cover it:

- **Segment a stream where there is an idle gap** — clicks into visits, pings into
  bursts. That is `SESSIONIZE`, which walks the order and hands each row a session id.
- **Compare a row with its neighbour** — the change since the previous row, the gap to
  the next. That is an offset window, `WINDOW LAG` (look back) or `LEAD` (look
  forward).

Both add a column and keep every row, so they compose with the rest of the query.

## Recipe 1: split a stream into sessions (SESSIONIZE)

A *visit* is a run of events with no long gap. `SESSIONIZE` orders each partition,
and starts a new session whenever the gap to the previous row exceeds the threshold.

```relix
EventsRaw := [
| user | ts                   |
|------|----------------------|
| 1    | 2026-01-01T10:00:00Z |
| 1    | 2026-01-01T10:20:00Z |
| 1    | 2026-01-01T11:30:00Z |
| 2    | 2026-01-01T09:00:00Z |
];

Events   := { π user, to_timestamp(ts) → ts (EventsRaw) };
Sessions := { SESSIONIZE ts GAP DURATION 'PT30M' PER user AS session (Events) };
query { Sessions };
```

```
 user  ts                    session
 ────  ────────────────────  ───────
    1  2026-01-01T10:00:00Z        1
    1  2026-01-01T10:20:00Z        1
    1  2026-01-01T11:30:00Z        2
    2  2026-01-01T09:00:00Z        1
(4 rows)
```

The session id is only useful once you aggregate over it — *how many visits, how long
each* is an ordinary γ keyed on the emergent `(user, session)` grain:

```relix
query { γ user, session, COUNT(ts) → events, MIN(ts) → started, MAX(ts) → ended (Sessions) };
```

```
 user  session  events  started               ended
 ────  ───────  ──────  ────────────────────  ────────────────────
    1        1       2  2026-01-01T10:00:00Z  2026-01-01T10:20:00Z
    1        2       1  2026-01-01T11:30:00Z  2026-01-01T11:30:00Z
    2        1       1  2026-01-01T09:00:00Z  2026-01-01T09:00:00Z
(3 rows)
```

## Recipe 2: compare a row with its neighbour (LAG/LEAD)

*Change since last* looks **back** one row: `LAG(reading)` brings the previous row's
value alongside the current one, so a subtraction gives the delta. The first row of a
partition has no predecessor, so its `LAG` is NULL.

```relix
Meter := [
| day        | reading |
|------------|---------|
| 2026-01-01 | 100     |
| 2026-01-02 | 130     |
| 2026-01-03 | 125     |
];

WithPrev := { WINDOW LAG(reading) SORT day ASC AS prev (Meter) };
query { π day, reading, reading - prev → change (WithPrev) };
```

```
 day         reading  change
 ──────────  ───────  ──────
 2026-01-01      100  NULL
 2026-01-02      130      30
 2026-01-03      125      -5
(3 rows)
```

`LEAD` is the mirror — it looks **forward** to the next row, for *time until the next
event* or *the value that follows*:

```relix
query { WINDOW LEAD(reading) SORT day ASC AS next_reading (Meter) };
```

```
 day         reading  next_reading
 ──────────  ───────  ────────────
 2026-01-01      100           130
 2026-01-02      130           125
 2026-01-03      125  NULL
(3 rows)
```

## Variations

- **Your own session boundary.** `SESSIONIZE`'s rule is a fixed *gap > threshold*.
  For a boundary on something else ("a new session when the page changes"), compute
  the neighbour with `LAG` and build the running id yourself.
- **`LAG(expr, n, default)`** looks back `n` rows and substitutes `default` for the
  missing edge, so `LAG(reading, 1, reading)` reports a first-row change of zero
  instead of NULL.
- **Runs of a repeated value** (a streak) is a sessionization on a derived order — a
  gap-and-island cousin.

## Pitfalls

- **Order must be a total order.** `SESSIONIZE` and the offset windows both depend on
  the `SORT`; two rows with equal keys can fall either way, changing which is
  "previous". Add a tie-breaker when the neighbour must be well-defined.
- **The gap is strict, and edges are NULL.** A gap *exactly* equal to the threshold
  stays in the same session; the first row of each partition has no `LAG` and the last
  has no `LEAD`, and arithmetic on that NULL is NULL. Decide what the edge should say.
- **`PER` resets everything.** Both operators restart per partition — session ids
  restart at 1, and `LAG` is NULL at each partition's first row, not just the global
  first. Forgetting `PER` bleeds one entity's stream into the next.
- **These are blocking.** `SESSIONIZE` and the windows buffer and sort each partition;
  over a provably-unbounded input they are rejected.

## Check it

- Put a gap **exactly** at the threshold and one just over it, and confirm only the
  second opens a new session.
- Check the session grain: `SUM` of each session's `events` equals the total row count
  per user.
- Put a single-row partition (user 2) in and confirm its `LAG`/`LEAD` are NULL and its
  session is 1.

## Related

- [As of, during, overlapping](temporal-alignment.md) — aligning *two* relations in
  time, where this class walks *one*.
- [How many, how much, per what](summary.md) — the per-session counts are an ordinary
  γ; `ROLLING` is the windowed aggregate cousin.
- Reference pages (`docs/reference`): `sessionize`, `window-offset`.
