# Name: USING Join (JOIN … USING)

# Syntax:
Relation1 ⋈ USING (<column>, ...) Relation2      -- inner
Relation1 ⟕ USING (<column>, ...) Relation2      -- left
Relation1 ⟖ USING (<column>, ...) Relation2      -- right
Relation1 ⟗ USING (<column>, ...) Relation2      -- full
Relation1 JOIN Relation2 USING (<column>, ...)   -- SQL's order; also LJOIN, RJOIN, FJOIN

Orders JOIN Customers USING (customer_id)
Customers ⟗ USING (customer_id) Orders

# Description:
A USING join joins two relations on the columns you name, which both of them have.
Each named column appears once in the result, first, and every other column of both
sides follows. It is the natural join with a say in which columns it matches on:
where two tables both have a `city`, a natural join would insist the cities agree,
and `USING (customer_id)` matches on the customer alone.

It comes in the four kinds SQL has. The inner join keeps the rows that match, the
left join every row of the left side, the right join every row of the right side,
and the full join every row of both, with NULLs where the other side had no match.

# Technical Description:
Rows match where every named column is equal on both sides, as `=` has it, so a NULL
matches nothing. The output heading is the named columns, then the left input's other
columns, then the right input's other columns. A right column whose name the left
already uses keeps the `_r` suffix every join gives it, so a column both sides have
and the join does not name appears twice: `city` and `city_r`.

A named column holds the left row's value in an inner or left join and the right
row's in a right join. In a full join it holds whichever side is present, SQL's
`COALESCE` of the two, so an unmatched right row still shows its key.

`USING` is written where a join's condition goes, between the operator and the right
input, or after the right input, as SQL writes it. Both inputs must have every named
column, with the same type, and no column may be named twice. A schema-on-read input
has no columns to name, so it is refused.

An inner USING join of two tables on one connection folds into SQL as
`JOIN … ON` equating the named columns. The outer forms run in the engine, over
whatever their inputs push down.

# Examples:
Orders with their customer, on the customer id only:
  Orders JOIN Customers USING (customer_id)

Every customer, with their orders if any:
  Customers LJOIN Orders USING (customer_id)

Match on two columns:
  LineItems ⋈ USING (order_id, line_no) OrderItems

# Worked Example:
Customers and orders both record a `city`: where the customer lives and where the
order went. A natural join would match on both columns and keep only the orders
shipped to the customer's own city. `USING (customer_id)` matches on the customer
alone, and the full join keeps the customer with no order and the order with no
customer:

```relix
Customers := [
| customer_id | name  | city   |
|-------------|-------|--------|
| 1           | Alice | London |
| 2           | Bob   | Berlin |
| 3           | Cara  | Lisbon |
];

Orders := [
| order_id | customer_id | city   |
|----------|-------------|--------|
| 100      | 1           | London |
| 101      | 1           | Paris  |
| 102      | 2           | Berlin |
| 103      | 4           | Rome   |
];

query { τ customer_id, order_id (Customers FJOIN Orders USING (customer_id)) };
```

```
 customer_id  name   city    order_id  city_r
 ───────────  ─────  ──────  ────────  ──────
           1  Alice  London       100  London
           1  Alice  London       101  Paris
           2  Bob    Berlin       102  Berlin
           3  Cara   Lisbon  NULL      NULL
           4  NULL   NULL         103  Rome
(5 rows)
```

`customer_id` comes once and first, and the order for customer 4, who is not in
`Customers`, still shows its 4. Both `city` columns are kept, the order's as
`city_r`, and order 101 went to Paris without being dropped for it.

A named column must be one both inputs have:

```relix-invalid
query { Customers JOIN Orders USING () };
```

# Limitations:
A USING column is best read unqualified above the join. The output holds one column
for both sides, which answers to the qualifier of the side it was taken from, the
left one except in a right join: after `Customers FJOIN Orders USING (customer_id)`,
`Customers.customer_id` names it and `Orders.customer_id` is an error.

# Alternatives:
The natural join (⋈) matches on every shared column. A theta or outer join with an
explicit condition (`A ⟕ A.id = B.a_id B`) joins on columns whose names differ.

# See Also:
[natural-join](natural-join.md), [left-outer-join](left-outer-join.md), [right-outer-join](right-outer-join.md), [full-outer-join](full-outer-join.md), [theta-join](theta-join.md)
