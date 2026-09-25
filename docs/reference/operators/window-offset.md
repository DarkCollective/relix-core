# Name: Window — offset functions (WINDOW LAG / LEAD / FIRST_VALUE / LAST_VALUE)

# Syntax:
WINDOW LAG(<expr> [, <offset> [, <default>]])  SORT <col> [ASC|DESC] [, ...] [PER <key> [, ...]] AS <col> (Relation)
WINDOW LEAD(<expr> [, <offset> [, <default>]]) SORT <col> [ASC|DESC] [, ...] [PER <key> [, ...]] AS <col> (Relation)
WINDOW FIRST_VALUE(<expr>)                     SORT <col> [ASC|DESC] [, ...] [PER <key> [, ...]] AS <col> (Relation)
WINDOW LAST_VALUE(<expr>)                      SORT <col> [ASC|DESC] [, ...] [PER <key> [, ...]] AS <col> (Relation)

WINDOW LAG(revenue, 1)   SORT month ASC PER region     AS prev_revenue (MonthlyRevenue)
WINDOW LEAD(event_time)  SORT event_time ASC PER user_id AS next_time   (ClickEvents)
WINDOW FIRST_VALUE(price) SORT trade_time ASC PER session_id AS open_price (Trades)

# Description:
An offset window reads a value from **another row** within the same ordered
partition and **adds it as a new column without collapsing or filtering any
rows**. The four functions differ only in which row they read:

- `LAG(expr, n, default)` — the value of `expr` `n` rows **before** the current
  row in the sort order. At the partition's leading edge (no such row) it emits
  `default`, or `NULL` when `default` is omitted.
- `LEAD(expr, n, default)` — the value of `expr` `n` rows **after** the current
  row. Past the partition's end it emits `default`, or `NULL` when omitted.
- `FIRST_VALUE(expr)` — the value of `expr` in the **first** row of the partition,
  repeated for every row.
- `LAST_VALUE(expr)` — the value of `expr` in the **last** row of the partition,
  repeated for every row.

For `LAG`/`LEAD` the offset `n` defaults to `1` (the immediately adjacent row) and
must be a positive integer literal. `FIRST_VALUE`/`LAST_VALUE` take only the value
expression.

The output schema is the input schema with the `AS` column appended; its type
follows the referenced expression's type (`ANY` for a complex expression). Every
input row is preserved.

# Technical Description:
Offset functions are the window operator (`WindowNode` with an `OffsetWindow`
function). Like the ranking and rolling families they add a column
without removing any row. The operator buffers each partition, sorts it by
the `SORT` keys, and indexes by position (`[bag]` materialisation); it is
blocking over a provably-unbounded input.

## SQL pushdown

When the source is backed by a JDBC connection and the dialect supports window
functions (PostgreSQL, DuckDB and the GENERIC H2 dialect; MySQL is conservative), the
offset window is folded into the SQL scan:

  SELECT region, month, revenue, LAG(revenue, 1) OVER (PARTITION BY region ORDER BY month ASC) AS prev
  FROM monthly_revenue

A `σ` immediately above an offset window over a pushed source is evaluated
in-engine. MongoDB sources evaluate windows in-engine.

`LAG`/`LEAD` require a constant, positive-integer offset (no column references).
The default value, when given, is evaluated against the partition's first row
(constants are the typical case).

# Examples:
Month-over-month revenue delta (`LAG`), worked through with real output below:

```relix
WithPrev := { WINDOW LAG(revenue, 1) SORT month ASC PER region AS prev_revenue (MonthlyRevenue) };
query { π region, month, revenue, revenue - prev_revenue → mom_delta (WithPrev) };
```

The first row of each partition has no predecessor, so `prev_revenue` is `NULL`
and the arithmetic delta propagates `NULL`. (Supply a default —
`LAG(revenue, 1, 0)` — to anchor the first delta to `0` instead.)

Time between events / session gap (`LEAD` over a `TIMESTAMP`, the substrate for
sessionization) — `next_time - event_time` uses temporal arithmetic and yields a
`DURATION`:

```
ClickEvents(user_id, event_time):
  7 2026-06-01T10:00:00Z      7 2026-06-01T10:05:00Z      7 2026-06-01T10:40:00Z
```

```relix
WithNext := { WINDOW LEAD(event_time, 1) SORT event_time ASC PER user_id AS next_time (ClickEvents) };
query { π user_id, event_time, next_time - event_time → gap (WithNext) };
```

  The last event per user has no successor, so its `gap` is `NULL`.

Opening and closing price per trading session (`FIRST_VALUE`/`LAST_VALUE`):

```relix
WithBounds := {
    WINDOW FIRST_VALUE(price) SORT trade_time ASC PER session_id AS open_price (Trades)
};
```

Every row of a session carries that session's first (opening) price; swap in
`LAST_VALUE` for the closing price.


# Worked Example:
The month-over-month question, which is what LAG is for:

```relix
MonthlyRevenue := [
| region | month | revenue |
|--------|-------|---------|
| East   | 1     | 100     |
| East   | 2     | 120     |
| East   | 3     | 90      |
| West   | 1     | 80      |
| West   | 2     | 95      |
];

query {
    WINDOW LAG(revenue, 1) SORT month ASC PER region AS prev
        (MonthlyRevenue)
};
```

```
 region  month  revenue  prev
 ──────  ─────  ───────  ────
 East        1      100  NULL
 East        2      120   100
 East        3       90   120
 West        1       80  NULL
 West        2       95    80
(5 rows)
```

Each row now carries the previous month's revenue from its own region. Month 1 has
no previous row, so `prev` is NULL — and note that West's month 1 does **not** read
East's month 3: `PER region` stops the window at the partition edge rather than
running off the end of one partition into the next.

Projecting the difference is the usual next step:

```relix
query {
    π region, month, revenue - prev → change (
        WINDOW LAG(revenue, 1) SORT month ASC PER region AS prev
            (MonthlyRevenue))
};
```

```
 region  month  change
 ──────  ─────  ──────
 East        1  NULL
 East        2      20
 East        3     -30
 West        1  NULL
 West        2      15
(5 rows)
```

The rows with no predecessor come out NULL rather than as a change of zero, which
is the honest answer: there is no previous month to have changed from. Give LAG a
third argument (`LAG(revenue, 1, 0)`) if a zero suits the report better.

# Limitations:
The added column needs a name the input does not already use. Over a
schema-on-read source (JSON, HTTP, MongoDB) there is no declared heading to
clash with, so the operator always runs; if a document turns out to carry a
field of that name, the added column replaces it.

`LAG`/`LEAD` take an optional positive-integer-literal offset (default `1`) and an
optional default value (default `NULL`); `FIRST_VALUE`/`LAST_VALUE` take only the
value expression. The `AS` column must not clash with an existing input column.
SQL pushdown is supported on PostgreSQL, DuckDB and GENERIC (H2) backends; MySQL and
MongoDB always evaluate offset windows in-engine.

# Alternatives:
For sliding/cumulative aggregates use [ROLLING](rolling.md). For per-partition rank
columns use [Window / Ranking](window-ranking.md). For one collapsed row per group
use [γ](group.md).

# See Also:
[log source](../language/log-source.md) — WINDOW LAG over a web server's access log, among other windowing examples on one real dataset

[window-ranking](window-ranking.md), [rolling](rolling.md), [sort](sort.md), [group](group.md)

# Notes:
`WINDOW` is a reserved keyword; the function names (`LAG`, `LEAD`, `FIRST_VALUE`,
`LAST_VALUE`) are **contextual** — parsed only inside a `WINDOW` clause — so they
remain usable as column names elsewhere.

**Partition pruning (WINDOW-001).** A selection that fixes a `PER` (partition)
key to a constant above a window — e.g.
`σ region = "WEST" (WINDOW LAG(amount) … PER region (Sales))` — is pushed *below*
the operator by the optimizer, so the offset is computed only for the matching
partition: `WINDOW LAG(amount) … PER region (σ region = "WEST" (Sales))`. This is
safe because an offset never reaches across a partition boundary. Only equalities
on a partition key are pushed; predicates on other columns stay as a residual σ
above the operator. Look for a `WINDOW-001` record in `--optimize` output.
