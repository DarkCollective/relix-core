# Name: CURRENT_DATE (today)

# Syntax:
CURRENT_DATE()

σ due_date = CURRENT_DATE() (Invoices)

# Description:
CURRENT_DATE returns today's date (in UTC) as a DATE — no time of day. Use it to
find records due today, compute days remaining, or filter to the current day.

# Technical Description:
CURRENT_DATE() → DATE. Returns LocalDate.now(UTC). Zero-argument. Reads system
state, so it is neither PURE nor DETERMINISTIC and is never folded by the
optimizer.

# Examples:
Invoices due today:
  σ due_date = CURRENT_DATE() (Invoices)

Days until a deadline:
  π id, (deadline - CURRENT_DATE()) → days_left (Tasks)

Records on or after today:
  σ event_date >= CURRENT_DATE() (Calendar)

# Pushdown:
The **call** is never handed to a backend — a database would answer from its own
clock, and the session's clock is what decides what "now" means here. The
**value** is: the engine evaluates it once for the run and sends the resulting
literal, so an operator containing it still folds. Every current-time call in one
run reads the same moment, so the folded and unfolded halves of a query agree.

# Reproducible runs (pinned clock):
By default NOW() reads the wall clock, so a query with a relative window returns
different rows each time it runs — which makes a result impossible to reproduce
later. Pass `relix --now <ISO-8601 UTC instant>` (or set `RELIX_NOW`) to pin
NOW(), CURRENT_DATE() and CURRENT_TIME() to a fixed instant for the whole run:

  relix --exec --now 2026-07-22T12:00:00Z report.relix

Every evaluation in that run then sees the same instant, so replaying the script
over the same data yields byte-identical results however much later it runs. The
functions stay non-PURE and non-DETERMINISTIC regardless — under the default
clock they still advance, so the optimizer must never fold or dedupe them.

# Limitations:
Non-deterministic (reads the clock); not folded. UTC-based, so "today" is the UTC
day.

# Alternatives:
NOW() for a full timestamp; CURRENT_TIME() for the clock time only.

# See Also:
[now](now.md), [current_time](current_time.md), [date-literal](../../literals/date-literal.md), [to_date](to_date.md)
