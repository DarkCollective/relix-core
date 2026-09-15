# Name: RTrim (strip trailing whitespace)

# Syntax:
RTrim(<string>)

π RTrim(line) → line (RawLines)

# Description:
RTrim removes whitespace from the end (right side) of a string, leaving any
leading spaces intact.

# Technical Description:
RTrim(s: STRING) → STRING. Strips trailing whitespace (Java stripTrailing()). NULL
input returns NULL. PURE, DETERMINISTIC, IDEMPOTENT.

# Examples:
Remove trailing spaces from imported cells:
  π RTrim(cell) → cell (Imported)

# Limitations:
STRING only; NULL in → NULL out. Trailing whitespace only.

# Alternatives:
Trim for both ends; LTrim for the leading side.

# See Also:
[trim](trim.md), [ltrim](ltrim.md)
