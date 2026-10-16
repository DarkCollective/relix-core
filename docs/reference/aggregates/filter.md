# Name: FILTER (aggregate only some rows)

# Syntax:
<aggregate>(…) FILTER (WHERE <condition>)

γ region, COUNT(*) FILTER (WHERE status = 'open') → open_orders (Orders)

# Description:
FILTER lets one grouping compute several conditional aggregates side by side: the
open orders and the closed ones, this year's revenue and last year's. Each aggregate
reads only the rows of its group where its condition holds, while the grouping
itself still sees every row.

# Technical Description:
`AGG(x) FILTER (WHERE c)` is SQL's spelling, and it parses to the portable form other
engines write, `AGG(IIf(c, x, NULL))`: an aggregate skips a NULL, so a row where `c`
is not true contributes nothing. `COUNT(*) FILTER (WHERE c)` counts the rows where
`c` holds. Over a group in which no row passes, COUNT is 0 and SUM, AVG, MIN and MAX
are NULL, as in PostgreSQL. FILTER follows SUM, AVG, COUNT, MIN, MAX, ARGMAX and
ARGMIN, with or without DISTINCT; ARGMAX and ARGMIN filter the ranking value. It
prints back as the IIf form. `FILTER` and `WHERE` are matched by their text, so
neither is reserved.

COLLECT keeps NULLs rather than skipping them, so it takes no FILTER: filter the
rows first with σ.

# Examples:
Open and closed orders per region, in one pass:
  γ region, COUNT(*) FILTER (WHERE status = 'open') → open_n, COUNT(*) FILTER (WHERE status = 'closed') → closed_n (Orders)

The average of the large orders only:
  γ customer_id, AVG(amount) FILTER (WHERE amount > 100) → large_avg (Orders)

# Worked Example:
Five orders in two regions. West has no open order:

```relix
O := [
| region | status | amount |
|--------|--------|--------|
| east   | open   | 10     |
| east   | open   | 30     |
| east   | closed | 5      |
| west   | closed | 7      |
| west   | closed | NULL   |
];

query { τ region (γ region, COUNT(*) FILTER (WHERE status = 'open') → open_n,
                  SUM(amount) FILTER (WHERE status = 'open') → open_sum,
                  AVG(amount) FILTER (WHERE status = 'open') → open_avg,
                  COUNT(*) → n (O)) };
```

```
 region  open_n  open_sum  open_avg  n
 ──────  ──────  ────────  ────────  ─
 east         2        40        20  3
 west         0  NULL      NULL      2
(2 rows)
```

West's open count is 0 and its open sum and average NULL: no row passed, and a sum of
nothing is not zero.

# Pushdown:
As its IIf form: `AGG(CASE WHEN c THEN x ELSE NULL END)`, which every SQL dialect
reads alike, wherever the aggregate itself pushes.

# See Also:
[group](../operators/group.md), [count](count.md), [sum](sum.md), [iif](../functions/conditional/iif.md)
