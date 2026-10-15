# Name: LAST_DAY (last day of the month)

# Syntax:
LAST_DAY(<date-or-timestamp>)
EOMONTH(<date-or-timestamp>)

π order_id, LAST_DAY(order_date) → month_end (Orders)

# Description:
LAST_DAY gives the last day of the month a date falls in: 29 February for any day of
February 2024, 31 December for any day of December. EOMONTH is the same function by
SQL Server's name.

# Technical Description:
LAST_DAY(d) → DATE. Accepts a DATE or a TIMESTAMP (read at UTC). NULL input returns
NULL. PURE, DETERMINISTIC.

# Examples:
Days left in each order's month:
  π order_id, DATEDIFF(day, order_date, LAST_DAY(order_date)) → days_left (Orders)

# Pushdown:
SQL: `LAST_DAY(…)` on MySQL; in the engine elsewhere.

# SQL names:
`EOMONTH` is the same function by SQL Server's name, in its one-argument form.

# See Also:
[make_date](make_date.md), [datediff](datediff.md), [date_trunc](date_trunc.md)
