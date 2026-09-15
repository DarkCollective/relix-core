# Name: LCase (lower-case)

# Syntax:
LCase(<string>)

π LCase(email) → email (Users)

# Description:
LCase converts a string to lower case. Commonly used to normalise emails,
usernames, or tags so that comparisons ignore capitalisation.

# Technical Description:
LCase(s: STRING) → STRING. Lower-cases s (Locale.ROOT). NULL input returns NULL.
PURE, DETERMINISTIC, and IDEMPOTENT.

# Examples:
Normalise emails to lower case:
  π LCase(email) → email (Users)

Case-insensitive filter:
  σ LCase(tag) = "urgent" (Tickets)

# Limitations:
STRING only; NULL in → NULL out. Case folding follows Locale.ROOT.

# See Also:
[ucase](ucase.md), [trim](trim.md), [replace](replace.md)
