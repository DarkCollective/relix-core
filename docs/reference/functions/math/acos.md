# Name: Acos (arccosine)

# Syntax:
Acos(<number>)

π Acos(ratio) → angle_rad (Triangles)

# Description:
Acos returns the arccosine: the angle, in radians, whose cosine is the input. It is
the inverse of Cos. Acos(-1) is π.

# Technical Description:
Acos(x: NUMBER) → NUMBER. Returns acos(x) in radians, in [0, π] (Math.acos). The
argument must lie in [−1, 1], else an evaluation error, as PostgreSQL raises. NULL
input returns NULL. PURE, DETERMINISTIC. Floating-point result. SQL's ACOS(x).

# Examples:
Angle between two unit vectors from their dot product:
  π a, b, Acos(dot) → angle_rad (Pairs)

# Limitations:
Errors on an argument outside [−1, 1]; a dot product computed in floating point can
land a hair outside, so clamp it with LEAST/GREATEST first. Returns radians. NUMBER
only; NULL in → NULL out.

# Alternatives:
Asin and Atan2 recover an angle from other ratios.

# See Also:
[cos](cos.md), [asin](asin.md), [atan2](atan2.md), [degrees](degrees.md)
