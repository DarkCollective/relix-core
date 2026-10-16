# Name: ADD_MONTHS (move a date by whole months, as Oracle does)

# Syntax:
ADD_MONTHS(<date-or-timestamp>, <n>)

π id, ADD_MONTHS(period_end, 1) → next_period_end (Billing)

# Description:
ADD_MONTHS moves a date by a number of whole months, following Oracle's rule for the
end of a month. If the target month lacks the day, the result is that month's last
day. The last day of a month also stays a last day, so the end of February plus one
month is 31 March. That is what you want when a period always ends on a month's end.

# Technical Description:
ADD_MONTHS(d: DATE | TIMESTAMP, n: NUMBER) → the type of d. `n` is cut to a whole
number toward zero, and a negative `n` moves backwards. A TIMESTAMP is read at UTC
and keeps its time of day. The last-day rule is where ADD_MONTHS differs from
`d + INTERVAL '1 month'` and DATEADD. Those keep the day of the month when they can,
so 28 February plus a month is 28 March. A NULL argument returns NULL. PURE,
DETERMINISTIC.

# Examples:
The next month's end after each period's end:
  π id, ADD_MONTHS(period_end, 1) → next_period_end (Billing)

The same date a year ago:
  π order_id, ADD_MONTHS(order_date, -12) → year_before (Orders)

# Pushdown:
Runs in the engine.

# See Also:
[dateadd](dateadd.md), [last_day](last_day.md), [duration-literal](../../literals/duration-literal.md)
