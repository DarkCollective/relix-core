# Name: WEEK (ISO week number)

# Syntax:
WEEK(<date-or-timestamp>)

γ ISOYEAR(order_date) → yr, WEEK(order_date) → wk, SUM(amount) → revenue (Orders)

# Description:
WEEK gives the ISO 8601 week of the year, 1 to 53. Weeks start on Monday, and week 1
is the week that holds the year's first Thursday, so the first days of January can
belong to the last week of the year before, and the last days of December to week 1
of the next.

# Technical Description:
WEEK(d) → NUMBER, 1 to 53. It is `EXTRACT(WEEK FROM d)`. Group by ISOYEAR with it,
not YEAR: 1 January 2021 is week 53 of ISO year 2020. Accepts a DATE or TIMESTAMP (a TIMESTAMP is read at UTC); other types are an
evaluation error. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
Weekly revenue, keyed by the year the week belongs to:
  γ ISOYEAR(order_date) → yr, WEEK(order_date) → wk, SUM(amount) → revenue (Orders)

# Pushdown:
SQL: `EXTRACT(WEEK FROM <col>)` on PostgreSQL and DuckDB, and `DATEPART(iso_week, …)`
at UTC on SQL Server. MySQL's `WEEK` has modes of its own, so there it runs in the
engine. MongoDB: `$isoWeek`.

# Limitations:
Only the ISO week. A week numbered from 1 January or from a Sunday is not offered.

# See Also:
[isoyear](isoyear.md), [isodow](isodow.md), [doy](doy.md), [EXTRACT](../../language/extract.md)
