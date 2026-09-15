# Name: to_timestamp (parse a string to a TIMESTAMP)

# Syntax:
to_timestamp(<string>)

σ to_timestamp(ts_str) >= TIMESTAMP '2026-01-01T00:00:00Z' (RawEvents)

# Description:
to_timestamp turns a text string into a proper TIMESTAMP (an instant), so
date-time data that arrived as text can be filtered, sorted, and used in time
arithmetic like a real timestamp.

# Technical Description:
to_timestamp(s: STRING) → TIMESTAMP. Parses s as an ISO-8601 date-time via the same
rule as the TIMESTAMP literal (an offset is normalised to UTC; an offsetless
payload is interpreted as UTC). A malformed string is an evaluation error. NULL
input returns NULL. PURE, DETERMINISTIC.

# Examples:
Parse and window imported event times:
  σ to_timestamp(ts_str) >= TIMESTAMP '2026-01-01T00:00:00Z' (RawEvents)

Sort by a parsed timestamp:
  τ to_timestamp(logged) DESC (RawLogs)

# Limitations:
Expects ISO-8601 date-time; other formats error. Offsets fold to UTC; offsetless
values are read as UTC. STRING argument; NULL in → NULL out.

# Alternatives:
to_date / to_time for date-only or time-only strings; a TIMESTAMP '…' literal for
constants.

# See Also:
[to_date](to_date.md), [to_time](to_time.md), [timestamp-literal](../../literals/timestamp-literal.md), [now](now.md)
