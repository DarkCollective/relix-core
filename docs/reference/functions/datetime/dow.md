# Name: DOW (day of week, Sunday 0)

# Syntax:
DOW(<date-or-timestamp>)

γ DOW(order_date) → weekday, COUNT(*) → orders (Orders)

# Description:
DOW gives the day of the week as a number, Sunday 0 through Saturday 6, the way
PostgreSQL's `EXTRACT(DOW …)` counts. Use it to compare weekdays with weekends or
to see which day of the week is busiest.

# Technical Description:
DOW(d) → NUMBER, 0 (Sunday) to 6 (Saturday). It is `EXTRACT(DOW FROM d)`. Accepts a DATE or TIMESTAMP (a TIMESTAMP is read at UTC); other types are an
evaluation error. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
Weekend orders:
  σ DOW(order_date) = 0 ∨ DOW(order_date) = 6 (Orders)

# Pushdown:
SQL: `EXTRACT(DOW FROM <col>)` on PostgreSQL and DuckDB, and `DAYOFWEEK(<col>) - 1`
on MySQL, whose count starts at Sunday = 1. SQL Server's weekday depends on the
session's `DATEFIRST`, so there it runs in the engine, as on SQLite and Db2.
MongoDB: `$dayOfWeek` less one.

# Limitations:
Sunday is 0. For a week that starts on Monday, ISODOW counts Monday 1 to Sunday 7.
MySQL's `DAYOFWEEK` counts Sunday as 1, so it is not offered under that name.

# See Also:
[isodow](isodow.md), [doy](doy.md), [week](week.md), [EXTRACT](../../language/extract.md)
