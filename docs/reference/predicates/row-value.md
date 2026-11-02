# Name: Row Value Comparison ((a, b) = (c, d))

# Syntax:
(a, b) = (1, 2)            (a, b) ≠ (1, 2)
(a, b) < (c, d)            (a, b) ≤ (c, d)    and > and ≥ alike
(a, b) IN {(1, 2), (3, 4)}
(a, b) NOT IN {(1, 2), (3, 4)}

σ (year, month) >= (2026, 3) (MonthlySales)
σ (region, code) IN {("eu", 1), ("us", 7)} (Stores)

# Description:
A row value is a parenthesised list of values compared as one. It says "this pair
of columns equals that pair" without repeating each column, and it gives the
ordering of a composite key: `(year, month) >= (2026, 3)` holds from March 2026
onward, which `year >= 2026 ∧ month >= 3` does not say.

Membership takes a list of rows, in braces as a set does, or in SQL's parentheses:
`(a, b) IN ((1, 2), (3, 4))`.

# Technical Description:
Each form parses to comparisons of the columns, pair by pair, and both sides must
be the same width:

| Form | Parses to |
|---|---|
| `(a, b) = (c, d)` | `a = c ∧ b = d` |
| `(a, b) ≠ (c, d)` | `a ≠ c ∨ b ≠ d` |
| `(a, b) < (c, d)` | `a < c ∨ (a = c ∧ b < d)` |
| `(a, b) ≤ (c, d)` | `a < c ∨ (a = c ∧ b ≤ d)` |
| `(a, b) IN {(1, 2), (3, 4)}` | `(a = 1 ∧ b = 2) ∨ (a = 3 ∧ b = 4)` |

`>` and `≥` follow `<` and `≤`. The expansion gives SQL's answer with NULLs: in
`(1, ⊥) = (1, 2)` the first pair is equal and the second UNKNOWN, so the whole is
UNKNOWN rather than false, and an ordering is decided by the first pair that is not
equal, or UNKNOWN if that pair holds a NULL. `IN` over an empty list is false.
There is no separate node, so pushdown sends the expansion.

# Examples:
A composite key matched in one comparison:
  σ (order_id, line) = (1001, 2) (OrderLines)

Every month from March 2026:
  σ (year, month) >= (2026, 3) (MonthlySales)

Two specific stores:
  σ (region, code) IN {("eu", 1), ("us", 7)} (Stores)

# Worked Example:
Monthly figures keyed by year and month. Testing each column on its own loses
January 2027, whose month is below 3:

```relix
Sales := [
| year | month | total |
|------|-------|-------|
| 2025 | 12    | 40    |
| 2026 | 2     | 55    |
| 2026 | 3     | 61    |
| 2026 | 11    | 70    |
| 2027 | 1     | 82    |
];

query { σ year >= 2026 ∧ month >= 3 (Sales) };
```

```
 year  month  total
 ────  ─────  ─────
 2026      3     61
 2026     11     70
(2 rows)
```

The row value orders by year first, then by month within the year:

```relix
query { σ (year, month) >= (2026, 3) (Sales) };
```

```
 year  month  total
 ────  ─────  ─────
 2026      3     61
 2026     11     70
 2027      1     82
(3 rows)
```

# Limitations:
Both sides must hold the same number of values, and a row value is only a
predicate's subject: it is not a value that can be projected or passed to a
function. Each column is written more than once in an ordering's expansion.

```relix-invalid
query { σ (year, month) = (2026) (Sales) };
```

# Alternatives:
A join on the key columns to match a list of rows held in a relation:
`Sales ⋉ Sales.year = Wanted.year ∧ Sales.month = Wanted.month Wanted`.

# See Also:
[comparison](comparison.md), [in](in.md), [and](and.md), [or](or.md)
