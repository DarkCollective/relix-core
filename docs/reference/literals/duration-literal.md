# Name: DURATION literal

# Syntax:
DURATION '<ISO-8601 duration>'

DURATION 'PT90M'      -- 90 minutes
DURATION 'PT2H30M'    -- 2 hours 30 minutes
DURATION 'P1D'        -- 1 day

# Description:
A DURATION literal is a length of time — "90 minutes", "2 hours 30 minutes", "3
days" — as opposed to a point in time. Use it to add or subtract spans from
timestamps, to express thresholds (e.g. "gaps longer than 30 minutes"), and as the
result of subtracting two timestamps.

# Technical Description:
`DURATION '…'` parses an ISO-8601 duration (e.g. PT90M, PT2H30M, P1D) into an exact
java.time.Duration at parse time; a malformed payload is a positioned parse error.
It infers as the DURATION scalar type.

**Pushdown**: on **Postgres** a DURATION literal folds to `INTERVAL '<ISO-8601>'`
(e.g. `INTERVAL 'PT30M'`), so predicates like `σ held > DURATION 'PT30M'` push to
`WHERE ("held" > INTERVAL 'PT30M')`. On MySQL and GENERIC dialects, DURATION
literals do **not** push (MySQL INTERVAL needs per-unit keywords that don't map
cleanly to ISO-8601) — the predicate runs in-engine.

# Examples:
Sessions held longer than 30 minutes:
  σ MINUTES(closed - opened) > 30 (Sessions)

Add a window to a timestamp:
  π id, (started + DURATION 'PT30M') → expires (Sessions)

Scale a duration by a number (DURATION × NUMBER → DURATION):
  π id, (base_slot * 3) → triple_slot (Bookings)

# Limitations:
Only pushed to Postgres (as `INTERVAL`); MySQL and GENERIC run in-engine. Built from
exact time units (hours/minutes/seconds/days); calendar-aware spans like "1 month"
are not a fixed DURATION.

# Alternatives:
The MINUTES/SECONDS/DAYS functions measure a DURATION as a plain NUMBER for
comparisons that read naturally (e.g. `MINUTES(gap) > 30`).

# See Also:
[timestamp-literal](timestamp-literal.md), [date-literal](date-literal.md), [time-literal](time-literal.md), [minutes](../functions/datetime/minutes.md), [seconds](../functions/datetime/seconds.md), [days](../functions/datetime/days.md)

# Notes:
DURATION arithmetic: DURATION ± DURATION → DURATION, DURATION × / ÷ NUMBER →
DURATION, DURATION ÷ DURATION → NUMBER, unary −DURATION → DURATION.
