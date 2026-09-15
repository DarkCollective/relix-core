# Name: Goal-Seek (SOLVE)

# Syntax:
SOLVE <left-expression> = <right-expression> (Relation)

SOLVE total = principal * rate (Loans)

# Description:
SOLVE fills in the one missing (NULL) value in an arithmetic equation, per row,
by rearranging the formula. Give it an equation like `total = principal * rate`
and for each row it figures out whichever single value is blank — if `total` is
missing it multiplies, if `rate` is missing it divides. It is a spreadsheet-style
goal-seek applied across every row at once.

# Technical Description:
SOLVE inverts an arithmetic equation per row to fill the single NULL participating
column. Both sides are restricted to invertible arithmetic (column / number /
+ − × ÷ / unary minus) with each column appearing at most once, so the inversion
is a deterministic tree-walk. Rows with zero or two-or-more NULL participating
columns pass through unchanged. Output schema = input schema. Streaming; does not
push down.

# Examples:
Fill whichever of total/principal/rate is blank, per row:
  SOLVE total = principal * rate (Loans)

Complete a units × price = revenue table where any one column may be missing:
  SOLVE revenue = units * price (Sales)

Recover a missing dimension from area:
  SOLVE area = width * height (Rectangles)

# Worked Example:
An invoice import arrives with gaps: some rows know the quantity and unit price
but not the line total, others know the total and one of its factors. The
relationship is always `line_total = qty * unit_price`, so a single SOLVE fills
whichever value is blank on each row — the direction is decided per row.

```relix
-- a blank cell is NULL
Invoice := [
| item     | qty | unit_price | line_total |
|----------|-----|------------|------------|
| Widget   | 3   | 5.00       |            |
| Gadget   |     | 12.00      | 60.00      |
| Sprocket | 4   |            | 10.00      |
];

Completed := { SOLVE line_total = qty * unit_price (Invoice) };
```

Each row is inverted independently:

```relix
query { Completed };
```

```
 item      qty  unit_price  line_total
 ────────  ───  ──────────  ──────────
 Widget      3           5          15
 Gadget      5          12          60
 Sprocket    4         2.5          10
(3 rows)
```

Widget multiplies (`3 × 5 = 15`); Gadget and Sprocket divide (`60 ÷ 12 = 5` and
`10 ÷ 4 = 2.5`). Numbers print in their shortest exact form, so the `5.00` in the
input reads back as `5`.

A row that already has every value (nothing blank) or has two blanks among the
participating columns is left exactly as it came in — SOLVE only acts when there
is precisely one hole to fill.

# Limitations:
Each participating column may appear at most once on each side, and only basic
arithmetic (+ − × ÷, unary minus) is invertible. A row must have exactly one
blank among the participating columns to be solved; otherwise it is left as-is.
This is the goal-seek half of the declarative solver; the optimisation half is
OPTIMIZE.

# Alternatives:
OPTIMIZE for choosing values subject to constraints (rather than inverting a
single equation). Projection with arithmetic when nothing is missing and you just
want to compute a column.

# See Also:
[optimize](optimize.md), [project](../operators/project.md)

# Notes:
Direction is decided per row, so the same SOLVE statement fills `total` in one
row and `rate` in another depending on which is NULL.
