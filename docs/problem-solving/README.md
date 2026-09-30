# Problem Solving with Relix

This manual is for readers who already know the language and want to know which
parts of it to reach for. The reference answers *what does this operator do?*; this
manual answers the question that comes first — *what kind of problem is this?* —
and then hands you a recipe.

Every problem in it is classified the same way, by three questions asked before
any code is written:

1. **What is the grain?** Finish the sentence *"one row per ___"*.
2. **What is the quantifier?** The word in the question that decides the logic —
   *which*, *how many*, *the most*, *any*, *none*, *every*, *connected*…
3. **What makes the data difficult?** Nested, dirty, NULL-heavy, unbounded,
   federated, or out of step in time.

The answers give a problem its *coordinate*, and the coordinate points at a recipe.
Each recipe carries its coordinate as one line under the title, in a
[fixed format](coordinate.md) that a reader reads and the recipe finder is generated
from.

## The method

- [Grain first](method/grain.md)
- [Find the quantifier](method/quantifier.md)
- [Decompose into views](method/decompose.md)
- [Check your answer](method/verify.md)

## Recipes: selection and summary

- [Which rows, and which columns](recipes/selection.md)
- [How many, how much, per what](recipes/summary.md)

## Recipes: ranking

- [The most, the top N, the latest](recipes/ranking.md)

## Recipes: existence and absence

- [Has at least one](recipes/existence.md)
- [None, never, missing](recipes/absence.md)

## Recipes: every and only

- [Every: related to all of a set, or all rows pass a test](recipes/every.md)

## Recipes: comparison

- [What changed, what differs](recipes/comparison.md)

## Recipes: graphs

- [Things that belong together](recipes/connected-groups.md)
- [Reachable, how far, and by what route](recipes/reachability.md)
- [When the rule is recursive](recipes/recursion.md)

## Recipes: time

- [As of, during, overlapping](recipes/temporal-alignment.md)
- [In a row, per visit, gaps](recipes/sequence.md)

## Recipes: choosing and solving

- [The best combination within limits](recipes/optimization.md)
- [What value makes this true](recipes/equation.md)
- [Enough cases to cover](recipes/generation.md)

## Recipes: reshaping

- [As columns, as a list, as a tree](recipes/reshaping.md)

## Recipes: explaining

- [Why is this row here, where did it come from](recipes/explanation.md)

## Recipes: sampling

- [A random, representative subset](recipes/sampling.md)

## Recipes: iteration

- [Iterate until it settles](recipes/iteration.md) *(verified against the ITERATE engine snapshot; reaches the front ends when the engine release carrying `ITERATE` is pinned)*

## Case studies

Problems that cross several classes, each classified out loud before any code:

- [Fraud rings](case-studies/fraud-rings.md) — existence + graph + explanation
- [Family history](case-studies/family-history.md) — graph + time
- [Inventory allocation](case-studies/inventory-allocation.md) — summary + optimization
- [SLA breaches](case-studies/sla-breaches.md) — temporal alignment + sequence
- [Reconciling two systems](case-studies/reconciliation.md) — comparison + federation
- [Test planning](case-studies/test-planning.md) — generation

## Engineering the solution

- [Engineering the solution](engineering.md) — reading a plan, what stops a pushdown,
  bounding a generator, materialization budgets, working offline, moving into Java

## When Relix is not the tool

- [When Relix is not the tool](not-the-tool.md) — row-at-a-time thinking forced into
  sets, recursion where an operator would do, and what is genuinely out of range
  (writes, text search, side effects — but not iterative numeric methods)

## Recipe finder

- [Recipe finder](finder.md) — by question word, by operator, by class

Generated from each recipe's [coordinate](coordinate.md) by
[`_generate_finder.py`](_generate_finder.py), never maintained by hand
(`_generate_finder.py --check` fails when it is stale).
