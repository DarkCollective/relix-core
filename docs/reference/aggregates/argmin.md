# Name: ARGMIN (aggregate)

# Syntax:
γ <grouping-cols>, ARGMIN(<rank-expr>, <yield-expr>) → <alias> (Relation)

γ route_id, ARGMIN(price, carrier) → cheapest_carrier (Fares)

# Description:
ARGMIN answers "for the row with the smallest X, give me Y". For each group it
finds the row where the rank expression is smallest and returns the yield
expression's value from that row — for example the carrier offering the cheapest
fare on each route, or the date of each account's first transaction.

# Technical Description:
ARGMIN(rank, yield) is a two-argument aggregate used only inside γ: it returns the
`yield` expression evaluated on the row whose `rank` expression is the group
minimum. Ties resolve to the first such row; rows with a NULL rank are skipped; an
empty group yields NULL. It infers as the yield expression's type and never pushes
down to SQL.

# Examples:
The cheapest carrier on each route:
  γ route_id, ARGMIN(price, carrier) → cheapest_carrier (Fares)

The product with the lowest stock per warehouse:
  γ warehouse, ARGMIN(stock, product_id) → scarcest (Inventory)

Date of each account's first transaction:
  γ account_id, ARGMIN(txn_date, txn_date) → first_txn (Transactions)

# Worked Example:
Four rows of sales, two of them with no `bonus` recorded — the NULLs are there deliberately, because they are what makes the rules below visible.

```relix
Sales := [
| region | rep  | amount | bonus |
|--------|------|--------|-------|
| east   | Ada  | 120    | 10    |
| east   | Bo   | 80     | NULL  |
| west   | Cy   | 200    | 25    |
| west   | Dee  | 50     | NULL  |
];

query { γ region, ARGMIN(amount, rep) → weakest_rep (Sales) };
```

```
 region  weakest_rep
 ──────  ───────────
 east    Bo
 west    Dee
(2 rows)
```

The mirror of [ARGMAX](argmax.md): rank by `amount`, return that row's `rep`,
taking the group **minimum**. `MIN(amount)` gives 80 and 50; this gives the reps who
recorded them.

Neither argument has to be a bare column. Ranking by an expression asks a
different question — "whose deal was closest to the 100 target?":

```relix
query { γ region, ARGMIN(Abs(amount - 100), rep) → closest (Sales) };
```

```
 region  closest
 ──────  ───────
 east    Ada
 west    Dee
(2 rows)
```

The result takes the yield expression's type.

# Limitations:
Returns a single value from one row, not the whole row — for the entire row use
TOP 1 rank ASC … PER. Ties pick the first encountered row.

# Alternatives:
TOP 1 rank ASC PER keys keeps the actual lowest row with all its columns. MIN
returns the minimum value itself rather than another column from that row.

# See Also:
[argmax](argmax.md), [min](min.md), [top](../advanced/top.md), [group](../operators/group.md)

# Notes:
ARGMIN is the minimum-side mirror of ARGMAX.
