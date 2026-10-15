# Name: SUBSTRING (part of a string, as SQL counts it)

# Syntax:
SUBSTRING(<string>, <start> [, <length>])
SUBSTRING(<string> FROM <start> [FOR <length>])
SUBSTR(<string>, <start> [, <length>])

π SUBSTRING(code FROM 1 FOR 3) → prefix (Products)

# Description:
SUBSTRING takes part of a string: the characters from a starting position, the first
being 1, for a given number of them, or to the end when no length is given. It is
PostgreSQL's function, in both of its spellings, and SUBSTR is the same function.

# Technical Description:
SUBSTRING(s: STRING, start: NUMBER [, length: NUMBER]) → STRING. Positions count
characters (code points) from 1. A start before 1 still counts towards the length,
so `SUBSTRING('hello', 0, 3)` is `'he'` and `SUBSTRING('hello', -5, 3)` is `''`; a
start past the end gives `''`. A negative length is an evaluation error. A NULL in
any argument returns NULL. `SUBSTRING(s FROM a FOR n)` is `SUBSTRING(s, a, n)`, and
`SUBSTRING(s FOR n)` starts at 1. PURE, DETERMINISTIC.

# Examples:
The first three characters:
  π SUBSTRING(code FROM 1 FOR 3) → prefix (Products)

Everything after the second character:
  π SUBSTR(code, 3) → rest (Products)

# Pushdown:
SQL: `SUBSTRING(…)` on PostgreSQL. MySQL reads a start of 0 as an empty result and a
negative start from the end, so there and elsewhere it runs in the engine.

# Alternatives:
Mid, which refuses a start before 1, and Left and Right for either end.

# See Also:
[mid](mid.md), [left](left.md), [right](right.md), [len](len.md)
