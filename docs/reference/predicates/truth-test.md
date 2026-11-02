# Name: Truth Test (IS [NOT] TRUE / FALSE / UNKNOWN)

# Syntax:
b IS TRUE          b IS NOT TRUE
b IS FALSE         b IS NOT FALSE
b IS UNKNOWN       b IS NOT UNKNOWN

σ paid IS NOT TRUE (Invoices)
σ amount > 100 IS NOT TRUE (Orders)

# Description:
A truth test asks which of its three values a boolean has: TRUE, FALSE, or UNKNOWN
(NULL). It is SQL's way of choosing what a missing value counts as. `paid = TRUE`
drops a row whose `paid` is NULL, and so does `paid = FALSE`; `paid IS NOT TRUE`
keeps it, with every row that is false.

The tested value is any boolean: a column, a function that returns one, or a
condition, whose truth value is UNKNOWN when a NULL decides it. A comparison
written before `IS` is tested whole, so `amount > 100 IS NOT TRUE` keeps the rows
where the comparison is false or UNKNOWN, as in PostgreSQL.

# Technical Description:
The answer is always true or false, never UNKNOWN. Each form parses to a comparison
and a null test written so that no part of it decides the answer while UNKNOWN:

| Form | Parses to |
|---|---|
| `b IS TRUE` | `b = TRUE ∧ b ≠ ⊥` |
| `b IS NOT TRUE` | `b = FALSE ∨ b = ⊥` |
| `b IS FALSE` | `b = FALSE ∧ b ≠ ⊥` |
| `b IS NOT FALSE` | `b = TRUE ∨ b = ⊥` |
| `b IS UNKNOWN` | `b = ⊥` |
| `b IS NOT UNKNOWN` | `b ≠ ⊥` |

There is no separate node, so pushdown sends that expansion, which every backend
reads alike. `TRUE`, `FALSE` and `UNKNOWN` after `IS` are the truth values; a column
named `unknown` is still a column anywhere else.

# Examples:
Invoices not known to be paid, the unrecorded ones included:
  σ paid IS NOT TRUE (Invoices)

Orders whose amount is missing, tested through the comparison:
  σ amount > 100 IS UNKNOWN (Orders)

A flag as a value, false and unknown alike counted as not set:
  π id, (flag IS TRUE) → set (Settings)

# Worked Example:
A payments ledger where some invoices have no recorded status. `paid = FALSE`
finds only the invoices recorded as unpaid:

```relix
Invoices := [
| id | paid  |
|----|-------|
| 1  | true  |
| 2  | false |
| 3  | NULL  |
];

query { σ paid = FALSE (Invoices) };
```

```
 id  paid
 ──  ─────
  2  false
(1 row)
```

`paid IS NOT TRUE` keeps invoice 3 too, the one nobody has recorded as paid:

```relix
query { σ paid IS NOT TRUE (Invoices) };
```

```
 id  paid
 ──  ─────
  2  false
  3  NULL
(2 rows)
```

# Limitations:
The tested value must be a boolean; `amount IS TRUE` is refused at analysis as a
comparison of a number with a boolean. The value is evaluated twice.

# Alternatives:
`b = TRUE` when a NULL should count as neither. [IS NULL](is-null.md) for a value of
any type. `Coalesce(b, FALSE) = TRUE` reads the same as `b IS TRUE`.

# See Also:
[is-null](is-null.md), [comparison](comparison.md), [is-distinct-from](is-distinct-from.md), [not](not.md)
