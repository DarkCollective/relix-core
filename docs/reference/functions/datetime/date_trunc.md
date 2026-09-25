# Name: DATE_TRUNC (truncate timestamp to a unit)

# Syntax:
DATE_TRUNC(<unit>, <timestamp>)

γ DATE_TRUNC('day', occurred_at) → day, COUNT(id) → hits (Events)

# Description:
DATE_TRUNC rounds a timestamp DOWN to the start of a chosen unit — the start of
the hour, day, month, or year. It is the time-series workhorse: truncate every
event to its day (or hour) and group, and you get a clean per-day (or per-hour)
series.

# Technical Description:
DATE_TRUNC(unit: STRING, ts: TIMESTAMP) → TIMESTAMP. Truncates ts (at UTC) to the
start of `unit` ∈ {year, month, day, hour, minute, second}; an unknown unit or a
non-TIMESTAMP value is an evaluation error. NULL ts returns NULL. PURE,
DETERMINISTIC.

# Examples:
Daily event counts:
  γ DATE_TRUNC('day', occurred_at) → day, COUNT(id) → hits (Events)

Hourly revenue series:
  γ DATE_TRUNC('hour', sold_at) → hr, SUM(amount) → revenue (Sales)

Monthly buckets:
  π id, DATE_TRUNC('month', created_at) → month (Accounts)

# Limitations:
The value must be a TIMESTAMP (not DATE/TIME). Units are year/month/day/hour/
minute/second only. Truncation is at UTC. NULL in → NULL out.

# Pushdown:
SQL: dialect-dependent, and the one built-in whose spelling differs in shape
rather than in name.
  - **Postgres** and **DuckDB**: `date_trunc('<unit>', <col>)` — standard Postgres
    form, which DuckDB shares.
  - **MySQL**: `CAST(DATE_FORMAT(<col>, '<pattern>') AS DATETIME)`, with one
    pattern per unit — `'%Y-01-01 00:00:00'` for a year, `'%Y-%m-%d %H:00:00'`
    for an hour, and so on. MySQL and MariaDB have no `DATE_TRUNC` function;
    `DATE_FORMAT` reaches the same value by writing out the parts to keep and
    zeroing the rest, and the cast is what makes the result a TIMESTAMP rather
    than the string `DATE_FORMAT` returns. The unit must be a literal: a call
    whose unit is a column or an expression is evaluated in-engine, since the
    unit is what chooses the pattern.
  - **GENERIC**: not pushed; the call is evaluated in-engine, and everything
    above the operator containing it stays in the engine with it. The generic
    dialect is an unidentified backend, so it is offered no spelling that has
    not been confirmed against it.

MongoDB: folds to `{"$dateTrunc": {"date": <tsExpr>, "unit": "<unit>"}}` inside a
`$project` stage when the timestamp argument is an attribute and an alias is
supplied (e.g. `π DATE_TRUNC('hour', at) → hr (Events)`).

# Alternatives:
YEAR/MONTH/DAY/HOUR extract a single numeric component instead of a bucket
timestamp.

# See Also:
[year](year.md), [month](month.md), [day](day.md), [hour](hour.md), [now](now.md), [timestamp-literal](../../literals/timestamp-literal.md)

# Notes:
DATE_TRUNC is the building block for time-series bucketing and sessionization.
