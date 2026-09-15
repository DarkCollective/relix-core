# Name: Log (natural logarithm)

# Syntax:
Log(<number>)

π Log(value) → log_value (Measurements)

# Description:
Log returns the natural logarithm (base e) of a number. It is used to compress
wide-ranging values onto a smaller scale and in growth/decay and statistical
calculations.

# Technical Description:
Log(x: NUMBER) → NUMBER. Returns ln(x) (Math.log). The argument must be positive
(else an evaluation error). NULL input returns NULL. PURE, DETERMINISTIC.
Floating-point result.

# Examples:
Log-transform a measurement:
  π Log(value) → log_value (Measurements)

Filter on a log-scaled threshold:
  σ Log(amount) > 10 (Transactions)

# Limitations:
Errors on a non-positive argument (x ≤ 0). NUMBER only; NULL in → NULL out. This
is the natural log (base e); divide by Log(base) for other bases.

# Alternatives:
Exp is the inverse (e^x). For log base 10, use Log(x) / Log(10).

# See Also:
[exp](exp.md), [power](power.md), [sqr](sqr.md)
