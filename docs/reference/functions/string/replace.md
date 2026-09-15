# Name: Replace (substitute text)

# Syntax:
Replace(<string>, <find>, <replacement>)

π Replace(phone, "-", "") → digits (Contacts)

# Description:
Replace swaps every occurrence of one piece of text for another. Use it to strip
characters (replace with an empty string), reformat values, or fix known typos in
bulk.

# Technical Description:
Replace(s, find, replacement) → STRING. Replaces all non-overlapping occurrences
of the literal find within s with replacement (Java String.replace — literal, not
regex). NULL string input returns NULL. PURE, DETERMINISTIC.

# Examples:
Strip dashes from phone numbers:
  π Replace(phone, "-", "") → digits (Contacts)

Normalise a separator:
  π Replace(path, "\\", "/") → path (Files)

Redact a token:
  π Replace(note, "SECRET", "[redacted]") → note (Logs)

# Pushdown:
SQL: folds to `REPLACE(<s>, <find>, <replacement>)` on all dialects. It is the one
string built-in that does. SQL's REPLACE searches case-sensitively whatever the
column's collation, which is what the engine does; the rest of the string library
is evaluated in-engine because SQL's same-named function answers differently —
see [pushdown](../../advanced/pushdown.md).

# Limitations:
Matching is literal text, not a regular expression. STRING arguments; a NULL
string returns NULL.

# Alternatives:
Trim/LTrim/RTrim to remove only surrounding whitespace; Mid/Left/Right to extract
rather than substitute.

# See Also:
[trim](trim.md), [mid](mid.md), [instr](instr.md), [ucase](ucase.md), [lcase](lcase.md)
