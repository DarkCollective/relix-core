# Name: Round (round to nearest)

# Syntax:
Round(<number>)
Round(<number>, <places>)

π Round(price, 2) → price (Products)

# Description:
Round rounds a number to the nearest whole number, or to a given number of decimal
places. With no places it rounds to an integer; with 2 it rounds to the nearest
penny/cent. Half-way values round up.

# Technical Description:
Round(x[, places]) → NUMBER. Rounds to `places` decimal places (0 if omitted)
using RoundingMode.HALF_UP. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
Round money to two decimals:
  π Round(price, 2) → price (Products)

Round to the nearest whole number:
  π Round(score) → grade (Results)

Round to the nearest hundred (negative places not supported — scale instead):
  π Round(amount, 0) → whole (Orders)

# Pushdown:
SQL: folds to `ROUND(<e>)` and `ROUND(<e>, <places>)` on every dialect but SQLite.
Both round half away from zero over an exact numeric column, which is what the engine
does. SQLite has no decimal type and rounds the nearest double instead — `-49.555`
rounds to `-49.55` there and `-49.56` here — so on SQLite the call runs in the engine.

# Limitations:
NUMBER first argument; NULL in → NULL out. Uses HALF_UP (0.5 rounds away from
zero). `places` is the number of decimal places (≥ 0 in practice).

# Alternatives:
Int / Fix / Ceil for directional rounding rather than nearest.

# See Also:
[int](int.md), [fix](fix.md), [ceil](ceil.md)
