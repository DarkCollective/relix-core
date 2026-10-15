# Name: ASCII (code of the first character)

# Syntax:
ASCII(<string>)

π name, ASCII(name) → first_code (Users)

# Description:
ASCII gives the character code of a string's first character, as SQL's function of
that name does: `ASCII('A')` is 65. An empty string gives 0.

# Technical Description:
ASCII(s: STRING) → NUMBER. The Unicode code point of the first character; 0 for the
empty string. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
Names that start with an upper-case Latin letter:
  σ ASCII(name) >= 65 ∧ ASCII(name) <= 90 (Users)

# Pushdown:
SQL: `ASCII(…)` on PostgreSQL; in the engine elsewhere.

# Alternatives:
Asc, which refuses an empty string, and Chr, which goes the other way.

# See Also:
[asc](asc.md), [chr](chr.md)
