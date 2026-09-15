# Name: Anti Join (▷ / ANTI)

# Syntax:
Relation1 ▷ <condition> Relation2
Relation1 ANTI <condition> Relation2

Customers ▷ Customers.customer_id = Orders.customer_id Orders

# Description:
An anti join keeps rows from the left relation that have NO match on the right.
It is the direct way to ask "which customers have never placed an order?" or
"which products have never sold?" — the things that are missing. The result has
the left relation's columns and only its unmatched rows.

# Technical Description:
R ▷_θ S returns the left rows for which no matching right row exists, projected
back to R's schema (the relational `WHERE NOT EXISTS (…)`). Left-side
distinctness and ordering are preserved. It is the complement of the semi join.

# Examples:
Customers who have never ordered:
  Customers ▷ Customers.customer_id = Orders.customer_id Orders

Products with no reviews:
  Products ▷ Products.id = Reviews.product_id Reviews

Active users who have not logged in this month:
  Users ▷ Users.id = MonthlyLogins.user_id MonthlyLogins

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

query { Customers ▷ Customers.customer_id = Orders.customer_id Orders };
```

```
 customer_id  name  city
 ───────────  ────  ──────
           3  Cara  Lisbon
(1 row)
```

Exactly the complement of the semi join: the one customer who has **never**
ordered. Same unchanged schema, no NULL padding, and no need to filter afterwards
— compare the left-outer-join route, which produces four rows and then needs
`σ order_id = NULL` to get to this same answer.

# Limitations:
Like the semi join, it returns only left-side columns. NULLs in the join keys
follow not-exists semantics — a left row with a NULL key matches nothing and is
kept.

# Alternatives:
A left outer join then `σ right.key = NULL` produces the same set but is more
verbose; the anti join states the intent directly. Set difference (−) works when
the two relations are union-compatible.

# See Also:
[semi-join](semi-join.md), [left-outer-join](left-outer-join.md), [difference](../set-operations/difference.md), [is-null](../predicates/is-null.md)

# Notes:
Prefer ▷ over the outer-join-plus-NULL-test idiom for "the ones that are
missing" — it is clearer and the planner handles it as a first-class join.
