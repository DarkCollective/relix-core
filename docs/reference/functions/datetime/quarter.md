# Name: QUARTER (quarter of the year)

# Syntax:
QUARTER(<date-or-timestamp>)

γ YEAR(order_date) → yr, QUARTER(order_date) → q, SUM(amount) → revenue (Orders)

# Description:
QUARTER gives the quarter of the year a date falls in: 1 for January to March, 2 for
April to June, 3 for July to September and 4 for October to December.

# Technical Description:
QUARTER(d) → NUMBER, 1 to 4. It is `EXTRACT(QUARTER FROM d)`. Accepts a DATE or TIMESTAMP (a TIMESTAMP is read at UTC); other types are an
evaluation error. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
Quarterly revenue:
  γ YEAR(order_date) → yr, QUARTER(order_date) → q, SUM(amount) → revenue (Orders)

# Pushdown:
SQL: `EXTRACT(QUARTER FROM <col>)` on PostgreSQL and DuckDB, `QUARTER` on MySQL and
`DATEPART(quarter, …)` at UTC on SQL Server; in the engine elsewhere.

# Limitations:
Calendar quarters only; a fiscal year that starts in another month is not offered.

# See Also:
[month](month.md), [year](year.md), [EXTRACT](../../language/extract.md)
