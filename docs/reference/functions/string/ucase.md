# Name: UCase (upper-case)

# Syntax:
UCase(<string>)

π UCase(code) → code_upper (Products)

# Description:
UCase converts a string to UPPER CASE. Use it to normalise text for display or to
make comparisons case-insensitive by upper-casing both sides.

# Technical Description:
UCase(s: STRING) → STRING. Upper-cases s (Locale.ROOT). NULL input returns NULL.
PURE, DETERMINISTIC, and IDEMPOTENT — applying it twice is the same as once, and
the optimizer collapses nested calls.

# Examples:
Normalise country codes:
  π UCase(country) → country (Customers)

Case-insensitive match:
  σ UCase(name) = "ACME" (Suppliers)

# Limitations:
STRING only; NULL in → NULL out. Case folding follows Locale.ROOT (locale-neutral).

# See Also:
[lcase](lcase.md), [trim](trim.md), [replace](replace.md)
