# Name: CInt (convert to integer)

# Syntax:
CInt(<value>)

π id, CInt(score_text) → score (RawImport)

# Description:
CInt converts a value to a whole number. A numeric value is rounded to the nearest
integer, a numeric string is parsed, and a boolean becomes 1 (true) or 0 (false).
Use it to coerce text or fractional data into integers.

# Technical Description:
CInt(value) → NUMBER. NumberValue → rounded to 0 places (HALF_UP); StringValue →
parsed numerically; BooleanValue → 1/0. NULL, nested (struct/array), and temporal
values are evaluation errors. PURE, DETERMINISTIC.

# Examples:
Parse a numeric text column to an integer:
  π id, CInt(score_text) → score (RawImport)

Round a fractional value to a whole number:
  π id, CInt(rating) → rating_int (Reviews)

Boolean to 0/1:
  π id, CInt(active) → active_flag (Accounts)

# Limitations:
Errors on NULL, nested, or temporal values, and on non-numeric strings. Rounds (it
does not truncate) — use Fix/Int for directional rounding.

# Alternatives:
CDbl keeps the fractional part; Round/Int/Fix for explicit rounding behaviour.

# SQL names:
SQL's `CAST(x AS INTEGER)` is `CInt(x)`, which rounds half away from zero as
PostgreSQL's does; CAST has no INTEGER type here. SQL's `TO_NUMBER(x)` keeps the
fractional part, and is `CAST(x AS NUMBER)`, which is CDbl.

# See Also:
[CAST](../../language/cast.md), [cdbl](cdbl.md), [cstr](cstr.md), [round](../math/round.md), [int](../math/int.md), [fix](../math/fix.md), [isnumeric](../typecheck/isnumeric.md)
