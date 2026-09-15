# Name: Power (raise to a power)

# Syntax:
Power(<base>, <exponent>)

π Power(2, n) → capacity (Levels)

# Description:
Power raises a base to an exponent — Power(2, 10) is 1024. Use it for exponential
quantities (doubling, compound factors) and for roots (a fractional exponent like
0.5 is a square root).

# Technical Description:
Power(base: NUMBER, exp: NUMBER) → NUMBER. Returns base^exp (Math.pow). A NULL in
either argument returns NULL. PURE, DETERMINISTIC. Floating-point result.

# Examples:
Powers of two:
  π Power(2, level) → capacity (Levels)

Compound factor over n periods:
  π Power(1 + rate, periods) → factor (Loans)

Cube root via fractional exponent:
  π Power(volume, 0.333333) → side (Cubes)

# Limitations:
NUMBER arguments; NULL in either → NULL out. Floating-point result. Some
base/exponent combinations (e.g. negative base with a fractional exponent) yield
NaN.

# Alternatives:
Sqr for the square root specifically; Exp/Log for base-e work.

# See Also:
[sqr](sqr.md), [exp](exp.md), [log](log.md)
