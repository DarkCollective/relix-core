# Name: CHARINDEX (position of a substring, SQL Server's order)

# Syntax:
CHARINDEX(<substring>, <string>)

π email, CHARINDEX('@', email) → at (Users)

# Description:
CHARINDEX is SQL Server's name for finding one string in another, with the string to
find first: `CHARINDEX('@', email)` is the position of the `@` in `email`, counting
from 1, or 0 when it has none.

# Technical Description:
CHARINDEX(sub: STRING, s: STRING) → NUMBER, which is STRPOS(s, sub): the 1-based
position of the first occurrence, 0 for none, 1 for an empty sub, exact and
case-sensitive. A NULL in either argument returns NULL. PURE, DETERMINISTIC.

# Examples:
Rows whose code holds a dash:
  σ CHARINDEX('-', code) > 0 (Products)

# Limitations:
The optional starting position SQL Server's CHARINDEX takes is not offered; InStr
takes one.

# See Also:
[strpos](strpos.md), [instr](instr.md)
