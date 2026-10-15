# Name: Regular-expression match (~ / MATCHES)

# Syntax:
<string> ~ <pattern>             -- matches
<string> ~* <pattern>            -- matches, ignoring case
<string> !~ <pattern>            -- does not match
<string> !~* <pattern>           -- does not match, ignoring case
<string> MATCHES <pattern>
<string> NOT MATCHES <pattern>

σ code ~ '^[A-Z]{2}[0-9]+$' (Products)

# Description:
A regular-expression match is the more powerful cousin of LIKE: it asks whether a
pattern occurs anywhere in a string, where the pattern can say "one or more digits",
"either of these words" or "at the start". `~` is PostgreSQL's spelling and
`MATCHES` a word for the same test; `~*` ignores case, and `!~` and `NOT MATCHES`
ask the opposite.

# Technical Description:
`s ~ p` is `REGEXP_LIKE(s, p) = TRUE`, `s ~* p` passes the flag `i`, and the
negations compare with FALSE, so a NULL string or pattern makes the test UNKNOWN
both ways, as it does in SQL. The match may be anywhere: anchor it with `^` to
match at the start. `MATCHES` is matched by its text and is not reserved.

**A backslash is written twice.** A Relix string reads `\` as an escape, so the
pattern `\d+` is the literal `'\\d+'`, and a pattern of `\.` is `'\\.'`. A bracket
needs none: `'[0-9]+'` and `'[.]'` say the same.

**The dialect is Java's** (`java.util.regex`), with one setting: `.` matches a
newline, as in PostgreSQL. Where it differs from POSIX and PostgreSQL:

- `\d`, `\w` and `\s` are ASCII digits, word characters and whitespace.
- `$` matches at the end or before a final line break; PostgreSQL's only at the end.
- POSIX classes are written `\p{Alpha}`, not `[[:alpha:]]`, and `[` and `&&` inside a
  bracket are a union and an intersection.
- Look-ahead and look-behind, `(?=…)`, `(?<=…)`, and inline flags such as `(?i)` are
  available, as are lazy quantifiers `*?` and `+?`.

A pattern is sent to a database only when it is a literal written in the part of the
syntax every dialect reads alike: literal characters, `.`, `^`, quantifiers, groups,
alternation, bracketed ranges and escaped metacharacters. It then becomes `~` or `~*`
on PostgreSQL and `REGEXP_LIKE(s, p, 'cn')` on MySQL, case-sensitive whatever the
column's collation. Anything else runs in the engine.

# Examples:
Product codes of two letters and digits:
  σ code ~ '^[A-Z]{2}[0-9]+$' (Products)

Names starting "mc" in any case:
  σ name ~* '^mc' (Users)

Addresses that are not on example.com:
  σ email NOT MATCHES '@example[.]com' (Users)

# Worked Example:
Order references come in several shapes, and only some follow the current scheme of
three letters, a dash and digits:

```relix
Refs := [
| ref        |
|------------|
| ABC-1042   |
| abc-77     |
| X-9        |
| DEF-0001-B |
];

query { σ ref ~ '^[A-Z]{3}-[0-9]+' (Refs) };
```

```
 ref
 ──────────
 ABC-1042
 DEF-0001-B
(2 rows)
```

The match is case-sensitive, so `abc-77` is not one, and the pattern is not anchored
at the end, so `DEF-0001-B` is. Ignoring case with `~*` and anchoring the end with
`$` turns both round:

```relix
query { σ ref ~* '^[A-Z]{3}-[0-9]+$' (Refs) };
```

```
 ref
 ────────
 ABC-1042
 abc-77
(2 rows)
```

A regular expression needs a pattern to match against:

```relix-invalid
query { σ ref ~ (Refs) };
```

# Alternatives:
LIKE, with `%` and `_`, for a fixed shape; REGEXP_REPLACE, REGEXP_SUBSTR and
REGEXP_COUNT to edit, extract or count what a pattern matches.

# See Also:
[like](like.md), [regexp_like](../functions/string/regexp_like.md), [regexp_replace](../functions/string/regexp_replace.md), [regexp_substr](../functions/string/regexp_substr.md), [regexp_count](../functions/string/regexp_count.md)
