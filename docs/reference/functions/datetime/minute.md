# Name: MINUTE (extract minute)

# Syntax:
MINUTE(<time-or-timestamp>)

π id, MINUTE(start_time) → min (Slots)

# Description:
MINUTE pulls the minutes part (0–59) out of a time or timestamp. Useful for
fine-grained time bucketing or schedule alignment.

# Technical Description:
MINUTE(t) → NUMBER (0–59). Accepts a TIME or TIMESTAMP (read at UTC); other types
are an evaluation error. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
Minutes part of a slot start:
  π id, MINUTE(start_time) → min (Slots)

On-the-hour events:
  σ MINUTE(occurred_at) = 0 (Events)

# Pushdown:
SQL: folds to `EXTRACT(MINUTE FROM <col>)` on every dialect but SQLite,
which has no date type to extract from. On SQL Server, which has no
`EXTRACT`, it is `DATEPART(MINUTE, SWITCHOFFSET(<col>, '+00:00'))` — the value
switched to UTC first, so a `DATETIMEOFFSET` is read at UTC as the engine reads it.
MongoDB: folds to `{"$minute": "$<col>"}` inside a `$project` stage (requires alias).

# Limitations:
Argument must be a TIME or TIMESTAMP. Returns 0–59. TIMESTAMP at UTC. NULL in →
NULL out.

# See Also:
[hour](hour.md), [second](second.md), [date_trunc](date_trunc.md)
