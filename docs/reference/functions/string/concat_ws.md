# Name: CONCAT_WS (join with a separator, skipping NULLs)

# Syntax:
CONCAT_WS(<separator>, <value>, ...)

π CONCAT_WS(', ', city, region) → place (Customers)

# Description:
CONCAT_WS joins values with a separator between each pair: `CONCAT_WS('-', 'a',
'b')` is `'a-b'`. A missing value is left out together with its separator, so
optional parts do not leave doubled separators behind.

# Technical Description:
CONCAT_WS(separator: STRING, value, …) → STRING. One or more values of any type,
each written as it displays; NULL values are skipped. A NULL separator gives NULL.
PURE, DETERMINISTIC.

# Examples:
An address from optional parts:
  π CONCAT_WS(', ', city, region) → place (Customers)

# Pushdown:
Runs in the engine: a backend writes numbers and dates as text its own way.

# See Also:
[concat](concat.md), [arithmetic](../../language/arithmetic.md)
