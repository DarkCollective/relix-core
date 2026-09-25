# Name: Ceil (round up)

# Syntax:
Ceil(<number>)

π Ceil(items / 10) → pages (Orders)

# Description:
Ceil rounds a number UP to the nearest whole number. 3.1 becomes 4. Use it for
"how many full containers do I need" calculations — pages, boxes, batches — where
any remainder needs a whole extra unit.

# Technical Description:
Ceil(x: NUMBER) → NUMBER. Rounds toward positive infinity (RoundingMode.CEILING).
NULL input returns NULL. PURE, DETERMINISTIC, IDEMPOTENT.

# Examples:
Pages needed at 10 items per page:
  π Ceil(item_count / 10) → pages (Orders)

Boxes needed (round any remainder up):
  π Ceil(units / box_size) → boxes (Shipments)

# Pushdown:
SQL: folds to `CEILING(<e>)` on every dialect but SQLite — the SQL standard's
spelling. Exact on an exact numeric column. On SQLite it is a floating-point function
and an optional part of the build, so the call runs in the engine.

# Limitations:
NUMBER only; NULL in → NULL out. Always rounds upward, including for negatives
(Ceil(−1.5) = −1).

# Alternatives:
Int / Fix round down/toward zero; Round rounds to nearest.

# See Also:
[int](int.md), [fix](fix.md), [round](round.md)
