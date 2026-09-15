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
  π Atn(slope) * 180 / 3.14159265 → angle_deg (Lines)

# Limitations:
Returns radians in (−π/2, π/2). NUMBER only; NULL in → NULL out. Single-argument
form only (no two-argument atan2). Floating point.

# Alternatives:
Tan is the forward direction (angle → ratio).

# See Also:
[tan](tan.md), [sin](sin.md), [cos](cos.md)
