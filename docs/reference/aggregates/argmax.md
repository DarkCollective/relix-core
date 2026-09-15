# Name: ARGMAX (aggregate)

# Syntax:
γ <grouping-cols>, ARGMAX(<rank-expr>, <yield-expr>) → <alias> (Relation)

γ customer_id, ARGMAX(amount, order_id) → biggest_order (Orders)

# Description:
ARGMAX answers "for the row with the biggest X, give me Y". For each group it
finds the row where the rank expression is largest and returns the yield
expression's value from that row — for example, the order id of each customer's
largest order, or the name of the top scorer in each class. It is the "row with
the maximum" lookup that SQL needs a window function or self-join for.

# Technical Description:
ARGMAX(rank, yield) is a two-argument aggregate used only inside γ: it returns the
`yield` expression evaluated on the row whose `rank` expression is the group
maximum. Ties resolve to the first such row; rows with a NULL rank are skipped; an
empty group yields NULL. It infers as the yield expression's type and never pushes
down to SQL.

# Examples:
The id of each customer's largest order:
  γ customer_id, ARGMAX(amount, order_id) → biggest_order (Orders)

The name of the top scorer per class:
  γ class, ARGMAX(score, student_name) → top_student (Results)

Yield a computed expression from the max-revenue row:
  γ region, ARGMAX(revenue, units * price) → top_line_value (Sales)

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

query { γ region, ARGMAX(amount, rep) → top_rep (Sales) };
```

```
 region  top_rep
 ──────  ───────
 east    Ada
 west    Cy
(2 rows)
```

`MAX(amount)` would return 120 and 200 — the amounts. `ARGMAX(amount, rep)`
returns *who* achieved them. Read the two arguments as "rank by the first, return
the second".

That is the "row with the maximum" question, which SQL answers only with a window
function or a self-join. Because the answer is a specific row's value, `ARGMAX`
never pushes down to SQL — it is evaluated in-engine.

# Limitations:
Returns a single value from one row, not the whole row — for the entire row use
TOP 1 … PER. Ties pick the first encountered row (not all of them).

# Alternatives:
TOP 1 rank DESC PER keys keeps the actual top row with all its columns. MAX
returns the maximum value itself rather than another column from that row.

# See Also:
[argmin](argmin.md), [max](max.md), [top](../advanced/top.md), [group](../operators/group.md)

# Notes:
ARGMAX is the aggregate counterpart to the TOP operator; use ARGMAX for a value,
TOP for the rows.
