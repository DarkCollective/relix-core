# Name: REVERSE (characters in reverse order)

# Syntax:
REVERSE(<string>)

π REVERSE(code) → reversed (Products)

# Description:
REVERSE gives a string's characters in the opposite order: `REVERSE('abc')` is
`'cba'`.

# Technical Description:
REVERSE(s: STRING) → STRING. Reverses characters, so a character outside the basic
multilingual plane, an emoji, stays whole. NULL input returns NULL. PURE,
DETERMINISTIC.

# Examples:
Codes read from the end:
  π REVERSE(code) → reversed (Products)

# Pushdown:
SQL: `REVERSE(…)` on PostgreSQL and MySQL. SQL Server's reverses UTF-16 units and
splits an emoji, so there and elsewhere it runs in the engine.

# See Also:
[left](left.md), [right](right.md)
