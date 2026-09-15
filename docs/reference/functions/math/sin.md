# Name: Sin (sine)

# Syntax:
Sin(<radians>)

π Sin(angle) → s (Angles)

# Description:
Sin returns the sine of an angle given in radians. It is used in geometry, signal,
and wave calculations. (Multiply degrees by π/180 to convert to radians first.)

# Technical Description:
Sin(x: NUMBER) → NUMBER. Returns sin(x) where x is in radians (Math.sin). NULL
input returns NULL. PURE, DETERMINISTIC. Floating-point result.

# Examples:
Resolve a wind reading into its east/west component:
  π station, speed * Sin(direction_rad) → east (Wind)

From degrees:
  π Sin(degrees * 3.14159265 / 180) → s (Angles)

# Limitations:
Argument is in radians, not degrees. NUMBER only; NULL in → NULL out. Floating
point.

# See Also:
[cos](cos.md), [tan](tan.md), [atn](atn.md)
