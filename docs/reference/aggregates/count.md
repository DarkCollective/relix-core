# Name: COUNT (aggregate)

# Syntax:
γ <grouping-cols>, COUNT(<expr>) → <alias> (Relation)

γ customer_id, COUNT(order_id) → num_orders (Orders)

# Description:
COUNT tells you how many rows are in each group — how many orders each customer
placed, how many employees per department, how many events per day. Use it inside
a GROUP (γ).

# Technical Description:
COUNT is an aggregate function used only inside γ, and comes in two forms:

- `COUNT(*)` counts **rows** — every row in the group, whatever it contains.
- `COUNT(expr)` counts **non-NULL values** of the expression. A group whose
  values are all NULL counts 0.

That is SQL's distinction exactly. `COUNT(*)` is a pure synonym for `COUNT(1)` —
a literal is never NULL, so counting it counts every row — and the two parse to
the identical tree, so `:tree` renders either as `COUNT(1)`.

With no grouping key, COUNT runs over the whole relation. Both forms push down to
SQL (`COUNT(*)` as `COUNT(1)`, which every backend treats identically).

# Examples:
Orders per customer:
  γ customer_id, COUNT(order_id) → num_orders (Orders)

Total row count of a table:
  γ COUNT(*) → total_rows (Users)

Rows versus values — how many orders each customer placed, and how many of those
recorded a shipping date:
  γ customer_id, COUNT(*) → orders, COUNT(shipped_at) → shipped (Orders)

Headcount and payroll together:
  γ dept, COUNT(emp_id) → headcount, SUM(salary) → payroll (Employees)

Count within a filtered subset:
  γ region, COUNT(customer_id) → active_count (σ active = true (Customers))

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

query { γ region, COUNT(*) → deals, COUNT(bonus) → bonused (Sales) };
```

```
 region  deals  bonused
 ──────  ─────  ───────
 east        2        1
 west        2        1
(2 rows)
```

The two columns differ, and that is the entire point: `COUNT(*)` counts **rows**
(2 per region), `COUNT(bonus)` counts **non-NULL values** (1 per region, since one
rep in each has no bonus). This is SQL's distinction exactly.

So `COUNT(col)` is a row count only when `col` is never NULL. When you mean "how
many rows", write `COUNT(*)`.

# Limitations:
Must appear inside a γ. `COUNT(col)` counts non-NULL values, so it is a row count
only when `col` is never NULL — use `COUNT(*)` when you mean "how many rows".
`*` is special only as the sole argument to COUNT: `SUM(*)` is a syntax error,
and `COUNT(a * b)` is still multiplication.

# Alternatives:
δ then a whole-relation COUNT for distinct-value counts; for "does any exist" a
SEMI join is cheaper than counting.

# See Also:
[group](../operators/group.md), [sum](sum.md), [avg](avg.md), [distinct](../operators/distinct.md), [semi-join](../joins/semi-join.md)

# Notes:
For approximate counts on huge tables, count over a SAMPLE and scale up.
