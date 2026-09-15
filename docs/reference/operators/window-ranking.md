# Name: Window — ranking functions (WINDOW ROW_NUMBER / RANK / DENSE_RANK / PERCENT_RANK / NTILE)

# Syntax:
WINDOW ROW_NUMBER()    SORT <col> [ASC|DESC] [, ...] [PER <key> [, ...]] AS <col> (Relation)
WINDOW RANK()          SORT <col> [ASC|DESC] [, ...] [PER <key> [, ...]] AS <col> (Relation)
WINDOW DENSE_RANK()    SORT <col> [ASC|DESC] [, ...] [PER <key> [, ...]] AS <col> (Relation)
WINDOW PERCENT_RANK()  SORT <col> [ASC|DESC] [, ...] [PER <key> [, ...]] AS <col> (Relation)
WINDOW NTILE(<n>)      SORT <col> [ASC|DESC] [, ...] [PER <key> [, ...]] AS <col> (Relation)

WINDOW RANK()    SORT units_sold DESC PER category_id AS rnk   (Products)
WINDOW NTILE(4)  SORT ytd_revenue DESC PER region      AS qtile (Sales)

# Description:
A ranking window assigns each row a number based on its position within an ordered
partition and **adds it as a new column without collapsing or filtering any rows**.
All five functions share the same structure — partition by the optional `PER`
keys, order each partition by the `SORT` keys, then number the rows:

- `ROW_NUMBER()` — a unique, sequential 1-based integer per partition (`1, 2, 3,
  …`); ties are broken by the row order within equal sort keys.
- `RANK()` — tied rows (equal under the `SORT` keys) share a rank; the next rank
  **skips** the gap left by the tie (`1, 1, 3, …`).
- `DENSE_RANK()` — tied rows share a rank; the next rank does **not** skip
  (`1, 1, 2, …`).
- `PERCENT_RANK()` — the relative rank `(rank − 1) / (rows − 1)` as a fraction in
  `[0.0, 1.0]`; fixed at `0.0` for a single-row partition.
- `NTILE(n)` — divides the partition into `n` buckets as evenly as possible and
  assigns each row its bucket number `1..n`. When the partition does not divide
  evenly, the leftover rows go to the **lower** buckets (bucket 1 first).

The output schema is the input schema with the `AS` column (always `NUMBER`)
appended. Every input row is preserved and carries its rank.

# Technical Description:
Ranking functions are the window operator (`WindowNode` with a `RankingWindow`
function). Unlike `TOP` (which filters to the top-k rows and
adds no column) and `γ`/`DOWNSAMPLE` (which collapse rows), a window adds a column
without removing any. The operator buffers per partition (`[bag]` materialisation)
and is blocking over a provably-unbounded input.

## SQL pushdown

When the source is backed by a JDBC connection and the dialect supports window
functions (PostgreSQL and the GENERIC H2 dialect; MySQL is conservative), the
ranking window is folded into the SQL scan:

  SELECT dept, emp_id, salary, ROW_NUMBER() OVER (PARTITION BY dept ORDER BY salary DESC) AS rn
  FROM employees

A `σ` immediately above a ranking window over a pushed source is evaluated
in-engine (SQL `WHERE` cannot reference window columns at the same query level).
MongoDB sources evaluate windows in-engine.

Ties are detected by comparing rows under the `SORT` keys: two rows are tied iff
they compare equal on every sort key. `NTILE(n)` requires a positive integer
literal `n` (no column references).

`TOP k … PER` and ranking windows are complementary, not overlapping: `TOP` keeps
only the top-k rows, while a ranking window keeps **every** row with its rank and
lets the caller decide what to do — typically filter downstream with `σ`.

# Examples:
Top sellers per category — rank, then filter (the three integer functions are
compared on one result below):

```relix
Ranked := { WINDOW RANK() SORT units_sold DESC PER category_id AS rnk (Products) };
query { σ rnk ≤ 3 (Ranked) };
```

Latest event per user — deduplication with `ROW_NUMBER`: number each user's events
newest-first, then keep only number 1. This is the "one row per key" idiom SQL
writes as `ROW_NUMBER() OVER (PARTITION BY … ORDER BY …) = 1`, and it is worked
through with real output below.

```relix
Latest := { WINDOW ROW_NUMBER() SORT event_time DESC PER user_id AS rn (Events) };
query { σ rn = 1 (Latest) };
```

Second-highest distinct salary per department (`DENSE_RANK`) — because
`DENSE_RANK` never skips, `dr = 2` selects the runner-up salary *tier*, even when
several employees share the top salary:

```relix
Tiered := { WINDOW DENSE_RANK() SORT salary DESC PER dept_id AS dr (Salaries) };
query { σ dr = 2 (Tiered) };
```

`RANK` would label that same employee `3` when two share the top salary, which is
why `DENSE_RANK` is the tool for "Nth-highest distinct value".

Salary percentile within department (`PERCENT_RANK`):

```relix
WithPct := { WINDOW PERCENT_RANK() SORT salary ASC PER dept AS percent_rank (Employees) };
query { σ percent_rank >= 0.9 (WithPct) };  -- the top decile per department
```

Quartiles by YTD revenue per region (`NTILE`):

  WINDOW NTILE(4) SORT ytd_revenue DESC PER region AS qtile (Sales)

A 5-row partition split into 2 buckets (`NTILE(2)`) assigns `1, 1, 1, 2, 2` — the
remainder row goes to the lower bucket.


# Worked Example:
The three integer ranking functions differ only in how they treat a tie, so the
way to see them is side by side over data that has one. `toys` has two products
tied on 30 units:

```relix
Products := [
| category | product | units |
|----------|---------|-------|
| toys     | yo-yo   | 30    |
| toys     | kite    | 30    |
| toys     | drum    | 10    |
| books    | atlas   | 20    |
];

query {
    WINDOW DENSE_RANK() SORT units DESC PER category AS dense (
        WINDOW RANK() SORT units DESC PER category AS rnk (
            WINDOW ROW_NUMBER() SORT units DESC PER category AS row_num
                (Products)))
};
```

```
 category  product  units  row_num  rnk  dense
 ────────  ───────  ─────  ───────  ───  ─────
 toys      yo-yo       30        1    1      1
 toys      kite        30        2    1      1
 toys      drum        10        3    3      2
 books     atlas       20        1    1      1
(4 rows)
```

Windows compose, which is why all three fit in one query: each adds its column
and passes every row on.

Read the `toys` partition across: `row_num` numbers the tied rows 1 and 2 —
it never ties, so it has to break the tie arbitrarily. `rnk` gives both 1 and then
jumps to 3, leaving a gap the size of the tie. `dense` gives both 1 and continues
at 2. `books` has a single row, so all three read 1.

**One row per key.** The most common use of `ROW_NUMBER` is not to show the number
but to throw it away again: number each partition in the order you care about, keep
number 1, drop the column.

```relix
Events := [
| user_id | event_time | action |
|---------|------------|--------|
| 7       | 2026-06-01 | login  |
| 7       | 2026-06-03 | logout |
| 7       | 2026-06-02 | view   |
| 9       | 2026-06-05 | login  |
];

Latest := { WINDOW ROW_NUMBER() SORT event_time DESC PER user_id AS rn (Events) };

query { π user_id, event_time, action (σ rn = 1 (Latest)) };
```

```
 user_id  event_time  action
 ───────  ──────────  ──────
       7  2026-06-03  logout
       9  2026-06-05  login
(2 rows)
```

User 7's other two events were numbered 2 and 3 and filtered out; user 9's single
event is its own number 1. Note that the window had to run over every row to
produce a result of two — ranking is blocking, and `σ rn = 1` is a filter on its
output, not a shortcut through it.

# Limitations:
The added column needs a name the input does not already use. Over a
schema-on-read source (JSON, HTTP, MongoDB) there is no declared heading to
clash with, so the operator always runs; if a document turns out to carry a
field of that name, the added column replaces it.

`NTILE(n)` takes a positive integer literal; `ROW_NUMBER`/`RANK`/`DENSE_RANK`/
`PERCENT_RANK` take no argument. The `AS` column must not clash with an existing
input column. Offset window functions (`WINDOW LAG / LEAD / FIRST_VALUE /
LAST_VALUE`) are a sibling family — see [Window / Offset](window-offset.md). SQL
pushdown is supported on PostgreSQL and GENERIC (H2) backends; MySQL and MongoDB
always evaluate ranking windows in-engine.

# Alternatives:
For "N highest rows per group" as a row filter use [TOP … PER](../advanced/top.md).
For sliding/cumulative aggregates use [ROLLING](rolling.md). For one collapsed row
per group use [γ](group.md).

# See Also:
[rolling](rolling.md), [group](group.md), [sort](sort.md), [top](../advanced/top.md)

# Notes:
`ROLLING` and `WINDOW` are reserved keywords; the function names (`ROW_NUMBER`,
`RANK`, `DENSE_RANK`, `PERCENT_RANK`, `NTILE`) are **contextual** — parsed only
inside a `WINDOW` clause — so they remain usable as column names elsewhere.

**Partition pruning (WINDOW-001).** A selection that fixes a `PER` (partition)
key to a constant above a window — e.g.
`σ region = "WEST" (WINDOW ROW_NUMBER() … PER region (Sales))` — is pushed
*below* the operator by the optimizer, so the window is computed only for the
matching partition and the others are never ranked:
`WINDOW ROW_NUMBER() … PER region (σ region = "WEST" (Sales))`. This is safe
because the window frame never crosses a partition boundary. Only equalities on a
partition key are pushed; a predicate on a non-partition column or on the computed
window column (e.g. `rk ≤ 3`) stays as a residual σ above the operator. Look for a
`WINDOW-001` record in `--optimize` output.
