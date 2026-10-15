# Name: Atan2 (two-argument arctangent)

# Syntax:
Atan2(<y>, <x>)

π Atan2(dy, dx) → bearing_rad (Moves)

# Description:
Atan2 returns the angle, in radians, of the point (x, y) measured from the positive
x axis. Unlike Atn(y / x) it knows the quadrant, since it sees the signs of both
coordinates, and it has an answer where x is zero. It is the function for turning a
displacement into a heading.

# Technical Description:
Atan2(y: NUMBER, x: NUMBER) → NUMBER. Returns atan2(y, x) in radians, in (−π, π]
(Math.atan2). Note the order: y first, as in SQL's ATAN2(y, x). Atan2(0, 0) is 0. A
NULL in either argument returns NULL. PURE, DETERMINISTIC. Floating-point result.

# Examples:
Heading of each move, in degrees:
  π id, Degrees(Atan2(dy, dx)) → heading_deg (Moves)

# Limitations:
Returns radians. NUMBER only; NULL in either → NULL out. The argument order is
(y, x); swapping them reflects the angle about the diagonal.

# Alternatives:
Atn(y / x) when x is known to be positive.

# See Also:
[atn](atn.md), [asin](asin.md), [acos](acos.md), [degrees](degrees.md)
