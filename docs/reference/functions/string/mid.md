# Name: Mid (substring)

# Syntax:
Mid(<string>, <start>)             -- from start to the end
Mid(<string>, <start>, <length>)   -- length characters from start

π Mid(sku, 4, 3) → category_code (Products)

# Description:
Mid extracts a slice from the middle of a string. Give it a starting position
(counting from 1) and optionally how many characters to take. Use it to pull a
field out of a fixed-format code or to grab a known section of text.

# Technical Description:
Mid(s, start[, length]) → STRING. start is 1-based; the two-argument form returns
from start to the end, the three-argument form returns at most length characters.
start must be ≥ 1 and length ≥ 0 (else an evaluation error). Positions and lengths
are counted in code points, so a slice never cuts a character in half.
Out-of-range slices clamp to the string. NULL string input returns NULL. PURE, DETERMINISTIC.

# Examples:
A 3-character category code starting at position 4:
  π Mid(sku, 4, 3) → category_code (Products)

Everything from the 5th character on:
  π Mid(reference, 5) → suffix (Documents)

# Pushdown:
SQL: folds to `SUBSTRING(<s>, <start>[, <length>])` on the **MySQL** dialect only. MySQL counts a string in
characters, which is what relix counts in; H2 (the generic dialect) counts UTF-16
code units, so its same-named function answers differently for text holding a
character outside the basic multilingual plane, and is offered nothing. Postgres
is not offered it either — not because it is believed to differ, but because no
Postgres has been run against it.

# Limitations:
start is 1-based and must be ≥ 1; length must be ≥ 0. STRING first argument; NULL
in → NULL out.

# Alternatives:
Left / Right for prefixes and suffixes; InStr to locate a position first.

# See Also:
[left](left.md), [right](right.md), [instr](instr.md), [len](len.md)
