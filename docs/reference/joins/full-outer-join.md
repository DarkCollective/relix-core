# Name: Full Outer Join (⟗ / |><| / FJOIN)

# Syntax:
Relation1 ⟗ <condition> Relation2
Relation1 |><| <condition> Relation2
Relation1 FJOIN <condition> Relation2      -- SQL-prior synonym

A ⟗ A.id = B.id B
A FJOIN A.id = B.id B                       -- identical AST

# Description:
A full outer join keeps every row from BOTH relations: matched rows are paired,
and any row without a partner on the other side is kept with the other side's
columns filled with NULL. It is the join to use when you want a complete picture
that loses nothing — for example, reconciling two lists to see what is in each,
in both, or in only one.

The ASCII `|><|` has a `|` on each side: both relations contribute all rows.

# Technical Description:
R ⟗_θ S = (R ⟕_θ S) ∪ (R ⟖_θ S): the inner matches plus unmatched left rows
(NULL-padded right) plus unmatched right rows (NULL-padded left). It is a
blocking ([bag]) operator and keeps both schemas.

# Examples:
Reconcile two account lists — see matches and orphans on both sides:
  LedgerA ⟗ LedgerA.ref = LedgerB.ref LedgerB

Records only in the left (right side is NULL):
  σ B.id = NULL (A ⟗ A.id = B.id B)

Records only in the right (left side is NULL):
  σ A.id = NULL (A ⟗ A.id = B.id B)

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

query { Customers ⟗ Customers.customer_id = Orders.customer_id Orders };
```

```
 customer_id  name   city    order_id  customer_id_r  amount
 ───────────  ─────  ──────  ────────  ─────────────  ──────
           1  Alice  London       100              1     120
           1  Alice  London       101              1      80
           2  Bob    Berlin       102              2      45
 NULL         NULL   NULL         103              4      60
           3  Cara   Lisbon  NULL      NULL           NULL
(5 rows)
```

**Both** unmatched rows survive: Cara with no order, and order 103 with no
customer. Five rows — the three matches plus one from each side. This is the join
to reach for when reconciling two sources and you need to see what each one has
that the other does not.

# Limitations:
Not available over a schema-on-read source (JSON, HTTP, MongoDB). This join
emits rows from the right that matched nothing, and such a row has no left row
to collide with — so a right field whose name the left also uses would be
renamed on a matched row and not on an unmatched one, leaving the column
meaning two different things. The other joins have no such ambiguity.

Full outer join materialises and is the heaviest of the joins. Both unmatched
sides produce NULLs, so downstream conditions must be NULL-aware.

# Alternatives:
Symmetric difference (∆) gives "rows in exactly one side" for union-compatible
relations. Left/right outer when you only need one side preserved.

# See Also:
[left-outer-join](left-outer-join.md), [right-outer-join](right-outer-join.md), [symmetric-difference](../set-operations/symmetric-difference.md), [is-null](../predicates/is-null.md)

# Notes:
The "in exactly one side" question is often cleaner via ∆ (SYMDIFF) when the two
relations share the same columns.
