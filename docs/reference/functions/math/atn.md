# Name: Atn (arctangent)

# Syntax:
Atn(<number>)

π Atn(slope) → angle_rad (Lines)

# Description:
Atn returns the arctangent — the angle (in radians) whose tangent is the input. It
is the inverse of Tan, useful for recovering an angle from a slope or ratio.

# Technical Description:
Atn(x: NUMBER) → NUMBER. Returns atan(x) in radians, in the range (−π/2, π/2)
(Math.atan). NULL input returns NULL. PURE, DETERMINISTIC. Floating-point result.

# Examples:
Angle of a slope, in radians:
  π Atn(rise / run) → angle_rad (Lines)

Convert the result to degrees:
  π Degrees(Atn(slope)) → angle_deg (Lines)

# Limitations:
Returns radians in (−π/2, π/2). NUMBER only; NULL in → NULL out. Floating point.
It cannot tell (1, 1) from (−1, −1), since both are a ratio of 1: Atan2(y, x) can.

# Alternatives:
Tan is the forward direction (angle → ratio). Atan2(y, x) takes the two
coordinates and returns the angle in the right quadrant.

# See Also:
[atan2](atan2.md), [tan](tan.md), [asin](asin.md), [acos](acos.md), [degrees](degrees.md)
