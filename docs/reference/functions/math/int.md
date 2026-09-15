# Name: Int (floor to integer)

# Syntax:
Int(<number>)

π Int(price) → whole_pounds (Products)

# Description:
Int rounds a number DOWN to the nearest whole number (toward negative infinity).
3.9 becomes 3, and −1.1 becomes −2. Use it when you want the integer floor of a
value.

# Technical Description:
Int(x: NUMBER) → NUMBER. Rounds toward negative infinity (RoundingMode.FLOOR — Jet
behaviour, same as mathematical floor). NULL input returns NULL. PURE,
DETERMINISTIC, IDEMPOTENT.

# Examples:
Whole-pound part of a price:
  π Int(price) → whole_pounds (Products)

Bucket ages into whole years (floor):
  π Int(age) → year_bucket (People)

# Pushdown:
SQL: folds to `FLOOR(<e>)` on all dialects. Exact on an exact numeric column.

# Limitations:
NUMBER only; NULL in → NULL out. Floors negatives downward (Int(−1.1) = −2) —
differs from Fix, which truncates toward zero.

# Alternatives:
Fix truncates toward zero; Ceil rounds up; Round rounds to nearest.

# See Also:
[fix](fix.md), [ceil](ceil.md), [round](round.md), [abs](abs.md)
