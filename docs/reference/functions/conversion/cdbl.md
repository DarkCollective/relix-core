# Name: CDbl (convert to number)

# Syntax:
CDbl(<value>)

π id, CDbl(amount_text) → amount (RawImport)

# Description:
CDbl converts a value to a number, keeping any fractional part. A numeric string
is parsed, a number passes through, and a boolean becomes 1 or 0. Use it to turn
text-form decimals from imports into real numbers you can compute with.

# Technical Description:
CDbl(value) → NUMBER. NumberValue → itself; StringValue → parsed numerically;
BooleanValue → 1/0. NULL, nested, and temporal values are evaluation errors. PURE,
DETERMINISTIC.

# Examples:
Parse a decimal text column:
  π id, CDbl(amount_text) → amount (RawImport)

Sum a parsed numeric column:
  γ SUM(CDbl(raw_amount)) → total (Imported)

# Limitations:
Errors on NULL, nested, or temporal values, and on non-numeric strings. Unlike
CInt it does not round to a whole number.

# Alternatives:
CInt rounds to an integer; CStr converts toward text. `CAST(x AS NUMBER)` is CDbl
that returns NULL for a NULL.

# SQL names:
SQL's `TO_NUMBER(x)`, and a spreadsheet's `VALUE(x)`, are `CAST(x AS NUMBER)`, which is
this function. There is no TO_NUMBER function.

# See Also:
[CAST](../../language/cast.md), [cint](cint.md), [cstr](cstr.md), [isnumeric](../typecheck/isnumeric.md)
