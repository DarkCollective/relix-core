# Name: Goal-Seek (SOLVE)

# Syntax:
SOLVE <left-expression> = <right-expression> (Relation)
SOLVE { <left> = <right>, <left> = <right>, ... } (Relation)

SOLVE total = principal * rate (Loans)
SOLVE { cups = coffee + tea, revenue = 3.5 * coffee + 2.5 * tea } (Sales)

# Description:
SOLVE fills in missing (NULL) values in an arithmetic equation, per row, by
rearranging the formula. Give it an equation like `total = principal * rate`
and for each row it figures out whichever single value is blank — if `total` is
missing it multiplies, if `rate` is missing it divides. It is a spreadsheet-style
goal-seek applied across every row at once.

Give it several equations in braces and it solves them together: two equations
can recover two missing values in a row, three can recover three. An equation may
also be written with your own functions — a `def` whose body is arithmetic is
solved through as if its formula had been written out.

# Technical Description:
SOLVE fills the NULL participating columns of each row; which columns those are is
decided per row. With one equation, a row is solved when exactly one participating
column is NULL: the equation is inverted by a deterministic tree-walk, which is
why each column may appear in it at most once. With several equations (a system),
a row is solved when it has as many NULL participating columns as there are
equations, every equation is linear in them — no product of two terms that both
hold an unknown, no unknown in a divisor — and the system is non-singular; it is
solved by Gaussian elimination with partial pivoting. A call to a `def` whose body
is arithmetic is expanded into that body before the equation is checked or solved.
Results round to ten fractional digits, as ÷ does. A row that cannot be solved
passes through unchanged. Output schema = input schema. Streaming; does not push
down.

# Examples:
Fill whichever of total/principal/rate is blank, per row:
  SOLVE total = principal * rate (Loans)

Complete a units × price = revenue table where any one column may be missing:
  SOLVE revenue = units * price (Sales)

Recover a pair of values from their sum and difference:
  SOLVE { total = a + b, diff = a - b } (Pairs)

Solve through your own formula:
```relix
def expanded(L0: NUMBER, alpha: NUMBER, dT: NUMBER) : NUMBER := { L0 * (1 + alpha * dT) };
query { SOLVE length = expanded(L0, alpha, dT) (Rods) };
```

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
participating columns is left exactly as it came in — one equation fills one hole.

Two equations fill two. A café records each day's cups sold and takings, and on
some days the split between coffee (3.50 a cup) and tea (2.50) as well:

```relix
Sales := [
| day | cups | revenue | coffee | tea |
|-----|------|---------|--------|-----|
| Mon | 40   | 120     |        |     |
| Tue |      |         | 30     | 12  |
| Wed | 50   |         |        | 10  |
| Thu |      | 100     |        |     |
];

query { SOLVE { cups = coffee + tea, revenue = 3.5 * coffee + 2.5 * tea } (Sales) };
```

```
 day  cups  revenue  coffee  tea
 ───  ────  ───────  ──────  ────
 Mon    40      120      20    20
 Tue    42      135      30    12
 Wed    50      165      40    10
 Thu  NULL      100  NULL    NULL
(4 rows)
```

Each day has a different pair of blanks, and the same two equations solve for
whichever they are: Monday's split, Tuesday's totals, Wednesday's coffee and
takings. Thursday has three blanks and two equations, so it is left as it came in.

A formula used in more than one place is worth a `def`, and SOLVE solves through
one. A rod of length `L0` heated by `dT` degrees grows to `L0 × (1 + alpha × dT)`;
given a measured length the same equation recovers the expansion coefficient:

```relix
def expanded(L0: NUMBER, alpha: NUMBER, dT: NUMBER) : NUMBER := { L0 * (1 + alpha * dT) };

Rods := [
| rod | length | L0  | alpha    | dT |
|-----|--------|-----|----------|----|
| A   | 100.12 | 100 |          | 50 |
| B   |        | 200 | 0.000012 | 25 |
];

query { SOLVE length = expanded(L0, alpha, dT) (Rods) };
```

```
 rod  length  L0   alpha     dT
 ───  ──────  ───  ────────  ──
 A    100.12  100  0.000024  50
 B    200.06  200  0.000012  25
(2 rows)
```

# Limitations:
Only basic arithmetic (+ − × ÷, unary minus) can be solved, written directly or
through a `def` whose body is such arithmetic; a built-in function cannot be
solved through. With one equation each participating column may appear at most
once. A system is solved only where it is linear in the row's blanks and
non-singular, and only where the row has exactly as many blanks as there are
equations; any other row is left as-is. This is the goal-seek half of the
declarative solver; the optimisation half is OPTIMIZE.

# Alternatives:
OPTIMIZE for choosing values subject to constraints (rather than solving
equations). Projection with arithmetic when nothing is missing and you just want
to compute a column.

# See Also:
[optimize](optimize.md), [project](../operators/project.md), [def](../language/def.md)

# Notes:
Direction is decided per row, so the same SOLVE statement fills `total` in one
row and `rate` in another depending on which is NULL.
