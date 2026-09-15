# Name: Sqr (square root)

# Syntax:
Sqr(<number>)

π Sqr(area) → side (Squares)

# Description:
Sqr returns the square root of a number — the value that, multiplied by itself,
gives the input. Use it in geometric and statistical calculations (e.g. recovering
a side length from an area, or a standard deviation from a variance).

# Technical Description:
Sqr(x: NUMBER) → NUMBER. Returns √x (Math.sqrt). The argument must be non-negative
(else an evaluation error). NULL input returns NULL. PURE, DETERMINISTIC. Result
is a floating-point approximation.

# Examples:
Side length from an area:
  π Sqr(area) → side (Squares)

Standard deviation from a variance column:
  π Sqr(variance) → std_dev (Stats)

# Limitations:
Errors on a negative argument. NUMBER only; NULL in → NULL out. Floating-point
result, not exact.

# Alternatives:
Power(x, 0.5) is equivalent; Power(x, 2) squares.

# See Also:
[power](power.md), [exp](exp.md), [log](log.md)
