# Name: STRPOS (position of a substring)

# Syntax:
STRPOS(<string>, <substring>)
POSITION(<substring> IN <string>)

π email, STRPOS(email, '@') → at (Users)

# Description:
STRPOS finds where one string first occurs in another: the position of its first
character, counting from 1, or 0 when it does not occur. POSITION is SQL's spelling
of the same question, with the arguments the other way round and IN between them.

# Technical Description:
STRPOS(s: STRING, sub: STRING) → NUMBER. The 1-based position, in characters, of
the first occurrence of sub in s; 0 when there is none, and 1 for an empty sub.
Matching is exact, case included. A NULL in either argument returns NULL.
`POSITION(sub IN s)` is `STRPOS(s, sub)`. PURE, DETERMINISTIC.

# Examples:
Where the domain starts:
  π email, STRPOS(email, '@') + 1 → domain_start (Users)

The same, in SQL's other spelling:
  π email, POSITION('@' IN email) → at (Users)

# Pushdown:
Runs in the engine: a backend's search follows the column's collation, which may
ignore case where this does not.

# Alternatives:
InStr, which takes the same arguments and a starting position, and CHARINDEX,
SQL Server's spelling.

# See Also:
[instr](instr.md), [charindex](charindex.md), [substring](substring.md)
