# Name: EXISTS (does a relation have a row)

# Syntax:
EXISTS ( <relation> )
NOT EXISTS ( <relation> )

σ EXISTS (σ customer_id = Customers.customer_id (Orders)) (Customers)
σ NOT EXISTS (σ customer_id = Customers.customer_id (Orders)) (Customers)
π name, EXISTS (σ customer_id = Customers.customer_id (Orders)) → has_orders (Customers)

# Description:
`EXISTS` asks whether a relation has any row. Written about the row being tested,
it keeps the customers who have placed an order, and `NOT EXISTS` those who have
not. In a PROJECT it is a BOOLEAN column: whether each row has a match.

# Technical Description:
The relation is a [subquery](../language/subquery.md): it may have any columns, and
names the tested row's columns qualified with their relation, as
`Customers.customer_id`. `EXISTS` is true when the relation has a row and false when
it has none; it is never UNKNOWN, so `NOT EXISTS` keeps exactly the rows `EXISTS`
drops, NULLs and all. That is the difference from `NOT IN`, which a NULL among the
values makes UNKNOWN for every row.

`EXISTS` is not reserved: it is the test when a relation in parentheses follows it,
and a column or a function's name otherwise. It is written in a SELECT's condition or
a PROJECT's columns, and each relation is read once per distinct set of outer values
it names, at most one row of it.

# Examples:
Customers with at least one order:
  σ EXISTS (σ customer_id = Customers.customer_id (Orders)) (Customers)

Customers who have never ordered:
  σ NOT EXISTS (σ customer_id = Customers.customer_id (Orders)) (Customers)

Whether each customer has spent over 100 in one order:
  π name, EXISTS (σ customer_id = Customers.customer_id ∧ amount > 100 (Orders)) → big_spender (Customers)

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

query { σ EXISTS (σ customer_id = Customers.customer_id (Orders)) (Customers) };
```

```
 customer_id  name   city
 ───────────  ─────  ──────
           1  Alice  London
           2  Bob    Berlin
(2 rows)
```

```relix
query { σ NOT EXISTS (σ customer_id = Customers.customer_id (Orders)) (Customers) };
```

```
 customer_id  name  city
 ───────────  ────  ──────
           3  Cara  Lisbon
(1 row)
```

As a value, whether each customer has an order over 100:

```relix
query { π name, EXISTS (σ customer_id = Customers.customer_id ∧ amount > 100 (Orders)) → big_spender (Customers) };
```

```
 name   big_spender
 ─────  ───────────
 Alice  true
 Bob    false
 Cara   false
(3 rows)
```

# Limitations:
`EXISTS` is written in a SELECT's condition or a PROJECT's columns only. SQL's
`EXISTS (SELECT … FROM …)` is written with Relix's operators:

```relix-invalid
query { σ EXISTS (SELECT 1 FROM Orders) (Customers) };
```

# Alternatives:
A [SEMI join](../joins/semi-join.md) (`⋉`) keeps the rows that have a match, and an
[ANTI join](../joins/anti-join.md) (`▷`) the rows that have none: the same answers
as `EXISTS` and `NOT EXISTS` over an equality, written as a join.

# SQL names:
SQL's `WHERE EXISTS (SELECT … FROM S WHERE S.k = R.k)` is
`σ EXISTS (σ k = R.k (S)) (R)`, and `NOT EXISTS` the same with `NOT`. Both are also
the [semi join](../joins/semi-join.md) and the [anti join](../joins/anti-join.md),
`R SEMI R.k = S.k S` and `R ANTI R.k = S.k S`.

# See Also:
[subquery](../language/subquery.md), [in](in.md), [quantified](quantified.md),
[semi-join](../joins/semi-join.md), [anti-join](../joins/anti-join.md)
