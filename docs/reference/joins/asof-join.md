# Name: AS-OF Join (ASOF)

# Syntax:
```
Left ASOF <condition> Right
Left ASOF INNER <condition> Right
Left ASOF <condition> WITHIN <duration> Right
Left ASOF <condition> TIES(FIRST|LAST) Right
Left ASOF INNER <condition> WITHIN <duration> TIES(FIRST|LAST) Right
```

The condition is one or more equality keys joined by `∧` (partition), plus
exactly one ordering inequality (`<` `<=` `>` `>=`) on the match column.

```
Trades ASOF Trades.sym = Quotes.sym ∧ Trades.ts >= Quotes.ts Quotes
Trades ASOF INNER Trades.sym = Quotes.sym ∧ Trades.ts >= Quotes.ts Quotes
Trades ASOF Trades.sym = Quotes.sym ∧ Trades.ts >= Quotes.ts WITHIN DURATION 'PT1H' Quotes
Trades ASOF Trades.ts >= Quotes.ts TIES(FIRST) Quotes
```

# Description:
An AS-OF join matches each left row to the single nearest right row in time
rather than to an exact match. It answers the classic time-series question "for
each trade, what was the most recent quote at-or-before it?" — pairing each
event with the latest (or earliest) related record. It is the relational form of
kdb's AS-OF / "by" join.

Three optional clauses refine the matching behaviour:

- **INNER** — by default, left rows with no match keep NULLs on the right
  (left-outer). Add `INNER` immediately after `ASOF` to drop those unmatched
  probes entirely (inner join semantics).

- **WITHIN duration** — an optional tolerance bound. Even if a qualifying right
  row is found, it is rejected if its temporal distance from the probe exceeds
  the bound (e.g. "the most recent quote, but no older than an hour"). The
  bound is a `DURATION` literal, and the match column must hold a temporal
  value — see *Technical Description* for what counts as a distance.

- **TIES(FIRST|LAST)** — when multiple right rows share the same nearest match
  value, `TIES(LAST)` (the default) keeps the last one in input order and
  `TIES(FIRST)` keeps the first.

# Technical Description:
The condition is a flat conjunction (`∧`-only) of equality comparisons (the
partition keys — equivalent to kdb's "by") plus exactly one ordering inequality
on the match column. The inequality direction selects the matching direction:

```
L.ts >= R.ts   backward: greatest R.ts ≤ L.ts ("as of" default)
L.ts >  R.ts   strict backward: R.ts strictly before L.ts
L.ts <= R.ts   forward: least R.ts ≥ L.ts
L.ts <  R.ts   strict forward: R.ts strictly after L.ts
```

For each left (probe) row the engine:
1. Finds the right-side partition whose equality keys match the probe.
2. Sorts that partition ascending by match column (stable, so input order
   within equal match values is preserved).
3. Binary-searches for the nearest qualifying value (greatest at-or-before for
   backward; least at-or-after for forward).
4. Among tied values, picks the last (default) or first (TIES(FIRST)) in
   input order.
5. If a tolerance is set, rejects the candidate when the temporal distance
   between probe and candidate exceeds the bound.

A `WITHIN` bound measures the same distance the language's own subtraction
does, so it is defined between two `TIMESTAMP`s, two `DATE`s (a whole-day
span) and two `DURATION`s. Anything else — text, numbers, a `TIME` (whose
difference wraps) — has no distance, and a `WITHIN` over such a match column is
an error at analysis time rather than a bound that quietly does nothing. Text
holding ISO-8601 timestamps still *orders* correctly, which is what makes the
silent version of this so easy to miss: convert the column first, with
`π to_timestamp(ts) → ts (…)`.
6. Emits the concatenated row, or a NULL-padded row (left-outer) / drops the
   probe (INNER).

# Examples:
Each trade paired with the prevailing quote (latest at-or-before), per symbol:
```
Trades ASOF Trades.sym = Quotes.sym ∧ Trades.ts >= Quotes.ts Quotes
```

Forward match — next scheduled price after each event:
```
Events ASOF Events.sku = Prices.sku ∧ Events.at <= Prices.at Prices
```

Strict backward match (exclude exact-time ties):
```
Readings ASOF Readings.sensor = Cal.sensor ∧ Readings.t > Cal.t Cal
```

Inner: only emit trades that actually had a quote (drop unmatched):
```
Trades ASOF INNER Trades.sym = Quotes.sym ∧ Trades.ts >= Quotes.ts Quotes
```

With tolerance — quote must be within 30 minutes of the trade:
```
Trades ASOF Trades.sym = Quotes.sym ∧ Trades.ts >= Quotes.ts
       WITHIN DURATION 'PT30M' Quotes
```

Prefer first-seen quote when two quotes share the same timestamp:
```
Trades ASOF Trades.sym = Quotes.sym ∧ Trades.ts >= Quotes.ts TIES(FIRST) Quotes
```

All three modifiers combined:
```
Trades ASOF INNER Trades.sym = Quotes.sym ∧ Trades.ts >= Quotes.ts
       WITHIN DURATION 'PT1H'
       TIES(FIRST) Quotes
```

# Worked Example:

## Basic AS-OF: trade slippage calculation

A trading system records executed trades and a separate stream of market quotes.
For each trade it needs the *prevailing* quote — the most recent quote for that
symbol at or before the trade's timestamp — so it can compute slippage. The
timestamps rarely match exactly, which is precisely why an equi-join fails and
an AS-OF join is the right tool.

```relix
Quotes := [
| sym  | ts                   | price |
|------|----------------------|-------|
| ACME | 2026-01-02T09:00:00Z | 10.00 |
| ACME | 2026-01-02T09:05:00Z | 10.25 |
| ACME | 2026-01-02T09:10:00Z | 10.40 |
];

Trades := [
| sym  | ts                   | qty |
|------|----------------------|-----|
| ACME | 2026-01-02T09:03:00Z | 100 |
| ACME | 2026-01-02T09:07:00Z | 50  |
| ACME | 2026-01-02T09:12:00Z | 75  |
];

Filled := { Trades ASOF Trades.sym = Quotes.sym ∧ Trades.ts >= Quotes.ts Quotes };
```

Backward matching (`Trades.ts >= Quotes.ts`) snaps each trade left to the
latest quote at or before it:

```
 quotes:   Q 10.00       Q 10.25        Q 10.40
           09:00         09:05          09:10
 ───────────●─────────────●──────────────●──────────────▶ time
                 ▲              ▲                   ▲
 trades:      T 09:03        T 09:07            T 09:12
              (→10.00)       (→10.25)           (→10.40)
```

Each trade keeps the price of the quote immediately to its left:

```relix
query { Filled };
```

```
 sym   ts                    qty  sym_r  ts_r                  price
 ────  ────────────────────  ───  ─────  ────────────────────  ─────
 ACME  2026-01-02T09:03:00Z  100  ACME   2026-01-02T09:00:00Z     10
 ACME  2026-01-02T09:07:00Z   50  ACME   2026-01-02T09:05:00Z  10.25
 ACME  2026-01-02T09:12:00Z   75  ACME   2026-01-02T09:10:00Z   10.4
(3 rows)
```

The matched quote is carried through whole, not just its `price`: `Quotes`'
`sym` and `ts` collide with the left side's, so they arrive as `sym_r` and
`ts_r` — `ts_r` is the useful one, since it names *which* quote was snapped to.
Project down to the columns you want if the extra pair is noise.

---

## WITHIN: staleness guard

A quote that is too old is not useful. Adding `WITHIN DURATION 'PT5M'` keeps
only trades where the most recent quote was within 5 minutes — stale trades get
no match. Because the default behaviour is left-outer, stale trades emit NULLs;
combining with `INNER` drops them entirely.

`WITHIN` measures a real temporal distance, so the match column has to be a
`TIMESTAMP`. An inline table holds text, so `to_timestamp` converts it first —
without that the columns are strings, which still *order* correctly (ISO-8601
sorts chronologically) but have no distance for the tolerance to measure, and
the analyzer rejects the query rather than ignoring the bound:

```relix
StaleFeed := [
| sym  | ts                   | price |
|------|----------------------|-------|
| ACME | 2026-01-02T09:00:00Z | 10.00 |   -- stale for trades after 09:05
| ACME | 2026-01-02T09:08:00Z | 10.35 |
];

Trades2Raw := [
| sym  | ts                   | qty |
|------|----------------------|-----|
| ACME | 2026-01-02T09:02:00Z | 100 |  -- 2 min after 09:00 → within 5 min ✓
| ACME | 2026-01-02T09:07:00Z | 50  |  -- 7 min after 09:00 → stale        ✗
| ACME | 2026-01-02T09:11:00Z | 75  |  -- 3 min after 09:08 → within 5 min ✓
];

QuotesStale := { π sym, to_timestamp(ts) → ts, price (StaleFeed) };
Trades2     := { π sym, to_timestamp(ts) → ts, qty (Trades2Raw) };

FreshOnly := {
    Trades2 ASOF INNER
        Trades2.sym = QuotesStale.sym ∧ Trades2.ts >= QuotesStale.ts
    WITHIN DURATION 'PT5M'
    QuotesStale
};
```

The staleness check rejects the 09:07 trade because the nearest prior quote
(09:00) is 7 minutes away — over the 5-minute limit. `INNER` then drops that
row instead of NULL-padding it.

```
 09:00─────────────────────────────────────────────────▶
   Q 10.00      ← 5 min window →
            09:02 ✓  |  09:07 ✗ (stale)    09:11 ✓
                     T            T              T
```

Result — only the two in-window trades:

```relix
query { FreshOnly };
```

```
 sym   ts                    qty  sym_r  ts_r                  price
 ────  ────────────────────  ───  ─────  ────────────────────  ─────
 ACME  2026-01-02T09:02:00Z  100  ACME   2026-01-02T09:00:00Z     10
 ACME  2026-01-02T09:11:00Z   75  ACME   2026-01-02T09:08:00Z  10.35
(2 rows)
```

---

## TIES: deterministic first-seen

An exchange sometimes pushes two quotes in the same millisecond. The default
`TIES(LAST)` matches the last-received quote; `TIES(FIRST)` instead matches
the first-received, preserving the arrival order visible in the source data.

```relix
Quotes3 := [
| sym  | ts                   | price | feed   |
|------|----------------------|-------|--------|
| ACME | 2026-01-02T09:05:00Z | 10.25 | NASDAQ |
| ACME | 2026-01-02T09:05:00Z | 10.26 | NYSE   |   -- same instant, second arrival
];

Trades3 := [
| sym  | ts                   | qty |
|------|----------------------|-----|
| ACME | 2026-01-02T09:07:00Z | 100 |
];

-- Default: last-in-input = NYSE quote
ByLast  := { Trades3 ASOF Trades3.sym = Quotes3.sym ∧ Trades3.ts >= Quotes3.ts Quotes3 };

-- First-in-input = NASDAQ quote
ByFirst := { Trades3 ASOF Trades3.sym = Quotes3.sym ∧ Trades3.ts >= Quotes3.ts TIES(FIRST) Quotes3 };
```

The default takes the last of the tied quotes — the NYSE one:

```relix
query { ByLast };
```

```
 sym   ts                    qty  sym_r  ts_r                  price  feed
 ────  ────────────────────  ───  ─────  ────────────────────  ─────  ────
 ACME  2026-01-02T09:07:00Z  100  ACME   2026-01-02T09:05:00Z  10.26  NYSE
(1 row)
```

`TIES(FIRST)` takes the NASDAQ one instead — same trade, same matched instant,
different fill:

```relix
query { ByFirst };
```

```
 sym   ts                    qty  sym_r  ts_r                  price  feed
 ────  ────────────────────  ───  ─────  ────────────────────  ─────  ──────
 ACME  2026-01-02T09:07:00Z  100  ACME   2026-01-02T09:05:00Z  10.25  NASDAQ
(1 row)
```

# SQL pushdown:
On a **PostgreSQL** or **DuckDB** connection an AS-OF over two bare connection
tables folds into a single
`LEFT JOIN LATERAL (SELECT … ORDER BY <match> DESC|ASC LIMIT 1) ON TRUE` pushed scan,
so the database does the nearest-match lookup. The ordering inequality fixes the
`ORDER BY` direction (backward → `DESC`, forward → `ASC`); the inner variant uses a
plain `JOIN LATERAL … ON TRUE`. `LATERAL` is absent from H2 (the GENERIC dialect) and
version-gated in MySQL (8.0.14+), so both fall back to the in-engine executor. A `WITHIN` tolerance also
falls back (the bound leans on temporal arithmetic with no portable SQL form), as
does a join spanning two connections. MongoDB pushdown is not implemented.

When pushed, ties on the match column are resolved by the database's `LIMIT 1` row
ordering rather than the in-engine `TIES(FIRST|LAST)` rule; the two agree unless the
tied right rows differ in a selected column (a documented divergence).

# Limitations:
Exactly one ordering inequality is required; zero inequalities (equality only)
or two or more is a validation error. The tolerance clause only applies when
both probe and candidate are temporal values (`TIMESTAMP`, `DATE`, `TIME`) whose
subtraction yields a `DURATION`; for other types the bound is not enforced at
plan time but the Relix executor will reject a non-temporal distance at runtime.

# Alternatives:
A theta join with a range condition plus `TOP 1 PER` can emulate nearest-match
but is far more verbose and has no tolerance clause. For interval overlap
matching (two-sided temporal ranges) see `IJOIN`.

# See Also:
[interval-join](interval-join.md), [theta-join](theta-join.md), [top](../advanced/top.md), [sort](../operators/sort.md), [duration-literal](../literals/duration-literal.md), [timestamp-literal](../literals/timestamp-literal.md)

# Notes:
ASOF pairs naturally with the first-class temporal types (TIMESTAMP/DATE/TIME).
The match column is typically a `TIMESTAMP` but any ordered type
works. For a two-sided interval overlap query use `IJOIN` instead.
