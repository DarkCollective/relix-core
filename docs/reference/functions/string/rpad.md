# Name: RPAD (pad on the right)

# Syntax:
RPAD(<string>, <length> [, <fill>])

π RPAD(name, 12, '.') → label (Users)

# Description:
RPAD brings a string to a given length by adding characters on its right, spaces
unless you name others: `RPAD('ab', 5, '.')` is `'ab...'`. A string longer than the
length is cut to it, from its end.

# Technical Description:
RPAD(s: STRING, length: NUMBER [, fill: STRING]) → STRING. Lengths count
characters. The fill repeats and is cut to fit; an empty fill leaves a short string
as it is, and a length of zero or less gives `''`. A NULL in any argument returns
NULL. PURE, DETERMINISTIC.

# Examples:
Dot leaders to a fixed width:
  π RPAD(name, 12, '.') → label (Users)

# Pushdown:
SQL: `RPAD(…)` on PostgreSQL; in the engine elsewhere, as for LPAD.

# See Also:
[lpad](lpad.md), [repeat](repeat.md), [right](right.md)
