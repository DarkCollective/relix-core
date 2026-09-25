# Name: TIMESTAMP literal

# Syntax:
TIMESTAMP '<ISO-8601 date-time, optional offset>'

TIMESTAMP '2026-06-15T13:40:00Z'
TIMESTAMP '2026-06-15T14:40:00+01:00'    -- normalised to UTC

# Description:
A TIMESTAMP literal is a precise moment in time — a date and a time together, as
an instant. It is the type for event times, log entries, and "when did this
happen" columns. Write it as the keyword TIMESTAMP followed by an ISO-8601
date-time in quotes; an offset is converted to UTC, and an offsetless value is
read as UTC.

# Technical Description:
`TIMESTAMP '…'` parses an ISO-8601 date-time into a UTC java.time.Instant at parse
time (an offset is normalised to UTC; an offsetless payload is interpreted as
UTC). A malformed payload is a positioned parse error. It infers as the TIMESTAMP
scalar type and pushes down to SQL (per dialect: a UTC wall-clock for
GENERIC/MYSQL/DB2, a Z-instant for POSTGRES and DUCKDB, a `DATETIME2` of the UTC
wall-clock for SQLSERVER) and to Mongo ($date). SQLite,
which has no date type, is sent none: a predicate holding one runs in the engine. Single- or
double-quoted payloads are accepted.

# Examples:
Events within a time window:
```relix
σ occurred_at >= TIMESTAMP '2026-01-01T00:00:00Z'
  ∧ occurred_at < TIMESTAMP '2026-02-01T00:00:00Z' (Events)
```

Elapsed time between two instants (TIMESTAMP − TIMESTAMP → DURATION):
  π id, (closed - opened) → held (Tickets)

Add a window to an instant (TIMESTAMP ± DURATION → TIMESTAMP):
  π id, (started + DURATION 'PT30M') → expires (Sessions)

# Limitations:
Stored as a UTC instant — there is no separate time-zone field; a local offset is
folded into UTC at parse time. Comparisons must be against another TIMESTAMP.

# Alternatives:
DATE for a calendar day only; TIME for a clock time only; to_timestamp(str) to
parse from a string column.

# See Also:
[date-literal](date-literal.md), [time-literal](time-literal.md), [duration-literal](duration-literal.md), [to_timestamp](../functions/datetime/to_timestamp.md), [now](../functions/datetime/now.md), [date_trunc](../functions/datetime/date_trunc.md), [asof-join](../joins/asof-join.md)

# Notes:
TIMESTAMP − TIMESTAMP gives a DURATION; this pairing powers AS-OF joins and
sessionization over event time.
