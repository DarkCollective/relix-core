# Name: SECOND (extract second)

# Syntax:
SECOND(<time-or-timestamp>)

π id, SECOND(captured_at) → sec (Samples)

# Description:
SECOND pulls the seconds part (0–59) out of a time or timestamp — the finest
whole-second component.

# Technical Description:
SECOND(t) → NUMBER (0–59). Accepts a TIME or TIMESTAMP (read at UTC); other types
are an evaluation error. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
Readings captured exactly on the minute (a sign of a scheduled, not sampled, feed):
  σ SECOND(captured_at) = 0 (Samples)

Spot a burst — how many events landed in each second of a minute:
  γ SECOND(occurred_at) → sec, COUNT(id) → hits (Events)

# Pushdown:
SQL: folds to `EXTRACT(SECOND FROM <col>)` on every dialect but SQLite,
which has no date type to extract from.
MongoDB: folds to `{"$second": "$<col>"}` inside a `$project` stage (requires alias).

# Limitations:
Argument must be a TIME or TIMESTAMP. Returns whole seconds 0–59 (no fractional
part). TIMESTAMP at UTC. NULL in → NULL out.

# See Also:
[hour](hour.md), [minute](minute.md), [date_trunc](date_trunc.md)
