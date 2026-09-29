# When Relix is not the tool

The most useful thing a manual can do is say where it stops. Two kinds of problem belong
here: the ones where you are holding Relix the wrong way round, and the ones that are
genuinely outside what it does. Recognising either saves more time than any recipe.

## Row-at-a-time thinking, forced into sets

The commonest way to misuse a relational engine is to write a loop in it. *"For each
order, look at the previous order and compute the gap"* is a sentence about one row at a
time, and translated literally it becomes a pile of self-joins keyed on *"the row before
this one"* — awkward to write, quadratic to run, and wrong at the edges.

Every such question has a set-shaped answer that is shorter and faster:

- *"compare each row with its neighbour"* is a window (`WINDOW LAG`/`LEAD`), not a
  self-join — see [sequence](recipes/sequence.md).
- *"a running total"* is `ROLLING`, not a triangular self-join — see
  [summary](recipes/summary.md).
- *"the top N per group"* is `TOP … PER`, not a correlated count — see
  [ranking](recipes/ranking.md).

The tell that you have slipped into row-at-a-time thinking is a query that mentions *the
previous*, *the next*, *so far*, or *running* and answers it with a join back to the same
table. Stop and name the set operation instead: the grain does not change, so it is a
window; the answer is one row per group, so it is a γ. Thinking in relations is the whole
skill [Part I](README.md#the-method) is trying to build, and this is where it pays off.

## Recursion where an operator would do

The second self-inflicted case is reaching for the general tool when a named one exists.
*"Everything reachable from here"* can be written as a `FIX`, and *"the parts of the
parts"* can be written as a hand-rolled loop — but both are `CLOSURE`, which is shorter,
faster (an adjacency-indexed loop rather than the general fixpoint), and says what you
mean. `PATH` gives you the distance, `TRACE` the optimal route, `CLUSTER` the undirected
groups. Writing a `FIX` where one of these fits is not wrong, just needless work you will
maintain forever.

`FIX` earns its keep only when the step needs more than two columns, a filter, or a join
each round; `ITERATE` when state is *replaced* rather than accumulated. Reach past the
named graph operators only when the shape genuinely does not fit them —
[reachability](recipes/reachability.md) and [recursion](recipes/recursion.md) draw the
line.

## Genuinely outside the range

Some problems are not a matter of holding the tool right — Relix simply does not do them.

- **Anything that writes.** Relix reads sources and derives relations from them; it does
  not insert, update, or delete. A query computes an answer, it does not change the world.
  "Update every overdue invoice" is not a query — compute the set of overdue invoices with
  Relix, and hand it to whatever owns the write.
- **Text search and fuzzy matching.** `LIKE` matches a simple pattern, and that is the
  whole of it. Ranked relevance search, full-text indexing, spelling-tolerant or semantic
  matching, embeddings — these belong to a search engine, and Relix has nothing to add to
  them. Join *on* their results if you must, but do not try to compute them here.
- **General-purpose and stateful computation with side effects.** Relix has no I/O, no
  mutable variables, no calling out to services mid-query. A computation that must talk to
  the network, keep a mutable cache, or interleave with the outside world is a program, not
  a relation — write it in a language, and use the [embedding
  API](engineering.md#moving-a-solution-into-java) to let it *call* Relix for the relational
  parts.

## What is *not* outside the range

One thing worth stating plainly, because it is a natural assumption and it is wrong:
**iterative numeric methods are in scope.** Convergence — PageRank, label propagation,
relaxation, a numeric fixpoint — is not a reason to leave the engine. `ITERATE` runs a
computation round by round until it settles, so *"iterate until the numbers stop moving"*
is a query like any other. See [iteration](recipes/iteration.md). The line is *writing*
and *side effects*, not *iteration*.

## The honest default

When a problem is outside the range, the right move is not to force it — it is to do the
relational part in Relix and hand the rest to the tool that owns it. Compute the set,
export it, and let the writer write, the search engine search, the program run. A front end
that knows its own edges is more useful than one that pretends it has none.
