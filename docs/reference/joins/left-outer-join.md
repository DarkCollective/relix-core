# Name: Left Outer Join (⟕ / |>< / LJOIN)

# Syntax:
Relation1 ⟕ <condition> Relation2
Relation1 |>< <condition> Relation2
Relation1 LJOIN <condition> Relation2      -- SQL-prior synonym

Customers ⟕ Customers.customer_id = Orders.customer_id Orders
Customers LJOIN Customers.customer_id = Orders.customer_id Orders   -- identical AST

# Description:
A left outer join keeps every row from the left relation, attaching matching
rows from the right where they exist and filling the right-hand columns with
NULL where they don't. It answers "list all customers, with their orders if they
have any" — including customers who have placed no orders at all.

The ASCII form `|><` is a mnemonic: the `|` is on the left, so the left relation
contributes all its rows.

# Technical Description:
R ⟕_θ S returns R ⨝_θ S plus, for each left row with no match, one row padded
with NULLs in S's columns. Output keeps both schemas. It is a streaming join (the
left side drives). Because unmatched left rows are preserved, a selection on the
right-side columns generally cannot be pushed below the join.

# Examples:
All customers, with order totals where they exist:
  Customers ⟕ Customers.customer_id = Orders.customer_id Orders

Find customers with no orders (outer join then keep the NULLs):
```relix
σ Orders.order_id = NULL
  (Customers ⟕ Customers.customer_id = Orders.customer_id Orders)
```

Attach optional profile data without dropping profile-less users:
  Users ⟕ Users.id = Profiles.user_id Profiles

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

query { Customers ⟕ Customers.customer_id = Orders.customer_id Orders };
```

```
 customer_id  name   city    order_id  customer_id_r  amount
 ───────────  ─────  ──────  ────────  ─────────────  ──────
           1  Alice  London       100              1     120
           1  Alice  London       101              1      80
           2  Bob    Berlin       102              2      45
           3  Cara   Lisbon  NULL      NULL           NULL
(4 rows)
```

**Cara is kept**, padded with NULLs — that is the whole point of the left outer
join: every left row survives, matched or not. Order 103 still disappears, since
nothing on the right is preserved. Filtering this result with `σ order_id = NULL`
gives "customers who have never ordered" — the idiom an ANTI join expresses more
directly.

# Limitations:
Joins work over schema-on-read sources (JSON, HTTP, MongoDB), which name their
columns per row rather than up front: a qualified reference resolves against the
document, a field neither side carries reads as NULL, and a right-hand field whose
name the left already uses is suffixed `_r`, as it is under a declared heading.
The two forms that emit an **unmatched right** row — `⟖` and `⟗` — are the
exception: that row has no left row to collide with, so the same field would be
named one way when it matched and another when it did not.

NULL padding means downstream conditions on right columns must account for NULLs.
Pushing filters across an outer join is restricted to preserve its semantics.

# Alternatives:
Right outer join (⟖) keeps the right side instead; full outer (⟗) keeps both.
ANTI join (▷) directly gives "left rows with no match" without the NULL test.

# See Also:
[right-outer-join](right-outer-join.md), [full-outer-join](full-outer-join.md), [theta-join](theta-join.md), [anti-join](anti-join.md), [is-null](../predicates/is-null.md)

# Notes:
The common "rows missing on the right" idiom is `left ⟕ cond right` followed by
`σ right.key = NULL`; an ANTI join is the more direct expression.
