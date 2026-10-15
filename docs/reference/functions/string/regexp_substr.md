# Name: REGEXP_SUBSTR (the text a regular expression matches)

# Syntax:
REGEXP_SUBSTR(<string>, <pattern> [, <flags>])

π REGEXP_SUBSTR(description, '[0-9]+') → first_number (Products)

# Description:
REGEXP_SUBSTR gives the part of a string that a regular expression first matches,
or NULL when nothing does.

# Technical Description:
REGEXP_SUBSTR(s: STRING, pattern: STRING [, flags: STRING]) → STRING. The first
match, or NULL for none. Flags `i` and `c` as for REGEXP_LIKE. A NULL argument
returns NULL. PURE, DETERMINISTIC.

Patterns are Java's regular expressions, with `.` matching a newline as in
PostgreSQL; see [regex match](../../predicates/regex-match.md) for how that dialect
differs from POSIX.

# Examples:
The first number in a description:
  π REGEXP_SUBSTR(description, '[0-9]+') → first_number (Products)

# Pushdown:
Runs in the engine: PostgreSQL has regexp_substr only from version 15.

# See Also:
[regexp_like](regexp_like.md), [regexp_count](regexp_count.md), [substring](substring.md)
