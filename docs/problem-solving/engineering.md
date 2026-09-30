# Engineering the solution

The recipes and case studies get the *answer* right. This part is about making a correct
query *run well* — reading what the engine decided, keeping the work where it belongs, and
moving a finished solution into a program. None of it changes what a query means; it
changes what it costs.

## Reading a plan

A query says *what*; the plan says *how*. `--explain` (or the `relix.plan` relation, the
same information as rows) prints the rewrites the optimiser applied and the physical plan
it will run. Read it before assuming anything about cost.

The most important thing a plan tells you over a database connection is **where each part
runs**. This query, over a Postgres connection, folds entirely into one SQL statement:

```
τ region (σ status = "paid" ∧ amount > 100 (sales.orders))
```

```
PushedScan [jdbc/sales] SELECT "id", "amount", "status", "region" FROM "orders"
  WHERE (("status" = 'paid') AND ("amount" > 100)) ORDER BY ("region") ...
```

The selection and the sort became a `WHERE` and an `ORDER BY`; the database does the work
and the engine receives only the rows it asked for. A `PushedScan` line is the plan
telling you a whole sub-tree left the engine.

## What stops a pushdown

A pushdown is not guaranteed, and the plan is where you find out it did not happen. The
recurring reasons:

- **A hard barrier.** `WHY` reifies lineage and no rewrite crosses it, so nothing above it
  pushes. It appears in the plan as a bare `Why` with the work stacked in-engine beneath.
- **Cross-connection work.** A join whose two sides are *different* connections cannot push
  — no single database can see both — so each side's scan pushes and the join runs in the
  engine. That is federation, and it is the [reconciliation](case-studies/reconciliation.md)
  case study's whole point.
- **The backend cannot express it.** A MongoDB source pushes σ, μ and cheap π/λ into an
  aggregation pipeline, but evaluates τ and γ in the engine over the rows that come back. A
  `σ` immediately above a window function stays in-engine, because SQL `WHERE` cannot
  reference a window column at the same level.
- **A collation mismatch.** A string comparison pushes only when the engine can trust the
  column compares as it does; on a case-insensitive database it is kept in-engine unless
  the connection declares otherwise.

When a plan shows work in the engine you expected in the database, it is one of these — not
a mystery. The `pushdown` reference page is the exhaustive account.

## Bounding a generator

A generator relation can be endless — the naturals, a range with no top. An operator that
must see all its input before producing any output — γ, δ, a sort, `COVER`, `ITERATE`, a
cumulative `ROLLING` — cannot run over an endless input, and the engine refuses it at plan
time rather than looping forever.

The fix is to put the bound **below** the blocking operator: a `σ` that the generator can
satisfy, or a `λ`/`TOP` that caps the stream. *Generator proposes, selection disposes* —
and the selection has to come first. A `TOP n` over a generator is the rescue case the
planner recognises: it makes the stream finite, so a blocking operator above it is safe
again.

## Materialization budgets

Some operators must hold rows in memory: a join's build side, a spool shared between two
readers, everything a `FIX` or `ITERATE` has derived so far. The engine shares
sub-expressions and spills work it can recompute for free, so most plans stay lean without
help. When one does not — a recursion that runs away, a product that explodes — two caps
turn a hang into a clear error:

- `--max-fixpoint-rounds` bounds how many rounds one `FIX`/`ITERATE` may iterate. Pick it
  from how *deep* you expect to go — a hierarchy's levels, a graph's diameter — not from
  the size of the answer.
- `--max-materialized-rows` bounds how many rows one operator may hold. Pick it when you do
  *not* know the shape of a terminating answer, since it bounds memory rather than depth.

Both are off unless asked for; setting both on a query you are unsure of is reasonable.

## Working offline

Analysing a query against a database connection normally introspects the live catalog for
each table's columns. That needs the database reachable, which is the wrong dependency for
writing and checking a query at your desk or in CI. Two ways to cut it:

- **Declare the schema.** A binding-form source states a table's columns inline
  (`source Orders from shop { table: "orders", schema: { id: NUMBER, amount: NUMBER } }`),
  so analysis, optimisation and plan inspection all run with nothing connected. The query
  contacts the database only when it actually executes.
- **Snapshot the catalog.** Capture the schema once and analyse against the snapshot, so a
  whole script type-checks and plans offline and reconnects only to run.

Either way, *reading a plan, checking a pushdown, and catching a wrong column name* are
things you do without touching the database — which is what makes them cheap enough to do
on every change.

## Moving a solution into Java

A `.relix` script is one front end; a program is another. When a solution graduates from
"a query I run" to "a step inside an application", it moves onto the **embedding API** —
the same engine, reached from Java, described in the programming guide.

The shape carries over directly. Building a query is the algebra as method calls, one per
operator; `optimized()` returns the rewritten form to inspect; `render()`, `explain()` and
`schema()` are terminals that execute nothing; `stream()` / `toList()` run it. A value that
varies — an order id, a threshold — is bound by building the query with it
(`select(eq(attr("id"), num(orderId)), rel("Orders"))`), not by pasting it into text. What
was a `.relix` script and a CLI flag becomes a `Relix` session and a method call, and the
query you verified against the engine is the query the program runs.

The programming guide is where that surface is taught, and its Java examples compile and
run as part of the build — so, like this manual's `relix` blocks, they are real.
