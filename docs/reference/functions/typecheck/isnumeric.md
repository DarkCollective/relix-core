# Name: IsNumeric (test if value looks numeric)

# Syntax:
IsNumeric(<value>)

σ IsNumeric(code) = true (RawImport)

# Description:
IsNumeric reports whether a value is a number, or a string that can be read as
one. Use it to validate messy imported data before converting it — skip the rows
whose "amount" isn't actually a number.

# Technical Description:
IsNumeric(value) → BOOLEAN. Returns true for a NumberValue, or for a StringValue
that parses as a BigDecimal; false otherwise (including for nested/temporal/NULL
values). PURE, DETERMINISTIC.

# Examples:
Keep only rows whose code parses as a number:
  σ IsNumeric(code) = true (RawImport)

Guard a conversion — IIf short-circuits, so CDbl never sees the bad rows:
  π id, IIf(IsNumeric(raw), CDbl(raw), 0) → amount (Imported)

Or drop the bad rows entirely rather than defaulting them:
  π id, CDbl(raw) → amount (σ IsNumeric(raw) = true (Imported))

# Limitations:
Tests parseability, not the declared type — a numeric-looking string returns true.
Does not itself convert (pair with CInt/CDbl). An *unguarded* conversion of a
non-numeric value is an error — `CDbl: cannot convert "abc" to a number` — so pair
it with IIf or σ as above.

# Alternatives:
CInt/CDbl to actually convert; to_date/to_timestamp to parse a temporal value.

# See Also:
[isnull](isnull.md), [cint](../conversion/cint.md), [cdbl](../conversion/cdbl.md)
