# Name: DATE_SUB (subtract an interval from a date, as MySQL writes it)

# Syntax:
DATE_SUB(<date-or-timestamp>, <interval>)

σ order_date > DATE_SUB(DATE '2026-10-16', INTERVAL 3 MONTH) (Orders)

# Description:
DATE_SUB is MySQL's way to write `d - INTERVAL …`. It subtracts an interval from a
date or a timestamp, and a date stays a date.

# Technical Description:
DATE_SUB(d: DATE | TIMESTAMP, interval: DURATION) → the type of d. It is DATE_ADD with
the interval negated. Months are moved first, and a day the target month lacks becomes
its last day: 31 March minus a month is 28 February, or 29 in a leap year. A DATE moved
by part of a day is refused. A TIMESTAMP is read at UTC. A NULL argument returns NULL.
PURE, DETERMINISTIC.

# Examples:
The last three months of orders:
  σ order_date > DATE_SUB(DATE '2026-10-16', INTERVAL 3 MONTH) (Orders)

# Pushdown:
Runs in the engine.

# See Also:
[date_add](date_add.md), [dateadd](dateadd.md), [duration-literal](../../literals/duration-literal.md)
