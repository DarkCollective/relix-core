# Name: MINUTES (measure a duration in minutes)

# Syntax:
MINUTES(<duration>)

σ MINUTES(closed - opened) > 30 (Sessions)

# Description:
MINUTES converts a length of time (a DURATION) into a plain number of minutes, so
you can compare it naturally — "sessions longer than 30 minutes" reads as
`MINUTES(gap) > 30` rather than wrestling with an interval literal.

# Technical Description:
MINUTES(d: DURATION) → NUMBER. Returns the whole number of minutes in d
(Duration.toMinutes — truncated, not rounded). A non-DURATION value is an
evaluation error. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
Sessions held longer than 30 minutes:
  σ MINUTES(closed - opened) > 30 (Sessions)

Average handling time in minutes:
  γ agent, AVG(MINUTES(handled)) → avg_min (Calls)

# Limitations:
Argument must be a DURATION (e.g. the result of TIMESTAMP − TIMESTAMP). Truncates
to whole minutes. NULL in → NULL out.

# Alternatives:
SECONDS / DAYS measure the same duration in other units.

# See Also:
[seconds](seconds.md), [days](days.md), [duration-literal](../../literals/duration-literal.md), [timestamp-literal](../../literals/timestamp-literal.md)

# Notes:
The common pattern is `MINUTES(ts2 - ts1)` — subtract two timestamps to a DURATION,
then measure it.
