# Name: Group / Aggregation (γ / GROUP [BY])

# Syntax:
γ <grouping-key> [→ alias] [, ...], <agg>(expr) → alias [, ...] (Relation)
GROUP <grouping-key> [-> alias] [, ...], <agg>(expr) -> alias [, ...] (Relation)
GROUP BY ...       -- SQL-prior synonym; BY is an optional noise word

γ dept, SUM(salary) → total (Employees)
GROUP BY dept, SUM(salary) -> total (Employees)   -- same AST as the γ form above
γ YEAR(order_date) → yr, SUM(amount) → revenue (Orders)   -- derived grouping key
γ SUM(amount) → grand_total (Orders)        -- no grouping = whole-relation total

# Description:
Group is how you summarise data: collapse many rows into one row per group and
compute totals, averages, counts, minimums, maximums and more. It answers
questions like "total sales per region" or "how many orders did each customer
place". List the keys to group by first, then the aggregate calculations.

A grouping key can be a bare column or a full expression — e.g.
`YEAR(order_date) → yr` or `price * qty → line` — with an optional `→ alias`
naming the output column (a bare column keeps its own name). This mirrors
projection: you can group by a computed value, not only a stored column. Write the
keys as a plain comma-separated list, without braces: `{region}` is an expression
too (a struct), so braces change what you group by instead of raising an error.
The worked example below shows the difference.

If you give no grouping keys, the whole relation collapses to a single
summary row.

# Technical Description:
γ partitions R by the grouping-key tuple and applies each aggregate function to
its group. Both grouping keys and aggregate arguments are full operand
expressions (e.g. group by `DATE_TRUNC('day', ts)`, aggregate `SUM(price*qty)`),
not just column names; each grouping key takes an optional `→ alias` (else the
column name for a bare column, or an expression-derived name). Supported
aggregates: SUM, AVG, COUNT, MIN, MAX, COLLECT (NEST → array), ARGMAX, ARGMIN.
Output schema = grouping-key columns + one column per aggregate (named by its
alias, else `op_arg`). γ is a blocking/materialising operator ([bag]) but becomes
a single streaming pass when its input is already ordered by the grouping keys.
SUM/AVG/COUNT/MIN/MAX over bare-column grouping keys push down to SQL GROUP BY (a
derived grouping key aggregates in-engine).

# Examples:
Total salary cost per department:
  γ dept, SUM(salary) → total (Employees)

Several aggregates at once — headcount and average pay per department:
  γ dept, COUNT(emp_id) → headcount, AVG(salary) → avg_pay (Employees)

Grand total over everything (no grouping key):
  γ SUM(amount) → grand_total (Orders)

Aggregate an expression, not just a column — revenue per region:
  γ region, SUM(price * qty) → revenue (Sales)

Group by a derived key — annual revenue, bucketing by the order year:
  γ YEAR(order_date) → yr, SUM(amount) → revenue (Orders)

Gather each customer's order ids into an array (NEST):
  γ customer_id, COLLECT(order_id) → order_ids (Orders)

Row-with-the-maximum — the biggest order id per customer:
  γ customer_id, ARGMAX(amount, order_id) → top_order (Orders)

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

query { γ region, SUM(amount) → total, COUNT(*) → deals (Sales) };
```

```
 region  total  deals
 ──────  ─────  ─────
 east      200      2
 west      250      2
(2 rows)
```

Four rows collapse into one per distinct `region`. The output schema is exactly
the grouping keys plus the aggregates — the input's other columns (`rep`, `bonus`)
are **gone**, because there is no single value for them within a group. If you
need one, say which: `ARGMAX(amount, rep)` picks the rep from the group's biggest
deal, and `COLLECT(rep)` keeps them all.

**Do not put braces around the grouping keys.** The keys are a plain
comma-separated list. Braces are legal there, but they do not group: `{region}`
[builds a struct](../literals/struct-construction.md), so the braced form groups
by a one-field struct and produces a different heading. It raises no error:

```relix
query { γ {region}, SUM(amount) → total (Sales) };
```

```
 group           total
 ──────────────  ─────
 {region: east}    200
 {region: west}    250
(2 rows)
```

The groups are the same, but there is no `region` column any more. There is one
struct column, called `group` because an expression key with no alias is named
that way. A later `σ region = 'east'` over this result fails analysis because the
column does not exist. `γ {region, rep}` is the same mistake with two keys: it
groups by the pair, but returns a single struct column where you wanted two. Write
`γ region, rep, …` instead.

# Limitations:
COLLECT, ARGMAX and ARGMIN do not push down to SQL (no portable equivalent) and
run in the engine. A group with all-NULL aggregate inputs follows SQL-style NULL
handling per aggregate.

At least one aggregate is required. Grouping keys on their own do not form a γ:

```relix-invalid
query { γ region (Sales) };
```

To list the distinct values of a column, project it and remove duplicates with
`δ (π region (Sales))`.

# Alternatives:
To keep the actual rows that achieve a maximum per group (not just one value),
use TOP k … PER. To test a condition across a whole group, use ∀ (FORALL).

# See Also:
[sum](../aggregates/sum.md), [avg](../aggregates/avg.md), [count](../aggregates/count.md), [min](../aggregates/min.md), [max](../aggregates/max.md), [collect](../aggregates/collect.md), [argmax](../aggregates/argmax.md), [argmin](../aggregates/argmin.md), [top](../advanced/top.md), [forall](forall.md), [sort](sort.md)

# Notes:
When the planner can prove the input is already sorted on the grouping keys it
chooses a streaming aggregate (no hash table, no full buffering) — visible in
`--explain` as `Aggregate streaming`.
