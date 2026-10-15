# Name: ISODOW (ISO day of week, Monday 1)

# Syntax:
ISODOW(<date-or-timestamp>)

σ ISODOW(order_date) ≤ 5 (Orders)

# Description:
ISODOW gives the day of the week as ISO 8601 numbers it, Monday 1 through Sunday 7.
Use it where the week starts on Monday, as it does in most of the world.

# Technical Description:
ISODOW(d) → NUMBER, 1 (Monday) to 7 (Sunday). It is `EXTRACT(ISODOW FROM d)`. Accepts a DATE or TIMESTAMP (a TIMESTAMP is read at UTC); other types are an
evaluation error. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
Weekdays only:
  σ ISODOW(order_date) ≤ 5 (Orders)

# Pushdown:
SQL: `EXTRACT(ISODOW FROM <col>)` on PostgreSQL and DuckDB, `WEEKDAY(<col>) + 1` on
MySQL; in the engine elsewhere. MongoDB: `$isoDayOfWeek`.

# Limitations:
The same day as DOW except that Sunday is 7 rather than 0.

# See Also:
[dow](dow.md), [week](week.md), [isoyear](isoyear.md), [EXTRACT](../../language/extract.md)
