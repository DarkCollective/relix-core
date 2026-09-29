# SLA breaches

*Temporal alignment + Sequence*

## The problem

*"Support tickets have an event log — opened, resolved, and status changes in between.
Which tickets breached the 8-hour resolution SLA, and how long did each one spend between
status changes?"*

## Classifying it

- *"how long from opened to resolved … breached the SLA"* — a **time** question: pair each
  ticket's open event with its resolve event and measure the span. Pairing two events of
  the same ticket is a self-join; measuring the span is temporal arithmetic
  (`TIMESTAMP − TIMESTAMP → DURATION`).
- *"how long between status changes"* — a **[sequence](../recipes/sequence.md)** question:
  compare each event with the previous one in the ticket's ordered log, `WINDOW LAG`.

Both need real timestamps, so the log is converted with `to_timestamp` first.

## The data

```relix
Events := [
| ticket | at                   | status   |
|--------|----------------------|----------|
| T1     | 2026-06-01T09:00:00Z | open     |
| T1     | 2026-06-01T11:00:00Z | resolved |
| T2     | 2026-06-01T09:00:00Z | open     |
| T2     | 2026-06-02T15:00:00Z | resolved |
| T3     | 2026-06-01T09:00:00Z | open     |
];

Typed := { π ticket, to_timestamp(at) → at, status (Events) };
```

T1 resolved in 2 hours, T2 took 30, T3 is still open.

## Stage 1: time to resolve, and the breaches (time)

Split the log into the open and resolved events, join them per ticket, and subtract:

```relix
Opened   := { π ticket, at → opened   (σ status = "open"     (Typed)) };
Resolved := { π ticket, at → resolved (σ status = "resolved" (Typed)) };
Elapsed  := { π ticket, resolved - opened → took (Opened ⋈ Resolved) };
query { Elapsed };
```

<!-- output: paste from a run. Expected: T1 took PT2H, T2 took PT30H. T3 has no resolved event, so the inner join drops it. -->

The breaches are a σ on the duration — comparing `DURATION`s directly:

```relix
query { σ took > DURATION 'PT8H' (Elapsed) };
```

<!-- output: paste from a run. Expected: T2 (PT30H). -->

## Stage 2: time in each status (sequence)

*How long did each event's status hold before the next change?* is the gap to the previous
row in the ticket's ordered log — `WINDOW LAG`:

```relix
query {
    π ticket, at, at - prev → since_prev (
        WINDOW LAG(at) SORT at ASC PER ticket AS prev (Typed))
};
```

<!-- output: paste from a run. Expected: each ticket's first event has a NULL gap; T1's resolved event is PT2H after its open, T2's is PT30H. -->

## What this shows

The join-and-subtract in Stage 1 is the general shape for *"duration between two related
events"*, and the `WINDOW LAG` in Stage 2 is *"duration between consecutive events"* — two
different questions that both reduce to temporal subtraction, and the reason time is its
own axis in this manual.

**T3 is the interesting omission.** The inner join in Stage 1 silently drops it, which is
correct for *"time to resolve"* (it has none) but **wrong** for *"still open past SLA"* —
that is a different question, about the absence of a resolve event more than 8 hours after
the open. Answering it needs a reference "now" and an [absence](../recipes/absence.md)
shape (an open with no matching resolve), and deciding it deliberately is exactly the
*Check it* discipline: what does the answer say about an input with no closing row?

## Recipes drawn on

- [As of, during, overlapping](../recipes/temporal-alignment.md) — temporal arithmetic and
  `DURATION` comparison.
- [In a row, per visit, gaps](../recipes/sequence.md) — `WINDOW LAG`.
- [None, never, missing](../recipes/absence.md) — the still-open-past-SLA question.
