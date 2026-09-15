# Goal-seek and optimisation

Two operators in Relix do not filter or combine rows — they *decide* something. `SOLVE`
fills in a missing value by rearranging an equation, and `OPTIMIZE` chooses the rows, or
the weights, that make a total as large or as small as it can be under constraints.

They are ordinary operators. A solved relation is a relation: it joins, it projects, it
composes with everything else on the way to an answer.

This page runs one capacity-planning session. A platform team has a set of services, each
carrying some business value and needing some CPU, spread over two regions.

```java
Relix relix = Relix.open();

relix.table("Workloads", List.of("service", "region", "value", "cpus"), List.of(
        Map.of("service", "search",     "region", "eu-west", "value", 60,  "cpus", 4),
        Map.of("service", "ingest",     "region", "eu-west", "value", 100, "cpus", 8),
        Map.of("service", "dashboards", "region", "eu-west", "value", 120, "cpus", 12),
        Map.of("service", "billing",    "region", "us-east", "value", 90,  "cpus", 6),
        Map.of("service", "reports",    "region", "us-east", "value", 40,  "cpus", 3)));

relix.relation("Workloads").toList().forEach(System.out::println);
```

```
(service=search, region=eu-west, value=60, cpus=4)
(service=ingest, region=eu-west, value=100, cpus=8)
(service=dashboards, region=eu-west, value=120, cpus=12)
(service=billing, region=us-east, value=90, cpus=6)
(service=reports, region=us-east, value=40, cpus=3)
```

## SOLVE: filling in the blank

The provider's invoice arrives with holes in it. Every line obeys the same relationship —
`charge = cpus × rate` — but which of the three is missing varies by line. A blank cell is
a NULL, and `table` writes one wherever a row does not mention a column:

```java
relix.table("Invoice", List.of("service", "cpus", "rate", "charge"), List.of(
        Map.of("service", "search",     "cpus", 4,  "rate", 12),
        Map.of("service", "ingest",     "rate", 15, "charge", 120),
        Map.of("service", "dashboards", "cpus", 12, "charge", 216),
        Map.of("service", "billing",    "cpus", 6,  "rate", 12, "charge", 72),
        Map.of("service", "reports",    "charge", 30)));

Relation reconciled = relix.relation("Invoice")
        .solve(attr("charge"), times(attr("cpus"), attr("rate")));

System.out.println(reconciled.render());
reconciled.toList().forEach(System.out::println);
```

```
SOLVE charge = cpus * rate (Invoice)
(service=search, cpus=4, rate=12, charge=48)
(service=ingest, cpus=8, rate=15, charge=120)
(service=dashboards, cpus=12, rate=18, charge=216)
(service=billing, cpus=6, rate=12, charge=72)
(service=reports, cpus=NULL, rate=NULL, charge=30)
```

One equation, three different directions, decided per row. `search` multiplies; `ingest`
divides the charge by the rate; `dashboards` divides it by the CPU count instead. Nothing
in the query says which — the operator inverts the arithmetic around whichever column is
blank.

The last two rows are the rule stated from the other side. `billing` has nothing missing,
and `reports` has two things missing, so neither is solved: `SOLVE` acts only where there
is exactly one hole, and a row it cannot solve passes through untouched rather than
failing the query. That is what makes it safe to run over a whole import.

`SOLVE` is arithmetic, not search. It rearranges `+ − × ÷` and unary minus, per row, in
pure Java — **no solver is involved**, so it works whatever else is on the classpath.

## OPTIMIZE: choosing the best subset

The other half is a real search. Reserved capacity is 16 CPUs per region, and the question
is which services to place on it:

```java
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ObjectiveSense;

Relation placed = relix.relation("Workloads").optimize(
        ObjectiveSense.MAXIMIZE,
        attr("value"),
        List.of(constraint(attr("cpus"), ComparisonOperator.LESS_EQUAL, 16)),
        List.of("region"));

System.out.println(placed.render());
placed.toList().forEach(System.out::println);
```

```
OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(cpus) ≤ 16 PER region (Workloads)
(service=search, region=eu-west, value=60, cpus=4)
(service=dashboards, region=eu-west, value=120, cpus=12)
(service=billing, region=us-east, value=90, cpus=6)
(service=reports, region=us-east, value=40, cpus=3)
```

Four arguments: which way to push the objective, the per-row quantity being totalled, the
constraints, and the columns a **separate problem** is solved for. `PER region` is not a
grouping of the answer — it is two independent knapsacks, solved one per region, whose
chosen rows are then emitted together.

The output is the chosen input rows, with the input's heading. `OPTIMIZE` is a filter that
happens to think hard: it does not aggregate, and it does not add a column.

### It is a search, not a heuristic

The eu-west answer is worth checking by hand, because the obvious approximation gets it
wrong. Ranked by value per CPU, `search` is the best buy at 15, then `ingest` at 12.5, then
`dashboards` at 10. Taking them greedily in that order fills 12 of the 16 CPUs with `search`
and `ingest` for a value of 160, and `dashboards` no longer fits.

The solver returns `search` and `dashboards` instead: 16 CPUs exactly, for 180. That gap is
the whole reason the operator exists rather than a `τ` and a running total.

```
       greedy by value/cpu          the optimum
       ─────────────────────        ─────────────────────
       search      4 cpu   60       search      4 cpu   60
       ingest      8 cpu  100       dashboards 12 cpu  120
       ─────────────────────        ─────────────────────
       12 of 16 cpu       160       16 of 16 cpu       180
```

When two subsets tie on the objective, which one comes back is the solver's choice and not
something the query states. Put the tie-break into the objective if a particular answer
matters.

### A group that cannot be satisfied

Turn the question around — *the cheapest set of services carrying at least 200 points of
value* — and one of the two regions cannot answer it at all. `us-east` has 130 points in
total, so no subset of it reaches 200:

```java
Relation cheapest = relix.relation("Workloads").optimize(
        ObjectiveSense.MINIMIZE,
        attr("cpus"),
        List.of(constraint(attr("value"), ComparisonOperator.GREATER_EQUAL, 200)),
        List.of("region"));

cheapest.toList().forEach(System.out::println);
```

```
(service=ingest, region=eu-west, value=100, cpus=8)
(service=dashboards, region=eu-west, value=120, cpus=12)
```

`eu-west` is solved — `ingest` and `dashboards`, 220 points for 20 CPUs, the cheapest way
to clear the bar. `us-east` contributes **no rows**, because there is nothing it could
contribute: the constraint is unsatisfiable there.

That is the outcome worth being precise about, because a search has three of them and only
two have an obvious shape:

| Outcome | What it means | What you see |
|---|---|---|
| Solved | An optimum was found | The chosen rows |
| Infeasible | **No** assignment satisfies the constraints | No rows for that group |
| Undetermined | The search stopped without deciding | An error |

The first two are answers and the third is a failure, and the empty result is the trap: a
group that is genuinely infeasible and a search that ran out of road both contribute
nothing. Relix keeps them apart — an infeasible group is skipped, an undetermined search
raises — so an empty group in your result is a fact about the data rather than a symptom
you have to go looking for.

### Allocation: weights instead of a subset

`ALLOCATE` swaps the binary in-or-out decision for a continuous one. Each row gets a weight
in a range, every row comes back, and the weight arrives as a new column. It is the shape
for splitting something divisible — a budget, a traffic share, a pool of capacity.

The combinator covers the operator, and the allocation clause is one of the places writing
the expression is easier to read than a sixth argument would be:

```java
Relation split = relix.relation("""
        OPTIMIZE ALLOCATE (0.0, 0.5) MAXIMIZE SUM(value)
          SUBJECT TO SUM(1) = 1.0 -> share PER region (Workloads)
        """);

split.sort(asc("region"), desc("share")).toList().forEach(System.out::println);
```

```
(service=ingest, region=eu-west, value=100, cpus=8, share=0.5)
(service=dashboards, region=eu-west, value=120, cpus=12, share=0.5)
(service=search, region=eu-west, value=60, cpus=4, share=0)
(service=billing, region=us-east, value=90, cpus=6, share=0.5)
(service=reports, region=us-east, value=40, cpus=3, share=0.5)
```

Read it as: split next quarter's pool across each region's services, giving no single
service more than half of it, in whatever proportion carries the most value. In `eu-west`
that is half to `dashboards` and half to `ingest`, and nothing to `search` — the cap is what
stops the whole pool going to the single best row, which is where an unconstrained linear
objective always ends up. `us-east` has only two services, so the halves are forced.

`SUM(1) = 1.0` is the constraint that makes the weights a *share*: each row contributes a
coefficient of 1, so the sum of the chosen weights is pinned to one whole pool.

## Composing with the rest of a query

Nothing above is a terminal. The chosen rows are a relation, so the plan can be joined,
aggregated and sorted like anything else — here, what each region's placement is worth:

```java
import com.darkcollective.relix.ast.AggregateOperator;

placed.aggregate(List.of("region"),
                List.of(agg(AggregateOperator.SUM, "value", "placed_value"),
                        agg(AggregateOperator.SUM, "cpus", "cpus_used")))
        .sort(asc("region"))
        .toList()
        .forEach(System.out::println);
```

```
(region=eu-west, placed_value=180, cpus_used=16)
(region=us-east, placed_value=130, cpus_used=9)
```

`OPTIMIZE` buffers its input and never folds into a backend query — the search happens in
the engine, over rows the engine has read. A selection that fixes a `PER` key to a constant
is pushed *below* the operator, so asking for one region's answer solves one region's
problem rather than both and discarding one.

## Checking that a solver is installed

`OPTIMIZE` needs an installed mathematical-programming solver, and so does `COVER … EXACT`.
One ships inside the published artifact, so a session assembled from that dependency has
one. A program that assembles its own classpath can end up without.

Providers are found by a `ServiceLoader` scan, which no compiler can check, so the way to
be sure is to ask the running session. `relix.version` lists what is actually present:

```java
relix.relation("π component, version (σ kind = 'solver' (relix.version))")
        .toList()
        .forEach(System.out::println);
```

```
(component=ojAlgo, version=<version>)
```

With none installed, that query returns nothing, and `OPTIMIZE` raises **before any input
is read** — the check is made while planning, so the failure arrives at once rather than
part-way through a scan. Every other operator is unaffected, `SOLVE` included: inverting an
equation is arithmetic and needs nothing installed at all.
