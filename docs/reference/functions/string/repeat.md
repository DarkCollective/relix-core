# Name: REPEAT (a string written n times)

# Syntax:
REPEAT(<string>, <count>)
REPLICATE(<string>, <count>)

π order_id, REPEAT('*', qty) → bar (Orders)

# Description:
REPEAT writes a string a number of times over: `REPEAT('ab', 3)` is `'ababab'`.
REPLICATE is the same function by SQL Server's name.

# Technical Description:
REPEAT(s: STRING, count: NUMBER) → STRING. A count of zero or less gives `''`. A
result longer than 100,000,000 characters is refused. A NULL in either argument
returns NULL. PURE, DETERMINISTIC.

# Examples:
A bar of stars:
  π order_id, REPEAT('*', qty) → bar (Orders)

# Pushdown:
SQL: `REPEAT(…)` on PostgreSQL, MySQL and DuckDB. SQL Server's REPLICATE returns
NULL for a negative count, so there and elsewhere it runs in the engine.

# See Also:
[lpad](lpad.md), [rpad](rpad.md)
