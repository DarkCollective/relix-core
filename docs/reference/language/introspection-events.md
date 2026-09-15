# Name: Observability feed (`relix.events` / `relix.rules`)

# Syntax:
relix.events    -- the previous run's decisions
                --   (seq, stage, code, description, target, rows, elapsed)
relix.rules     -- the optimizer's slice of that feed (seq, code, description, target)

# Description:
Every other `relix.*` relation answers *what is* — which relations exist, their
columns, the shape of each expression tree. `relix.events` answers *what
happened*: it is the same feed of engine decisions that `--trace` prints, exposed
as rows so `σ`, `γ`, and `τ` can read it instead of your eyes.

- **`relix.events`** — one row per observed decision, with columns:
  - `seq` (`NUMBER`) — the event's 1-based position in the feed.
  - `stage` (`STRING`) — `"OPTIMIZE"` (a logical rewrite rule fired), `"PLAN"`
    (a physical planning choice), or `"EXECUTE"` (a decision taken while running).
  - `code` (`STRING`) — the short identifier, e.g. `"SEL-001"`, `"JOIN"`.
  - `description` (`STRING`) — a one-line summary of the decision.
  - `rows` (`NUMBER`) — the quantity the event measured, NULL for the majority
    that measured nothing. Every executed query reports one, under `code = "ROWS"`.
  - `elapsed` (`DURATION`) — how long the step took, NULL wherever `rows` is.
    Wall clock, so it is a number to diagnose a slow query with rather than one to
    build a check on.
  - `target` (`STRING`) — the query or relation it applies to, NULL when the
    emitter did not know one.
- **`relix.rules`** — the `"OPTIMIZE"` rows of `relix.events`, with the (constant)
  `stage` column dropped. The direct answer to "which rewrites fired?".

## The feed is the *previous* statement's

`relix.events` holds the events of the statement that ran **before** the one
querying it — never its own. A statement's events do not exist until it has been
optimized, planned, and executed, and all three happen after the analysis that
has to register the relation, so a statement cannot observe itself.

What follows from that:

- **In the REPL**, where each statement is re-analysed, `relix.events` shows the
  run immediately before. Run a query, then ask what it did.
- **In a one-shot script run**, the whole file is analysed once, before anything
  executes, so there is no earlier feed to carry in and `relix.events` is empty.
  Use `--trace` there.
- Each observed run **replaces** the window rather than adding to it, so the
  relation describes one run, not a session total.

That last point has a consequence worth stating outright: **the query that reads
the feed is itself a run, and replaces it.** So a feed is readable once — after

    :opt Report
    query { relix.rules };          -- the firings
    query { γ code, COUNT(*) → fired (relix.rules) };   -- empty: reads the reader

the second query sees the feed of the first, not of `:opt`. Slice a feed in one
query, or re-run the statement that produced it.

The retained feed is capped, so a very long run keeps its most recent events;
`seq` numbers what was retained.

## Which stages appear

A run only reports what it actually did. A plain query in the REPL is planned and
executed but not optimized, so it contributes `PLAN` and `EXECUTE` rows and no
`OPTIMIZE` ones; `:opt` and `:explain` run the optimizer and contribute the
`OPTIMIZE` rows that `relix.rules` reads. Asking for the feed does not cause work
that the run would not otherwise do.

# Technical Description:
`relix.events` is a read-only system relation in the reserved `relix` namespace,
like the rest of the catalog: a user definition can never redefine it, and it is
never pushed down to a backend — the rows are engine state, not table data.

`relix.rules` is not computed separately. It is a *view* over the feed, in the
same derived-over-primitive layering as `relix.dependencies` over `relix.plan`:

    relix.rules = π seq, code, description, target (σ stage = "OPTIMIZE" (relix.events))

so the two cannot disagree about what fired. `seq` is kept rather than dropped
because the feed carries no timestamp: arrival order is its only temporal fact,
and a `τ` needs a stored key to sort on — the algebra guarantees no order that a
scan did not declare.

`rows` is what makes the feed arithmetic rather than prose. A `description` is
written for a person reading a trace, so a consumer that wants to *compute* with
what a run observed — comparing an estimate against the cardinality actually
produced — would otherwise have to read a number back out of a sentence. It is
NULL for every event that measured nothing: a rule firing describes a rewrite, it
does not count anything. `relix.rules` therefore has no `rows` column at all,
since its slice is exactly the events that never carry one.

The count an executed query reports is the rows it **delivered**, which equals the
query's cardinality whenever the result was read to the end. A query that matched
nothing still reports, as `0` — "returned no rows" and "counted nothing" are
different facts, and the column distinguishes them: `0` for the first, NULL for
the second. So `γ SUM(rows) (σ code = "ROWS" (relix.events))` totals a run's
output without an empty result silently dropping out of the sum.

Three `EXECUTE` codes carry one, and they measure different things. `"ROWS"` is the
query's own output, above. `"SCAN"` is a base relation read to its end — the
relation's real size, which is why a scan stopped early by a `λ` reports nothing:
that count would be about the consumer. `"MATERIALIZE"` is a blocking operator
reporting what it buffered, named by its plan operator (`Sort`, `Aggregate`,
`Join`) rather than by a relation, since the buffer belongs to the operator and
not to anything a user named. It is how a query's memory becomes answerable: an
operator that cannot emit a row before it has read its last holds its whole input,
and nothing in the plan says how much that is. A join that buffers **both** sides
emits two.

A query that **failed** part-way reports no count at all — not even a partial one.
It delivered no result, so there is no cardinality to attribute to it, and a
partial figure in the feed would be indistinguishable from a small one. The
absence is the signal: a run whose feed carries fewer `"ROWS"` events than it ran
queries had one fail.

## Where the time went

`elapsed` is the profiling half of the feed, and the three codes that carry it
time deliberately different things.

`"ROWS"` is the query's own wall clock, from the first pull to the last, and is
therefore **inclusive** — it covers every scan, buffer and join done on the
query's behalf, because a pull-based engine does that work when it is asked and
there is no interval that excludes the asking.

`"SCAN"` and `"MATERIALIZE"` are **self** time, and that distinction is the whole
point of the column. Only the pull is timed: a scan reports what producing its
rows cost, not what the join above it then did with them. Timed the other way, a
scan under a join would report roughly the whole query and the feed would say the
leaf was the problem in every plan ever written. So the numbers nest — each
`"SCAN"` and `"MATERIALIZE"` is a share of its query's `"ROWS"` — and the
arithmetic that answers *is the join the problem, or one of the scans?* is the
query's total against the sum of the parts beneath it:

    query { τ elapsed DESC (σ elapsed IS NOT NULL (relix.events)) };

Two things follow from `elapsed` being wall clock. It varies run to run with the
machine, the page cache and whatever else the host is doing, so it is read to find
the operator worth looking at and not to certify that anything is fast enough. And
it is a real `DURATION` rather than a count of milliseconds, so `SECONDS(elapsed)`
and `τ elapsed DESC` work on it without a unit having to be agreed with whoever
reads the query.

The column is `elapsed` and not `duration` because `DURATION` opens a temporal
literal, so a column of that name could never be projected or sorted on.

`target` is NULL for an event no query owns, and NULL is a value the ordinary
predicates treat as unknown, so `σ target = "X"` drops those rows. Use
`σ target IS NOT NULL` when you want exactly the attributable ones.

# Examples:
Given a session that has just run a join:

```relix
Users  := [| id | name  |
           | 1  | Alice |];
Orders := [| id | amount |
           | 1  | 10     |];
query { Users ⋈ Orders };
```

The next statement can read what the engine decided, in order:

```relix
query { τ seq (π seq, stage, code, target (relix.events)) };
-- seq  stage  code  target
-- ---  -----  ----  ------
-- 1    PLAN   JOIN  Joined
```

Whether one particular rewrite fired on the last run:

```relix
query { σ code = "SEL-001" (relix.rules) };
```

How often each optimizer rule fired — the feed aggregated like any relation:

```relix
query { γ code, COUNT(*) → fired (relix.rules) };
-- code      fired
-- --------  -----
-- SEL-001   2
-- PROJ-003  1
```

How the work split across stages:

```relix
query { γ stage, COUNT(*) → decisions (relix.events) };
-- stage     decisions
-- --------  ---------
-- OPTIMIZE  3
-- PLAN      1
```

Only the decisions attributable to a query, newest first:

```relix
query { τ seq DESC (σ target IS NOT NULL (relix.events)) };
```

Where the last run's time went, slowest step first — the steps that measured one,
ordered by how long they took:

```relix
query { τ elapsed DESC (π target, code, elapsed (σ elapsed IS NOT NULL (relix.events))) };
```

Joining the feed to the structural catalog — which of the relations the optimizer
touched are views rather than base tables:

```relix
Touched := { δ (π target→name (σ target IS NOT NULL (relix.events))) };
query { σ kind = "QR" (Touched ⋈ relix.relations) };
```

# See also:
- [introspection stdlib](introspection-stdlib.md) — `relix.unused` / `relix.deps` /
  `relix.impact` and friends, the structural half of the same idea.
- `relix help catalog` — every `relix.*` relation and its columns.
- [optimizer](../advanced/optimizer.md) — what the rule codes in `relix.rules` mean.
