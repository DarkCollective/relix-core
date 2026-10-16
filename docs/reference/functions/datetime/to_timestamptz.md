# Name: to_timestamptz (a TIMESTAMPTZ from text or a timestamp)

# Syntax:
to_timestamptz(<string-or-temporal>)
CAST(<value> AS TIMESTAMPTZ)
CAST(<value> AS TIMESTAMP WITH TIME ZONE)

π id, to_timestamptz(logged_at) → at (RawCalls)

# Description:
to_timestamptz turns text such as `2026-03-01T09:00:00+02:00` into a TIMESTAMPTZ that
keeps the offset, so the local time it was recorded at is not lost. `CAST(x AS
TIMESTAMPTZ)` is this function.

# Technical Description:
to_timestamptz(s: STRING | DATE | TIMESTAMP | TIMESTAMPTZ) → TIMESTAMPTZ. Text is read
as the TIMESTAMPTZ literal reads it: ISO-8601 with an offset, or with a region in
brackets, or at UTC with neither. A TIMESTAMP is put at UTC, a DATE at its UTC midnight,
and a TIMESTAMPTZ is returned as it is. Text that is not a timestamp, or any other kind,
is an evaluation error. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
Keep the offset each call was logged with:
  π id, to_timestamptz(logged_at) → at (RawCalls)

# Pushdown:
Runs in the engine.

# See Also:
[to_timestamp](to_timestamp.md), [timestamptz-literal](../../literals/timestamptz-literal.md), [cast](../../language/cast.md)
