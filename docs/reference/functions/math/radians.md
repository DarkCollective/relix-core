# Name: Radians (degrees to radians)

# Syntax:
Radians(<degrees>)

π Radians(angle_deg) → angle_rad (Angles)

# Description:
Radians converts an angle from degrees to radians: Radians(180) is π. Sin, Cos and
Tan take radians, so an angle stored in degrees goes through Radians first.

# Technical Description:
Radians(x: NUMBER) → NUMBER. Returns x × π / 180 (Math.toRadians). NULL input
returns NULL. PURE, DETERMINISTIC. Floating-point result. SQL's RADIANS(x).

# Examples:
East component of a wind reading given in degrees:
  π station, speed * Sin(Radians(direction)) → east (Wind)

# Limitations:
NUMBER only; NULL in → NULL out. Floating point.

# See Also:
[degrees](degrees.md), [sin](sin.md), [cos](cos.md), [pi](pi.md)
