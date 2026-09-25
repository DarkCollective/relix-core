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
SQL: folds to `RIGHT(<s>, <n>)` on the
**MySQL**, **PostgreSQL** and **DuckDB** dialects, and to `SUBSTR(<s>, MAX(LENGTH(<s>) - <n>, 0) + 1)` on
**SQLite**, which lacks the name but counts the same way, and to a `SUBSTRING(…, CODEUNITS32)` on
**Db2**, whose own functions count bytes unless told otherwise. Each counts a string in
characters, which is what relix counts in, and each was put on that list by running
it against text holding a character outside the basic multilingual plane. H2 (the
generic dialect) counts UTF-16 code units, so its same-named function answers
differently for such text, and is offered nothing.

# Limitations:
n must be ≥ 0. STRING first argument; NULL in → NULL out.

# Alternatives:
Left for the start; Mid for a middle slice.

# See Also:
[left](left.md), [mid](mid.md), [len](len.md), [instr](instr.md)
