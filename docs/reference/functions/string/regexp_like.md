# Name: REGEXP_LIKE (does a regular expression match)

# Syntax:
REGEXP_LIKE(<string>, <pattern> [, <flags>])

π email, REGEXP_LIKE(email, '^[a-z]+@') → simple (Users)

# Description:
REGEXP_LIKE says whether a regular expression matches anywhere in a string, TRUE or
FALSE. `s ~ p` and `s MATCHES p` are the same test written as a condition.

# Technical Description:
REGEXP_LIKE(s: STRING, pattern: STRING [, flags: STRING]) → BOOLEAN. Flags: `i` to
ignore case, `c` to respect it (the default); the last one written wins. A NULL
string or pattern returns NULL. An invalid pattern or an unknown flag is an error.
PURE, DETERMINISTIC.

Patterns are Java's regular expressions, with `.` matching a newline as in
PostgreSQL; see [regex match](../../predicates/regex-match.md) for how that dialect
differs from POSIX.

# Examples:
Codes that start with a letter and a digit:
  σ REGEXP_LIKE(code, '^[A-Z][0-9]') = TRUE (Products)

Ignoring case:
  σ REGEXP_LIKE(name, '^mc', 'i') = TRUE (Users)

# Pushdown:
SQL: `(s ~ p)` or `~*` on PostgreSQL and `REGEXP_LIKE(s, p, 'cn')` on MySQL, when the
pattern and flags are literals in the portable part of the syntax; in the engine
otherwise.

# See Also:
[regex match](../../predicates/regex-match.md), [regexp_replace](regexp_replace.md), [regexp_substr](regexp_substr.md), [regexp_count](regexp_count.md)
