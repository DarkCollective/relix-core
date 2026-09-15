# Name: COLLECT (aggregate / NEST)

# Syntax:
γ <grouping-cols>, COLLECT(<expr>) → <alias> (Relation)

γ customer_id, COLLECT(order_id) → order_ids (Orders)

# Description:
COLLECT gathers all the values in a group into a single array, producing nested
output. Instead of one summary number it gives you the whole list — each
customer's order ids bundled into an array, each article's tags in one column. It
is the inverse of UNNEST (μ), which explodes an array back into rows.

# Technical Description:
COLLECT is the NEST aggregate, used only inside γ. It gathers each group's values
into an ArrayValue, so the output column has an array type (NF² nested relation).
Its argument is a full operand expression, so you can collect structs too:
`COLLECT({order_id, amount})`. It produces nested output and never pushes down to
SQL.

# Examples:
Each customer's order ids as an array:
  γ customer_id, COLLECT(order_id) → order_ids (Orders)

Collect structs — bundle each customer's orders as objects:
```relix
Built := { π customer, { order_id, amount } → o (Orders) };
query { γ customer, COLLECT(o) → orders (Built) };
```

Tags per article:
  γ article_id, COLLECT(tag) → tags (ArticleTags)

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

query { γ region, COLLECT(rep) → reps (Sales) };
```

```
 region  reps
 ──────  ─────────
 east    [Ada, Bo]
 west    [Cy, Dee]
(2 rows)
```

Where every other aggregate reduces a group to one scalar, `COLLECT` keeps all
the values as an array — the relation is no longer flat. [`μ`](../operators/unnest.md)
is the exact inverse, expanding that array back into rows.

`COLLECT` is also the one aggregate that **keeps NULLs** (matching `array_agg` and
`$push`): `COLLECT(bonus)` yields `[10, NULL]` and `[25, NULL]`, not `[10]` and
`[25]`.

# Limitations:
Produces nested (array-valued) columns, which do not push down to SQL and may
need UNNEST (μ) to flatten again downstream. Order within the collected array is
not guaranteed unless the input is ordered. Unlike the other aggregates, COLLECT
**keeps NULLs** rather than skipping them (matching `array_agg` / `$push`), so the
collected array always has one element per row in the group.

# Alternatives:
UNNEST (μ) is the opposite direction. For a single representative value per group
use MIN/MAX/ARGMAX instead of collecting all of them.

# See Also:
[unnest](../operators/unnest.md), [group](../operators/group.md), [struct-construction](../literals/struct-construction.md), [array-construction](../literals/array-construction.md)

# Notes:
COLLECT followed by μ round-trips back to the original rows; the optimizer can
collapse that round trip (NEST-001).
