# Name: Interval Join (IJOIN)

# Syntax:
```
Left IJOIN <relation> (lStart, lEnd, rStart, rEnd) Right
```

`<relation>` is one of the thirteen Allen interval-relation keywords —
`PRECEDES`/`PRECEDED_BY`, `MEETS`/`MET_BY`, `OVERLAPS`/`OVERLAPPED_BY`,
`STARTS`/`STARTED_BY`, `DURING`/`CONTAINS`, `FINISHES`/`FINISHED_BY`, and
`EQUALS` — plus the convenience superset `INTERSECTS` (any shared time).

```
Stays    IJOIN OVERLAPS  (Stays.checkin, Stays.checkout, Bookings.from, Bookings.to) Bookings
Meetings IJOIN INTERSECTS(Meetings.start, Meetings.end, Blocks.start, Blocks.end)    Blocks
Events   IJOIN PRECEDES  (Events.start, Events.end, Deadlines.start, Deadlines.end)  Deadlines
```

# Description:
An interval join matches two relations where each row carries a temporal
(or numeric) interval `[start, end)` and the two intervals satisfy a named
geometric relationship. Rather than writing a multi-condition theta join, you
state the relationship by name — one of Allen's thirteen interval relations
(`OVERLAPS`, `DURING`, `CONTAINS`, `MEETS`, `PRECEDES`, `STARTS`, `FINISHES`,
`EQUALS`, and their converses) or the symmetric convenience `INTERSECTS` — and
Relix applies the right predicate for you.

The thirteen base relations form seven converse pairs — for every relation `X`
there is an `X_BY` such that `ℓ X r` iff `r X_BY ℓ` (with `EQUALS` its own
converse). Naming the converse explicitly lets you keep the two inputs in their
natural order instead of swapping them.

IJOIN is an *inner* join: only pairs of rows whose intervals satisfy the named
relationship are emitted. There is no NULL-padding.

# Technical Description:
Intervals are half-open: `[start, end)` — start is inclusive, end is
exclusive. This matches the standard convention for date/time ranges (e.g.
a hotel stay `[check-in, check-out)` or a calendar event `[start, end)`).

The fourteen supported relations (Allen's thirteen + `INTERSECTS`) and their
endpoint predicates are:

```
INTERSECTS     ℓ.s < r.e ∧ r.s < ℓ.e     any part of L overlaps any part of R (symmetric)
PRECEDES       ℓ.e < r.s                  L ends strictly before R begins (gap between them)
PRECEDED_BY    ℓ.s > r.e                  L begins strictly after R ends (converse of PRECEDES)
MEETS          ℓ.e = r.s                  L ends exactly where R begins (adjacent, no gap)
MET_BY         ℓ.s = r.e                  L begins exactly where R ends (converse of MEETS)
OVERLAPS       ℓ.s < r.s ∧ ℓ.e > r.s      L starts before R and ends inside R
               ∧ ℓ.e < r.e                 (strictly — L neither contains nor equals R)
OVERLAPPED_BY  r.s < ℓ.s ∧ r.e > ℓ.s      R starts before L and ends inside L (converse)
               ∧ r.e < ℓ.e
STARTS         ℓ.s = r.s ∧ ℓ.e < r.e      same start, L ends first
STARTED_BY     ℓ.s = r.s ∧ r.e < ℓ.e      same start, R ends first (converse of STARTS)
DURING         r.s < ℓ.s ∧ ℓ.e < r.e      L is strictly within R (both endpoints interior)
CONTAINS       ℓ.s < r.s ∧ r.e < ℓ.e      L strictly contains R (converse of DURING)
FINISHES       ℓ.e = r.e ∧ r.s < ℓ.s      same end, L starts later
FINISHED_BY    ℓ.e = r.e ∧ ℓ.s < r.s      same end, R starts later (converse of FINISHES)
EQUALS         ℓ.s = r.s ∧ ℓ.e = r.e      identical intervals (self-converse)
```

**Semantics.** All thirteen base relations use strict endpoint bounds (Allen
1983), so they are mutually exclusive and jointly exhaustive — any pair of
intervals stands in **exactly one** of them. Shared-boundary cases are named by
their own relation (`STARTS`, `FINISHES`, `EQUALS`) rather than folded into
`DURING`/`CONTAINS`: an interval with the *same* start as another is `STARTS` (or
`STARTED_BY`/`EQUALS`), never `DURING`. The only relation that overlaps the
others is the convenience `INTERSECTS`, which subsumes every relation that shares
any time.

Column names may be qualified with a table prefix (`Table.column`). The four
endpoint columns are resolved to indices at plan time; the validator checks they
exist in their respective schemas and share a compatible temporal type. The
operation runs in-engine and never pushes down to SQL or MongoDB backends.

Execution is endpoint-based rather than a quadratic nested loop. For the eleven
relations whose intervals must overlap or touch, IJOIN sorts the interval
endpoints of both sides and runs a **plane sweep**: as the sweep crosses each
endpoint it keeps a set of currently-active intervals per side and pairs each
opening interval only with the active intervals on the other side — exactly the
candidate pairs whose intervals intersect — then confirms the precise Allen
relation. The two disjoint relations (`PRECEDES`/`PRECEDED_BY`) instead sort the
right side once and binary-search the boundary, emitting the matching
suffix/prefix per left row. Both strategies cost `O((n+m)·log(n+m))` plus the
number of result rows, versus `O(n·m)` for a full nested loop.

# Allen Relation Reference:

Allen's thirteen relations cover every distinct way two intervals can relate on
a timeline, exhaustively and (in their strict form) exclusively. The diagrams
below use `L = [====]` for the left interval and `R = [====]` for the right. Each
relation is paired with its converse — flipping L and R turns `X` into `X_BY`.

```
INTERSECTS — any shared time (symmetric, convenience superset)
  L: [==========]
  R:       [===========]
  Rule: L.start < R.end  AND  R.start < L.end

PRECEDES / PRECEDED_BY — L entirely before R (gap between them)
  L: [====]
  R:          [====]
  Rule: L.end < R.start            (PRECEDED_BY: L.start > R.end)

MEETS / MET_BY — L ends exactly where R begins (adjacent, no overlap, no gap)
  L: [======]
  R:         [======]
  Rule: L.end = R.start            (MET_BY: L.start = R.end)

OVERLAPS / OVERLAPPED_BY — L starts before R and ends inside R
  L: [==========]
  R:       [===========]
  Rule: L.start < R.start  AND  L.end > R.start  AND  L.end < R.end
  Note: differs from INTERSECTS because L does not extend beyond R's end.
        OVERLAPPED_BY is the mirror case (R starts first).

STARTS / STARTED_BY — both begin together, L ends first
  L: [====]
  R: [==========]
  Rule: L.start = R.start  AND  L.end < R.end
                                   (STARTED_BY: R.end < L.end)

DURING — L strictly inside R            CONTAINS — L strictly contains R
  L:      [====]                          L: [=============]
  R: [=============]                      R:      [====]
  Rule: R.start < L.start                 Rule: L.start < R.start
        AND L.end < R.end                       AND R.end < L.end

FINISHES / FINISHED_BY — both end together, L starts later
  L:      [======]
  R: [===========]
  Rule: L.end = R.end  AND  R.start < L.start
                                   (FINISHED_BY: L.start < R.start)

EQUALS — identical intervals (self-converse)
  L: [==========]
  R: [==========]
  Rule: L.start = R.start  AND  L.end = R.end
```

`INTERSECTS` is the symmetric superset: any pair satisfying `OVERLAPS`,
`OVERLAPPED_BY`, `DURING`, `CONTAINS`, `STARTS`, `STARTED_BY`, `FINISHES`,
`FINISHED_BY`, or `EQUALS` also satisfies `INTERSECTS` (these all share time).
`MEETS`/`MET_BY` (touching, half-open → no shared instant) and
`PRECEDES`/`PRECEDED_BY` (disjoint) are the relations *not* captured by
`INTERSECTS`.

# Examples:

Find every hotel stay that overlaps an existing booking:
```
Stays IJOIN OVERLAPS (Stays.checkin, Stays.checkout, Bookings.from, Bookings.to) Bookings
```

Find shifts that a maintenance window runs into from earlier (the window
started before the shift and ends partway through it — the mirror of OVERLAPS):
```
Shifts IJOIN OVERLAPPED_BY (Shifts.start, Shifts.end, Windows.start, Windows.end) Windows
```

Any meeting that intersects (any overlap with) a maintenance window:
```
Meetings IJOIN INTERSECTS (Meetings.start, Meetings.end, Windows.start, Windows.end) Windows
```

Tasks that fall entirely within a project's active phase:
```
Tasks IJOIN DURING (Tasks.start, Tasks.end, Phases.start, Phases.end) Phases
```

Find project phases that fully contain a task (inverse — phase CONTAINS task):
```
Phases IJOIN CONTAINS (Phases.start, Phases.end, Tasks.start, Tasks.end) Tasks
```

Events that finish exactly when the next event begins (scheduling chains):
```
Events IJOIN MEETS (Events.start, Events.end, Next.start, Next.end) Events Next
```

Sessions that start the instant the preceding break ends (back-to-back, no gap
— the converse of MEETS):
```
Sessions IJOIN MET_BY (Sessions.start, Sessions.end, Breaks.start, Breaks.end) Breaks
```

Short launch promos that kick off a longer campaign (same start, promo ends
first):
```
Promos IJOIN STARTS (Promos.start, Promos.end, Campaigns.start, Campaigns.end) Campaigns
```

Campaigns viewed from the other side — those that open with a launch promo
(same start, campaign runs longer — the converse of STARTS):
```
Campaigns IJOIN STARTED_BY (Campaigns.start, Campaigns.end, Promos.start, Promos.end) Promos
```

Steps that are entirely before their dependencies start:
```
Steps IJOIN PRECEDES (Steps.start, Steps.end, Deps.start, Deps.end) Deps
```

Shifts that share their end-of-day with the store's closing window
(`FINISHES` — same end, shift starts later than the window opens):
```
Shifts IJOIN FINISHES (Shifts.start, Shifts.end, Hours.open, Hours.close) Hours
```

Billing periods that end with a final grace window (same end, period starts
earlier — the converse of FINISHES):
```
Periods IJOIN FINISHED_BY (Periods.start, Periods.end, Grace.start, Grace.end) Grace
```

Two reservations covering exactly the same slot (`EQUALS`):
```
A IJOIN EQUALS (A.start, A.end, B.start, B.end) B
```

A late job whose interval begins only after an earlier job has ended
(`PRECEDED_BY` — the converse of `PRECEDES`, keeping `Late` on the left):
```
Late IJOIN PRECEDED_BY (Late.start, Late.end, Early.start, Early.end) Early
```

# Worked Example:

## Hotel availability check

A booking platform stores two kinds of intervals: `Requests` (potential
bookings not yet confirmed) and `Confirmed` (accepted reservations). Before
accepting a new request, the platform needs to know which existing confirmed
reservations it would conflict with.

```relix
Confirmed := [
| booking_id | room | checkin              | checkout             |
|------------|------|----------------------|----------------------|
| B1         | 101  | 2026-03-01T14:00:00Z | 2026-03-05T11:00:00Z |
| B2         | 101  | 2026-03-08T14:00:00Z | 2026-03-10T11:00:00Z |
| B3         | 102  | 2026-03-03T14:00:00Z | 2026-03-07T11:00:00Z |
];

Requests := [
| request_id | room | from_date            | to_date              |
|------------|------|----------------------|----------------------|
| R1         | 101  | 2026-03-04T14:00:00Z | 2026-03-09T11:00:00Z |  -- spans B1 end and B2 start
| R2         | 102  | 2026-03-01T14:00:00Z | 2026-03-03T14:00:00Z |  -- ends exactly when B3 starts
| R3         | 102  | 2026-03-10T12:00:00Z | 2026-03-12T11:00:00Z |  -- after all B3 (no conflict)
];
```

The intervals look like this on a timeline (per room):

```
Room 101:
  Confirmed:    B1 [=====Mar01–Mar05=====]       B2 [=Mar08–Mar10=]
  Request:                        R1 [======Mar04–Mar09======]
                                     ← conflicts with both B1 and B2

Room 102:
  Confirmed:             B3 [====Mar03–Mar07====]
  Request:  R2 [Mar01–Mar03]                   R3 [Mar10–Mar12]
            ← meets B3 (no overlap)             ← after B3 (no conflict)
```

**Step 1** — find overlapping conflicts using `INTERSECTS`:

```relix
Conflicts := {
    σ room = room_r (
        Requests IJOIN INTERSECTS
            (Requests.from_date, Requests.to_date, Confirmed.checkin, Confirmed.checkout)
        Confirmed
    )
};
```

`INTERSECTS` requires `R.from < C.checkout AND C.checkin < R.to` — any shared
time is a conflict. Because `IJOIN` matches on the interval endpoints only, the
`σ room = room_r` keeps just the *same-room* pairs (the join disambiguates
`Confirmed`'s `room` to `room_r`). Note that `R2` ends at `2026-03-03T14:00:00Z`
exactly when `B3` (its room) begins — half-open intervals make this a `MEETS`
relationship, not `INTERSECTS` — so `R2` does **not** appear:

```relix
query { Conflicts };
```

```
 request_id  room  from_date             to_date               booking_id  room_r  checkin               checkout
 ──────────  ────  ────────────────────  ────────────────────  ──────────  ──────  ────────────────────  ────────────────────
 R1           101  2026-03-04T14:00:00Z  2026-03-09T11:00:00Z  B1             101  2026-03-01T14:00:00Z  2026-03-05T11:00:00Z
 R1           101  2026-03-04T14:00:00Z  2026-03-09T11:00:00Z  B2             101  2026-03-08T14:00:00Z  2026-03-10T11:00:00Z
(2 rows)
```

R1 conflicts with both B1 and B2. R2 and R3 are conflict-free.

**Step 2** — report only the conflicting requests (without the booking detail):

```relix
BlockedRequests := {
    π request_id, room (Conflicts)
};
```

```
| request_id | room |
|------------|------|
| R1         | 101  |
```

**Step 3** — to also detect the MEETS case ("back-to-back with no gap, but
no actual overlap"), use a separate query:

```relix
BackToBack := {
    σ room = room_r (
        Requests IJOIN MEETS
            (Requests.from_date, Requests.to_date, Confirmed.checkin, Confirmed.checkout)
        Confirmed
    )
};
```

As in Step 1, the `σ room = room_r` keeps only same-room adjacency — `R2` ends
exactly when `B3` begins, both in room `102`:

```
| request_id | room | from_date            | to_date              | booking_id | room_r | checkin              | checkout             |
|------------|------|----------------------|----------------------|------------|--------|----------------------|----------------------|
| R2         | 102  | 2026-03-01T14:00:00Z | 2026-03-03T14:00:00Z | B3         | 102    | 2026-03-03T14:00:00Z | 2026-03-07T11:00:00Z |
```

Whether a back-to-back booking is a conflict depends on business rules (e.g.
does housekeeping need a gap?). By keeping the two concepts separate in the
query — `INTERSECTS` for overlap, `MEETS` for adjacency — the business rule
lives in the RA script, not hidden inside a comparison.

---

## Schedule gap detection

A maintenance team schedules maintenance windows. A gap between two consecutive
windows means a service period — the PRECEDES relationship identifies windows
followed by another window with an unscheduled gap in between.

```relix
Windows := [
| id | start                | end                  |
|----|----------------------|----------------------|
| W1 | 2026-04-01T02:00:00Z | 2026-04-01T04:00:00Z |
| W2 | 2026-04-01T04:00:00Z | 2026-04-01T06:00:00Z |  -- meets W1 exactly
| W3 | 2026-04-01T08:00:00Z | 2026-04-01T09:00:00Z |  -- gap after W2
];

-- Windows that PRECEDE another (a gap exists between them)
Gaps := {
    π W.id, W.end, Later.start
    (σ W.id != Later.id
     (ρ W (Windows) IJOIN PRECEDES
          (W.start, W.end, Later.start, Later.end)
      ρ Later (Windows)))
};
```

```
| id | end                  | start                |
|----|----------------------|----------------------|
| W2 | 2026-04-01T06:00:00Z | 2026-04-01T08:00:00Z |
```

W2 precedes W3 (gap from 06:00 to 08:00). W1 does not precede W2 — it *meets*
it exactly (no gap). The gap size can be computed from the result:
`Later.start − W.end` gives `DURATION 'PT2H'`.

# Limitations:
IJOIN is an inner join — there is no outer form or NULL-padding. Endpoints must
be valid comparable values (temporal or numeric); NULL endpoints cause that row
to produce no matches (silently dropped). All thirteen Allen relations and their
converses are first-class keywords (plus the `INTERSECTS` convenience), so you
never need to swap Left and Right or hand-write a theta join to express a
relation — including `EQUALS`, `OVERLAPPED_BY`, `MET_BY`, and `PRECEDED_BY`.

**SQL pushdown.** Over two bare connection tables on one
JDBC connection, an IJOIN folds into a single `JOIN … ON <endpoint predicate>`
pushed scan whose `ON` clause is the chosen relation's endpoint comparison — e.g.
`OVERLAPS` → `l.s < r.s AND l.e > r.s AND l.e < r.e`. The comparisons mirror the
in-engine test exactly, and SQL's three-valued logic excludes any NULL-endpoint row
(matching the engine's NULL-endpoint drop). It works on **every** SQL dialect (only
`<`/`=`/`>` are used — no SQL:2011 period-predicate syntax is required); a join
spanning two connections, or a non-database source, falls back to the in-engine
executor. MongoDB pushdown is not implemented.

When not pushed, IJOIN runs in-engine (endpoint plane sweep / sorted band — see
Technical Description). The sweep is output-sensitive: a relation that genuinely
matches most pairs still produces a large result, so on equality-partitioned data
add a selection (`σ`) on the partition keys (e.g. same room, same sensor) before
the IJOIN to keep each sweep small. A merge variant skips the sort when both
inputs already arrive ordered, reusing the engine's ordering machinery.

# Alternatives:
A theta join (`⨝ / ><`) can express any single interval predicate as two
range comparisons, but requires writing out the predicates by hand. IJOIN
names the relationship declaratively. For *nearest-in-time* point matching
(one side is a point, not a range) use `ASOF` instead.

# See Also:
[asof-join](asof-join.md), [theta-join](theta-join.md), [duration-literal](../literals/duration-literal.md), [timestamp-literal](../literals/timestamp-literal.md), [select](../operators/select.md)

# Notes:
Half-open interval semantics `[start, end)` are the standard convention for
calendar intervals — a stay from `2026-03-01` to `2026-03-05` does **not**
include the checkout date itself. The MEETS predicate (`L.end = R.start`)
therefore represents a clean handoff with no overlap and no gap — a common
boundary condition in scheduling. Use INTERSECTS (not OVERLAPS) when you want
any shared time regardless of which side starts first.
