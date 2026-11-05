# Name: Universal Semi Join (USEMI)

# Syntax:
Relation1 USEMI <condition> Relation2

Products USEMI price <= cap Budgets

# Description:
A universal semi join keeps a row from the left relation only when EVERY row of
the right relation matches it. It answers "which products are cheap enough for
every department's budget?" or "which candidates meet every requirement?" — a
question SQL writes as a double negative, `NOT EXISTS (… WHERE NOT …)`.

It is the "for all" partner of the [semi join](semi-join.md), which keeps a left
row when AT LEAST ONE right row matches. Like the semi join it brings none of the
right relation's columns along: the result has the left relation's columns, and
the right relation is a test rather than a source of data.

There is no glyph for it; it is written `USEMI`.

# Technical Description:
R USEMI_θ S returns the rows ℓ of R for which θ(ℓ, r) holds for every row r of S.
The output schema is R's schema, and each left row appears at most once.

NULL semantics are strict: a condition that evaluates to UNKNOWN for any right
row counts as not satisfied, so one UNKNOWN disqualifies the left row. This is
the reading `NOT EXISTS (SELECT … FROM S WHERE NOT θ)` gives, except that SQL's
version would keep the row when θ is UNKNOWN.

When S is empty the condition is vacuously true, so every left row is kept.

It is not pushed down to a database: the engine computes it.

# Examples:
Employees paid no more than every department's budget:
  Employees USEMI salary <= budget Departments

Products priced at or under every plan's cost:
  Products USEMI Products.price <= Plans.cost Plans

Products whose id differs from every reviewed product's id, so none has a review:
  Products USEMI Products.product_id <> Reviews.product_id Reviews

# Worked Example:
A catalogue is checked against the budgets of two departments. A product is
affordable only if its price is within the cap of **both**.

```relix
Products := [
| product  | price |
|----------|-------|
| Widget   | 10    |
| Sprocket | 25    |
| Gadget   | 40    |
];

Budgets := [
| department | cap |
|------------|-----|
| ops        | 30  |
| lab        | 50  |
];

query { Products USEMI price <= cap Budgets };
```

```
 product   price
 ────────  ─────
 Widget       10
 Sprocket     25
(2 rows)
```

Gadget costs 40, which the lab can afford but ops cannot, so it fails "every".
A semi join over the same relations would keep it, because one budget is enough
for a semi join. The columns are Products' own; the departments do not appear.

# Limitations:
The condition may compare any left column with any right column, but only the
left relation's columns are returned. If you need right-side data as well, use a
regular join.

An empty right relation keeps every left row, which is the right answer to "does
it satisfy every one of none?" and is sometimes a surprise.

# Alternatives:
[∀ (FORALL)](../operators/forall.md) tests a condition over the rows of one
relation, grouped by keys; USEMI tests a condition between the rows of two.

The same result is the left relation minus its [anti join](anti-join.md) with the
right relation on the negated condition, provided no value in the condition can
be NULL.

# SQL names:
`WHERE NOT EXISTS (SELECT 1 FROM S WHERE NOT (R.a <= S.b))` is
`R USEMI R.a <= S.b S`, and so is `WHERE R.a <= ALL (SELECT b FROM S)`.

# See Also:
[semi-join](semi-join.md), [anti-join](anti-join.md), [forall](../operators/forall.md)

# Notes:
A relation with a duplicate right row is tested once per row, so duplicates change
nothing: if every row matches, every copy does.
