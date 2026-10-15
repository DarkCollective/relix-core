# Name: Sin (sine)

# Syntax:
Sin(<radians>)

π Sin(angle) → s (Angles)

# Description:
Sin returns the sine of an angle given in radians. It is used in geometry, signal,
and wave calculations. (Convert degrees with Radians first.)

# Technical Description:
Sin(x: NUMBER) → NUMBER. Returns sin(x) where x is in radians (Math.sin). NULL
input returns NULL. PURE, DETERMINISTIC. Floating-point result.

# Examples:
Resolve a wind reading into its east/west component:
  π station, speed * Sin(direction_rad) → east (Wind)

From degrees:
  π Sin(Radians(degrees)) → s (Angles)

# Limitations:
Argument is in radians, not degrees. NUMBER only; NULL in → NULL out. Floating
point.

# See Also:
[cos](cos.md), [tan](tan.md), [asin](asin.md), [radians](radians.md)
