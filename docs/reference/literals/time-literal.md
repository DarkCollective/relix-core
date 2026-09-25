# Name: TIME literal

# Syntax:
TIME '<HH:MM[:SS]>'

TIME '09:30:00'

# Description:
A TIME literal is a clock time with no date — like an opening hour or a daily
deadline. Write it as the keyword TIME followed by an ISO-8601 time in quotes. Use
it to compare against time-of-day columns or to compute time offsets.

# Technical Description:
`TIME '…'` parses an ISO-8601 local time into a zone-less civil java.time.LocalTime
at parse time; a malformed payload is a positioned parse error. It infers as the
TIME scalar type. TIME literals push down to SQL (per dialect),
except to SQLite, which has no time type. Single- or
double-quoted payloads are both accepted.

# Examples:
Rows during business hours:
  σ start_time >= TIME '09:00:00' ∧ start_time < TIME '17:00:00' (Shifts)

Shift a time by a duration (TIME ± DURATION → TIME, wraps at 24h):
  π id, (start_time + DURATION 'PT30M') → grace_end (Shifts)

A fixed cut-off:
  π id, TIME '23:59:59' → cutoff (Slots)

# Limitations:
A TIME has no date component, and arithmetic wraps around at 24 hours. Comparisons
must be against another TIME (same-type only).

# Alternatives:
TIMESTAMP for a full date+time instant; DATE for a calendar day; to_time(str) to
parse a time from a string.

# See Also:
[timestamp-literal](timestamp-literal.md), [date-literal](date-literal.md), [duration-literal](duration-literal.md), [to_time](../functions/datetime/to_time.md), [hour](../functions/datetime/hour.md), [minute](../functions/datetime/minute.md), [second](../functions/datetime/second.md)

# Notes:
TIME ± DURATION yields a TIME and wraps over midnight.
