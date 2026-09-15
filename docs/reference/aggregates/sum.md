# Name: SUM (aggregate)

# Syntax:
γ <grouping-cols>, SUM(<expr>) → <alias> (Relation)

γ region, SUM(amount) → revenue (Sales)
γ SUM(amount) → grand_total (Orders)        -- whole-relation total

# Description:
SUM adds up the values in each group. It is the workhorse for totals — total
sales per region, total hours per project, grand totals across everything. Use it
inside a GROUP (γ); the column it produces is named with the → arrow.

# Technical Description:
SUM is an aggregate function used only inside γ (AggregationNode). Its argument is
a full operand expression, not just a column name, so `SUM(price * qty)` works. It
reduces a group's multiset to one number. With no grouping key it produces a
single grand total. Pushes down to SQL `SUM(...)`.

# Examples:
Revenue per region:
  γ region, SUM(amount) → revenue (Sales)

Grand total over the whole table:
  γ SUM(amount) → grand_total (Orders)

Sum of a computed expression — revenue from price × quantity:
  γ region, SUM(price * qty) → revenue (Sales)

Multiple totals at once:
  γ dept, SUM(salary) → payroll, SUM(salary) → bonuses (Employees)

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

query { γ region, SUM(amount) → total (Sales) };
```

```
 region  total
 ──────  ─────
 east      200
 west      250
(2 rows)
```

`SUM` over `bonus` instead would give 10 and 25 — the NULLs are **skipped**, not
treated as zero. That distinction matters when the sum feeds a division: summing a
column that is half NULL and dividing by `COUNT(*)` gives a different answer from
`AVG`, which divides by the count of non-NULL values.

# Limitations:
Only meaningful on numeric values. NULL inputs are skipped per standard aggregate
NULL handling, and a group with no non-NULL value sums to **NULL, not 0** — so an
empty total stays distinguishable from a real total of zero. To fold that back to
zero, project over the γ result (a scalar function cannot wrap an aggregate
in-place):
  Totals := { γ region, SUM(amount) → revenue (Sales) };
  { π region, Nz(revenue, 0) → revenue (Totals) }
Must appear inside a γ.

# Alternatives:
AVG for the mean, COUNT for how many.

# See Also:
[group](../operators/group.md), [avg](avg.md), [count](count.md), [min](min.md), [max](max.md)

# Notes:
The aggregate argument is an expression, so you rarely need a separate projection
to compute the thing you are summing.
