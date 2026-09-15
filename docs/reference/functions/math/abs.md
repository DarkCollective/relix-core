# Name: Abs (absolute value)

# Syntax:
Abs(<number>)

π Abs(delta) → magnitude (Readings)

# Description:
Abs returns the size of a number ignoring its sign — −7 and 7 both become 7. Use
it to measure how far a value is from zero regardless of direction (e.g. the size
of an error or a price change).

# Technical Description:
Abs(x: NUMBER) → NUMBER. Returns |x| with full BigDecimal precision. NULL input
returns NULL. PURE, DETERMINISTIC, IDEMPOTENT.

# Examples:
Magnitude of a signed delta:
  π Abs(delta) → magnitude (Readings)

Filter on absolute deviation:
  σ Abs(forecast - actual) > 100 (Forecasts)

Smallest absolute error per sensor:
  γ sensor, MIN(Abs(error)) → best (Readings)

# Pushdown:
SQL: folds to `ABS(<e>)` on all dialects. The result is exact on an exact numeric
column, so the database's answer and the engine's are the same digits.

# Limitations:
NUMBER only; NULL in → NULL out.

# See Also:
[sgn](sgn.md), [round](round.md), [int](int.md), [fix](fix.md)
