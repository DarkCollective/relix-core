# Name: DATE_ADD (add an interval to a date, as MySQL writes it)

# Syntax:
DATE_ADD(<date-or-timestamp>, <interval>)

π id, DATE_ADD(started_on, INTERVAL 1 MONTH) → first_bill (Subscriptions)

# Description:
DATE_ADD is MySQL's way to write `d + INTERVAL …`. It adds an interval to a date or a
timestamp, and a date stays a date.

# Technical Description:
DATE_ADD(d: DATE | TIMESTAMP, interval: DURATION) → the type of d. The interval is any
DURATION, such as `INTERVAL 1 MONTH`, `INTERVAL '2 weeks'` or `DURATION 'P1M'`. Its
months are added first, and a month from the 31st lands on a shorter month's last day.
Its exact part is added after them. A DATE moved by part of a day is refused, because
the result would be a TIMESTAMP: write `CAST(d AS TIMESTAMP) + INTERVAL …` for that.
A TIMESTAMP is read at UTC. A NULL argument returns NULL. PURE, DETERMINISTIC.

# Examples:
The first bill, a month after the start:
  π id, DATE_ADD(started_on, INTERVAL 1 MONTH) → first_bill (Subscriptions)

# Pushdown:
Runs in the engine.

# See Also:
[date_sub](date_sub.md), [dateadd](dateadd.md), [duration-literal](../../literals/duration-literal.md)
