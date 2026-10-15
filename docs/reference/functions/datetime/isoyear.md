# Name: ISOYEAR (ISO week-numbering year)

# Syntax:
ISOYEAR(<date-or-timestamp>)

γ ISOYEAR(event_time) → yr, WEEK(event_time) → wk, COUNT(*) → n (Events)

# Description:
ISOYEAR gives the year an ISO week belongs to. It is the calendar year except in the
first and last few days of a year, where the week that holds them may belong to the
year before or the year after.

# Technical Description:
ISOYEAR(d) → NUMBER. It is `EXTRACT(ISOYEAR FROM d)`. 30 December 2024 is in week 1 of
ISO year 2025. Accepts a DATE or TIMESTAMP (a TIMESTAMP is read at UTC); other types are an
evaluation error. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
A key for weekly totals that never splits a week across two years:
  γ ISOYEAR(order_date) → yr, WEEK(order_date) → wk, COUNT(*) → orders (Orders)

# Pushdown:
SQL: `EXTRACT(ISOYEAR FROM <col>)` on PostgreSQL and DuckDB; in the engine elsewhere.
MongoDB: `$isoWeekYear`.

# Limitations:
Pair it with WEEK. With MONTH or DAY, use YEAR instead.

# See Also:
[week](week.md), [year](year.md), [EXTRACT](../../language/extract.md)
