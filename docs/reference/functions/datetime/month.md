# Name: MONTH (extract month)

# Syntax:
MONTH(<date-or-timestamp>)

γ MONTH(order_date) → mo, SUM(amount) → revenue (Orders)

# Description:
MONTH pulls the month number (1–12) out of a date or timestamp. Use it to group or
filter by month — monthly reports, seasonal patterns, "everything in December".

# Technical Description:
MONTH(d) → NUMBER (1–12). Accepts a DATE or TIMESTAMP (read at UTC); other types
are an evaluation error. NULL input returns NULL. PURE, DETERMINISTIC.

# Examples:
Monthly revenue within a year:
```relix
γ MONTH(order_date) → mo, SUM(amount) → revenue
  (σ YEAR(order_date) = 2026 (Orders))
```

December records:
  σ MONTH(event_date) = 12 (Calendar)

# Pushdown:
SQL: folds to `EXTRACT(MONTH FROM <col>)` on every dialect but SQLite, which has
no date type to extract from.
MongoDB: folds to `{"$month": "$<col>"}` inside a `$project` stage (requires alias).

# Limitations:
Argument must be a DATE or TIMESTAMP. Returns 1–12. TIMESTAMP at UTC. NULL in →
NULL out.

# See Also:
[year](year.md), [day](day.md), [date_trunc](date_trunc.md)
