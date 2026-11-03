# Name: REGEXP_REPLACE (replace a regular-expression match)

# Syntax:
REGEXP_REPLACE(<string>, <pattern>, <replacement> [, <flags>])
REGEXP_REPLACE(<string>, <pattern>, <replacement>, <position> [, <occurrence> [, <flags>]])

π REGEXP_REPLACE(phone, '[^0-9]', '', 'g') → digits (Users)
π REGEXP_REPLACE(phone, '[^0-9]', '', 1, 0) → digits (Users)

# Description:
REGEXP_REPLACE replaces what a regular expression matches. The replacement can
repeat what was matched: `\1` to `\9` for a parenthesised group and `\&` for the
whole match.

With no flag saying otherwise, it replaces the **first** match, as PostgreSQL's does.
MySQL and Oracle replace **every** match by default, so their
`REGEXP_REPLACE('a-b-c', '-', '+')` is `'a+b+c'` where this one gives `'a+b-c'`. To
get theirs, write the flag `'g'`, or an occurrence of 0 in the positional form below.
SQL Server's documentation disagrees between versions on its default, and SQLite has
no `REGEXP_REPLACE`.

The **positional form** is MySQL's, Oracle's and SQL Server's: after the replacement
come a `position`, where the search starts, counted from 1, and an `occurrence`, which
match to replace: 0 for every one, the default in this form, or *n* for the *n*th.
`REGEXP_REPLACE('a-b-c', '-', '+', 1, 0)` is `'a+b+c'`, and
`REGEXP_REPLACE('a-b-c', '-', '+', 1, 2)` is `'a-b+c'`.

# Technical Description:
REGEXP_REPLACE(s: STRING, pattern: STRING, replacement: STRING [, flags: STRING]) →
STRING. Flags: `g` for every match, `i` to ignore case, `c` to respect it.

REGEXP_REPLACE(s, pattern, replacement, position: NUMBER [, occurrence: NUMBER [, flags:
STRING]]) → STRING. The fourth argument's type tells the two forms apart: a NUMBER is
a position, a STRING the flags. The search starts at character `position`, and the text
before it is kept as it is. It still sees that text, so `^` means the start of the
string, not the position, and a `\b` there reads the character before it. A position
past the end replaces nothing. `occurrence` counts the matches found from the position:
0, or omitted, replaces every one, and *n* only the *n*th; a string with fewer than *n*
matches comes back unchanged. The flags are `i` and `c`; `g` is refused, since the
occurrence says the same thing. A position below 1 or a negative occurrence is an
error. In the
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
replacement and literal flags; in the engine otherwise. The positional form runs in the
engine: PostgreSQL has it only from version 15, and MySQL's, Oracle's and SQL Server's
regular-expression dialects and replacement syntax differ from this one.

# See Also:
[regexp_like](regexp_like.md), [regexp_substr](regexp_substr.md), [replace](replace.md)
