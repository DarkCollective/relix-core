# Name: Asc (code of first character)

# Syntax:
Asc(<string>)

π Asc(letter) → code (Letters)     -- "A" → 65

# Description:
Asc returns the numeric character code of the first character of a string — for
example "A" becomes 65. It is the inverse of Chr, useful for sorting by code or
for character-level computation.

# Technical Description:
Asc(s: STRING) → NUMBER. Returns the code point of s's first character. An empty
string is an evaluation error; NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
First character's code:
  π Asc(letter) → code (Letters)

Bucket by leading-letter code:
  σ Asc(name) >= 65 ∧ Asc(name) <= 90 (People)   -- starts A-Z

# Limitations:
Errors on an empty string. STRING argument; NULL in → NULL out. Reads only the
first character.

# Alternatives:
Chr converts the other way (code → character).

# See Also:
[chr](chr.md), [left](left.md), [len](len.md)
