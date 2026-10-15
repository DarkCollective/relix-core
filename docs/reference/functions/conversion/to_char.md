# Name: TO_CHAR (format a date or number as text)

# Syntax:
TO_CHAR(<timestamp-date-or-number>, <pattern>)

π order_id, TO_CHAR(order_date, 'YYYY-MM') → month (Orders)

# Description:
TO_CHAR writes a date, a timestamp or a number as text in the shape a pattern
describes, in PostgreSQL's pattern language: `TO_CHAR(order_date, 'DD Mon YYYY')`
gives `08 Jul 2026`, and `TO_CHAR(amount, '9,999.00')` gives ` 1,234.50`.

# Technical Description:
TO_CHAR(value, pattern: STRING) → STRING. A TIMESTAMP is read at UTC (convert it
first with AT TIME ZONE for a local reading); a DATE is its midnight.

Date and time patterns, matched in any letter case: `YYYY`, `YYY`, `YY`, `Y` (year),
`IYYY` (ISO year), `MM` (month number), `MONTH`, `MON` (name, abbreviated), `DD` (day
of month), `DDD` (day of year), `D` (day of week, Sunday 1), `ID` (ISO day, Monday
1), `DAY`, `DY` (day name, abbreviated), `IW` (ISO week), `WW` (week of year from 1
January), `Q` (quarter), `HH24`, `HH12` or `HH`, `MI`, `SS`, `MS` (milliseconds),
`US` (microseconds), and `AM`, `PM`, `A.M.`, `P.M.`. A name is written in the case of
its pattern — `MONTH`, `Month`, `month` — and padded to nine characters. `FM` before a
pattern drops its padding and leading zeros. Text in double quotes is copied as it is,
and so is anything that is not a pattern.

Number patterns: `9` a digit, `0` a digit kept even when it is a leading zero, `.` or
`D` the decimal point, `,` or `G` a group separator, and `FM` at the start to drop the
padding and the trailing zeros of the `9`s after the point. The value is rounded half
away from zero to the pattern's decimals, one leading place is kept for the sign,
and a value too wide for the pattern is written as `#`s.

A NULL value or pattern returns NULL. PURE, DETERMINISTIC.

# Examples:
Month buckets:
  γ TO_CHAR(order_date, 'YYYY-MM') → month, SUM(amount) → revenue (Orders)

A readable date:
  π order_id, TO_CHAR(order_date, 'FMDay, FMDD Month YYYY') → day (Orders)

A grouped amount:
  π order_id, TO_CHAR(amount, 'FM999,999.00') → shown (Orders)

# Pushdown:
Runs in the engine: a backend that read one of a pattern's letters differently would
answer with different text rather than fail.

# Limitations:
English month and day names only. Not offered: `TZ`, ordinal suffixes (`TH`), `CC`,
`J`, and the number patterns for signs (`S`, `MI`, `PL`), Roman numerals and
exponents.

# Alternatives:
FORMAT for a grouped decimal, CStr for a value's default text, and EXTRACT for a
field as a number.

# See Also:
[format](format.md), [cstr](cstr.md), [AT TIME ZONE](../../language/at-time-zone.md), [extract](../../language/extract.md)
