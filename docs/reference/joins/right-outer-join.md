# Name: Right Outer Join (⟖ / ><| / RJOIN)

# Syntax:
Relation1 ⟖ <condition> Relation2
Relation1 ><| <condition> Relation2
Relation1 RJOIN <condition> Relation2      -- SQL-prior synonym

Orders ⟖ Orders.customer_id = Customers.customer_id Customers
Orders RJOIN Orders.customer_id = Customers.customer_id Customers   -- identical AST

# Description:
A right outer join keeps every row from the right relation, attaching matching
rows from the left where they exist and filling the left-hand columns with NULL
where they don't. It is the mirror image of a left outer join — use whichever
reads more naturally for the relation you want to preserve in full.

The ASCII form `><|` is a mnemonic: the `|` is on the right, so the right
relation contributes all its rows.

# Technical Description:
R ⟖_θ S returns R ⨝_θ S plus, for each right row with no match, a row padded with
NULLs in R's columns. Equivalent to S ⟕_θ R with column order from R then S.
Output keeps both schemas.

# Examples:
Keep all customers (on the right), with orders where present:
  Orders ⟖ Orders.customer_id = Customers.customer_id Customers

List every product, with sales figures where they exist:
  Sales ⟖ Sales.product_id = Products.id Products

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

query { Customers ⟖ Customers.customer_id = Orders.customer_id Orders };
```

```
 customer_id  name   city    order_id  customer_id_r  amount
 ───────────  ─────  ──────  ────────  ─────────────  ──────
           1  Alice  London       100              1     120
           1  Alice  London       101              1      80
           2  Bob    Berlin       102              2      45
 NULL         NULL   NULL         103              4      60
(4 rows)
```

The mirror image: **order 103 is kept** with NULLs on the customer side, and
Cara is dropped. This is how orphaned rows are found — an order whose customer no
longer exists shows up here as a row with a NULL `name`.

# Limitations:
Not available over a schema-on-read source (JSON, HTTP, MongoDB). This join
emits rows from the right that matched nothing, and such a row has no left row
to collide with — so a right field whose name the left also uses would be
renamed on a matched row and not on an unmatched one, leaving the column
meaning two different things. The other joins have no such ambiguity.

As with any outer join, NULL padding on the unmatched side must be handled by
downstream conditions, and filter pushdown is restricted to preserve semantics.

# Alternatives:
Left outer join (⟕) — usually you can swap operands and use a left join, which
many find easier to reason about. Full outer (⟗) keeps both sides.

# See Also:
[left-outer-join](left-outer-join.md), [full-outer-join](full-outer-join.md), [theta-join](theta-join.md), [is-null](../predicates/is-null.md)

# Notes:
A right outer join is exactly a left outer join with the operands swapped; pick
the form that puts the "keep all of this one" relation where it reads clearly.
