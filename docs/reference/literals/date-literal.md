# Name: DATE literal

# Syntax:
DATE '<YYYY-MM-DD>'

DATE '2026-06-15'

# Description:
A DATE literal is a calendar day with no time-of-day — just year, month, and day,
like a birthday or a due date. Write it as the keyword DATE followed by an
ISO-8601 date in quotes. Use it to filter or compute against date columns.

# Technical Description:
`DATE '…'` parses an ISO-8601 calendar date (YYYY-MM-DD) into a zone-less civil
java.time.LocalDate at parse time; a malformed payload is a positioned parse
error. It infers as the DATE scalar type. DATE literals push down to SQL (per
dialect), except to SQLite, which has no date type. Single- or double-quoted payloads are both accepted.

# Examples:
Filter rows on or after a date:
  σ due_date >= DATE '2026-01-01' (Invoices)

Date arithmetic — days until due (DATE − DATE → DURATION):
  π id, (due_date - DATE '2026-06-15') → remaining (Invoices)

A computed deadline column:
  π id, DATE '2026-12-31' → year_end (Projects)

# Limitations:
A DATE has no time component (use TIMESTAMP for date+time). Comparisons must be
against another DATE (same-type only). The payload must be valid ISO-8601.

# Alternatives:
TIMESTAMP for an instant with time-of-day; TIME for a clock time only;
to_date(str) to parse a date from a string column.

# See Also:
[timestamp-literal](timestamp-literal.md), [time-literal](time-literal.md), [duration-literal](duration-literal.md), [to_date](../functions/datetime/to_date.md), [year](../functions/datetime/year.md), [month](../functions/datetime/month.md), [day](../functions/datetime/day.md)

# Notes:
DATE − DATE yields a DURATION; DATE ± DURATION yields a TIMESTAMP.
