# Name: INITCAP (capitalise each word)

# Syntax:
INITCAP(<string>)

π INITCAP(name) → name (Users)

# Description:
INITCAP gives each word a capital first letter and lower-cases the rest:
`INITCAP('hELLO wORLD')` is `'Hello World'`.

# Technical Description:
INITCAP(s: STRING) → STRING. A word is a run of letters and digits, so
`o'neil-smith` becomes `O'Neil-Smith`. Case mapping follows Unicode, independent of
locale. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
Tidy names:
  π INITCAP(name) → name (Users)

# Pushdown:
Runs in the engine: case mapping belongs to a backend's collation, as for UCase.

# See Also:
[ucase](ucase.md), [lcase](lcase.md)
