# Name: DOY (day of year)

# Syntax:
DOY(<date-or-timestamp>)

π order_id, DOY(order_date) → day_of_year (Orders)

# Description:
DOY gives the day of the year, 1 for 1 January through 365, or 366 in a leap year.

# Technical Description:
DOY(d) → NUMBER, 1 to 366. It is `EXTRACT(DOY FROM d)`. Accepts a DATE or TIMESTAMP (a TIMESTAMP is read at UTC); other types are an
evaluation error. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
The first hundred days of each year:
  σ DOY(order_date) ≤ 100 (Orders)

# Pushdown:
SQL: `EXTRACT(DOY FROM <col>)` on PostgreSQL and DuckDB, `DAYOFYEAR` on MySQL and
`DATEPART(dayofyear, …)` at UTC on SQL Server. MongoDB: `$dayOfYear`.

# Limitations:
Counts calendar days, so the same day of year falls on a different date after 29
February in a leap year.

# See Also:
[week](week.md), [quarter](quarter.md), [EXTRACT](../../language/extract.md)
