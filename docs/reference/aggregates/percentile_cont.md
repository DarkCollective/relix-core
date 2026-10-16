# Name: PERCENTILE_CONT (continuous percentile, with PERCENTILE_DISC)

# Syntax:
PERCENTILE_CONT(<fraction>) WITHIN GROUP (ORDER BY <expr> [ASC|DESC])
PERCENTILE_DISC(<fraction>) WITHIN GROUP (ORDER BY <expr> [ASC])
PERCENTILE_CONT(<fraction>, <expr>)
PERCENTILE_DISC(<fraction>, <expr>)

γ region, PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY amount) → p90 (Orders)

# Description:
A percentile is the value a given fraction of a group lies below: the 90th percentile
of response times is the time 90% of requests beat. PERCENTILE_CONT interpolates
between the two values either side of the point, so it can return a value that is in
no row; PERCENTILE_DISC returns the first actual value that reaches the fraction.

# Technical Description:
Over the n non-NULL values in ascending order, PERCENTILE_CONT(p) is the value at
position p × (n − 1), counting from 0, interpolated linearly between its neighbours;
its argument must be a NUMBER. PERCENTILE_DISC(p) is the value at position
⌈p × n⌉ − 1, at least 0, and works on any type that orders, dates and strings
included. Both are NULL for a group with no values. The fraction must be between 0 and
1 and the same in every row; anything else is an error.

`WITHIN GROUP (ORDER BY x)` is SQL's spelling and `PERCENTILE_CONT(p, x)` a shorter
one; both build the same tree, which prints back in the shorter form. For
PERCENTILE_CONT, `ORDER BY x DESC` reads the fraction from the top, which is the
fraction 1 − p ascending; PERCENTILE_DISC takes an ascending order only. A percentile
needs every value of its group at once, so it does not roll in ROLLING.

# Examples:
The 90th-percentile order per region:
  γ region, PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY amount) → p90 (Orders)

The first order date by which half of a customer's orders had been placed:
  γ customer_id, PERCENTILE_DISC(0.5, order_date) → halfway (Orders)

# Worked Example:
Four values and a missing one:

```relix
M := [
| x    |
|------|
| 1    |
| 2    |
| 3    |
| 4    |
| NULL |
];

query { γ PERCENTILE_CONT(0.5, x) → cont50, PERCENTILE_CONT(0.25, x) → cont25,
          PERCENTILE_DISC(0.5, x) → disc50, PERCENTILE_DISC(0.25, x) → disc25 (M) };
```

```
 cont50  cont25  disc50  disc25
 ──────  ──────  ──────  ──────
    2.5    1.75       2       1
(1 row)
```

The NULL is not a value. The continuous median falls between 2 and 3, and its
quarter point three quarters of the way from 1 to 2; the discrete ones are the second
and first of the four values.

# Pushdown:
Runs in the engine: PostgreSQL computes its continuous percentile in binary floating
point.

# See Also:
[percentile_disc](percentile_disc.md), [median](median.md), [min](min.md), [max](max.md), [group](../operators/group.md)
