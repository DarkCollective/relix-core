# Name: Subquery (a relation used as a value)

# Syntax:
( <relation> )

π name, (γ COUNT(*) → n (σ customer_id = Customers.customer_id (Orders))) → orders (Customers)
σ amount > (γ AVG(amount) → a (Orders)) (Orders)
σ amount = (γ MAX(amount) → m (σ grp = T.grp (ρ U (T)))) (T)

# Description:
A subquery is a relation written where a value goes, in parentheses. Its value is the
one column of its one row. It answers questions such as "how many orders has each
customer placed" or "which orders are above their customer's average" without a join
and a regrouping.

A subquery can read the row it is computed for. Name that row's columns qualified with
their relation, as `Customers.customer_id`, and the subquery is worked out again for
each row: it is *correlated*. A subquery that reads nothing from outside is worked out
once.

The same relations are what [EXISTS](../predicates/exists.md) tests for a row, what
[IN](../predicates/in.md) and [ANY / ALL](../predicates/quantified.md) compare with,
and what [LATERAL](../joins/lateral-join.md) joins row by row.

# Technical Description:
**Where it goes.** A subquery is written in a PROJECT's columns or a SELECT's
condition, anywhere in an expression there: `(…) + 1`, `Coalesce((…), 0)`, a
comparison's either side. Analysis refuses one in any other operator's expressions,
such as a sort key or a join condition.

**Spelling.** The parenthesis holds a relation that opens on a relational operator,
`(π v (S))`, `(γ COUNT(*) → n (S))`, `(λ 1 (τ v (S)))`, or on a name and a binary
operator, `(A ⋈ B)`. A bare name in parentheses, `(Orders)`, is a column, as it always
was, so write `(π v (Orders))` for the relation's values.

**The outer row.** Inside the subquery, a qualified name `R.c` whose relation `R` the
subquery does not read is a column of the outer row. An unqualified name is always the
subquery's own: when only the outer row has it, analysis says so and names the
qualified spelling. This is stricter than SQL, which looks outward for an unqualified
name, and it is the case where SQL quietly compares a column with itself. To correlate
a relation with itself, rename the inner copy with [ρ](../operators/rename.md):
`σ grp = T.grp (ρ U (T))` reads `T.grp` from outside, since `ρ` hides `T` inside. A
qualified name that is neither inside nor outside is an error at the name.

**Its value.** The relation has exactly one column, which analysis checks, and the
value's type is that column's. With no row the value is NULL. With more than one, the
query stops with an error naming where the subquery was written and the outer values
it read: aggregate with `γ`, or keep one row with `λ 1`. An ungrouped `γ` always has
one row, so `(γ COUNT(*) → n (…))` is 0 for a row with no matches, as SQL's
`COUNT(*)` is, while `(γ MAX(x) → m (…))` is NULL.

**NULL.** An outer column that is NULL matches nothing inside: `customer_id =
Customers.customer_id` is UNKNOWN for it, as in SQL.

**How it runs.** Each subquery becomes a lateral join over the operator's input: an
outer lateral that keeps a row whose subquery is empty, holding at most one row per
outer row. The subquery is planned once for each distinct set of outer values it
reads, and its rows are reused when the same values come round again.

# Examples:
Each customer with the number of orders they placed:
  π name, (γ COUNT(*) → n (σ customer_id = Customers.customer_id (Orders))) → orders (Customers)

Orders above the overall average:
  σ amount > (γ AVG(amount) → a (Orders)) (Orders)

Orders above their own customer's average, the inner copy renamed:
  σ amount > (γ AVG(amount) → a (σ customer_id = Orders.customer_id (ρ O (Orders)))) (Orders)

Each customer's latest order date, one row kept:
  π name, (π placed_at (λ 1 (τ placed_at DESC (σ customer_id = Customers.customer_id (Orders))))) → latest (Customers)

# Worked Example:
The manual's usual customers and orders. Cara has placed no orders, and order 103
belongs to a customer who is not listed.

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

query { π name, (γ COUNT(*) → n (σ customer_id = Customers.customer_id (Orders))) → orders (Customers) };
```

```
 name   orders
 ─────  ──────
 Alice       2
 Bob         1
 Cara        0
(3 rows)
```

Cara is counted 0: her subquery has no rows, and an ungrouped `γ` still has one, whose
count is 0. A largest amount has nothing to report for her:

```relix
query { π name, (γ MAX(amount) → m (σ customer_id = Customers.customer_id (Orders))) → biggest (Customers) };
```

```
 name   biggest
 ─────  ───────
 Alice      120
 Bob         45
 Cara   NULL
(3 rows)
```

In a condition, the orders above their own customer's average. The inner copy of
`Orders` is renamed `O`, so `Orders.customer_id` is the outer row's:

```relix
query { σ amount > (γ AVG(amount) → a (σ customer_id = Orders.customer_id (ρ O (Orders)))) (Orders) };
```

```
 order_id  customer_id  amount
 ────────  ───────────  ──────
      100            1     120
(1 row)
```

Asking for Alice's only order, `(π order_id (σ customer_id = Customers.customer_id
(Orders)))`, stops the query: she has two, and a value is one row's.

# Limitations:
- A subquery is written in a PROJECT's columns or a SELECT's condition only. For one
  elsewhere, compute it as a column first with PROJECT.
- Inside a subquery, a dotted name whose part before the last dot is not a relation
  the subquery reads is read from the outer row. To read a nested field inside one,
  flatten it first in a view, `Flat := { π addr.city → city (People) };`, and let the
  subquery read `Flat`.
- A subquery runs in the engine, though the relations it reads are planned as any
  other and may be pushed to a database.

# Alternatives:
A join followed by `γ` gives the same counts, as `Customers ⟕ … Orders` grouped by
customer, and a [window](../operators/rolling.md) puts a group's total beside each of
its rows. For the top rows of each group, [TOP](../advanced/top.md) is made for it.

# SQL names:
SQL's scalar subquery, `SELECT name, (SELECT COUNT(*) FROM orders o WHERE o.cid = c.id)
FROM customers c`, is `π name, (γ COUNT(*) → n (σ cid = Customers.id (Orders))) → n
(Customers)`: the inner `SELECT` list is a `π` or a `γ`, its `WHERE` a `σ`, and the
outer table is named by its relation, as Relix has no table aliases.

# See Also:
[EXISTS](../predicates/exists.md), [IN](../predicates/in.md),
[ANY / ALL](../predicates/quantified.md), [LATERAL](../joins/lateral-join.md),
[GROUP](../operators/group.md), [rename](../operators/rename.md)
