# The best combination within limits

> **Grain:** one row per chosen item · **Class:** Optimization · **Signals:** the best, the most/least within, under a budget, maximise, minimise, subject to, without exceeding · **Operators:** `OPTIMIZE`

## The problem

*"We have 50 person-days this quarter and a backlog of features, each with a business
value and a build cost. Which subset delivers the most value without going over
budget?"*

## How to recognise it

The question asks for the **best combination** subject to **limits** — *maximise value
under a budget*, *the cheapest set that covers everything*, *pick the most within a
cap*. The tell is that no single row is the answer and no sort finds it: the value of
a choice depends on the *other* choices, because they compete for the same limited
resource. That is optimisation, and `OPTIMIZE` states it directly — an objective to
maximise or minimise, and one or more `SUBJECT TO` constraints.

This is a knapsack, and it is exactly the shape SQL cannot express: *the most valuable
subset whose costs sum to at most 50* is not a filter, a rank, or an aggregate.

## The data

```relix
Backlog := [
| feature      | value | cost |
|--------------|-------|------|
| SearchRevamp | 60    | 10   |
| MobileSync   | 100   | 20   |
| Dashboards   | 120   | 30   |
| DarkMode     | 40    | 5    |
];
```

## Recipe: maximise a total under a budget (OPTIMIZE)

State the objective and the constraint; `OPTIMIZE` returns the **chosen input rows**,
schema unchanged — it is a filter that picks the optimal subset.

```relix
query { OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(cost) <= 50 (Backlog) };
```

<!-- output: paste from a run. Expected: SearchRevamp, Dashboards, DarkMode — value 220 at cost 45, the best reachable inside the budget. -->

`SUM(value)` is what to maximise; `SUM(cost) <= 50` is the limit. The solver evaluates
every feasible subset — you describe *what optimal means*, not how to search.

**A separate problem per group** is `PER`. Give each team its own budget in one
statement:

```relix
TeamBacklog := [
| team    | feature      | value | cost |
|---------|--------------|-------|------|
| search  | SearchRevamp | 60    | 10   |
| search  | Dashboards   | 120   | 30   |
| mobile  | MobileSync   | 100   | 20   |
| mobile  | DarkMode     | 40    | 5    |
];

query { OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(cost) <= 30 PER team (TeamBacklog) };
```

<!-- output: paste from a run. Expected: search → Dashboards (120); mobile → MobileSync + DarkMode (140 at cost 25). Neither budget constrains the other. -->

## Variations

- **Minimise instead** — `MINIMIZE SUM(cost) SUBJECT TO SUM(coverage) >= 10` is the
  set-cover shape: the cheapest set that meets a requirement.
- **Several constraints** — chain them with `AND`: `SUBJECT TO SUM(cost) <= 50 AND
  SUM(risk) <= 3`.
- **Continuous allocation** rather than a yes/no subset — `OPTIMIZE ALLOCATE (0.0, 1.0)
  … -> weight` assigns each row a fractional weight (a portfolio), returning every row
  with an allocation column.

## Pitfalls

- **A constraint is required.** `OPTIMIZE MAXIMIZE SUM(value)` with no `SUBJECT TO` is
  an error — unbounded "maximise" has no answer. There is always a limit; name it.
- **Ties are the solver's choice.** When two subsets reach the same optimum, which one
  comes back is not specified. If you need a particular winner, break the tie in the
  objective (`MAXIMIZE SUM(value) - SUM(cost)`).
- **An infeasible group yields no rows, silently.** If a group's constraints cannot be
  met, it is logged and skipped rather than failing the query — so a missing group is
  not necessarily a bug in your data. Check for groups you expected but did not get.
- **`OPTIMIZE` blocks and never pushes down.** It buffers its input and runs the solver
  in-engine; it needs the mathematical-programming solver installed (ojAlgo ships with
  Relix).
- **Objective and constraints are linear `SUM` forms.** A non-linear goal is out of
  scope for this operator.

## Check it

- Work the tiny case by hand: enumerate the feasible subsets of four items and confirm
  the returned one is genuinely the best. Optimisation answers are easy to *believe*
  and hard to *eyeball*, so a known-answer case matters most here.
- Check the constraint holds: `SUM(cost)` over the returned rows must be within the
  budget.
- Make a group **infeasible** (every item over budget) and confirm it drops out rather
  than erroring.

## Related

- [What value makes this true](equation.md) — `SOLVE` inverts one equation; `OPTIMIZE`
  searches a space of choices.
- [How many, how much, per what](summary.md) — a plain `MAX`/`MIN` finds an extreme
  value; `OPTIMIZE` finds the best *combination* under constraints.
- [The most, the top N, the latest](ranking.md) — `TOP` ranks independent rows;
  optimisation is for when the rows compete.
- Reference pages (`docs/reference`): `optimize`, `solve`.
