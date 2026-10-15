# Name: DATEDIFF (calendar units between two dates)

# Syntax:
DATEDIFF(<unit>, <start>, <end>)

<unit> ::= year | quarter | month | week | day | hour | minute | second

π id, DATEDIFF(month, signup_date, CURRENT_DATE()) → months (Customers)

# Description:
DATEDIFF counts calendar units from one date or timestamp to another: months,
quarters, years, days. Subtracting two timestamps gives a DURATION, which can say
"40 days" but not "1 month"; DATEDIFF says how many month starts lie between them.

# Technical Description:
DATEDIFF(unit, start, end) → NUMBER, counted as SQL Server counts it: the number of
unit boundaries crossed from start to end, negative when end is earlier. So 31
January to 1 February is 1 month and 1 day, 23:59 to 00:01 is 1 day, and 1 January
to 31 December is 11 months. A week starts on Monday. Start and end are each a DATE
(its midnight) or a TIMESTAMP, read at UTC. The unit is a bare word or a string, in
any case, and SQL Server's abbreviations (`yy`, `qq`, `mm`, `wk`, `dd`, `hh`, `mi`,
`ss` and the rest) are accepted. A NULL date returns NULL; an unknown unit is an
error. PURE, DETERMINISTIC.

# Examples:
Months since sign-up:
  π customer_id, DATEDIFF(month, signup_date, CURRENT_DATE()) → months (Customers)

# Worked Example:
Three subscriptions, each crossing a month's end or a leap day:

```relix
Subs := [
| id | started    | ended      |
|----|------------|------------|
| 1  | 2024-01-31 | 2024-02-01 |
| 2  | 2024-01-01 | 2024-12-31 |
| 3  | 2023-02-28 | 2024-02-29 |
];

query { π id, DATEDIFF(month, CAST(started AS DATE), CAST(ended AS DATE)) → months,
          DATEDIFF(day, CAST(started AS DATE), CAST(ended AS DATE)) → days,
          LAST_DAY(CAST(ended AS DATE)) → month_end (Subs) };
```

```
 id  months  days  month_end
 ──  ──────  ────  ──────────
  1       1     1  2024-02-29
  2      11   365  2024-12-31
  3      12   366  2024-02-29
(3 rows)
```

One day spans a month boundary, so it counts one month; a whole calendar year is
eleven month starts. To count complete months instead, compare the day of the month
as well.

# Pushdown:
SQL: `DATEDIFF(unit, …)` on SQL Server, both sides switched to UTC, for every unit
but week, whose start there depends on the session's `DATEFIRST`; in the engine
elsewhere.

# See Also:
[last_day](last_day.md), [make_date](make_date.md), [days](days.md), [date_trunc](date_trunc.md)
