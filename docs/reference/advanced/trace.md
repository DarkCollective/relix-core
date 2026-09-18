# Name: Optimal-Path Extraction (TRACE)

# Syntax:
TRACE <from>, <to> VIA <weight> MINIMIZE|MAXIMIZE AS <path> (Edges)

TRACE <from> ↔ <to> VIA <weight> MINIMIZE|MAXIMIZE AS <path> (Edges)  -- both ways

TRACE origin, dest VIA cost MINIMIZE AS route (Flights)

# Description:
TRACE finds the cheapest (or highest-score) path between every pair of
reachable nodes in a directed weighted graph and returns the path itself as a
first-class ordered array value. It answers the question *"what is the optimal
route between these two nodes, and exactly which nodes does it pass through?"* —
work that `CLOSURE`/`RCLOSURE` cannot do (they produce only reachability, not
paths or costs).

# Technical Description:
Given an edge relation, TRACE reads the `from` and `to` columns as directed
endpoints and the `weight` column as a numeric edge cost. It computes all-pairs
optimal paths using an iterative relaxation algorithm (Bellman-Ford style):

1. **Seed** the path table with each direct edge: `best[(from,to)] = (weight, [from, to])`.
2. **Relax**: for every known path ending at `v` and every outgoing edge `(v→w, ew)`,
   if extending to `w` would be better (lower cost for MINIMIZE, higher for MAXIMIZE),
   update `best[(origin, w)]` and record the extended path.
3. **Iterate** until no improvement occurs.

Writing `↔` (ASCII `<->`) in place of the comma reads the two columns as an
**undirected** edge: each edge is traversable in either direction at the same cost,
so a route may run against the direction the row was written in. The `(from, to)`
pairs are then symmetric, and a pair's `weight` is the same either way.

Cycle avoidance: a node is never revisited within a single path, so the algorithm
terminates on graphs that contain cycles.

The output schema is `(from:T, to:T, weight:NUMBER, path:array<T>)` where `T`
is the type of the `from` column. Null endpoints and non-numeric weights are
silently skipped. Unreachable pairs are absent from the output (the operator
produces directed reachability only). It is a blocking operator (must see the
whole edge set) and never pushes down to a source. The `--max-fixpoint-rounds`
guard applies to the relaxation loop.

# Examples:
Find the cheapest flight itinerary between all connected airport pairs:
  TRACE <from> ↔ <to> VIA <weight> MINIMIZE|MAXIMIZE AS <path> (Edges)  -- both ways

TRACE origin, dest VIA cost MINIMIZE AS route (Flights)

Find the highest-scoring game route between levels:
  TRACE src, dst VIA score MAXIMIZE AS path (Graph)

Use with a selection to trace routes only from a specific origin:
  σ origin = "JFK" (TRACE origin, dest VIA cost MINIMIZE AS route (Flights))

# Worked Example:
An airline has a table of direct flights with fares. A query analyst wants to
find the cheapest itinerary (with intermediate stops) between all airport pairs,
and see exactly which airports the route passes through.

```relix
Flights := [
| origin | dest  | cost |
|--------|-------|------|
| JFK    | ORD   |  200 |
| ORD    | LAX   |  150 |
| JFK    | LAX   |  500 |
| ORD    | DEN   |  100 |
| DEN    | LAX   |   80 |
];

CheapestRoutes := { TRACE origin, dest VIA cost MINIMIZE AS route (Flights) };
```

The flight graph:

```
JFK --200--> ORD --150--> LAX
              |
             100
              |
             DEN --80--> LAX

JFK --500--> LAX (direct, expensive)
```

`CheapestRoutes` finds the optimal path for every reachable pair:

```relix
query { τ origin, dest (CheapestRoutes) };
```

```
 origin  dest  cost  route
 ──────  ────  ────  ───────────────
 DEN     LAX     80  [DEN, LAX]
 JFK     DEN    300  [JFK, ORD, DEN]
 JFK     LAX    350  [JFK, ORD, LAX]
 JFK     ORD    200  [JFK, ORD]
 ORD     DEN    100  [ORD, DEN]
 ORD     LAX    150  [ORD, LAX]
(6 rows)
```

One row per reachable pair, each carrying the winning route. `JFK → LAX` costs
350 through ORD, beating the 500 direct hop, and the `route` column names the
stops that make up the total. `ORD → LAX` keeps the direct 150 rather than the
180 available through DEN: a pair appears **once**, holding its optimum, so the
losing path is not in the output at all. The `τ` sorts for readability — the
fixpoint yields a set in no guaranteed order.

Filter to just JFK departures:

```relix
JfkRoutes := { σ origin = "JFK" (CheapestRoutes) };
```

Drill into the cheapest JFK→LAX route's stops:

```relix
JfkLax := { σ origin = "JFK" ∧ dest = "LAX" (CheapestRoutes) };
```

# Optimization — endpoint pushdown (single-source path search):
Computing the *all-pairs* optimal-path table just to keep one origin (or one
destination, or one pair) is wasteful. When a selection fixes an endpoint to a
constant, the optimizer folds that equality **into** the operator as a
source/target bound, turning the all-pairs relaxation into single-source,
single-target, or single-pair search — the *magic-sets / sideways-information-passing*
rewrite specialised to optimal paths. It is recorded as `TRACE-001` on
the `relix-events` feed (visible via `--trace`):

```relix
-- After view inlining the σ sits directly above the TRACE, so the bound folds in.
FromJfk  := { σ origin = "JFK" (TRACE origin, dest VIA cost MINIMIZE AS route (Flights)) };  -- single-source
IntoLax  := { σ dest   = "LAX" (TRACE origin, dest VIA cost MINIMIZE AS route (Flights)) };  -- single-target (reversed graph)
JfkToLax := { σ origin = "JFK" ∧ dest = "LAX" (TRACE origin, dest VIA cost MINIMIZE AS route (Flights)) };  -- single-pair
```

| Form                       | Pushed bound           | Work done                                              |
|----------------------------|------------------------|-------------------------------------------------------|
| `σ origin = c`             | source = c             | relaxation seeded only from `c`                       |
| `σ dest = c`               | target = c             | single-source from `c` over the **reversed** graph    |
| `σ origin = a ∧ dest = b`  | source = a, target = b | single-pair search `a ⇝ b`                            |

The rewrite is exact: `origin`/`dest` index independent sub-computations, so the
all-pairs result filtered to `origin = c` is identical to seeding the search from
`c` alone (the optimal cost and path are unchanged). Only top-level **equalities**
on the endpoint columns against **literals** are pushed; anything else — an
inequality such as a `cost < 300` residual, a predicate on another column,
`origin = dest` — stays as a `σ` above the now-bounded trace, so the optimization
can never change the answer. The bound is shown in the physical plan, e.g.
`Trace origin, dest via cost MINIMIZE AS route [origin="JFK"]` (use `--explain`).

When **both** endpoints are bound and the sense is `MINIMIZE`, the planner makes a
second, *physical* decision: it switches the algorithm from the all-pairs
Bellman-Ford relaxation to a **single-pair Dijkstra** search with goal-directed
early termination (it halts the moment the bound target is finalized). This is a
`Stage.PLAN` event (`code "TRACE"`, distinct from the `Stage.OPTIMIZE` `TRACE-001`
boundary event) and shows in the plan as a `[dijkstra]` tag:

```
Trace origin, dest via cost MINIMIZE AS route [origin="JFK"] [dest="LAX"] [dijkstra]
```

Dijkstra is only sound for **non-negative** weights — a precondition the planner
cannot verify (edge weights are data, not known at plan time). The executor checks
it at runtime and transparently **falls back** to the relaxation if it finds a
negative weight, so the answer is always correct regardless of the planner's
choice. (`MAXIMIZE` always stays on the relaxation.)

# MINIMIZE vs MAXIMIZE:
`MINIMIZE` returns the path with the lowest accumulated weight (shortest path,
cheapest itinerary). `MAXIMIZE` returns the path with the highest accumulated
weight (longest weighted path, highest-scoring route). The objective applies to
the sum of edge weights along the path; no path visits a node more than once.

# Relationship to CLOSURE:
`CLOSURE src, dst (Edges)` answers "is `dst` reachable from `src`?" and emits
only the `(from, to)` pair. `TRACE` additionally emits the cost of the best path
and the sequence of intermediate nodes. For unweighted reachability, prefer
`CLOSURE`; for path-finding with costs, use `TRACE`.

# Limitations:
The added column needs a name the input does not already use. Over a
schema-on-read source (JSON, HTTP, MongoDB) there is no declared heading to
clash with, so the operator always runs; if a document turns out to carry a
field of that name, the added column replaces it.

TRACE works on directed graphs only. The weight column must be `NUMBER` (or
`ANY`, resolved at runtime). Cycles are handled by the no-revisit rule (a node
is not visited twice in one path), so simple-cycle graphs terminate correctly.
`MAXIMIZE` over graphs with positive-weight improvement-cycles may require a
round cap (`--max-fixpoint-rounds`). The operator always runs in-engine and is
never pushed down to a SQL or MongoDB source.

# See Also:
[closure](closure.md), [cluster](cluster.md), [fix](fix.md)

# Notes:
The path column contains an ordered `array<T>` value — the sequence of node
identifiers from origin to destination inclusive (first element = origin, last
element = destination). Use `μ route (CheapestRoutes)` to explode the stops
into individual rows if needed.
