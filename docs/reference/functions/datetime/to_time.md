# Name: to_time (parse a string to a TIME)

# Syntax:
to_time(<string-or-temporal>)

π id, to_time(time_str) → at (RawImport)

# Description:
to_time turns a text string into a proper TIME (a clock time), so time-of-day data
that arrived as text can be compared and computed like a real time.

# Technical Description:
to_time(s: STRING | TIME | TIMESTAMP) → TIME. Parses s as an ISO-8601 local time (LocalTime.parse); a
malformed string is an evaluation error. NULL input returns NULL. PURE,
DETERMINISTIC.

Given a temporal value it converts it as SQL's CAST does: a TIMESTAMP gives its time of day at UTC. A
TIME is returned as it is, and any other kind is an evaluation error. `CAST(x AS
TIME)` is this function.

# Examples:
Convert an imported text time:
  π id, to_time(time_str) → at (RawImport)

Within business hours:
  σ to_time(start) >= TIME '09:00:00' (RawShifts)

# Limitations:
Expects ISO-8601 time (HH:MM[:SS]); other formats error. STRING argument; NULL in
→ NULL out.

# Alternatives:
to_date / to_timestamp for date or date-time strings; a TIME '…' literal for
constants.

# See Also:
[to_date](to_date.md), [to_timestamp](to_timestamp.md), [time-literal](../../literals/time-literal.md)
