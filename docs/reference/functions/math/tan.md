# Name: Tan (tangent)

# Syntax:
Tan(<radians>)

π Tan(angle) → t (Angles)

# Description:
Tan returns the tangent of an angle given in radians, used in geometry and slope
calculations. (Multiply degrees by π/180 to convert to radians first.)

# Technical Description:
Tan(x: NUMBER) → NUMBER. Returns tan(x) where x is in radians (Math.tan). NULL
input returns NULL. PURE, DETERMINISTIC. Floating-point result.

# Examples:
Height of an object from its angle of elevation and distance:
  π id, distance * Tan(elevation_rad) → height (Sightings)

Gradient of a ramp as a rise over a fixed run:
  π id, Tan(slope_rad) * run → rise (Ramps)

# Limitations:
Argument is in radians. NUMBER only; NULL in → NULL out. Near odd multiples of
π/2 the result is extremely large (asymptote). Floating point.

# See Also:
[sin](sin.md), [cos](cos.md), [atn](atn.md)
