# Name: CONCAT (join values as text, skipping NULLs)

# Syntax:
CONCAT(<value>, <value>, ...)

π CONCAT(first_name, ' ', name) → full (Users)

# Description:
CONCAT joins any number of values into one string, in order. A missing value adds
nothing rather than making the whole result missing, which is where it differs from
`+` and `||`: `CONCAT('a', NULL, 'b')` is `'ab'`, where `'a' + NULL + 'b'` is NULL.
A number or a date is written as it displays.

# Technical Description:
CONCAT(value, …) → STRING. One or more arguments of any type; each non-NULL argument
contributes its display text, and NULLs contribute nothing, so the result is never
NULL. This is PostgreSQL's and SQL Server's CONCAT; MySQL's returns NULL when any
argument is NULL. PURE, DETERMINISTIC.

# Examples:
A label from a number and text:
  π CONCAT('#', order_id, ' ', status) → label (Orders)

A full name that survives a missing first name:
  π CONCAT(first_name, ' ', name) → full (Users)

# Pushdown:
Runs in the engine: a backend writes numbers and dates as text in its own way.

# Alternatives:
`+` or `||` between two strings, which is NULL when either is.

# See Also:
[arithmetic](../../language/arithmetic.md), [cstr](../conversion/cstr.md), [coalesce](../conditional/coalesce.md)
