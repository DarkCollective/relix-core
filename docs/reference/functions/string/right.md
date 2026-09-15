# Name: Right (rightmost characters)

# Syntax:
Right(<string>, <n>)

π Right(card_number, 4) → last_four (Cards)

# Description:
Right returns the last n characters of a string. Common uses: the last four
digits of a card or account number, a file extension, a trailing suffix.

# Technical Description:
Right(s: STRING, n: NUMBER) → STRING. Returns the last n characters (or the whole
string if shorter). n must be non-negative. NULL string input returns NULL. PURE,
DETERMINISTIC.

# Examples:
Last four digits of a card:
  π Right(card_number, 4) → last_four (Cards)

Trailing year of a reference:
  π Right(ref, 4) → year (Documents)

# Pushdown:
SQL: folds to `RIGHT(<s>, <n>)` on the **MySQL** dialect only. MySQL counts a string in
characters, which is what relix counts in; H2 (the generic dialect) counts UTF-16
code units, so its same-named function answers differently for text holding a
character outside the basic multilingual plane, and is offered nothing. Postgres
is not offered it either — not because it is believed to differ, but because no
Postgres has been run against it.

# Limitations:
n must be ≥ 0. STRING first argument; NULL in → NULL out.

# Alternatives:
Left for the start; Mid for a middle slice.

# See Also:
[left](left.md), [mid](mid.md), [len](len.md), [instr](instr.md)
