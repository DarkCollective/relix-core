# Name: Declarative Optimisation (OPTIMIZE)

# Syntax:
-- MIP subset selection (default):
OPTIMIZE MAXIMIZE|MINIMIZE SUM(<objective>)
  SUBJECT TO SUM(<expr>) <=|>=|= <bound> [AND ...] [PER <keys>] (Relation)

-- LP continuous allocation (ALLOCATE):
OPTIMIZE ALLOCATE (<lo>, <hi>) MAXIMIZE|MINIMIZE SUM(<objective>)
  SUBJECT TO SUM(<expr>) <=|>=|= <bound> -> <col> [PER <keys>] (Relation)

OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) <= 100 PER region (Candidates)

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
(-> col). PER is optional (absent = whole relation). Solved by the installed
mathematical-programming solver (ojAlgo ships with Relix): a group the solver
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
 feature       value  cost
 ────────────  ─────  ────
 SearchRevamp     60    10
 Dashboards      120    30
 DarkMode         40     5
(3 rows)
```

Two different subsets reach 220 here: the three above at a cost of 45, and
`MobileSync + Dashboards` at exactly 50. Both are optimal, and the solver returns
one of them — when an objective ties, which winner you get is the solver's
choice, not something the query specifies. Add a tie-breaker to the objective
(`MAXIMIZE SUM(value) - SUM(cost)`, say) if you need one particular answer.

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
At least one constraint is required. Constraint operators are ≤, ≥, or =.
Infeasible groups produce no rows (MIP) / are skipped (LP). It materialises and
never pushes down. Objective and constraints are linear in SUM form.

# Alternatives:
SOLVE for inverting a single equation (no choice/constraints). γ with MAX/MIN for
a plain extreme value rather than a constrained optimum.

# See Also:
[solve](solve.md), [group](../operators/group.md), [top](top.md)

# Notes:
OPTIMIZE is the optimisation half of the declarative-solver pair (SOLVE is the
goal-seek half), backed by a real mixed-integer / linear-programming solver.
