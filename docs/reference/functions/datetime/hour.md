# Name: HOUR (extract hour)

# Syntax:
HOUR(<time-or-timestamp>)

γ HOUR(occurred_at) → hr, COUNT(id) → hits (Events)

# Description:
HOUR pulls the hour-of-day (0–23) out of a time or timestamp. Use it to bucket
activity by hour — traffic by time of day, peak-hour analysis.

# Technical Description:
HOUR(t) → NUMBER (0–23). Accepts a TIME or TIMESTAMP (a TIMESTAMP is read at UTC);
other types are an evaluation error. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
Hits per hour of day:
  γ HOUR(occurred_at) → hr, COUNT(id) → hits (Events)

Overnight records:
  σ HOUR(logged_at) < 6 (Logs)

# Pushdown:
SQL: folds to `EXTRACT(HOUR FROM <col>)` on every dialect but SQLite,
which has no date type to extract from. On SQL Server, which has no
`EXTRACT`, it is `DATEPART(HOUR, SWITCHOFFSET(<col>, '+00:00'))` — the value
switched to UTC first, so a `DATETIMEOFFSET` is read at UTC as the engine reads it.
MongoDB: folds to `{"$hour": "$<col>"}` inside a `$project` stage (requires alias).

# Limitations:
Argument must be a TIME or TIMESTAMP. Returns 0–23. TIMESTAMP at UTC. NULL in →
NULL out.

# See Also:
[minute](minute.md), [second](second.md), [date_trunc](date_trunc.md), [year](year.md)
