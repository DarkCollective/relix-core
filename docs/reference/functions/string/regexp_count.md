# Name: REGEXP_COUNT (how many times a regular expression matches)

# Syntax:
REGEXP_COUNT(<string>, <pattern> [, <flags>])

π REGEXP_COUNT(description, '[0-9]+') → numbers (Products)

# Description:
REGEXP_COUNT counts the non-overlapping matches of a regular expression in a string.

# Technical Description:
REGEXP_COUNT(s: STRING, pattern: STRING [, flags: STRING]) → NUMBER. Matches are
counted from the start, each search resuming after the previous match. Flags `i`
and `c` as for REGEXP_LIKE. A NULL argument returns NULL. PURE, DETERMINISTIC.

Patterns are Java's regular expressions, with `.` matching a newline as in
PostgreSQL; see [regex match](../../predicates/regex-match.md) for how that dialect
differs from POSIX.

# Examples:
Words in a description:
  π REGEXP_COUNT(description, '[A-Za-z]+') → words (Products)

# Pushdown:
Runs in the engine: PostgreSQL has regexp_count only from version 15.

# See Also:
[regexp_like](regexp_like.md), [regexp_substr](regexp_substr.md)
