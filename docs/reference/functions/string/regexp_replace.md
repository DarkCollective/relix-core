# Name: REGEXP_REPLACE (replace a regular-expression match)

# Syntax:
REGEXP_REPLACE(<string>, <pattern>, <replacement> [, <flags>])

π REGEXP_REPLACE(phone, '[^0-9]', '', 'g') → digits (Users)

# Description:
REGEXP_REPLACE replaces what a regular expression matches. As in PostgreSQL, it
replaces the first match, and every match with the flag `g`. The replacement can
repeat what was matched: `\1` to `\9` for a parenthesised group and `\&` for the
whole match.

# Technical Description:
REGEXP_REPLACE(s: STRING, pattern: STRING, replacement: STRING [, flags: STRING]) →
STRING. Flags: `g` for every match, `i` to ignore case, `c` to respect it. In the
replacement, `\1`–`\9` is a group (empty when it took no part), `\&` the match and
`\\` a backslash; any other character is itself. A Relix string writes a backslash
as `\\`, so the replacement `\1` is the literal `'\\1'`. A NULL argument returns NULL.
PURE, DETERMINISTIC.

Patterns are Java's regular expressions, with `.` matching a newline as in
PostgreSQL; see [regex match](../../predicates/regex-match.md) for how that dialect
differs from POSIX.

# Examples:
Keep only the digits:
  π REGEXP_REPLACE(phone, '[^0-9]', '', 'g') → digits (Users)

Swap two words:
  π REGEXP_REPLACE(name, '(\\w+) (\\w+)', '\\2, \\1') → filed (Users)

# Pushdown:
SQL: `regexp_replace(…)` on PostgreSQL for a portable literal pattern, a literal
replacement and literal flags; in the engine otherwise.

# See Also:
[regexp_like](regexp_like.md), [replace](replace.md)
