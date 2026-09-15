# Name: AVG (aggregate)

# Syntax:
γ <grouping-cols>, AVG(<expr>) → <alias> (Relation)

γ dept, AVG(salary) → avg_salary (Employees)

# Description:
AVG computes the average (mean) of the values in each group — average salary per
department, average order value per customer, average score per class. Use it
inside a GROUP (γ).

# Technical Description:
AVG is an aggregate function used only inside γ. Its argument is a full operand
expression. It returns the arithmetic mean of the non-NULL values in each group.
With no grouping key it averages the whole relation. It is **computed by the engine**
rather than pushed to the database: an average divides, and a relix average is exact
decimal to ten places rounded half-up, which no backend's `AVG` carries. `SUM` and
`COUNT` do push, and dividing them yourself puts the rounding under your control.

# Examples:
Average salary per department:
  γ dept, AVG(salary) → avg_salary (Employees)

Overall average order value:
  γ AVG(amount) → avg_order (Orders)

Average of a computed expression:
  γ store, AVG(price * qty) → avg_line_value (LineItems)

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

query { γ region, AVG(amount) → mean (Sales) };
```

```
 region  mean
 ──────  ────
 east     100
 west     125
(2 rows)
```

Over `bonus` the whole-relation average is `17.5`, not `8.75`: `AVG` skips NULLs
on both sides of the division — it is `35 / 2`, not `35 / 4`. If you want the
NULLs counted as zeros, say so explicitly with `AVG(Nz(bonus, 0))`.

# Limitations:
Numeric only; NULLs are excluded from the average. Must appear inside a γ. An
empty group has no average.

# Alternatives:
SUM ÷ COUNT computed yourself if you need control over NULL handling; AVG is the
direct form.

# See Also:
[group](../operators/group.md), [sum](sum.md), [count](count.md), [min](min.md), [max](max.md)

# Notes:
Combine with τ and λ for "top N by average" reports, e.g.
`λ 10 (τ avg_salary DESC (γ dept, AVG(salary) → avg_salary (Employees)))`.
