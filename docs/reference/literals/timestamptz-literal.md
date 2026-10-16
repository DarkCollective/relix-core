# Name: TIMESTAMPTZ literal (a timestamp that keeps its offset)

# Syntax:
TIMESTAMPTZ '<ISO-8601 date-time with offset>'
TIMESTAMP WITH TIME ZONE '<ISO-8601 date-time with offset>'

TIMESTAMPTZ '2026-03-01T09:00:00+02:00'
TIMESTAMPTZ '2026-03-01T09:00:00[Europe/London]'   -- a region's offset at that time
TIMESTAMP WITH TIME ZONE '2026-03-01T07:00:00Z'

# Description:
A TIMESTAMPTZ is a moment in time together with the UTC offset it was recorded at. It
answers "what local time did this happen?" as well as "when did it happen?". A call
logged at 09:00 in Athens is 07:00 UTC. A TIMESTAMP keeps only the 07:00. A TIMESTAMPTZ
keeps the 09:00+02:00 too, and can be written back out with it.

# Technical Description:
TIMESTAMPTZ is SQL's `TIMESTAMP WITH TIME ZONE`, held as an OffsetDateTime. The payload
is ISO-8601 with an offset (`+02:00`, `-05:00` or `Z`). A region name in brackets, such
as `[Europe/London]`, takes that region's offset at that local time. A payload with
neither is read at UTC. A malformed payload is a positioned parse error. `TIMESTAMPTZ` is
not reserved. It is also a column type, `at: TIMESTAMPTZ` in a source's schema, and a
CAST type: `CAST(x AS TIMESTAMPTZ)` or `CAST(x AS TIMESTAMP WITH TIME ZONE)`.

**Comparison** is by instant, as SQL compares: `TIMESTAMPTZ '2026-03-01T09:00:00+02:00'`
equals `TIMESTAMPTZ '2026-03-01T07:00:00Z'`, and equals the TIMESTAMP naming that
instant. Grouping, DISTINCT, joins and sorting read the instant too. A group shows the
offset of its first row. Each value keeps its own offset, and that is what it displays.

**Local time.** YEAR, MONTH, DAY, HOUR, MINUTE, SECOND, the other date parts, TO_CHAR,
DATE_TRUNC and DATEDIFF read a TIMESTAMPTZ in its own offset, as SQL Server reads a
`datetimeoffset`. So `HOUR(TIMESTAMPTZ '2026-03-01T09:00:00+02:00')` is 9. EPOCH counts
from the instant.

**Conversion.** `CAST(tz AS TIMESTAMP)` is its instant, and `CAST(tz AS DATE)` and
`CAST(tz AS TIME)` are its local date and time. `CAST(ts AS TIMESTAMPTZ)` puts a
TIMESTAMP at UTC, and text keeps the offset it carries. `tz AT TIME ZONE 'Region'` is the
same instant at that region's offset then, following its daylight-saving changes, as SQL
Server converts a `datetimeoffset`. The wall clock it shows is the one PostgreSQL gives
for `timestamptz AT TIME ZONE`.

**Arithmetic.** TIMESTAMPTZ ± DURATION keeps the offset, and moves the months on its own
local calendar. TIMESTAMPTZ − TIMESTAMPTZ, or − TIMESTAMP, is the DURATION between the
instants.

**JDBC.** A `TIMESTAMP WITH TIME ZONE` column, PostgreSQL's `timestamptz` or SQL Server's
`datetimeoffset`, is typed TIMESTAMPTZ and keeps the offset it is read with. SQL Server
stores the offset it was given. PostgreSQL stores only the instant, and sends it at the
session's offset.

**Pushdown**: on Postgres and DuckDB the literal folds to
`TIMESTAMP WITH TIME ZONE '<ISO-8601>'`, and on SQL Server to
`CAST('<ISO-8601>' AS DATETIMEOFFSET)`, so a comparison such as
`σ at > TIMESTAMPTZ '…'` runs in the database by instant. MySQL, Db2 and SQLite have no
offset-bearing type, and it runs in-engine there.

# Examples:
Calls before 07:30 UTC, whatever offset each was logged at:
  σ CAST(at AS TIMESTAMPTZ) < TIMESTAMPTZ '2026-03-01T07:30:00Z' (Calls)

The hour of the day each call was made, locally:
  π id, HOUR(CAST(at AS TIMESTAMPTZ)) → local_hour (Calls)

# Worked Example:
Three calls logged in Athens, in London and in New York:

```relix
Calls := [
| id | at                        |
|----|---------------------------|
| 1  | 2026-03-01T09:00:00+02:00 |
| 2  | 2026-03-01T07:00:00Z      |
| 3  | 2026-03-01T08:30:00-05:00 |
];

query { τ id (π id, CAST(at AS TIMESTAMPTZ) → at, HOUR(CAST(at AS TIMESTAMPTZ)) → local_hour,
                    CAST(at AS TIMESTAMPTZ) AT TIME ZONE 'Europe/London' → london (Calls)) };
```

```
 id  at                         local_hour  london
 ──  ─────────────────────────  ──────────  ────────────────────
  1  2026-03-01T09:00:00+02:00           9  2026-03-01T07:00:00Z
  2  2026-03-01T07:00:00Z                7  2026-03-01T07:00:00Z
  3  2026-03-01T08:30:00-05:00           8  2026-03-01T13:30:00Z
(3 rows)
```

Calls 1 and 2 happened at the same instant, so they are equal and London shows both at
07:00. Each keeps the offset it was logged at, so call 1 was made at 9 in the morning
locally.

# Limitations:
An offset is kept, not a region: a TIMESTAMPTZ written with `[Europe/London]` holds the
offset London had then. SESSIONIZE, DOWNSAMPLE and ASOF take a TIMESTAMP; CAST a
TIMESTAMPTZ to one first.

# See Also:
[timestamp-literal](timestamp-literal.md), [at-time-zone](../language/at-time-zone.md), [cast](../language/cast.md), [to_timestamptz](../functions/datetime/to_timestamptz.md), [duration-literal](duration-literal.md)
