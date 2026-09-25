# Name: Window — sliding / cumulative aggregate (ROLLING)

# Syntax:
ROLLING <agg>(<expr>) OVER <n> ROWS  SORT <col> [ASC|DESC] [, ...] [PER <key> [, ...]] AS <col> (Relation)
ROLLING <agg>(<expr>) OVER ALL ROWS  SORT <col> [ASC|DESC] [, ...] [PER <key> [, ...]] AS <col> (Relation)

where <agg> ∈ { SUM, AVG, COUNT, MIN, MAX }.

ROLLING AVG(price)  OVER 3 ROWS   SORT trade_time ASC PER ticker  AS avg3    (StockTicks)
ROLLING SUM(revenue) OVER ALL ROWS SORT month ASC      PER region  AS running (MonthlySales)
ROLLING MAX(score)  OVER ALL ROWS SORT ts ASC                       AS running_max (Events)

# Description:
A window computes a per-row value over a group of neighbouring rows and **adds it
as a new column without collapsing or filtering any rows**. It is the composable,
algebraic form of SQL's `OVER (PARTITION BY … ORDER BY … ROWS …)` clause — the
tool for moving averages, running totals, and other "value computed over an
ordered window" questions.

`ROLLING` partitions the input by the optional `PER` keys (the whole relation is a
single partition when `PER` is omitted), orders each partition by the `SORT`
keys, and for every row aggregates over the rows in its **frame**:

- `OVER n ROWS` — a *trailing* window: the current row plus the preceding `n − 1`
  rows (so `OVER 3 ROWS` is a 3-row sliding window). This is the moving-average
  case.
- `OVER ALL ROWS` — the *cumulative* frame: every row of the partition up to and
  including the current row (running total, running max, …).

The output schema is the input schema with the `AS` column appended. Every input
row is preserved and carries its computed value.

# Technical Description:
`ROLLING` is the window operator (`WindowNode`). Unlike `TOP` (which
filters rows) and `γ`/`DOWNSAMPLE` (which collapse rows), a window adds a column
without removing any. It buffers per partition (`[bag]` materialisation).

A **bounded** frame (`OVER n ROWS`) is materialisation-safe over an unbounded
input: the per-partition buffer never exceeds `n` rows, so the boundedness checker
treats it like `λ`'s rescue and permits it over a generator. A **cumulative**
frame (`OVER ALL ROWS`) is blocking and is rejected over a provably-unbounded
input.

## SQL pushdown

When the input is backed by a JDBC connection and the dialect supports window
functions (PostgreSQL, DuckDB, SQLite, SQL Server, Db2 and the GENERIC H2 dialect; MySQL is conservative since
the minimum MySQL 5.x version predates window functions), `ROLLING` is folded
into the SQL scan as an ANSI SQL:2003 OVER clause:

  SELECT ticker, t, price,
         AVG(price) OVER (PARTITION BY ticker ORDER BY t ASC
                          ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS avg3
  FROM ticks

A `σ` (WHERE) above a `ROLLING` blocks because SQL `WHERE` cannot reference a
window column at the same query level; the selection is evaluated in-engine
instead. `τ` (ORDER BY) and `λ` (LIMIT) remain pushable on top of a pushed
window expression.

`SUM`/`AVG`/`MIN`/`MAX` ignore NULL values within the frame (matching `γ`);
`COUNT` counts the rows in the frame. The output column type is the argument
expression's type for `SUM`/`AVG`/`MIN`/`MAX` and `NUMBER` for `COUNT`.

# Examples:
3-row moving average of closing price per ticker — the frame is the current row
and the two before it, so it is short at the start of a partition and slides
thereafter (worked through with real output below):

  ROLLING AVG(price) OVER 3 ROWS SORT trade_time ASC PER ticker AS avg3 (Ticks)

Cumulative revenue per region, ordered by month:

  ROLLING SUM(revenue) OVER ALL ROWS SORT month ASC PER region AS running_total (MonthlySales)

Global running maximum (no PER — one partition over the whole relation):

  ROLLING MAX(score) OVER ALL ROWS SORT ts ASC AS running_max (Events)


# Worked Example:
Monthly revenue for two regions. A window adds a column and keeps every row —
which is what makes it different from `γ`, and what makes it worth seeing beside
its input.

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
    ROLLING SUM(revenue) OVER ALL ROWS SORT month ASC PER region AS running
        (MonthlyRevenue)
};
```

```
 region  month  revenue  running
 ──────  ─────  ───────  ───────
 East        1      100      100
 East        2      120      220
 East        3       90      310
 West        1       80       80
 West        2       95      175
(5 rows)
```

Five rows in, five rows out, each carrying the running total of its own region:
East accumulates 100 → 220 → 310, and West starts again from 80 because `PER
region` makes each region its own partition.

The bounded frame is the other half of the operator. `OVER 2 ROWS` averages the
current row and the one before it:

```relix
query {
    ROLLING AVG(revenue) OVER 2 ROWS SORT month ASC PER region AS avg2
        (MonthlyRevenue)
};
```

```
 region  month  revenue  avg2
 ──────  ─────  ───────  ────
 East        1      100   100
 East        2      120   110
 East        3       90   105
 West        1       80    80
 West        2       95  87.5
(5 rows)
```

Month 1 of each region has no preceding row, so its frame holds one row and the
average is just that row's own value — a trailing frame is not skipped at the
partition's edge, it is simply shorter.

# Limitations:
The aggregate must be one of `SUM`, `AVG`, `COUNT`, `MIN`, `MAX` (not `COLLECT`,
`ARGMAX`, or `ARGMIN`):

```relix-invalid
query { ROLLING COLLECT(price) OVER 3 ROWS SORT trade_time AS recent (StockTicks) };
```

 The `AS` column must not clash with an existing input
column. Over a schema-on-read source (JSON, HTTP, MongoDB)
there is no declared heading to clash with, so the operator always runs; if a
document turns out to carry a field of that name, the added column replaces it. SQL pushdown is supported on PostgreSQL and GENERIC (H2) JDBC backends;
MySQL and MongoDB sources always evaluate `ROLLING` in-engine.
A `σ` immediately above a `ROLLING` over a pushed source runs in-engine (SQL
WHERE cannot reference window results at the same query level).

# Alternatives:
For "N highest rows per group" use [TOP … PER](../advanced/top.md) (a row filter).
For one collapsed row per group use [γ](group.md). For time-bucket consolidation
that collapses rows use [DOWNSAMPLE](../advanced/downsample.md).

# See Also:
[log source](../language/log-source.md) — ROLLING over a web server's access log, among other windowing examples on one real dataset

[group](group.md), [sort](sort.md), [top](../advanced/top.md)

# Notes:
`ROLLING` and `WINDOW` are reserved keywords; relations or columns previously
named `rolling`/`window` must be renamed. The frame keyword `ALL` is contextual
and remains usable as a column name elsewhere.

**Partition pruning (WINDOW-001).** A selection that fixes a `PER` (partition)
key to a constant above a rolling window — e.g.
`σ ticker = "ACME" (ROLLING AVG(price) … PER ticker (Ticks))` — is pushed
*below* the operator by the optimizer, so the rolling aggregate is computed only
for the matching partition:
`ROLLING AVG(price) … PER ticker (σ ticker = "ACME" (Ticks))`. This is safe
because the frame never crosses a partition boundary. Only equalities on a
partition key are pushed; predicates on other columns stay as a residual σ above
the operator. Look for a `WINDOW-001` record in `--optimize` output.
