# Name: FROM_TZ (a local wall-clock time read as an instant in a zone)

# Syntax:
FROM_TZ(<timestamp>, <zone>)

π shop, FROM_TZ(opens, 'Europe/London') → opens_at (Openings)

# Description:
FROM_TZ is the other direction from [TIMEZONE](timezone.md). It takes a time read off
a clock in a time zone and gives the moment that was: 09:00 on 8 July on a London
clock is `2026-07-08T09:00:00+01:00`, which is 08:00 UTC, since London is on summer
time then. It is for data whose times were recorded as local readings, such as a CSV
of shop opening times or a log kept in local time, and turns them into instants that
compare, sort and subtract correctly across zones and across a change of the clocks.

It is Oracle's `FROM_TZ`. PostgreSQL writes it `ts AT TIME ZONE zone` over a
`timestamp` column, and SQL Server `dt AT TIME ZONE zone` over a `datetime2`; in
Relix, `AT TIME ZONE` always goes the other way (see
[AT TIME ZONE](../../language/at-time-zone.md)).

# Technical Description:
FROM_TZ(ts: TIMESTAMP, zone: STRING) → TIMESTAMPTZ. The timestamp's date and time read
at UTC are taken as the wall-clock reading in `zone`. The result is the instant at
which a clock there showed that reading, with the zone's UTC offset in force then. The
zone is an IANA region name such as `'Europe/London'` or `'America/New_York'`, in any
letter case, or `'UTC'`. A NULL argument returns NULL; an unknown zone is an error.
PURE, DETERMINISTIC.

Two readings are not one instant each, because of daylight saving:

- **A reading the clocks pass twice**, in the hour they go back, could be either of
  two instants. FROM_TZ takes the **later**, at the offset in force after the change:
  01:30 on 25 October 2026 in London is `2026-10-25T01:30:00Z`, on winter time, not
  `01:30:00+01:00`, an hour earlier.
- **A reading the clocks skip**, in the hour they go forward, never happened.
  FROM_TZ moves it **forward by the length of the gap**: 01:30 on 29 March 2026 in
  London is read as 02:30, `2026-03-29T02:30:00+01:00`.

Both are what PostgreSQL's `timestamp AT TIME ZONE` does, and Oracle's `FROM_TZ`
reads an ambiguous time as standard time too.

# Examples:
Opening times recorded on the local clock, as instants:
  π shop, FROM_TZ(opens, 'Europe/London') → opens_at (Openings)

Two logs kept on different local clocks, in the order their lines happened:
  τ FROM_TZ(logged, zone) (NewYorkLog ∪ LondonLog)

# Worked Example:
London's clocks went forward at 01:00 on 29 March 2026. A reading either side of the
change gets the offset in force at it:

```relix
Readings := [
| at                   |
|----------------------|
| 2026-03-29T00:30:00Z |
| 2026-03-29T02:30:00Z |
] schema { at: TIMESTAMP };

query { π at, FROM_TZ(at, 'Europe/London') → instant (Readings) };
```

```
 at                    instant
 ────────────────────  ─────────────────────────
 2026-03-29T00:30:00Z  2026-03-29T00:30:00Z
 2026-03-29T02:30:00Z  2026-03-29T02:30:00+01:00
(2 rows)
```

The first reading is before the change, so London is at UTC and the instant is the
reading. The second is after it, at UTC+01:00, so the instant is 01:30 UTC: the two
readings are two hours apart on the clock and one hour apart in time.

# Pushdown:
Runs in the engine. PostgreSQL's `AT TIME ZONE` converts in this direction only for a
`timestamp` column, and which kind a column is cannot be told from here.

# Limitations:
The input is a TIMESTAMP, read at UTC as the local reading. A TIMESTAMPTZ already
names its instant, so it is refused; [TIMEZONE](timezone.md) gives its reading in
another zone. A numeric offset such as `'UTC+2'` is refused, since POSIX and ISO
count its sign oppositely; use a region name.

# See Also:
[TIMEZONE](timezone.md), [AT TIME ZONE](../../language/at-time-zone.md), [TIMESTAMPTZ literal](../../literals/timestamptz-literal.md)
