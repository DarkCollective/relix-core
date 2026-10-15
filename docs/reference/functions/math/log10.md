# Name: Log10 (base-10 logarithm)

# Syntax:
Log10(<number>)

π Log10(value) → magnitude (Measurements)

# Description:
Log10 returns the logarithm of a number to base 10: the power 10 must be raised to
to give it. Log10(1000) is 3. It is the usual way to put a value on an
order-of-magnitude scale (decibels, pH, the digit count of a number).

# Technical Description:
Log10(x: NUMBER) → NUMBER. Returns log₁₀(x) (Math.log10), exact for a power of ten.
The argument must be positive (else an evaluation error). NULL input returns NULL.
PURE, DETERMINISTIC. Floating-point result. SQL's LOG10(x).

# Examples:
Order of magnitude of a reading:
  π Int(Log10(amount)) → magnitude (Transactions)

# Limitations:
Errors on a non-positive argument (x ≤ 0). NUMBER only; NULL in → NULL out.

# Alternatives:
Log(10, x) is the same value; Log(b, x) takes any other base, and Log(x) is the
natural logarithm.

# See Also:
[log](log.md), [exp](exp.md), [power](power.md)
