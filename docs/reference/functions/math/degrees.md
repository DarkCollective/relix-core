# Name: Degrees (radians to degrees)

# Syntax:
Degrees(<radians>)

π Degrees(angle_rad) → angle_deg (Angles)

# Description:
Degrees converts an angle from radians to degrees: Degrees(Pi()) is 180. The
inverse trigonometric functions return radians, so this is the usual last step
when a person is going to read the angle.

# Technical Description:
Degrees(x: NUMBER) → NUMBER. Returns x × 180 / π (Math.toDegrees). NULL input
returns NULL. PURE, DETERMINISTIC. Floating-point result. SQL's DEGREES(x).

# Examples:
Heading in degrees:
  π id, Degrees(Atan2(dy, dx)) → heading_deg (Moves)

# Limitations:
NUMBER only; NULL in → NULL out. Floating point.

# See Also:
[radians](radians.md), [atan2](atan2.md), [pi](pi.md)
