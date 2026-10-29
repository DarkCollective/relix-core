# Name: Declarative Optimisation (OPTIMIZE)

# Syntax:
-- MIP subset selection (default):
OPTIMIZE MAXIMIZE|MINIMIZE SUM(<objective>)
  SUBJECT TO SUM(<expr>) <=|>=|= <bound> [AND ...] [PER <keys>] (Relation)
-- returns the chosen input rows, schema unchanged

-- LP continuous allocation (ALLOCATE):
OPTIMIZE ALLOCATE (<lo>, <hi>) MAXIMIZE|MINIMIZE SUM(<objective>)
  SUBJECT TO SUM(<expr>) <=|>=|= <bound> -> <col> [PER <keys>] (Relation)
-- returns every input row plus the allocation column <col>
-- each SUM(x) already multiplies x by <col>, so <col> is not written inside it;
-- SUM(1) is the sum of the allocations

OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) <= 100 PER region (Candidates)
OPTIMIZE ALLOCATE (0.1, 0.5) MAXIMIZE SUM(expected_return)
  SUBJECT TO SUM(1) = 1.0 -> share (Channels)

# Description:
OPTIMIZE solves an optimisation problem directly in a query. In its default mode
it picks the best SUBSET of rows that maximises or minimises a total subject to
limits — the classic "knapsack": choose items to maximise value while staying
under a weight budget. In ALLOCATE mode it instead assigns each row a continuous
weight (e.g. a portfolio allocation) to optimise a goal under constraints. Add
PER to solve a separate problem for each group.

# Technical Description:
Default mode is a per-group binary 0/1 knapsack (MIP): per group it chooses the
subset of candidate rows that maximises/minimises SUM(objective) subject to one
or more SUM(expr) ≤|≥|= bound constraints; output = the chosen input rows (schema
= input — a windowed filter). ALLOCATE mode is an LP: each row gets a continuous
variable in [lo, hi]; all rows are returned with an appended allocation column
(-> col). That column is the LP's variable, so each SUM(x) of ALLOCATE, in the
objective and in every constraint, is Σ x · col over the rows: MAXIMIZE
SUM(expected_return) maximises the allocation's expected return, and SUM(1) = 1.0
makes the allocations sum to 1. The column is not one of the input's and is not
written inside a SUM; SUM(expected_return * share) is refused with the rewrite. PER is optional (absent = whole relation). Solved by the installed
mathematical-programming solver (ojAlgo ships with Relix). Tied optima are
settled by rule: the fewest rows, then the earliest in input order, so the
result does not depend on the solver's search. A group the solver
proves infeasible is logged and skipped, while a search that stops without
deciding raises an error rather than quietly contributing no rows. [bag]; never
pushes down.

# Examples:
Knapsack — pick the highest-value items under a weight budget, per region:
```relix
OPTIMIZE MAXIMIZE SUM(value)
  SUBJECT TO SUM(weight) <= 100 PER region (Candidates)
```

Minimise cost while meeting a coverage requirement:
```relix
OPTIMIZE MINIMIZE SUM(cost)
  SUBJECT TO SUM(coverage) >= 10 (Plans)
```

Portfolio allocation — weights summing to 1, maximising expected return:
```relix
OPTIMIZE ALLOCATE (0.0, 1.0) MAXIMIZE SUM(ret)
  SUBJECT TO SUM(1) = 1.0 -> weight PER sector (Assets)
```

# Worked Example:
A product team has a fixed engineering budget of 50 person-days for the next
quarter and a backlog of features, each with an estimated business value and a
build cost. They want the subset of features that delivers the most value without
exceeding the budget — the classic 0/1 knapsack.

```relix
Backlog := [
| feature      | value | cost |
|--------------|-------|------|
| SearchRevamp | 60    | 10   |
| MobileSync   | 100   | 20   |
| Dashboards   | 120   | 30   |
| DarkMode     | 40    | 5    |
];

Roadmap := { OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(cost) <= 50 (Backlog) };
```

The solver evaluates every feasible subset. The best achievable value inside the
budget is 220 — beating, say, `SearchRevamp + MobileSync + DarkMode` (value 200,
cost 35). `OPTIMIZE` returns the *chosen input rows* (schema unchanged), not an
aggregate:

```relix
query { Roadmap };
```

```
 feature     value  cost
 ──────────  ─────  ────
 MobileSync    100    20
 Dashboards    120    30
(2 rows)
```

Two different subsets reach 220 here: the two above at exactly 50, and
`SearchRevamp + Dashboards + DarkMode` at a cost of 45. Both are optimal. When
an objective ties, OPTIMIZE settles it by a fixed rule rather than by whichever
the solver happens to meet first: it returns the optimum with the **fewest
rows**, and among those the one whose rows come **earliest in the input**. So
the answer is the same on every run, and every read of a view like `Roadmap`
within a script sees the same rows. The rule is a tie-breaker, not a preference
the query states: add one to the objective (`MAXIMIZE SUM(value * 1000 - cost)`,
say, for the cheaper of the tied subsets) when a particular optimum is the one
you mean.

Add `PER` to solve an independent knapsack for each group — one statement, one
problem per team, each against its own budget:

```relix
TeamBacklog := [
| team    | feature      | value | cost |
|---------|--------------|-------|------|
| search  | SearchRevamp | 60    | 10   |
| search  | Dashboards   | 120   | 30   |
| mobile  | MobileSync   | 100   | 20   |
| mobile  | DarkMode     | 40    | 5    |
];

TeamRoadmaps := { OPTIMIZE MAXIMIZE SUM(value)
                    SUBJECT TO SUM(cost) <= 30 PER team (TeamBacklog) };

query { TeamRoadmaps };
```

```
 team    feature     value  cost
 ──────  ──────────  ─────  ────
 search  Dashboards    120    30
 mobile  MobileSync    100    20
 mobile  DarkMode       40     5
(3 rows)
```

Each team is solved against 30 of its own: `search` takes `Dashboards` alone,
since adding `SearchRevamp` to it costs 40; `mobile` takes both of its features
for 140 at a cost of 25. Neither group's budget constrains the other.

If a group cannot satisfy its constraints (e.g. every feature in it costs more
than the budget), that group simply contributes no rows — it is logged and
skipped rather than failing the whole query.

# Optimization — group pruning (solve only the asked-for group):
`OPTIMIZE` solves an independent problem for each `PER` group, so computing every
group's MIP/LP and then keeping one is wasteful — and the solver search is the
dominant cost. When a selection fixes a grouping key to a constant, the optimizer
pushes that equality **below** the operator, so only the matching group is solved
— the *partition-pruning* member of the magic-sets / SIP family. It is
recorded as `OPTIMIZE-001` on the `relix-events` feed (visible via `--trace`):

```relix
-- After view inlining the σ sits directly above the OPTIMIZE, so the bound folds in.
WestPick := { σ region = "WEST"
                (OPTIMIZE MAXIMIZE SUM(value)
                   SUBJECT TO SUM(weight) <= 100 PER region (Candidates)) };
--   →  OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) <= 100 PER region
--        (σ region = "WEST" (Candidates))
```

The rewrite is exact: each `PER` group is a separate sub-problem, so the all-groups
result filtered to `region = "WEST"` is identical to solving the `WEST` group
alone. Only top-level **equalities** on a grouping key against a **literal** are
pushed; everything else stays as a `σ` above the operator, so the optimization can
never change the answer.

In particular a predicate on a **non-grouping** column (e.g. `value > 50`) is
*not* pushed: under `OPTIMIZE`'s emit-the-optimum semantics a candidate row the
solver would have chosen, then discarded by the post-filter, is **not** the same
as a row the solver never sees — removing it from the input would change the
chosen optimum. So such a predicate is left as a residual `σ` above the operator
(correctness by construction). `SOLVE` (a per-row equation goal-seek with no
search space or group dimension) has nothing to prune and is unaffected.

# Limitations:
At least one constraint is required, so `SUBJECT TO` cannot be left out:

```relix-invalid
query { OPTIMIZE MAXIMIZE SUM(value) (Candidates) };
```

 Constraint operators are ≤, ≥, or =.
Infeasible groups produce no rows (MIP) / are skipped (LP). It materialises and
never pushes down. Objective and constraints are linear in SUM form. Proving an
optimum unique costs one more solve per group; settling a tie costs up to one
more per row, so a large group with many tied optima solves several times.
ALLOCATE's continuous ties are the solver's choice.

# Alternatives:
SOLVE for inverting a single equation (no choice/constraints). γ with MAX/MIN for
a plain extreme value rather than a constrained optimum.

# See Also:
[solve](solve.md), [group](../operators/group.md), [top](top.md)

# Notes:
OPTIMIZE is the optimisation half of the declarative-solver pair (SOLVE is the
goal-seek half), backed by a real mixed-integer / linear-programming solver.
