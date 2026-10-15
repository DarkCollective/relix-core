# Name: FORMAT (a number with grouped digits)

# Syntax:
FORMAT(<number>, <places>)

π order_id, FORMAT(amount, 2) → shown (Orders)

# Description:
FORMAT writes a number with a comma between each group of three digits and a fixed
number of decimal places, as MySQL's FORMAT does: `FORMAT(1234567.891, 2)` is
`'1,234,567.89'`.

# Technical Description:
FORMAT(x: NUMBER, places: NUMBER) → STRING. The value is rounded half away from zero
to `places` decimals, none for a negative count, and grouped by threes with a comma,
a point before the decimals. A NULL argument returns NULL. PURE, DETERMINISTIC.

# Examples:
Amounts for a report:
  π order_id, FORMAT(amount, 2) → shown (Orders)

# Pushdown:
Runs in the engine.

# Limitations:
MySQL's form only, with no locale argument. SQL Server's FORMAT takes a .NET pattern
instead; write TO_CHAR for a pattern.

# See Also:
[to_char](to_char.md), [round](../math/round.md), [cstr](cstr.md)
