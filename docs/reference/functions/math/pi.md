# Name: Pi (the constant π)

# Syntax:
Pi()

π Pi() * Power(radius, 2) → area (Circles)

# Description:
Pi returns the mathematical constant π (approximately 3.14159), the ratio of a
circle's circumference to its diameter. Use it in geometry and anywhere the
trigonometric functions appear — they measure angles in radians, and a half turn
is `Pi()` radians.

# Technical Description:
Pi() → NUMBER. Zero-argument. Returns π as the nearest double (Math.PI,
3.141592653589793). PURE, DETERMINISTIC — the optimizer folds it to a constant, so
it evaluates once rather than per row. Not pushed down to a backend: a folded
constant already agrees digit for digit, where a backend's own PI() need not.

# Examples:
Area of a circle from its radius:
  π Pi() * Power(radius, 2) → area (Circles)

Convert an angle from degrees to radians:
  π degrees * Pi() / 180 → radians (Angles)

Radians to degrees:
  π radians * 180 / Pi() → degrees (Angles)

# Limitations:
Returns the nearest double, not an exact value — no decimal constant can hold π.
Zero-argument: `Pi` without parentheses is a column reference, not a call.

# Alternatives:
Write the literal `3.14159` where a rough value will do; Pi() is the full-precision
constant and reads as intent.

# See Also:
[sin](sin.md), [cos](cos.md), [tan](tan.md), [atn](atn.md)

# Notes:
Relix trigonometry is in radians: `Sin(Pi() / 2)` is 1, and `Pi()` radians is a
half turn.
