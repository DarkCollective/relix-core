# Name: SECONDS (measure a duration in seconds)

# Syntax:
SECONDS(<duration>)

π id, SECONDS(elapsed) → secs (Requests)

# Description:
SECONDS converts a length of time (a DURATION) into a plain number of seconds, for
natural comparisons and reporting of short spans like request latency.

# Technical Description:
SECONDS(d: DURATION) → NUMBER. Returns the whole number of seconds in d
(Duration.getSeconds). A non-DURATION value is an evaluation error. NULL input
returns NULL. PURE, DETERMINISTIC.

# Examples:
Latency in seconds:
  π id, SECONDS(finished - started) → secs (Requests)

Requests slower than 5 seconds:
  σ SECONDS(finished - started) > 5 (Requests)

# Limitations:
Argument must be a DURATION. Whole seconds (no fractional part). NULL in → NULL
out.

# Alternatives:
MINUTES / DAYS for coarser units.

# See Also:
[minutes](minutes.md), [days](days.md), [duration-literal](../../literals/duration-literal.md)
