# Name: MAX (aggregate)

# Syntax:
γ <grouping-cols>, MAX(<expr>) → <alias> (Relation)

γ product_id, MAX(price) → highest_price (Listings)

# Description:
MAX finds the largest value in each group — the highest price, the latest date,
the top score. Use it inside a GROUP (γ).

# Technical Description:
MAX is an aggregate used only inside γ; its argument is a full operand expression.
It returns the maximum non-NULL value in each group and works on numbers, strings
(lexicographic), and temporal types. A group with no non-NULL value yields NULL.
Pushes down to SQL `MAX(...)`.

# Examples:
Highest listed price per product:
  γ product_id, MAX(price) → highest_price (Listings)

Most recent login per user:
  γ user_id, MAX(login_at) → last_seen (Logins)

Largest single line total per order:
  γ order_id, MAX(price * qty) → biggest_line (LineItems)

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

query { γ region, MAX(amount) → largest (Sales) };
```

```
 region  largest
 ──────  ───────
 east        120
 west        200
(2 rows)
```

As with `MIN`, this is the largest **value** and not the row that carried it. The
"who had the biggest deal?" question is [ARGMAX](argmax.md), which is a genuinely
different operation — in SQL it needs a window function or a self-join.

# Limitations:
Returns only the value, not the row that achieved it — for the whole row use
ARGMAX or TOP … PER. Must appear inside a γ.

# Alternatives:
ARGMAX(rank, yield) returns a value from the maximum-rank row; TOP 1 … PER keeps
the actual highest row.

# See Also:
[group](../operators/group.md), [min](min.md), [argmax](argmax.md), [top](../advanced/top.md)

# Notes:
MAX over a temporal column gives the chronologically latest value.
