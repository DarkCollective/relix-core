# Name: Trim (strip surrounding whitespace)

# Syntax:
Trim(<string>)

π Trim(name) → name (Users)

# Description:
Trim removes whitespace from both ends of a string. It is the standard cleanup for
imported or user-entered data where stray leading/trailing spaces creep in.

# Technical Description:
Trim(s: STRING) → STRING. Strips leading and trailing whitespace (Java strip(),
Unicode-aware). NULL input returns NULL. PURE, DETERMINISTIC, IDEMPOTENT.

# Examples:
Clean up a name column:
  π Trim(name) → name (Customers)

Match ignoring stray spaces:
  σ Trim(code) = "A100" (Parts)

# Limitations:
STRING only; NULL in → NULL out. Removes whitespace only — not other characters.

# Alternatives:
LTrim / RTrim to strip only one side. Replace to remove specific characters.

# See Also:
[ltrim](ltrim.md), [rtrim](rtrim.md), [replace](replace.md)
