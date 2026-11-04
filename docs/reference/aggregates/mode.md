# Name: MODE (the most frequent value)

# Syntax:
MODE(<expr>)
MODE(<expr> ORDER BY <sort-key>)               -- ties go to the first in this order
MODE() WITHIN GROUP (ORDER BY <expr> [DESC])   -- PostgreSQL's spelling
STATS_MODE(<expr>)                             -- Oracle's name

γ region, MODE(status) → usual_status (Orders)

# Description:
MODE is the value that occurs most often in a group: each customer's usual status,
each store's best-selling product. Where two or more values are equally frequent,
it picks one by a fixed rule, so the same data always gives the same answer.

# Technical Description:
MODE(x) → the type of x, for any value that orders. NULLs are skipped, and a group
with no value, empty or all NULL, gives NULL. Values are counted under the engine's
equality, so 1 and 1.0 are one value.

A tie goes to the **smallest** of the equally frequent values, in the engine's value
order. PostgreSQL and Oracle both document their choice as arbitrary; a fixed rule
is the only way the answer is reproducible. `MODE() WITHIN GROUP (ORDER BY x DESC)`
gives the tie to the largest instead, as the ordering suggests and as PostgreSQL does
in practice; it prints back as `MODE(x ORDER BY x DESC)`. In general the tie goes to
the first of the equally frequent values in the aggregate's `ORDER BY`, which is the
value itself, ascending, when it names none. STATS_MODE is the same aggregate under
Oracle's name. `DISTINCT` is refused, since it would make every value equally
frequent.

MODE is an ordered-set aggregate, like the percentiles, and does not run in a ROLLING
window. It cannot be computed in parts. The names are matched by their text, so a
column may still be called `mode`.

# Examples:
The usual status of each region's orders:
  γ region, MODE(status) → usual_status (Orders)

PostgreSQL's spelling, a tie going to the largest:
  γ region, MODE() WITHIN GROUP (ORDER BY qty DESC) → usual_qty (Orders)

Oracle's name:
  γ customer_id, STATS_MODE(region) → home_region (Orders)

# Worked Example:
Two codes tie in the east, each sold twice:

```relix
Sales := [
| region | code |
|--------|------|
| east   | B    |
| east   | A    |
| east   | B    |
| east   | A    |
| east   | C    |
| west   | C    |
| west   | C    |
| west   | A    |
];

query { τ region (γ region, MODE(code) → usual, MODE() WITHIN GROUP (ORDER BY code DESC) → usual_desc (Sales)) };
```

```
 region  usual  usual_desc
 ──────  ─────  ──────────
 east    A      B
 west    C      C
(2 rows)
```

In the east `A` and `B` both occur twice, and `MODE(code)` takes the smaller, `A`;
ordered descending, the tie goes to `B`. The west has one most frequent value, so
the order makes no difference there.

# Pushdown:
Runs in the engine. Neither PostgreSQL nor Oracle promises which value a tie gives,
and a backend spelling would claim the backend computes the same value.

# Alternatives:
Without MODE, the most frequent value is a count per value followed by the top one
per group: `TOP 1 n DESC PER region (γ region, code, COUNT(*) → n (Sales))`. That
form also gives the count, and with `WITH TIES` every tied value.

# See Also:
[median](median.md), [percentile_disc](percentile_disc.md), [top](../advanced/top.md), [count](count.md), [group](../operators/group.md)
