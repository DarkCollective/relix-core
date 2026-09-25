# Name: YEAR (extract year)

# Syntax:
YEAR(<date-or-timestamp>)

γ YEAR(order_date) → yr, SUM(amount) → revenue (Orders)

# Description:
YEAR pulls the year number out of a date or timestamp. Use it to group or filter
by year — annual totals, "everything from 2026", year-over-year comparisons.

# Technical Description:
YEAR(d) → NUMBER. Accepts a DATE or TIMESTAMP (a TIMESTAMP is read at UTC); other
types are an evaluation error. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
Annual revenue:
  γ YEAR(order_date) → yr, SUM(amount) → revenue (Orders)

Filter to one year:
  σ YEAR(created_at) = 2026 (Accounts)

# Pushdown:
SQL: folds to `EXTRACT(YEAR FROM <col>)` on every dialect but SQLite, which has
no date type to extract from. On SQL Server, which has no
`EXTRACT`, it is `DATEPART(YEAR, SWITCHOFFSET(<col>, '+00:00'))` — the value
switched to UTC first, so a `DATETIMEOFFSET` is read at UTC as the engine reads it.
MongoDB: folds to `{"$year": "$<col>"}` inside a `$project` pipeline stage when the
argument is an attribute and an alias is supplied (e.g. `π YEAR(at) → yr (Events)`).

# Limitations:
Argument must be a DATE or TIMESTAMP. TIMESTAMP is interpreted at UTC. NULL in →
NULL out.

# See Also:
[month](month.md), [day](day.md), [date_trunc](date_trunc.md), [hour](hour.md)
