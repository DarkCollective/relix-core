# Name: EXTRACT (a field of a date or time)

# Syntax:
EXTRACT(<field> FROM <operand>)

<field> ::= YEAR | MONTH | DAY | HOUR | MINUTE | SECOND
          | DOW | ISODOW | DOY | WEEK | ISOYEAR | QUARTER | EPOCH

γ EXTRACT(QUARTER FROM order_date) → q, SUM(amount) → revenue (Orders)

# Description:
EXTRACT is SQL's way of asking for one part of a date, a time or a timestamp: its
year, its day of the week, the ISO week it falls in. Every field is also a function
of its own name, so `EXTRACT(WEEK FROM d)` and `WEEK(d)` are the same thing, and you
can write whichever you are used to.

# Technical Description:
`EXTRACT(field FROM x)` parses to the field's function applied to `x`, and prints
back as that call. The fields mean what PostgreSQL's do:

| Field | Function | Value |
|-------|----------|-------|
| `YEAR`, `MONTH`, `DAY` | YEAR, MONTH, DAY | of a DATE or TIMESTAMP |
| `HOUR`, `MINUTE`, `SECOND` | HOUR, MINUTE, SECOND | of a TIME or TIMESTAMP; SECOND is whole |
| `DOW` | DOW | 0 (Sunday) to 6 (Saturday) |
| `ISODOW` | ISODOW | 1 (Monday) to 7 (Sunday) |
| `DOY` | DOY | 1 to 366 |
| `WEEK` | WEEK | the ISO 8601 week, 1 to 53 |
| `ISOYEAR` | ISOYEAR | the year the ISO week belongs to |
| `QUARTER` | QUARTER | 1 to 4 |
| `EPOCH` | EPOCH | seconds since 1970-01-01T00:00:00Z, or a duration's seconds |

A TIMESTAMP is read at UTC. A NULL gives NULL. The field is a word, in any case, and
`FROM` follows it; a function called `EXTRACT` with an argument list is still
written and called as usual.

# Examples:
Orders per weekday:
  γ EXTRACT(DOW FROM order_date) → weekday, COUNT(*) → orders (Orders)

Weekly totals keyed by the year each week belongs to:
  γ EXTRACT(ISOYEAR FROM order_date) → yr, EXTRACT(WEEK FROM order_date) → wk, SUM(amount) → revenue (Orders)

# Worked Example:
The first days of January can belong to the last ISO week of the year before, and
the last days of December to week 1 of the next. Four dates near the boundaries:

```relix
Days := [
| day        |
|------------|
| 2020-12-31 |
| 2021-01-01 |
| 2024-12-30 |
| 2026-07-05 |
];

query { π day, EXTRACT(YEAR FROM CAST(day AS DATE)) → yr,
          EXTRACT(ISOYEAR FROM CAST(day AS DATE)) → iso_yr,
          EXTRACT(WEEK FROM CAST(day AS DATE)) → wk,
          EXTRACT(DOW FROM CAST(day AS DATE)) → dow,
          EXTRACT(QUARTER FROM CAST(day AS DATE)) → q (Days) };
```

```
 day         yr    iso_yr  wk  dow  q
 ──────────  ────  ──────  ──  ───  ─
 2020-12-31  2020    2020  53    4  4
 2021-01-01  2021    2020  53    5  1
 2024-12-30  2024    2025   1    1  4
 2026-07-05  2026    2026  27    0  3
(4 rows)
```

1 January 2021 is a Friday in week 53 of ISO year 2020, and 30 December 2024 a Monday
in week 1 of 2025. 5 July 2026 is a Sunday, so its DOW is 0.

A field EXTRACT does not have is a syntax error:

```relix-invalid
query { π EXTRACT(FORTNIGHT FROM order_date) → f (Orders) };
```

# Pushdown:
As the field's function: `EXTRACT(field FROM …)` on PostgreSQL and DuckDB for every
field, and per dialect elsewhere; see each function's page.

# See Also:
[year](../functions/datetime/year.md), [dow](../functions/datetime/dow.md), [isodow](../functions/datetime/isodow.md), [doy](../functions/datetime/doy.md), [week](../functions/datetime/week.md), [isoyear](../functions/datetime/isoyear.md), [quarter](../functions/datetime/quarter.md), [epoch](../functions/datetime/epoch.md), [CAST](cast.md)
