# Name: AT TIME ZONE (local time in a zone)

# Syntax:
<timestamp> AT TIME ZONE <zone>

π event_id, event_time AT TIME ZONE 'Europe/London' → local (Events)

# Description:
`AT TIME ZONE` turns a moment into the time a clock in a given place showed at it.
Timestamps in Relix are instants and read at UTC; `AT TIME ZONE 'Europe/London'`
gives the London wall-clock time instead, summer time included.

# Technical Description:
`ts AT TIME ZONE zone` parses to `TIMEZONE(zone, ts)`. It binds more tightly than any
arithmetic, as in PostgreSQL, so `ts AT TIME ZONE 'UTC' + DURATION 'PT1H'` adds the
hour to the converted time. `AT`, `TIME` and `ZONE` are matched by their text, so
none is reserved. See TIMEZONE for the zones accepted and the direction converted.

# Examples:
Local event times:
  π event_id, event_time AT TIME ZONE 'Europe/London' → local (Events)

# Worked Example:
London's clocks go forward at 01:00 UTC on 29 March 2026 and back at 01:00 UTC on 25
October. Four departures, half an hour either side of each change:

```relix
Flights := [
| flight | departs              |
|--------|----------------------|
| BA1    | 2026-03-29T00:30:00Z |
| BA2    | 2026-03-29T01:30:00Z |
| BA3    | 2026-10-25T00:30:00Z |
| BA4    | 2026-10-25T01:30:00Z |
];

query { π flight, TO_CHAR(CAST(departs AS TIMESTAMP) AT TIME ZONE 'Europe/London',
                          'YYYY-MM-DD HH24:MI') → london (Flights) };
```

```
 flight  london
 ──────  ────────────────
 BA1     2026-03-29 00:30
 BA2     2026-03-29 02:30
 BA3     2026-10-25 01:30
 BA4     2026-10-25 01:30
(4 rows)
```

An hour apart in March becomes two hours on the clock, since 01:00 to 02:00 London
time never happened; an hour apart in October shows the same 01:30 twice, since that
hour happened twice.

# See Also:
[timezone](../functions/datetime/timezone.md), [to_char](../functions/conversion/to_char.md), [timestamp literal](../literals/timestamp-literal.md)
