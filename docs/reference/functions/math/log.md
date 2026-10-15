# Name: Log (logarithm)

# Syntax:
Log(<number>)
Log(<base>, <number>)

π Log(value) → log_value (Measurements)

# Description:
Log returns the natural logarithm (base e) of a number, or with two arguments the
logarithm of the second to the base given first: Log(2, 8) is 3. It is used to
compress wide-ranging values onto a smaller scale and in growth/decay and
statistical calculations.

# Technical Description:
Log(x: NUMBER) → NUMBER. Returns ln(x) (Math.log). The argument must be positive
(else an evaluation error). NULL input returns NULL. PURE, DETERMINISTIC.
Floating-point result.

Log(b: NUMBER, y: NUMBER) → NUMBER. Returns log_b(y) = ln(y) / ln(b), in SQL's
argument order (PostgreSQL's LOG(b, x)), and exactly the integer n where y is b to
the n: Log(10, 1000) is 3, not 2.9999999999999996. Both arguments must be positive
and the base must not be 1, else an evaluation error. A NULL in either returns NULL.

# Examples:
Log-transform a measurement:
  π Log(value) → log_value (Measurements)

Filter on a log-scaled threshold:
  σ Log(amount) > 10 (Transactions)

Bits needed to count to n:
  π Ceil(Log(2, n)) → bits (Counters)

# Limitations:
Errors on a non-positive argument (x ≤ 0), and on a base of 1. NUMBER only; NULL
in → NULL out. With one argument this is the natural log (base e), where SQL's
one-argument LOG(x) is base 10 in PostgreSQL: write Log10(x) for that.

# Alternatives:
Exp is the inverse (e^x). Log10(x) is the base-10 logarithm.

# See Also:
[log10](log10.md), [exp](exp.md), [power](power.md), [sqr](sqr.md)
