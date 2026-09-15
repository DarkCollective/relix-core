# Name: DAY (extract day of month)

# Syntax:
DAY(<date-or-timestamp>)

σ DAY(invoice_date) = 1 (Invoices)

# Description:
DAY pulls the day-of-month (1–31) out of a date or timestamp. Use it for
day-level filters — "invoices dated the 1st", end-of-month checks, day buckets.

# Technical Description:
DAY(d) → NUMBER (1–31). Accepts a DATE or TIMESTAMP (read at UTC); other types are
an evaluation error. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
First-of-the-month invoices:
  σ DAY(invoice_date) = 1 (Invoices)

# Pushdown:
SQL: folds to `EXTRACT(DAY FROM <col>)` on all dialects.
MongoDB: folds to `{"$dayOfMonth": "$<col>"}` inside a `$project` stage (requires
alias). Note: MongoDB uses `$dayOfMonth`, not `$day`.

# Limitations:
Argument must be a DATE or TIMESTAMP. Returns the day of month (1–31), not the day
of week. TIMESTAMP at UTC. NULL in → NULL out.

# See Also:
[year](year.md), [month](month.md), [date_trunc](date_trunc.md)
