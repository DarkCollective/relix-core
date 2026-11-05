# Name: Power (raise to a power)

# Syntax:
Power(<base>, <exponent>)

π Power(2, n) → capacity (Levels)

# Description:
Power raises a base to an exponent — Power(2, 10) is 1024. Use it for exponential
quantities (doubling, compound factors) and for roots (a fractional exponent like
0.5 is a square root).

# Technical Description:
Power(base: NUMBER, exp: NUMBER) → NUMBER. Returns base^exp. A whole-number exponent
is computed exactly in decimal arithmetic, so Power(9223372036854775807, 2) is
85070591730234615847396907784232501249; a negative one is the reciprocal, to 34
significant digits. Any other exponent is computed in binary floating point
(Math.pow). A NULL in either argument returns NULL. PURE, DETERMINISTIC.

# Examples:
Powers of two:
  π Power(2, level) → capacity (Levels)

Compound factor over n periods:
  π Power(1 + rate, periods) → factor (Loans)

Cube root via fractional exponent:
  π Power(volume, 0.333333) → side (Cubes)

# Limitations:
NUMBER arguments; NULL in either → NULL out. A fractional exponent gives a
floating-point result. Where there is no answer the call is an error: a negative base
with a fractional exponent, zero to a negative power, a result beyond the range of a
double (fractional exponent) or of 100000 digits (whole exponent).

# Alternatives:
Sqr for the square root specifically; Exp/Log for base-e work.

# See Also:
[sqr](sqr.md), [exp](exp.md), [log](log.md)
