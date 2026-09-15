# Name: Gap-and-Island / Sessionization (SESSIONIZE)

# Syntax:
SESSIONIZE <orderColumn> GAP <threshold> [PER <key> [, <key> …]] AS <session> (R)

SESSIONIZE ts GAP DURATION 'PT30M' PER user_id AS session (Events)

# Description:
SESSIONIZE solves the famous "gap-and-island" / sessionization problem: it walks an
ordered stream and splits it into **sessions** wherever there is an idle gap. It is
the operator behind "group these clicks into browsing sessions", "collapse these
sensor pings into bursts of activity", and "find runs of consecutive events" — work
that in SQL is a multi-CTE `LAG`/running-sum incantation. Every input row is kept;
SESSIONIZE only *adds* a session-id column, so it composes cleanly with the rest of
your query.

# Technical Description:
Within each partition (the optional `PER` keys; absent means one partition over the
whole relation), rows are ordered ascending by `<orderColumn>` and scanned once. The
first row of each partition is session `1`; a new session begins whenever the gap to
the prior row exceeds the threshold:

```
orderᵢ − orderᵢ₋₁ > threshold      ⇒  new session
```

The appended `AS <session>` column is a dense, 1-based `NUMBER` that increments at
each boundary and restarts at `1` for every partition. The order column is typically
a `TIMESTAMP` (with a `DURATION` gap, e.g. `DURATION 'PT30M'`) or a `NUMBER` (with a
`NUMBER` gap) — leaning on the first-class temporal arithmetic substrate
(`TIMESTAMP − TIMESTAMP → DURATION`). The gap is **strict**: a gap exactly equal to
the threshold stays in the same session. A `NULL` order value never opens a new
session (it stays in the running one). The output schema is the input schema with the
session column appended.

SESSIONIZE buffers and sorts each partition before emitting, so it is a blocking
operator (`[bag]` materialisation), never pushes down to a source, and is subject to
the boundedness check over unbounded inputs.

# Examples:
Group web events into 30-minute browsing sessions per user:
  SESSIONIZE ts GAP DURATION 'PT30M' PER user_id AS session (Events)

Collapse consecutive sensor readings into bursts (idle > 5 sequence units):
  SESSIONIZE seq GAP 5 AS burst (Readings)

Sessionize across the whole stream with no partition:
  SESSIONIZE at GAP DURATION 'PT1H' AS visit (PageViews)

# Worked Example:
A product team wants to count distinct browsing sessions, where a session ends after
30 minutes of inactivity. The raw event log is one row per page view:

```relix
Events := [
| user_id | ts                     |
|---------|------------------------|
| 1       | 2026-01-01T10:00:00Z   |
| 1       | 2026-01-01T10:20:00Z   |
| 1       | 2026-01-01T11:30:00Z   |
| 2       | 2026-01-01T09:00:00Z   |
];

Sessions := { SESSIONIZE ts GAP DURATION 'PT30M' PER user_id AS session (Events) };
```

Within user `1`, the gap `10:00 → 10:20` is 20 minutes (≤ 30, same session) but
`10:20 → 11:30` is 70 minutes (> 30, new session). User `2` has a single event. The
session id restarts per user:

```relix
query { Sessions };
```

```
 user_id  ts                    session
 ───────  ────────────────────  ───────
       1  2026-01-01T10:00:00Z        1
       1  2026-01-01T10:20:00Z        1
       1  2026-01-01T11:30:00Z        2
       2  2026-01-01T09:00:00Z        1
(4 rows)
```

"How many sessions did each user have, and how long was each?" is then an ordinary
aggregation over the labelled output:

```relix
SessionSpans := {
  γ user_id, session, COUNT(ts) → events, MIN(ts) → started, MAX(ts) → ended (Sessions)
};
```

# Limitations:
The added column needs a name the input does not already use. Over a
schema-on-read source (JSON, HTTP, MongoDB) there is no declared heading to
clash with, so the operator always runs; if a document turns out to carry a
field of that name, the added column replaces it.

SESSIONIZE orders by, and computes the gap on, a **single** column. The threshold
must be a constant literal — a `NUMBER` for a numeric order column or a `DURATION`
for a temporal one. The boundary test is a fixed `gap > threshold` rule; arbitrary
boundary predicates (e.g. "new session when the page changes") are not yet
expressible. As a blocking operator it is subject to the boundedness check over
unbounded inputs.

# Alternatives:
WINDOW LAG can compute the inter-row gap as a column if you want to apply your own
boundary logic, but it does not assign the running session id. TOP and the window
ranking functions rank within a partition but do not segment by a gap.

# See Also:
[window-offset](../operators/window-offset.md), [downsample](downsample.md),
[aggregation](../operators/group.md), [duration literal](../literals/duration-literal.md)

# Notes:
The session id is deterministic for a given partition and order column. The order
column is sorted ascending before sessions are assigned, so unsorted input is handled
correctly.
