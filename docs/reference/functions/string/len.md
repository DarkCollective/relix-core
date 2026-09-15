# Name: Len (string length)

# Syntax:
Len(<string>)

π Len(name) → name_length (Users)

# Description:
Len returns the number of characters in a string — useful for validating input
lengths, filtering by name size, or reporting field widths.

# Technical Description:
Len(s: STRING) → NUMBER. Returns the character count of s, counted in code
points — so a character outside the basic multilingual plane, an emoji say, counts
once. NULL input returns NULL. PURE and DETERMINISTIC, so the optimizer may fold it on constants.

# Examples:
Length of each name:
  π name, Len(name) → chars (Users)

Filter to short codes:
  σ Len(code) <= 5 (Products)

Find suspiciously long entries:
  σ Len(description) > 1000 (Articles)

# Pushdown:
SQL: folds to `CHAR_LENGTH(<s>)` on the **MySQL** dialect only. MySQL counts a string in
characters, which is what relix counts in; H2 (the generic dialect) counts UTF-16
code units, so its same-named function answers differently for text holding a
character outside the basic multilingual plane, and is offered nothing. Postgres
is not offered it either — not because it is believed to differ, but because no
Postgres has been run against it.

# Limitations:
Operates on STRING values; a non-string argument is an evaluation error. NULL in →
NULL out.

# See Also:
[left](left.md), [right](right.md), [mid](mid.md), [instr](instr.md), [trim](trim.md)
