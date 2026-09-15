# Name: Semi Join (⋉ / SEMI)

# Syntax:
Relation1 ⋉ <condition> Relation2
Relation1 SEMI <condition> Relation2

Employees ⋉ Employees.dept = Departments.dept Departments

# Description:
A semi join keeps rows from the left relation that HAVE a match on the right —
but it does not bring any of the right relation's columns along. It answers
"which customers have placed at least one order?" without duplicating a customer
once per order. The result is a filtered version of the left relation, same
columns, no fan-out.

# Technical Description:
R ⋉_θ S returns the left rows for which at least one matching right row exists,
projected back to R's schema, with no duplication of left rows (existential
match). It is the relational form of SQL `WHERE EXISTS (…)`. Left-side
distinctness and ordering are preserved.

# Examples:
Customers who have at least one order:
  Customers ⋉ Customers.customer_id = Orders.customer_id Orders

Products that have ever been reviewed:
  Products ⋉ Products.id = Reviews.product_id Reviews

Employees in a department that exists in the Departments table:
  Employees ⋉ Employees.dept = Departments.dept Departments

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

query { Customers ⋉ Customers.customer_id = Orders.customer_id Orders };
```

```
 customer_id  name   city
 ───────────  ─────  ──────
           1  Alice  London
           2  Bob    Berlin
(2 rows)
```

Two rows, not three — and the schema is **unchanged**. A semi join filters the
left relation by existence: Alice appears **once** even though she has two
orders, because it asks "is there a match?", not "which matches?". That is the
difference from a natural join, and the reason a semi join is the right tool when
you only want to filter.

# Limitations:
A semi join cannot return any column from the right side — if you need right-side
data, use a regular (theta/natural) join. Each left row appears at most once.

# Alternatives:
A theta join then δ achieves a similar set but risks fan-out and needs the
dedupe; semi join is cleaner and avoids duplicating left rows. ANTI join (▷) is
the complement ("no match").

# See Also:
[anti-join](anti-join.md), [theta-join](theta-join.md), [natural-join](natural-join.md), [forall](../operators/forall.md), [distinct](../operators/distinct.md)

# Notes:
Prefer ⋉ over `δ (π left.* (left ⨝ right))` — it expresses existence directly and
avoids materialising duplicates.
