# As of, during, overlapping

> **Grain:** one row per event (as-of), or one per overlapping pair (interval) · **Class:** Temporal alignment · **Signals:** as of, at the time, in effect, during, while, overlapping, conflicts with · **Operators:** `ASOF`, `IJOIN`

## The problem

*"What exchange rate was in effect at the moment of each order? And which room
reservations overlap a maintenance window?"*

## How to recognise it

The question aligns rows **in time** rather than on an exact key. Two shapes, and the
tell is whether each side is a *point* or a *period*:

- **A point matched to the nearest point in time** — *as of*, *at the time*, *in
  effect*, *the last known …*. The timestamps rarely line up exactly, so an equi-join
  fails and you want the **nearest** row. That is an **AS-OF join** (`ASOF`).
- **A period overlapping a period** — *during*, *while*, *overlapping*, *conflicts
  with*, *back-to-back*. Each row carries an interval `[start, end)` and you match by
  a geometric relationship. That is an **interval join** (`IJOIN`).

Both need real temporal values. An inline table holds text, so convert the columns
with `to_timestamp` first — text sorts chronologically for ISO-8601, but only a real
`TIMESTAMP` has a *distance* for a tolerance to measure.

## Recipe 1: the value in effect at a moment (ASOF)

Each order should carry the exchange rate that was current *at or before* it. An
AS-OF join snaps each left row to the nearest right row in the direction the
inequality names — `>=` is backward (*at or before*, the "as of" default).

```relix
RatesRaw := [
| at                   | rate |
|----------------------|------|
| 2026-01-02T09:00:00Z | 0.80 |
| 2026-01-02T12:00:00Z | 0.82 |
];

OrdersRaw := [
| at                   | usd |
|----------------------|-----|
| 2026-01-02T10:30:00Z | 100 |
| 2026-01-02T13:00:00Z | 200 |
| 2026-01-02T08:00:00Z | 50  |
];

Rates  := { π to_timestamp(at) → at, rate (RatesRaw) };
Orders := { π to_timestamp(at) → at, usd  (OrdersRaw) };

query { Orders ASOF Orders.at >= Rates.at Rates };
```

```
 at                    usd  at_r                  rate
 ────────────────────  ───  ────────────────────  ────
 2026-01-02T10:30:00Z  100  2026-01-02T09:00:00Z   0.8
 2026-01-02T13:00:00Z  200  2026-01-02T12:00:00Z  0.82
 2026-01-02T08:00:00Z   50  NULL                  NULL
(3 rows)
```

By default an order with no prior rate keeps NULLs (left-outer). The 08:00 order has
no rate before it. Add `INNER` to drop such rows, and now the conversion pays off:

```relix
query { π at, usd, rate, usd * rate → gbp (Orders ASOF INNER Orders.at >= Rates.at Rates) };
```

```
 at                    usd  rate  gbp
 ────────────────────  ───  ────  ───
 2026-01-02T10:30:00Z  100   0.8   80
 2026-01-02T13:00:00Z  200  0.82  164
(2 rows)
```

`WITHIN DURATION 'PT30M'` adds a staleness bound — reject a match older than the
tolerance — and `<=` flips the direction to *the next* rate at or after the order.

## Recipe 2: periods that overlap (IJOIN)

Which reservations clash with a maintenance window? Each side is an interval, and
"clash" is *any shared time* — Allen's `INTERSECTS`. An interval join names the
endpoints of each side and applies the geometric predicate for you:

```relix
MaintRaw := [
| room | start                | finish               |
|------|----------------------|----------------------|
| 101  | 2026-03-04T00:00:00Z | 2026-03-06T00:00:00Z |
];

ResRaw := [
| res | room | checkin              | checkout             |
|-----|------|----------------------|----------------------|
| R1  | 101  | 2026-03-05T14:00:00Z | 2026-03-08T11:00:00Z |
| R2  | 101  | 2026-03-06T14:00:00Z | 2026-03-09T11:00:00Z |
| R3  | 102  | 2026-03-05T14:00:00Z | 2026-03-08T11:00:00Z |
];

Maint := { π room, to_timestamp(start) → start, to_timestamp(finish) → finish (MaintRaw) };
Res   := { π res, room, to_timestamp(checkin) → checkin, to_timestamp(checkout) → checkout (ResRaw) };

query {
    σ room = room_r (
        Res IJOIN INTERSECTS (Res.checkin, Res.checkout, Maint.start, Maint.finish) Maint)
};
```

```
 res  room  checkin               checkout              room_r  start                 finish
 ───  ────  ────────────────────  ────────────────────  ──────  ────────────────────  ────────────────────
 R1    101  2026-03-05T14:00:00Z  2026-03-08T11:00:00Z     101  2026-03-04T00:00:00Z  2026-03-06T00:00:00Z
(1 row)
```

The interval join matches on **endpoints only**, so the same-room condition is a
`σ` on top — and the right side's `room` arrives disambiguated as `room_r`.

## Variations

- **The thirteen Allen relations** name every way two intervals can relate:
  `OVERLAPS`, `DURING`, `CONTAINS`, `MEETS` (touching, no gap and no overlap),
  `PRECEDES` (a gap between), `EQUALS`, and their converses. `INTERSECTS` is the
  convenience superset for *any* shared time.
- **`TIES(FIRST|LAST)`** on an AS-OF join decides which of two same-instant right rows
  to take.
- **The gap between events** — how long until the next one — is a Sequence question
  (`WINDOW LEAD`), a cousin of this class.

## Pitfalls

- **Text is not a timestamp.** ISO-8601 text *orders* correctly, so an AS-OF without a
  tolerance may look right, but it has no *distance*: a `WITHIN` over text is rejected,
  and any duration arithmetic is wrong. Convert with `to_timestamp` first.
- **Intervals are half-open `[start, end)`.** A reservation ending exactly when a
  window starts does **not** overlap it — that is `MEETS`, not `INTERSECTS`. Whether a
  clean handoff counts as a conflict is a business rule; naming the relation puts it in
  the script rather than hiding it in a comparison.
- **AS-OF is left-outer by default.** An event with no prior row keeps NULLs, not no
  row. Use `INNER` when an unmatched event should disappear, and remember a downstream
  arithmetic on the NULL side yields NULL.
- **`IJOIN` matches on endpoints only.** A partition key (same room, same sensor) is a
  `σ` on the result, and it matters for performance too — filter to the partition
  before the join on large data.
- **The inequality direction is the match direction.** `>=` is backward (as-of), `<=`
  is forward (the next one). Getting it backwards silently returns the wrong neighbour.

## Check it

- Put an event **before any** right-side row (the 08:00 order) and confirm the default
  keeps it with NULLs and `INNER` drops it.
- Put a boundary case — an interval that **touches** but does not overlap — and confirm
  `INTERSECTS` excludes it while `MEETS` finds it.
- On tiny data, work out the nearest match by hand and check `at_r` names the row you
  expected.

## Related

- [In a row, per visit, gaps](sequence.md) — the gap to the *next* event is
  `WINDOW LEAD`, over one relation rather than two.
- [How many, how much, per what](summary.md) — `DOWNSAMPLE` buckets one relation by
  time; this class aligns two.
- Reference pages (`docs/reference`): `asof-join`, `interval-join`,
  `to_timestamp`, `duration literal`.
