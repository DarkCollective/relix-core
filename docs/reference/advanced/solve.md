# Name: Goal-Seek (SOLVE)

# Syntax:
SOLVE <left-expression> = <right-expression> [PER <key>, ...]
      [WITHIN <tolerance>] [MAX <n> ROUNDS] [START <column> = <expression>, ...] (Relation)
SOLVE { <left> = <right>, <left> = <right>, ... } [PER <key>, ...]
      [WITHIN <tolerance>] [MAX <n> ROUNDS] [START <column> = <expression>, ...] (Relation)

SOLVE total = principal * rate (Loans)
SOLVE { cups = coffee + tea, revenue = 3.5 * coffee + 2.5 * tea } (Sales)
SOLVE y = slope * x + intercept PER series (Points)
SOLVE area = side * side (Squares)
SOLVE area = side * side START side = -1 (Squares)

# Description:
SOLVE fills in missing (NULL) values in an arithmetic equation, per row, by
rearranging the formula. Give it an equation like `total = principal * rate`
and for each row it figures out whichever single value is blank — if `total` is
missing it multiplies, if `rate` is missing it divides. It is a spreadsheet-style
goal-seek applied across every row at once.

Give it several equations in braces and it solves them together: two equations
can recover two missing values in a row, three can recover three. An equation that
cannot be rearranged — the missing value appears twice, or two missing values
multiply — is solved by searching for the answer instead. An equation may
also be written with your own functions — a `def` whose body is arithmetic is
solved through as if its formula had been written out.

A search finds one answer, and some equations have several: a square of area 4 has
a side of 2 or of −2. START says where the search begins for a missing value, and
so which answer it finds.

With PER, SOLVE fits instead of solving: rows that share a key are treated as
measurements of the same thing, and the values missing from all of them are chosen
to fit every measurement as closely as possible — a calibration, or a line through
a set of points.

# Technical Description:
SOLVE fills the NULL participating columns of each row; which columns those are is
decided per row. A row is solved when it has exactly as many NULL participating
columns as there are equations, by the cheapest strategy that applies: a single
equation whose unknown appears once is inverted by a deterministic tree-walk;
equations linear in the unknowns — no product of two terms that both hold an
unknown, no unknown in a divisor — are solved by Gaussian elimination with partial
pivoting; anything else is solved iteratively by Levenberg–Marquardt, a damped
Newton method. A call to a `def` whose body is arithmetic is expanded into that
body before the equation is checked or solved. Results round to ten fractional
digits, as ÷ does. Output schema = input schema. Streaming; does not push down.

The iteration starts every unknown at 1, so where an equation has more than one
root it finds the one reached from 1 — for `area = side * side`, the positive
one. START u = e, … starts the unknown u at e instead, an expression evaluated over
the row being solved, so the start, and the root reached, may differ from row to
row. An unknown START does not name still starts at 1, a START whose value is NULL
in a row leaves its unknown at 1 there, and a row in which u is not blank ignores
its START. Inversion and elimination find the one answer there is and do not read
START. Each START must name a column an equation solves for, at most once, and its
value must be a NUMBER; under PER, where one fit serves a whole group, it must be a
constant. The iteration stops when no unknown moves by more than the tolerance in a round (WITHIN,
default 0.0000000001), and each equation must then hold to within the tolerance
times the larger of 1 and the size of its sides; MAX n ROUNDS caps the attempts
(default 100). A row that cannot be posed — the wrong number of blanks, or
equations that do not determine the blanks (a singular linear system, or only a
product of blanks fixed) — passes through unchanged. A row that is posed but has no
solution, or on which the search does not converge within its rounds, raises an
error naming the row: a query does not return a blank it was asked to fill
without saying so.

With PER, the rows are grouped by the keys and each group is fitted by least
squares. The unknowns are the participating columns that are NULL in every row of
the group; each row whose other participating columns are all present is an
observation, contributing one residual (left − right) per equation; the unknowns
minimise the sum of the squared residuals — from the normal equations when the
equations are linear in them, by the same iteration (Gauss–Newton) otherwise. A
group is fitted when it has at least as many residuals as unknowns and they
determine the unknowns; the fitted values are then written into every row of the
group, observation or not. Any other group passes through unchanged, and a search
that does not converge raises an error naming the group. With a single row and as
many residuals as unknowns the fit is the exact solve. PER buffers its input
(blocking), and the rows come back in input order.

# Examples:
Fill whichever of total/principal/rate is blank, per row:
  SOLVE total = principal * rate (Loans)

Complete a units × price = revenue table where any one column may be missing:
  SOLVE revenue = units * price (Sales)

Recover a pair of values from their sum and difference:
  SOLVE { total = a + b, diff = a - b } (Pairs)

Fit a line through each series of points:
  SOLVE y = slope * x + intercept PER series (Points)

Find a side from an area — an equation with no rearrangement, solved by search:
  SOLVE area = side * side (Squares)

Fit a saturating curve per enzyme, with a looser tolerance and a tighter cap:
  SOLVE rate = vmax * s / (km + s) PER enzyme WITHIN 0.000001 MAX 50 ROUNDS (Assays)

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

Fitting is what PER adds. A lab measures rods of two materials at several
temperatures; the expansion coefficient of each material is the one unknown, NULL
in every row, and each measured row is an observation of it. The fit fills the
coefficient into every row of the material — including the steel rod nobody has
measured yet, whose length a second SOLVE can then predict:

```relix
def expanded(L0: NUMBER, alpha: NUMBER, dT: NUMBER) : NUMBER := { L0 * (1 + alpha * dT) };

Measurements := [
| rod | material  | L0  | dT  | length  | alpha |
|-----|-----------|-----|-----|---------|-------|
| S1  | steel     | 100 | 10  | 100.012 |       |
| S2  | steel     | 100 | 50  | 100.06  |       |
| S3  | steel     | 100 | 100 | 100.121 |       |
| S4  | steel     | 200 | 30  |         |       |
| A1  | aluminium | 50  | 20  | 50.023  |       |
| A2  | aluminium | 50  | 40  | 50.046  |       |
];

Calibrated := { SOLVE length = expanded(L0, alpha, dT) PER material (Measurements) };
query { SOLVE length = expanded(L0, alpha, dT) (Calibrated) };
```

```
 rod  material   L0   dT   length       alpha
 ───  ─────────  ───  ───  ───────────  ────────────
 S1   steel      100   10      100.012  0.0000120794
 S2   steel      100   50       100.06  0.0000120794
 S3   steel      100  100      100.121  0.0000120794
 S4   steel      200   30  200.0724764  0.0000120794
 A1   aluminium   50   20       50.023      0.000023
 A2   aluminium   50   40       50.046      0.000023
(6 rows)
```

The three steel measurements do not agree exactly, so steel's coefficient is the
least-squares compromise; aluminium's two agree, so its fit is exact. S4 was not an
observation — its length was blank — but it is in the steel group, so it receives
steel's coefficient, and the outer, row-by-row SOLVE then has one blank left to
fill.

When an equation cannot be rearranged, SOLVE searches. A balance left to grow for
two years at a rate compounded yearly is `principal × (1 + rate) × (1 + rate)`:
`rate` appears twice, so there is no tree-walk to undo, and the row is solved
iteratively instead. The other rows, whose blank appears once, are still inverted:

```relix
Deposits := [
| account | principal | rate | balance |
|---------|-----------|------|---------|
| A-100   | 1000      |      | 1102.5  |
| A-200   | 2000      | 0.03 |         |
| A-300   |           | 0.04 | 540.8   |
];

query { SOLVE balance = principal * (1 + rate) * (1 + rate) (Deposits) };
```

```
 account  principal  rate  balance
 ───────  ─────────  ────  ───────
 A-100         1000  0.05   1102.5
 A-200         2000  0.03   2121.8
 A-300          500  0.04    540.8
(3 rows)
```

A-100's rate was found by search, starting from 1 and settling on 5%. A-200 and
A-300 multiplied and divided as they always did.

Where an equation has more than one root, the start decides which one a search
reaches. A projectile launched upward at `vy` from height `y0` is back at ground
level when `0 = y0 + vy·t − ½·g·t²`. That quadratic has a root at or before launch
and one at impact. `t` appears twice, so SOLVE searches, and from 1 it reaches the
launch-side root:

```relix
Shots := [
| shot_id | y0 | vy                 |
|---------|----|--------------------|
| 1       | 2  | 21.213203435596424 |
| 2       | 0  | 25                 |
];
Unknown := [
| shot_id | t |
|---------|---|
| 1       |   |
| 2       |   |
];

query { π shot_id, t (SOLVE 0 = y0 + vy * t - 0.5 * 9.81 * t * t (Shots ⋈ Unknown)) };
```

```
 shot_id  t
 ───────  ─────────────
       1  -0.0923105886
       2              0
(2 rows)
```

Impact is the later root, and from the launch height it comes just after the time
the shot takes to return there, `2·vy/g`. Starting the search at that time, row by
row, reaches the impact time instead:

```relix
query { π shot_id, t (SOLVE 0 = y0 + vy * t - 0.5 * 9.81 * t * t
                      START t = 2 * vy / 9.81 (Shots ⋈ Unknown)) };
```

```
 shot_id  t
 ───────  ────────────
       1  4.4171227059
       2  5.0968399592
(2 rows)
```

# Limitations:
Only basic arithmetic (+ − × ÷, unary minus) can be solved, written directly or
through a `def` whose body is such arithmetic; a built-in function cannot be
solved through. A row is solved only where it has exactly as many blanks as there
are equations and the equations determine them; any other row is left as-is. An
equation solved by search yields one root — the one reached from its START, or
from 1 — and a row with no solution is an error rather than a row left blank. A
START cannot bound the search: it chooses where the search begins, not which
values it may reach. A PER fit buffers its
input. This is the goal-seek half of the declarative solver; the optimisation half
is OPTIMIZE.

# Alternatives:
OPTIMIZE for choosing values subject to constraints (rather than solving
equations). Projection with arithmetic when nothing is missing and you just want
to compute a column.

# See Also:
[optimize](optimize.md), [project](../operators/project.md), [def](../language/def.md)

# Notes:
Direction is decided per row, so the same SOLVE statement fills `total` in one
row and `rate` in another depending on which is NULL.
