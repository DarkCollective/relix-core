# Name: Left (leftmost characters)

# Syntax:
Left(<string>, <n>)

π Left(phone, 3) → area_code (Contacts)

# Description:
Left returns the first n characters of a string. Use it to pull out prefixes —
area codes, country prefixes, the first initial, a fixed-width code's leading
segment.

# Technical Description:
Left(s: STRING, n: NUMBER) → STRING. Returns the first n characters (or the whole
string if shorter). n must be non-negative (else an evaluation error). NULL string
input returns NULL. PURE, DETERMINISTIC.

# Examples:
First three digits of a phone number:
  π Left(phone, 3) → area_code (Contacts)

First initial:
  π Left(first_name, 1) → initial (People)

# Pushdown:
SQL: folds to `LEFT(<s>, <n>)` on the
**MySQL**, **PostgreSQL** and **DuckDB** dialects. Each counts a string in
characters, which is what relix counts in, and each was put on that list by running
it against text holding a character outside the basic multilingual plane. H2 (the
generic dialect) counts UTF-16 code units, so its same-named function answers
differently for such text, and is offered nothing.

# Limitations:
n must be ≥ 0. STRING first argument; NULL in → NULL out.

# Alternatives:
Right for the end of the string; Mid for a middle slice.

# See Also:
[right](right.md), [mid](mid.md), [len](len.md), [instr](instr.md)
