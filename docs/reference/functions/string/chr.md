# Name: Chr (character from code)

# Syntax:
Chr(<code>)

π Chr(65) → letter (Codes)     -- "A"

# Description:
Chr turns a numeric character code into the corresponding single character — for
example 65 becomes "A". It is the inverse of Asc, and is handy for building
characters programmatically (e.g. a tab or newline by code).

# Technical Description:
Chr(n: NUMBER) → STRING. Returns the single character whose code point is n.
Requires 0 ≤ n ≤ 1114111 (U+10FFFF, the last code point), and rejects a surrogate
(0xD800–0xDFFF), which is half of a character rather than one. Else an evaluation
error. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
Code to letter:
  π Chr(65) → letter (Codes)        -- "A"

Build a delimiter character:
  π Chr(9) → tab (Config)            -- tab character

# Limitations:
The code must be in 0–65535. NUMBER argument; NULL in → NULL out.

# Alternatives:
Asc converts the other way (character → code).

# See Also:
[asc](asc.md), [replace](replace.md)
