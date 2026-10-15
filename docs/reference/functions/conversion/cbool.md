# Name: CBool (convert to boolean)

# Syntax:
CBool(<value>)

π id, CBool(active_flag) → active (Imports)

# Description:
CBool reads a value as TRUE or FALSE: a number is TRUE unless it is zero, and text is
one of the words SQL uses for the two, such as `yes`, `no`, `true`, `false`, `1` and
`0`. Use it to turn flags imported as text or numbers into booleans you can test.
`CAST(x AS BOOLEAN)` is CBool with a NULL passed through.

# Technical Description:
CBool(value) → BOOLEAN. A BOOLEAN is itself; a NUMBER is FALSE for zero and TRUE
otherwise; a STRING, trimmed and in any letter case, is TRUE for `t`, `true`, `y`,
`yes`, `on` and `1` and FALSE for `f`, `false`, `n`, `no`, `off` and `0`, as
PostgreSQL reads them. Any other text is an evaluation error naming it, as are NULL,
a date or time and a nested value. PURE, DETERMINISTIC.

# Examples:
A flag imported as text:
  σ CBool(subscribed) = TRUE (Imports)

A count read as "any at all":
  π id, CBool(open_tickets) → has_open (Accounts)

# Limitations:
NULL is an error, as it is for CStr, CInt and CDbl; `CAST(x AS BOOLEAN)` returns NULL
for it instead.

# Alternatives:
A comparison, `open_tickets > 0`, says the same as CBool of a count, and IsNumeric
asks whether text holds a number.

# See Also:
[cstr](cstr.md), [cdbl](cdbl.md), [cint](cint.md), [CAST](../../language/cast.md)
