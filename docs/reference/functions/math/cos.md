# Name: Cos (cosine)

# Syntax:
Cos(<radians>)

π Cos(angle) → c (Angles)

# Description:
Cos returns the cosine of an angle given in radians, used in geometry and wave
calculations. (Multiply degrees by π/180 to convert to radians first.)

# Technical Description:
Cos(x: NUMBER) → NUMBER. Returns cos(x) where x is in radians (Math.cos). NULL
input returns NULL. PURE, DETERMINISTIC. Floating-point result.

# Examples:
Resolve a wind reading into its north/south component:
  π station, speed * Cos(direction_rad) → north (Wind)

Distance along the ground from a slant range and angle:
  π id, slant_range * Cos(elevation_rad) → ground_distance (Sightings)

# Limitations:
Argument is in radians, not degrees. NUMBER only; NULL in → NULL out. Floating
point.

# See Also:
[sin](sin.md), [tan](tan.md), [atn](atn.md)
