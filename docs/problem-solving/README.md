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

The four moves that turn a question into a query, in order.

| Chapter | What it teaches |
|---|---|
| [Grain first](method/grain.md) | Decide *"one row per ___"* before choosing an operator |
| [Find the quantifier](method/quantifier.md) | The word in the question that fixes the logic |
| [Decompose into views](method/decompose.md) | Build the answer as a pipeline of named relations |
| [Check your answer](method/verify.md) | The checklist that tells *runs* from *right* |

## Recipes: selection and summary

| Recipe | For |
|---|---|
| [Which rows, and which columns](recipes/selection.md) | Filtering rows and choosing columns, keeping the grain |
| [How many, how much, per what](recipes/summary.md) | Totals and counts per group, running totals, time buckets |

## Recipes: ranking

| Recipe | For |
|---|---|
| [The most, the top N, the latest](recipes/ranking.md) | The top rows, the latest per key, and ties |

## Recipes: existence and absence

| Recipe | For |
|---|---|
| [Has at least one](recipes/existence.md) | Keeping an entity when a matching row exists |
| [None, never, missing](recipes/absence.md) | Keeping an entity with no match; what a reference list lacks |

## Recipes: every and only

| Recipe | For |
|---|---|
| [Every: related to all of a set, or all rows pass a test](recipes/every.md) | Related to *all* of a set, or *every* row passing |

## Recipes: comparison

| Recipe | For |
|---|---|
| [What changed, what differs](recipes/comparison.md) | Reconciling two snapshots, or two systems |

## Recipes: graphs

| Recipe | For |
|---|---|
| [Things that belong together](recipes/connected-groups.md) | Undirected groups — duplicates, households, rings |
| [Reachable, how far, and by what route](recipes/reachability.md) | Directed reachability, distance, and the optimal route |
| [When the rule is recursive](recipes/recursion.md) | General recursion when the two-column form will not do |

## Recipes: time

| Recipe | For |
|---|---|
| [As of, during, overlapping](recipes/temporal-alignment.md) | The value as of a moment; overlapping periods |
| [In a row, per visit, gaps](recipes/sequence.md) | Sessions, gaps, and each row's neighbour in order |

## Recipes: choosing and solving

| Recipe | For |
|---|---|
| [The best combination within limits](recipes/optimization.md) | The best subset under a budget |
| [What value makes this true](recipes/equation.md) | Filling the one value that satisfies an equation |
| [Enough cases to cover](recipes/generation.md) | A covering test suite over a parameter space |

## Recipes: reshaping

| Recipe | For |
|---|---|
| [As columns, as a list, as a tree](recipes/reshaping.md) | Pivoting, nesting, unnesting, folding to a tree |

## Recipes: explaining

| Recipe | For |
|---|---|
| [Why is this row here, where did it come from](recipes/explanation.md) | Reifying a result's lineage and reading it |

## Recipes: sampling

| Recipe | For |
|---|---|
| [A random, representative subset](recipes/sampling.md) | A reproducible random sample |

## Recipes: iteration

| Recipe | For |
|---|---|
| [Iterate until it settles](recipes/iteration.md) | Converging, or simulating rounds, replacing state each time |

## Case studies

Problems that cross several classes, each classified out loud before any code.

| Case study | Classes it crosses |
|---|---|
| [Fraud rings](case-studies/fraud-rings.md) | Existence + graph + explanation |
| [Family history](case-studies/family-history.md) | Graph + time |
| [Inventory allocation](case-studies/inventory-allocation.md) | Summary + optimization |
| [SLA breaches](case-studies/sla-breaches.md) | Temporal alignment + sequence |
| [Reconciling two systems](case-studies/reconciliation.md) | Comparison + federation |
| [Test planning](case-studies/test-planning.md) | Generation |

## Engineering the solution

Making a correct query run well, and moving a solution into a program.

| Chapter | Covers |
|---|---|
| [Engineering the solution](engineering.md) | Reading a plan, what stops a pushdown, bounding a generator, budgets, working offline, moving into Java |

## When Relix is not the tool

Where to stop — the problems it does not do, and the ones it does that you might think it does not.

| Chapter | Covers |
|---|---|
| [When Relix is not the tool](not-the-tool.md) | Row-at-a-time thinking forced into sets, recursion where an operator would do, and what is out of range |

## Recipe finder

Generated from each recipe's [coordinate](coordinate.md), never maintained by hand.

| Index | By |
|---|---|
| [Recipe finder](finder.md) | Question word, operator, and class |
