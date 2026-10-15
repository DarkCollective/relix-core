# Name: EPOCH (seconds since 1970)

# Syntax:
EPOCH(<timestamp-date-time-or-duration>)

π event_id, EPOCH(event_time) → unix_seconds (Events)

# Description:
EPOCH turns a moment into the number of seconds since 1970-01-01 00:00:00 UTC, the
Unix time other systems store and send. Given a duration it gives its length in
seconds, and given a time of day the seconds since midnight.

# Technical Description:
EPOCH(t) → NUMBER. For a TIMESTAMP, seconds since 1970-01-01T00:00:00Z with the
fraction of a second; for a DATE, the seconds at its midnight at UTC; for a TIME,
seconds since midnight; for a DURATION, its length in seconds, negative for a
negative one. It is `EXTRACT(EPOCH FROM t)`. NULL input returns NULL; any other type
is an evaluation error. PURE, DETERMINISTIC.

# Examples:
Unix time for each event:
  π event_id, EPOCH(event_time) → unix_seconds (Events)

A duration in seconds:
  π session_id, EPOCH(closed - opened) → seconds_open (Sessions)

# Pushdown:
SQL: `EXTRACT(EPOCH FROM <col>)` on PostgreSQL. MySQL's `UNIX_TIMESTAMP` reads a
`DATETIME` in the session's zone, so there and elsewhere it runs in the engine.

# Limitations:
Seconds, not milliseconds: multiply by 1000 for a millisecond count.

# See Also:
[seconds](seconds.md), [to_timestamp](to_timestamp.md), [EXTRACT](../../language/extract.md)
