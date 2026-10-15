# Name: COUNT (aggregate)

# Syntax:
γ <grouping-cols>, COUNT(<expr>) → <alias> (Relation)

γ customer_id, COUNT(order_id) → num_orders (Orders)
γ user_id, COUNT(DISTINCT url) → pages (Clicks)

# Description:
COUNT tells you how many rows are in each group — how many orders each customer
placed, how many employees per department, how many events per day. Use it inside
a GROUP (γ).

# Technical Description:
COUNT is an aggregate function used only inside γ, and comes in two forms:

- `COUNT(*)` counts **rows** — every row in the group, whatever it contains.
- `COUNT(expr)` counts **non-NULL values** of the expression. A group whose
  values are all NULL counts 0.

- `COUNT(DISTINCT expr)` counts **distinct non-NULL values** — each value once,
  however many rows hold it.

That is SQL's distinction exactly. `COUNT(*)` is a pure synonym for `COUNT(1)` —
a literal is never NULL, so counting it counts every row — and the two parse to
the identical tree, so `:tree` renders either as `COUNT(1)`.

With no grouping key, COUNT runs over the whole relation. All three forms push
down to SQL (`COUNT(*)` as `COUNT(1)`, which every backend treats identically, and
`COUNT(DISTINCT x)` as itself). Values are distinct under the engine's equality, so
`5` and `5.0` are one value, and a distinct count reaches a database under a
collation that tells `a` from `A`. `DISTINCT` is written the same way in `SUM`, `AVG`,
`MIN`, `MAX` and `COLLECT`, inside γ.

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

Distinct values beside rows, in one γ — pages each user visited, and clicks:
  γ user_id, COUNT(DISTINCT url) → pages, COUNT(*) → clicks (Clicks)

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

The third question is "how many different values". A click log has a row per
click, so counting a user's sessions means counting each session once:

```relix
Clicks := [
| user | session | page    |
|------|---------|---------|
| ann  | s1      | home    |
| ann  | s1      | pricing |
| ann  | s1      | signup  |
| ann  | s2      | home    |
| ann  | s2      | docs    |
| bob  | s3      | home    |
| bob  | s3      | pricing |
];

query { τ user (γ user, COUNT(DISTINCT session) → sessions, COUNT(*) → clicks (Clicks)) };
```

```
 user  sessions  clicks
 ────  ────────  ──────
 ann          2       5
 bob          1       2
(2 rows)
```

`COUNT(session)` would have answered 5 for ann, the number of clicks, since
every click has a session.

# Limitations:
Must appear inside a γ. `COUNT(col)` counts non-NULL values, so it is a row count
only when `col` is never NULL — use `COUNT(*)` when you mean "how many rows".
`*` is special only as the sole argument to COUNT, and `COUNT(a * b)` is still
multiplication. Any other aggregate needs an expression:

```relix-invalid
query { γ SUM(*) → total (Orders) };
```

A distinct count needs something to count distinct values of, as in SQL:

```relix-invalid
query { γ COUNT(DISTINCT *) → n (Orders) };
```

`DISTINCT` is written only inside γ's aggregates; ROLLING and WINDOW take none.

# Alternatives:
`γ COUNT(*) (δ (π x (R)))` is the two-step distinct count, for one value at a time;
for "does any exist" a SEMI join is cheaper than counting.

# See Also:
[group](../operators/group.md), [sum](sum.md), [avg](avg.md), [distinct](../operators/distinct.md), [semi-join](../joins/semi-join.md)

# Notes:
For approximate counts on huge tables, count over a SAMPLE and scale up.
