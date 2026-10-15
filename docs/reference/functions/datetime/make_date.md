# Name: MAKE_DATE (a date from its parts)

# Syntax:
MAKE_DATE(<year>, <month>, <day>)
DATEFROMPARTS(<year>, <month>, <day>)

π order_id, MAKE_DATE(YEAR(order_date), MONTH(order_date), 1) → month_start (Orders)

# Description:
MAKE_DATE builds a date from a year, a month and a day: `MAKE_DATE(2024, 2, 29)` is
29 February 2024. DATEFROMPARTS is the same function by SQL Server's name.

# Technical Description:
MAKE_DATE(year: NUMBER, month: NUMBER, day: NUMBER) → DATE. Each part is a whole
number. A date the calendar does not have, such as 29 February 2023, or a year before
1, is an error naming it. A NULL part returns NULL. PURE, DETERMINISTIC.

# Examples:
The first of each order's month:
  π order_id, MAKE_DATE(YEAR(order_date), MONTH(order_date), 1) → month_start (Orders)

# Pushdown:
SQL: `make_date(…)` on PostgreSQL and DuckDB, `DATEFROMPARTS(…)` on SQL Server; in the
engine elsewhere.

# SQL names:
`DATEFROMPARTS` is the same function by SQL Server's name.

# See Also:
[last_day](last_day.md), [to_date](to_date.md), [date-literal](../../literals/date-literal.md)
