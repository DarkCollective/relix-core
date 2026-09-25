# Name: Top-K Per Group (TOP … PER)

# Syntax:
TOP <count> <sort-specs> [PER <keys>] (Relation)
TOP <offset>, <count> <sort-specs> [PER <keys>] (Relation)

TOP 3 amount DESC PER customer_id (Orders)
TOP 5, 3 amount DESC PER customer_id (Orders)   -- skip 5, take 3 per group
TOP 3 amount DESC (Orders)                      -- one global group: the top 3 overall

# Description:
TOP keeps the highest (or lowest) N rows within each group. It answers "the 3
biggest orders for each customer", "the 5 most recent events per device", "the
top earner in each department" — the per-group ranking that SQL needs a window
function for. You give the number to keep, how to rank (a sort spec), and the
column(s) that define each group (PER).

# Technical Description:
**`PER` is optional.** Written without it, there is one global group and TOP is the
top `count` rows overall — which is what `λ` over `τ` means, and what the optimizer
rewrites that pair into (`LIM-003`). Writing it directly says the same thing and
costs the reader nothing to recognise.

TOP returns, per partition defined by the PER keys, the `count` rows ranked
highest by the sort specifications (optionally skipping `offset` rows first) —
the `ROW_NUMBER() OVER (PARTITION BY … ORDER BY …) ≤ k` idiom. Requires ≥1 sort
spec and ≥1 PER key. Output = full input rows (a windowed filter). It cannot
desugar (no per-group ranking primitive) and does not push down ([bag]).

# Examples:
The 3 biggest orders per customer:
  TOP 3 amount DESC PER customer_id (Orders)

The most recent reading per sensor:
  TOP 1 reading_time DESC PER sensor_id (Readings)

2nd and 3rd place per category (skip the winner):
  TOP 1, 2 score DESC PER category (Entries)

Top earner in each department:
  TOP 1 salary DESC PER dept (Employees)

# Worked Example:
A retailer wants the two biggest orders for each customer — a leaderboard per
group, keeping the full order rows (not just the amount).

```relix
Orders := [
| customer | order_id | amount |
|----------|----------|--------|
| Alice    | 1        | 120    |
| Alice    | 2        | 80     |
| Alice    | 3        | 200    |
| Bob      | 4        | 50     |
| Bob      | 5        | 75     |
];

Biggest := { TOP 2 amount DESC PER customer (Orders) };
```

Within each `customer` partition the rows are ranked by `amount DESC` and the top
two kept:

```relix
query { Biggest };
```

```
 customer  order_id  amount
 ────────  ────────  ──────
 Alice            3     200
 Alice            1     120
 Bob              5      75
 Bob              4      50
(4 rows)
```

Bob has only two orders, so both survive. To get *2nd and 3rd place* per customer
(skip the winner) use the offset form — `TOP 1, 2 amount DESC PER customer
(Orders)` skips 1 row and takes the next 2. And to keep only a single value per
group rather than the whole row — e.g. just the highest amount — reach for the
`ARGMAX` aggregate instead.

# Limitations:
Requires both a sort spec and at least one PER key — a global "top N overall" is
written `λ N (τ … )`, not TOP. (The optimizer *fuses* that pair into a keyless
TOP for you — `LIM-003`, see the [optimizer](optimizer.md) page — so you get the
bounded heap without a spelling for it.) Returns whole rows, not a single
aggregated value.

# Alternatives:
γ with ARGMAX/ARGMIN returns one value from the extreme row per group (not the
whole row, and only one). λ over τ for a global top-N without grouping.

# See Also:
[log source](../language/log-source.md) — TOP over a web server's access log, among other windowing examples on one real dataset

[argmax](../aggregates/argmax.md), [argmin](../aggregates/argmin.md), [group](../operators/group.md), [sort](../operators/sort.md), [limit](../operators/limit.md)

# Notes:
TOP is the "keep the actual rows" counterpart to the ARGMAX aggregate (which
returns a value).

**Ties.** Where the sort specifications cannot separate two rows, the one that
reached the operator first is kept — the same rows a full sort followed by a
limit would leave. So `TOP 1 amount DESC PER customer` over two orders of equal
amount returns the earlier of the two rather than an arbitrary one. A relation
has no inherent row order, though, so "earlier" is only as meaningful as the
order the input delivers: add a tie-breaking sort key when the choice matters.

**Partition pruning (TOPK-001).** A selection that fixes a PER key to a constant
above a TOP — `σ customer = "Alice" (TOP 2 amount DESC PER customer (Orders))` —
is pushed *below* the operator by the optimizer, so top-k is computed only for the
matching group instead of every group:
`TOP 2 amount DESC PER customer (σ customer = "Alice" (Orders))`. This is safe
because top-k is computed independently per group. Only equalities on a PER key
are pushed; a predicate on any other column (or on a computed rank) stays as a
residual σ above the operator. Look for a `TOPK-001` record in `--optimize`
output.
