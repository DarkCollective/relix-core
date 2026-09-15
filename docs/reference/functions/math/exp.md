# Name: Exp (exponential)

# Syntax:
Exp(<number>)

π Exp(rate * years) → growth_factor (Investments)

# Description:
Exp returns e raised to the power of the input (e ≈ 2.718). It is the inverse of
Log and appears in compound growth, decay, and probability calculations.

# Technical Description:
Exp(x: NUMBER) → NUMBER. Returns e^x (Math.exp). NULL input returns NULL. PURE,
DETERMINISTIC. Floating-point result.

# Examples:
Continuous-growth factor:
  π Exp(rate * years) → growth_factor (Investments)

Undo a natural log:
  π Exp(log_value) → original (Measurements)

# Limitations:
NUMBER only; NULL in → NULL out. Floating-point result; very large inputs overflow
to infinity.

# Alternatives:
Log is the inverse; Power(base, exp) for a base other than e.

# See Also:
[log](log.md), [power](power.md), [sqr](sqr.md)
