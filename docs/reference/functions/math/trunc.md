# Name: TRUNC (truncate towards zero, to places)

# Syntax:
TRUNC(<x> [, <places>])
TRUNCATE(<x>, <places>)

π amount, TRUNC(amount, 1) → one_place (Orders)

# Description:
TRUNC cuts a number towards zero: to a whole number, or to a number of decimal
places, dropping the rest rather than rounding it. `TRUNC(2.78, 1)` is 2.7 and
`TRUNC(-2.78)` is -2. Negative places cut to tens, hundreds and so on. TRUNCATE is
the same function by MySQL's name.

# Technical Description:
TRUNC(x: NUMBER [, places: NUMBER]) → NUMBER. Exact decimal truncation towards zero
to `places` digits after the point, 0 when omitted; `TRUNC(1234, -2)` is 1200. A NULL
in either argument returns NULL. `TRUNC(x)` is `Fix(x)`. PURE, DETERMINISTIC.

# Examples:
Whole units, dropping the fraction:
  π TRUNC(amount) → whole (Orders)

To the hundred below, towards zero:
  π TRUNC(amount, -2) → hundreds (Orders)

# Pushdown:
SQL: `TRUNC(x [, places])` on PostgreSQL, DuckDB and Db2, and `TRUNCATE(x, places)`
on MySQL; in the engine elsewhere.

# Alternatives:
Round for the nearest value, Int and Ceil for the floor and ceiling.

# SQL names:
`TRUNCATE` is the same function by MySQL's name.

# See Also:
[fix](fix.md), [round](round.md), [int](int.md), [ceil](ceil.md)
