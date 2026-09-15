# Name: MIN (aggregate)

# Syntax:
γ <grouping-cols>, MIN(<expr>) → <alias> (Relation)

γ product_id, MIN(price) → lowest_price (Listings)

# Description:
MIN finds the smallest value in each group — the cheapest price, the earliest
date, the lowest score. Use it inside a GROUP (γ).

# Technical Description:
MIN is an aggregate used only inside γ; its argument is a full operand expression.
It returns the minimum non-NULL value in each group and works on numbers, strings
(lexicographic), and temporal types. A group with no non-NULL value yields NULL.
Pushes down to SQL `MIN(...)`.

# Examples:
Lowest listed price per product:
  γ product_id, MIN(price) → lowest_price (Listings)

Earliest order date per customer:
  γ customer_id, MIN(order_date) → first_order (Orders)

Smallest absolute deviation per sensor (expression argument):
  γ sensor, MIN(Abs(delta)) → closest (Readings)

# Worked Example:
Four rows of sales, two of them with no `bonus` recorded — the NULLs are there deliberately, because they are what makes the rules below visible.

```relix
Sales := [
| region | rep  | amount | bonus |
|--------|------|--------|-------|
| east   | Ada  | 120    | 10    |
| east   | Bo   | 80     | NULL  |
| west   | Cy   | 200    | 25    |
| west   | Dee  | 50     | NULL  |
];

query { γ region, MIN(amount) → smallest (Sales) };
```

```
 region  smallest
 ──────  ────────
 east          80
 west          50
(2 rows)
```

`MIN` returns the smallest **value**, not the row it came from — `rep` is not
available here, because there is no single rep per group. When you want the row,
use [ARGMIN](argmin.md): `ARGMIN(amount, rep)` returns `Bo` and `Dee`.

Over an all-NULL group `MIN` yields NULL rather than an error, since every NULL is
skipped and nothing is left to compare.

# Limitations:
Returns only the value, not the row that achieved it — for the whole row use
ARGMIN or TOP … PER. Must appear inside a γ.

# Alternatives:
ARGMIN(rank, yield) returns a value from the minimum-rank row; TOP 1 … PER keeps
the actual lowest row.

# See Also:
[group](../operators/group.md), [max](max.md), [argmin](argmin.md), [top](../advanced/top.md)

# Notes:
MIN over a temporal column gives the chronologically earliest value.
