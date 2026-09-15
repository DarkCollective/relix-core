# Name: NOW (current timestamp)

# Syntax:
NOW()

π id, NOW() → fetched_at (Records)

# Description:
NOW returns the current moment as a TIMESTAMP (in UTC). Use it to stamp results
with the time they were produced, or to compute how long ago something happened
relative to right now.

# Technical Description:
NOW() → TIMESTAMP. Returns the current instant (Instant.now(), UTC). Zero-argument.
It reads system state, so it is neither PURE nor DETERMINISTIC — the optimizer
never folds or dedupes it, and every evaluation reflects the call time.

# Examples:
Stamp each row with the fetch time:
  π id, NOW() → fetched_at (Records)

Records from the last hour:
  σ created_at >= (NOW() - DURATION 'PT1H') (Events)

Age of each ticket:
  π id, (NOW() - opened_at) → age (Tickets)

# Pushdown:
The **call** is never handed to a backend — a database asked to evaluate NOW()
answers from its own clock, and the session's clock is what decides what "now"
means here. The **value** is: the engine evaluates NOW() once for the run and
sends the resulting TIMESTAMP literal, which a backend has no opinion about. So
`σ at < NOW() (conn.events)` folds into a `WHERE` and filters at the source, with
the session's instant in it.

Every current-time call in one run reads the same moment. That is what makes the
substitution honest rather than convenient: the value sent to the database is the
same value the rest of the query computes here, so a query half folded and half
not still compares against one instant.

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
Non-deterministic (reads the clock); not folded by the optimizer. Always UTC.

# Alternatives:
CURRENT_DATE for today's date only; CURRENT_TIME for the clock time only.

# See Also:
[current_date](current_date.md), [current_time](current_time.md), [timestamp-literal](../../literals/timestamp-literal.md), [date_trunc](date_trunc.md)
