# Name: DAYS (measure a duration in days)

# Syntax:
DAYS(<duration>)

π id, DAYS(closed - opened) → days_open (Tickets)

# Description:
DAYS converts a length of time (a DURATION) into a plain number of whole days —
ideal for "how many days was this ticket open" or "age in days" reporting.

# Technical Description:
DAYS(d: DURATION) → NUMBER. Returns the whole number of days in d (Duration.toDays
— truncated). A non-DURATION value is an evaluation error. NULL input returns NULL.
PURE, DETERMINISTIC.

# Examples:
Days a ticket stayed open:
  π id, DAYS(closed - opened) → days_open (Tickets)

Items older than 90 days:
  σ DAYS(NOW() - created_at) > 90 (Items)

# Limitations:
Argument must be a DURATION. Whole days (truncated). NULL in → NULL out. For
calendar-aware month/year spans, compute from DATE parts instead.

# Alternatives:
MINUTES / SECONDS for finer units.

# See Also:
[minutes](minutes.md), [seconds](seconds.md), [duration-literal](../../literals/duration-literal.md), [now](now.md)
