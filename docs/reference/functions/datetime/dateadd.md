# Name: DATEADD (add a number of units to a date)

# Syntax:
DATEADD(<unit>, <n>, <date-or-timestamp>)
TIMESTAMPADD(<unit>, <n>, <date-or-timestamp>)

π id, DATEADD(month, 1, renewed_on) → next_renewal (Subscriptions)

# Description:
DATEADD moves a date or a timestamp by a whole number of units: a month later, two
quarters earlier, 90 days on. A month moves to the same day of the next month. If that
month is shorter, it lands on the month's last day, so 31 January plus a month is
28 February, or 29 in a leap year. This is SQL Server's DATEADD and MySQL's
TIMESTAMPADD.

# Technical Description:
DATEADD(unit: STRING, n: NUMBER, d: DATE | TIMESTAMP) → the type of d. The unit is
year, quarter, month, week, day, hour, minute or second, or one of SQL Server's
abbreviations: yy, yyyy, qq, q, mm, m, wk, ww, dd, d, hh, mi, n, ss, s. It may be
written as a bare word, as SQL Server writes it: `DATEADD(month, 1, d)`. `n` is cut
to a whole number toward zero, and a negative `n` moves backwards. A year is twelve
months, a quarter three and a week seven days. A DATE stays a DATE, and moving one by
an hour, a minute or a second is refused, because the result would be a TIMESTAMP. A
TIMESTAMP is read at UTC. The arithmetic is the same as `d + INTERVAL '<n> <unit>'`.
A NULL argument returns NULL. PURE, DETERMINISTIC.

# Examples:
The renewal a month after each one, landing on a month's end when it must:
  π id, DATEADD(month, 1, renewed_on) → next_renewal (Subscriptions)

Ninety days before each order:
  π order_id, DATEADD(day, -90, order_date) → lookback (Orders)

# Pushdown:
Runs in the engine.

# SQL names:
`TIMESTAMPADD` is the same function by MySQL's name.

# See Also:
[add_months](add_months.md), [date_add](date_add.md), [datediff](datediff.md), [duration-literal](../../literals/duration-literal.md)
