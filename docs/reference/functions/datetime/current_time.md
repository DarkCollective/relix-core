# Name: CURRENT_TIME (current clock time)

# Syntax:
CURRENT_TIME()

π id, CURRENT_TIME() → at (Hits)

# Description:
CURRENT_TIME returns the current time of day (in UTC) as a TIME — no date. Use it
when only the clock time matters, such as comparing against business hours.

# Technical Description:
CURRENT_TIME() → TIME. Returns LocalTime.now(UTC). Zero-argument. Reads system
state, so it is neither PURE nor DETERMINISTIC and is never folded.

# Examples:
Stamp the clock time:
  π id, CURRENT_TIME() → at (Hits)

Within business hours right now:
  σ CURRENT_TIME() >= TIME '09:00:00' ∧ CURRENT_TIME() < TIME '17:00:00' (Flags)

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
Non-deterministic (reads the clock); not folded. UTC-based.

# Alternatives:
NOW() for a full timestamp; CURRENT_DATE() for today's date only.

# See Also:
[now](now.md), [current_date](current_date.md), [time-literal](../../literals/time-literal.md), [to_time](to_time.md)
