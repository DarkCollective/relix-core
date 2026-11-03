# Name: REGEXP_SUBSTR (the text a regular expression matches)

# Syntax:
REGEXP_SUBSTR(<string>, <pattern> [, <flags>])
REGEXP_SUBSTR(<string>, <pattern>, <position> [, <occurrence> [, <flags>]])

π REGEXP_SUBSTR(description, '[0-9]+') → first_number (Products)
π REGEXP_SUBSTR(description, '[0-9]+', 1, 2) → second_number (Products)

# Description:
REGEXP_SUBSTR gives the part of a string that a regular expression first matches,
or NULL when nothing does. MySQL's and Oracle's positional form says where to start
and which match to give: `REGEXP_SUBSTR('a1 b22 c333', '[0-9]+', 1, 2)` is `'22'`.

# Technical Description:
REGEXP_SUBSTR(s: STRING, pattern: STRING [, flags: STRING]) → STRING. The first
match, or NULL for none. Flags `i` and `c` as for REGEXP_LIKE. A NULL argument
returns NULL. PURE, DETERMINISTIC.

REGEXP_SUBSTR(s, pattern, position: NUMBER [, occurrence: NUMBER [, flags: STRING]]) →
STRING. A NUMBER third argument is a position, a STRING the flags. The search starts at
character `position`, counted from 1, and gives the `occurrence`th match found from
there, 1 when omitted, or NULL when there are fewer. `^` still means the start of the
string. A position below 1 or an occurrence below 1 is an error.

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
