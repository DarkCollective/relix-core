# Name: LTrim (strip leading whitespace)

# Syntax:
LTrim(<string>)

π LTrim(line) → line (RawLines)

# Description:
LTrim removes whitespace from the start (left side) of a string, leaving any
trailing spaces intact. Useful when only leading indentation needs removing.

# Technical Description:
LTrim(s: STRING) → STRING. Strips leading whitespace (Java stripLeading()). NULL
input returns NULL. PURE, DETERMINISTIC, IDEMPOTENT.

# Examples:
Remove leading indentation:
  π LTrim(line) → line (RawLines)

# Limitations:
STRING only; NULL in → NULL out. Leading whitespace only.

# Alternatives:
Trim for both ends; RTrim for the trailing side.

# See Also:
[trim](trim.md), [rtrim](rtrim.md)
