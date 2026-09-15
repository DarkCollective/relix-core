# Name: Natural Join (⋈ / JOIN)

# Syntax:
Relation1 ⋈ Relation2
Relation1 JOIN Relation2

Users ⋈ Orders
Users JOIN Orders

# Description:
A natural join stitches two relations together by matching rows that agree on
every column they share by name. If Users and Orders both have a `user_id`
column, `Users ⋈ Orders` pairs each user with their orders automatically — no
condition needed. The shared column appears once in the result.

It is the simplest join to write when your tables are designed with consistent
column names.

# Technical Description:
R ⋈ S joins on the intersection of their attribute names (equality on all common
columns) and projects away the duplicate join columns, keeping one copy. With no
common columns it degenerates to a cross product. It is commutative and the
planner may reorder/choose build side by cardinality; same-connection natural
joins push down to a SQL JOIN.

# Examples:
Pair each user with their orders (shared user_id):
  Users ⋈ Orders

Enrich line items with product details (shared product_id):
  LineItems ⋈ Products

Three-way join chains naturally on shared keys:
  (Customers ⋈ Orders) ⋈ LineItems

# Worked Example:
The same two relations run through every join on this page, so the
results are directly comparable. Two rows do the work: **Cara** has placed no
orders, and **order 103** belongs to customer 4, who is not in `Customers`. Which
of those two survives is exactly what distinguishes one join from the next.

```relix
Customers := [
| customer_id | name  | city   |
|-------------|-------|--------|
| 1           | Alice | London |
| 2           | Bob   | Berlin |
| 3           | Cara  | Lisbon |
];

Orders := [
| order_id | customer_id | amount |
|----------|-------------|--------|
| 100      | 1           | 120    |
| 101      | 1           | 80     |
| 102      | 2           | 45     |
| 103      | 4           | 60     |
];

query { Customers ⋈ Orders };
```

```
 customer_id  name   city    order_id  amount
 ───────────  ─────  ──────  ────────  ──────
           1  Alice  London       100     120
           1  Alice  London       101      80
           2  Bob    Berlin       102      45
(3 rows)
```

Three rows, because Alice has two orders — a join multiplies, it does not
merely look up. Cara and order 103 both vanish: a natural join keeps only rows
that match on **every** shared column, here just `customer_id`. Note that
`customer_id` appears **once**: the natural join projects the duplicate away.

# Limitations:
A natural join needs both sides to **declare** their columns: it matches on the
shared ones, and those are settled before any row is read. Over a schema-on-read
source (JSON, HTTP, MongoDB) there is nothing declared to share, so the join is
rejected rather than silently matching nothing. Use a theta join
(`L ⨝ L.id = R.id R`) there — it names its columns explicitly and resolves them
per row, so it works over documents.

Natural join depends entirely on column names lining up — if the keys are named
differently (user_id vs uid) it will not match them, and if unrelated columns
share a name it will match on those too. Use a theta join (⨝) for explicit
control.

# Alternatives:
Theta join (⨝) with an explicit condition when names differ or you need a
non-equality match. Composition (∘) joins on shared columns and then drops them
all.

# See Also:
[theta-join](theta-join.md), [left-outer-join](left-outer-join.md), [semi-join](semi-join.md), [composition](../set-operations/composition.md), [rename](../operators/rename.md)

# Notes:
Rename (ρ) is the usual fix when key names don't match or when self-joining.
