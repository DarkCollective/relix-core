# Name: to_date (parse a string to a DATE)

# Syntax:
to_date(<string-or-temporal>)

π id, to_date(date_str) → day (RawImport)

# Description:
to_date turns a text string into a proper DATE value, so date data that arrived as
text (from a CSV, a JSON field, or an open schema) can be compared and computed
like a real date.

# Technical Description:
to_date(s: STRING | DATE | TIMESTAMP) → DATE. Parses s as an ISO-8601 date (YYYY-MM-DD,
LocalDate.parse); a malformed string is an evaluation error. NULL input returns
NULL. PURE, DETERMINISTIC.

Given a temporal value it converts it as SQL's CAST does: a TIMESTAMP gives its date at UTC, and ISO text naming a timestamp gives that timestamp's date at UTC. A
DATE is returned as it is, and any other kind is an evaluation error. `CAST(x AS
DATE)` is this function.

# Examples:
Convert an imported text date:
  π id, to_date(date_str) → day (RawImport)

Filter parsed dates:
  σ to_date(due) >= DATE '2026-01-01' (Tasks)

# Limitations:
Expects ISO-8601 (YYYY-MM-DD); other formats error. STRING argument; NULL in →
NULL out.

# Alternatives:
to_timestamp for date+time strings; to_time for clock-time strings; a DATE '…'
literal when the value is constant.

# See Also:
[to_timestamp](to_timestamp.md), [to_time](to_time.md), [date-literal](../../literals/date-literal.md)
