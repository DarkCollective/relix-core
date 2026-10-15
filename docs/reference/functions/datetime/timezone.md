# Name: TIMEZONE (wall-clock time in a zone)

# Syntax:
TIMEZONE(<zone>, <timestamp>)
<timestamp> AT TIME ZONE <zone>

π id, TIMEZONE('Europe/London', placed_at) → london (Orders)

# Description:
TIMEZONE gives the time a clock in a time zone showed at a moment: for a timestamp
of 13:40 UTC on 8 July, `'Europe/London'` gives 14:40, since London is on summer
time then. `ts AT TIME ZONE zone` is SQL's way of writing it.

# Technical Description:
TIMEZONE(zone: STRING, ts: TIMESTAMP) → TIMESTAMP. The zone is an IANA region name
such as `'Europe/London'` or `'America/New_York'`, in any letter case, or `'UTC'`.
The result is the zone's wall-clock date and time at the instant `ts`, held as a
TIMESTAMP that reads it at UTC, so YEAR, HOUR, TO_CHAR and the rest give the local
reading. The zone's offset is the one in force at that instant, so the result follows
the zone's daylight-saving changes. A NULL argument returns NULL; an unknown zone is
an error. PURE, DETERMINISTIC.

# Examples:
Orders by local hour of day:
  γ HOUR(TIMEZONE('America/New_York', placed_at)) → local_hour, COUNT(*) → orders (Orders)

# Pushdown:
Runs in the engine: PostgreSQL's `AT TIME ZONE` converts in one direction for a
`timestamptz` column and in the other for a `timestamp` one, and which a column is
cannot be told from here.

# Limitations:
Relix has one TIMESTAMP kind, an instant, so only the instant-to-local direction is
offered: there is no conversion from a local reading back to an instant. A numeric
offset such as `'UTC+2'` is refused, since POSIX and ISO count its sign oppositely;
use a region name.

# See Also:
[AT TIME ZONE](../../language/at-time-zone.md), [to_char](../conversion/to_char.md), [hour](hour.md)
