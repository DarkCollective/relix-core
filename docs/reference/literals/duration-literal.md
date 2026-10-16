# Name: DURATION literal

# Syntax:
DURATION '<ISO-8601 duration>'
INTERVAL '<interval text>'
INTERVAL '<n>' <unit>
INTERVAL <n> <unit>

DURATION 'PT90M'      -- 90 minutes
DURATION 'PT2H30M'    -- 2 hours 30 minutes
DURATION 'P1D'        -- 1 day
DURATION 'P1M'        -- 1 calendar month
DURATION 'P1Y2M'      -- 1 year and 2 months
INTERVAL '1 month'    -- PostgreSQL's spelling of DURATION 'P1M'
INTERVAL '3' DAY      -- standard SQL's: 3 days
INTERVAL 1 MONTH      -- MySQL's

# Description:
A DURATION literal is a length of time, such as "90 minutes", "3 days" or "1 month",
as opposed to a point in time. Use it to add or subtract spans from dates and
timestamps, to express thresholds such as "gaps longer than 30 minutes", and as the
result of subtracting two timestamps.

A month has no fixed length, so adding one is calendar arithmetic. It moves a date to
the same day of the next month, or to that month's last day when it is shorter:
31 January plus a month is 28 February, or 29 in a leap year. Billing periods, "the
same month last year" and month-on-month reports all need this.

# Technical Description:
A DURATION holds two parts, as PostgreSQL's INTERVAL does: a number of calendar months
and an exact span of seconds and nanoseconds. A year is twelve months. A week is seven
days, and a day is always 24 hours, so days and weeks belong to the exact part.
`DURATION '…'` reads ISO-8601, such as PT90M, P1D, P1M, P1Y2MT3H or -P1M, at parse time.
A malformed payload is a positioned parse error. It infers as the DURATION scalar type.

Relix has one interval type rather than a calendar type beside an exact one. A month
and an hour combine in the same value, such as `INTERVAL '1 month 2 hours'`, and SQL's
INTERVAL is one type in every engine that has one.

`INTERVAL` is SQL's spelling, and `INTERVAL` is not reserved. It takes PostgreSQL's
text: numbers with units, `'2 years 3 months'`, `'1 day 02:30:00'`, `'3 hours ago'`,
or ISO-8601. It also takes the standard's `INTERVAL '3' DAY` and MySQL's
`INTERVAL 1 MONTH`. A unit is year, quarter, month, week, day, hour, minute or second,
singular or plural, or a short form: yr, mon, hr, min, sec, y, w, d, h, m or s. As in
PostgreSQL, a fraction of a year is whole months and the rest is dropped. A fraction of
a month is days, at 30 to a month. `INTERVAL` parses to the same DURATION and prints as
`DURATION '…'`.

**Arithmetic.** DATE or TIMESTAMP ± DURATION → TIMESTAMP. A TIMESTAMP is read at UTC.
The months are added first, landing on a shorter month's last day. Then the exact part
is added, so `TIMESTAMP '2026-03-31T09:00:00Z' - INTERVAL '1 month 1 hour'` is
2026-02-28T08:00:00Z. TIME ± DURATION moves the time of day by the exact part only,
because a time has no month to move, as in PostgreSQL. DURATION ± DURATION adds months
to months and the exact parts together. DURATION × or ÷ NUMBER scales both parts, and a
fraction of a month becomes days, at 30 to a month: `DURATION 'P1M' * 1.5` is one month
and 15 days, and `DURATION 'P1M' / 2` is 15 days. DURATION ÷ DURATION → NUMBER, and
unary −DURATION negates both parts.

**Comparison.** Two durations compare by length, reading a month as 30 days, as
PostgreSQL compares intervals. So `DURATION 'P1M' = DURATION 'P30D'` is true, and P1M is
shorter than P31D. Equality, grouping, DISTINCT and sorting all use the same length.
Each value keeps the parts it was written with, and that is what it displays. DAYS,
MINUTES, SECONDS and EPOCH measure a month as 30 days too.

**Where only an exact length will do**, a duration with months is refused when it is
analysed: a RANGE frame offset, an AS-OF join's WITHIN tolerance, and a SESSIONIZE GAP.
Write those in days, as `DURATION 'P30D'`.

**Pushdown**: on **Postgres** a DURATION literal folds to `INTERVAL '<ISO-8601>'`
(e.g. `INTERVAL 'PT30M'` or `INTERVAL 'P1M'`), so predicates like
`σ held > DURATION 'PT30M'` push to `WHERE ("held" > INTERVAL 'PT30M')`, and PostgreSQL
moves a date by its months the same way. On **DuckDB**, which rejects the ISO-8601
string, the exact part folds to a count of microseconds, `to_microseconds(1800000000)`,
and the months to `to_months(n)`. A duration finer than a microsecond does not push. On
MySQL, SQLite, SQL Server, Db2 and GENERIC dialects, DURATION literals do **not** push,
and the predicate runs in-engine.

# Examples:
Sessions held longer than 30 minutes:
  σ MINUTES(closed - opened) > 30 (Sessions)

Add a window to a timestamp:
  π id, (started + DURATION 'PT30M') → expires (Sessions)

The same day next month, and a year ago:
  π id, renewed + INTERVAL '1 month' → next_renewal, renewed - DURATION 'P1Y' → year_before (Subscriptions)

Scale a duration by a number (DURATION × NUMBER → DURATION):
  π id, (base_slot * 3) → triple_slot (Bookings)

# Worked Example:
Three renewal dates: the end of January in an ordinary year and in a leap year, and the
end of March:

```relix
Renewals := [
| id | renewed    |
|----|------------|
| 1  | 2026-01-31 |
| 2  | 2024-01-31 |
| 3  | 2026-03-31 |
];

query { τ id (π id, CAST(renewed AS DATE) + INTERVAL '1 month' → plus_month,
                    CAST(renewed AS DATE) - INTERVAL '1 month' → minus_month (Renewals)) };
```

```
 id  plus_month            minus_month
 ──  ────────────────────  ────────────────────
  1  2026-02-28T00:00:00Z  2025-12-31T00:00:00Z
  2  2024-02-29T00:00:00Z  2023-12-31T00:00:00Z
  3  2026-04-30T00:00:00Z  2026-02-28T00:00:00Z
(3 rows)
```

February has no 31st, so a month from the 31st of January lands on its last day, and
in 2024 that day is the 29th. April has no 31st either. PostgreSQL gives the same
dates.

# Limitations:
Pushed only to Postgres (as `INTERVAL`) and DuckDB (as `to_microseconds` and
`to_months`). Elsewhere it runs in-engine. A day in a DURATION is always 24 hours.

# Alternatives:
The MINUTES/SECONDS/DAYS functions measure a DURATION as a plain NUMBER for
comparisons that read naturally (e.g. `MINUTES(gap) > 30`). DATEADD and ADD_MONTHS
move a date by a number of units and keep it a DATE, and AGE gives the years, months
and days between two dates.

# See Also:
[timestamp-literal](timestamp-literal.md), [date-literal](date-literal.md), [time-literal](time-literal.md), [minutes](../functions/datetime/minutes.md), [seconds](../functions/datetime/seconds.md), [days](../functions/datetime/days.md), [dateadd](../functions/datetime/dateadd.md), [add_months](../functions/datetime/add_months.md), [age](../functions/datetime/age.md)

# Notes:
DURATION arithmetic: DURATION ± DURATION → DURATION, DURATION × / ÷ NUMBER →
DURATION, DURATION ÷ DURATION → NUMBER, unary −DURATION → DURATION.
