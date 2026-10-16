# Name: Group / Aggregation (γ / GROUP [BY])

# Syntax:
γ <grouping-key> [→ alias] [, ...], <agg>(expr) → alias [, ...] (Relation)
GROUP <grouping-key> [-> alias] [, ...], <agg>(expr) -> alias [, ...] (Relation)
GROUP BY ...       -- SQL-prior synonym; BY is an optional noise word

γ dept, SUM(salary) → total (Employees)
GROUP BY dept, SUM(salary) -> total (Employees)   -- same AST as the γ form above
γ YEAR(order_date) → yr, SUM(amount) → revenue (Orders)   -- derived grouping key
γ SUM(amount) → grand_total (Orders)        -- no grouping = whole-relation total
γ user_id, COUNT(DISTINCT url) → pages (Clicks)   -- each distinct value once
γ region, COUNT(*) FILTER (WHERE status = 'open') → open_n (Orders)   -- only some rows
γ ROLLUP (region, product), SUM(amount) → total (Sales)   -- subtotals and a grand total
γ CUBE (region, product), SUM(amount) → total (Sales)     -- every combination of totals
γ GROUPING SETS ((region), (product), ()), SUM(amount) → total (Sales)   -- the totals listed

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

To get subtotals in the same result, group by `ROLLUP (region, product)`. That
gives a row for each region and product, a subtotal for each region and a grand
total. `CUBE` gives the totals for every combination of its keys. `GROUPING SETS`
gives exactly the groupings you list.

# Technical Description:
γ partitions R by the grouping-key tuple and applies each aggregate function to
its group. Both grouping keys and aggregate arguments are full operand
expressions (e.g. group by `DATE_TRUNC('day', ts)`, aggregate `SUM(price*qty)`),
not just column names; each grouping key takes an optional `→ alias` (else the
column name for a bare column, or an expression-derived name). Supported
aggregates: SUM, AVG, COUNT, MIN, MAX, COLLECT (NEST → array), ARGMAX, ARGMIN, and
the statistical STDDEV, STDDEV_SAMP, STDDEV_POP, VARIANCE, VAR_SAMP, VAR_POP, MEDIAN,
PERCENTILE_CONT and PERCENTILE_DISC, and STRING_AGG (with its GROUP_CONCAT and
LISTAGG spellings), which with COLLECT takes an `ORDER BY` inside its parentheses.
All but ARGMAX and ARGMIN take SQL's `DISTINCT` after the parenthesis —
`COUNT(DISTINCT session)` — and then read each distinct non-NULL value of the
argument once per group (COLLECT keeps one NULL, as it keeps NULLs at all).
Output schema = grouping-key columns + one column per aggregate (named by its
alias, else `op_arg`). γ is a blocking/materialising operator ([bag]) but becomes
a single streaming pass when its input is already ordered by the grouping keys.
SUM/AVG/COUNT/MIN/MAX over bare-column grouping keys push down to SQL GROUP BY (a
derived grouping key aggregates in-engine).

**Grouping sets.** A grouping element can be SQL's `ROLLUP (k, …)`, `CUBE (k, …)` or
`GROUPING SETS (set, …)`, where a set is `(k, …)`, `()` or one key. Each grouping set
groups the rows by its own keys, and every other key is NULL in the rows it produces.
`ROLLUP (a, b)` is the sets `(a, b)`, `(a)` and `()`. `CUBE (a, b)` is `(a, b)`, `(a)`,
`(b)` and `()`, and a CUBE takes at most 12 keys. Several elements combine as SQL's do,
one set from each: `region, ROLLUP (product)` is `(region, product)` and `(region)`.
A key written twice is one key, and a set listed twice gives its rows twice. The
output columns are the keys in the order the sets first name them, which is the order
they are written in unless a plain key follows a GROUPING SETS whose sets do not all
name the same keys. [GROUPING(k)](../aggregates/grouping.md) tells a rolled-up NULL
from a NULL in the data. The input is read once, and every set is computed from that
read. An empty set, the grand total, gives its row over an empty input, as an ungrouped
γ does. The other sets give no rows then. A σ above the γ on a key that some set rolls
up is not moved below it, because below it would keep that set's rows. Grouping sets
push down as `GROUP BY GROUPING SETS (…)` to PostgreSQL, DuckDB, SQL Server and Db2.
They push as `GROUP BY … WITH ROLLUP` to MySQL when they are a ROLLUP of the keys in
the order listed. Anything else runs in the engine. The γ prints back as
`GROUPING SETS (…)`.

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

Distinct pages per user from a click log, beside the click count:
  γ user_id, COUNT(DISTINCT url) → pages, COUNT(*) → clicks (Clicks)

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

**Subtotals.** `ROLLUP` adds a subtotal for each region and a grand total below the
detail. [GROUPING](../aggregates/grouping.md) marks the rows it added:

```relix
query { τ level, region, rep (γ ROLLUP (region, rep), SUM(amount) → total,
                              GROUPING(region, rep) → level (Sales)) };
```

```
 region  rep   total  level
 ──────  ────  ─────  ─────
 east    Ada     120      0
 east    Bo       80      0
 west    Cy      200      0
 west    Dee      50      0
 east    NULL    200      1
 west    NULL    250      1
 NULL    NULL    450      3
(7 rows)
```

The `level` is 1 where `rep` is rolled up and 3 where both keys are. A NULL with
level 0 would be a NULL in the data.

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
[sum](../aggregates/sum.md), [avg](../aggregates/avg.md), [count](../aggregates/count.md), [min](../aggregates/min.md), [max](../aggregates/max.md), [collect](../aggregates/collect.md), [argmax](../aggregates/argmax.md), [argmin](../aggregates/argmin.md), [filter](../aggregates/filter.md), [grouping](../aggregates/grouping.md), [stddev](../aggregates/stddev.md), [percentile](../aggregates/percentile_cont.md), [top](../advanced/top.md), [forall](forall.md), [sort](sort.md)

# Notes:
When the planner can prove the input is already sorted on the grouping keys it
chooses a streaming aggregate (no hash table, no full buffering) — visible in
`--explain` as `Aggregate streaming`.
