# Name: Asin (arcsine)

# Syntax:
Asin(<number>)

π Asin(ratio) → angle_rad (Triangles)

# Description:
Asin returns the arcsine: the angle, in radians, whose sine is the input. It is the
inverse of Sin, used to recover an angle from the ratio of two sides.

# Technical Description:
Asin(x: NUMBER) → NUMBER. Returns asin(x) in radians, in [−π/2, π/2] (Math.asin).
The argument must lie in [−1, 1], else an evaluation error, as PostgreSQL raises.
NULL input returns NULL. PURE, DETERMINISTIC. Floating-point result. SQL's ASIN(x).

# Examples:
Angle of incline from the rise and the length of the slope:
  π Degrees(Asin(rise / length)) → incline_deg (Ramps)

# Limitations:
Errors on an argument outside [−1, 1]. Returns radians. NUMBER only; NULL in → NULL
out.

# Alternatives:
Acos and Atan2 recover an angle from other ratios.

# See Also:
[sin](sin.md), [acos](acos.md), [atan2](atan2.md), [degrees](degrees.md)
