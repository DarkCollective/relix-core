# Reachable, how far, and by what route

> **Grain:** one row per reachable pair · **Class:** Graph · **Signals:** reachable, can get to, connected to, within N hops, shortest route, cheapest route, via · **Operators:** `CLOSURE`, `PATH`, `TRACE`

## The problem

*"From each airport, where can I get to with any number of connections? Which are
within two hops? And what is the cheapest itinerary between two cities, and which
airports does it pass through?"*

## How to recognise it

The question is about following a **directed edge** repeatedly — *reachable*, *can get
to*, *within N hops*, *the route via*, *the cheapest way*. What makes it a graph
problem rather than a join is the chain: a single join finds the direct edges, but
*reachable* means any number of hops. Three operators answer three sharpenings of the
question, and the tell is **how much you need to know about the connection**:

- **Just whether you can get there** — `CLOSURE` (every reachable pair, any distance).
- **Within a hop budget, and how far** — `PATH` (a hop window, stamping each pair with
  its shortest distance).
- **The best weighted route, and its stops** — `TRACE` (the cheapest/highest route as
  an ordered node sequence).

For *undirected* "who belongs together", see [Things that belong together](connected-groups.md)
(`CLUSTER`); this recipe is directed.

## The data

Direct flights with a fare. `Edges` is the two-column projection the closure operators
want.

```relix
Flights := [
| origin | dest | cost |
|--------|------|------|
| JFK    | ORD  | 200  |
| ORD    | LAX  | 150  |
| JFK    | LAX  | 500  |
| ORD    | DEN  | 100  |
| DEN    | LAX  | 80   |
];

Edges := { π origin, dest (Flights) };
```

## Recipe 1: everything reachable (CLOSURE)

`CLOSURE from, to` follows the edge to a fixpoint and returns every pair connected by
one or more hops — the direct edges plus every derived one.

```relix
query { τ origin, dest (CLOSURE origin, dest (Edges)) };
```

<!-- output: paste from a run. Expected: six pairs — the direct hops plus JFK→DEN and JFK→LAX-via-connections; each derived pair appears once (set semantics), so cycles terminate. -->

*"Can I get from JFK to anywhere?"* is then a plain σ on the result — and the optimiser
folds that σ **into** the closure as a single-source search, so scoping to one origin
is an ordinary selection, not new syntax:

```relix
query { σ origin = "JFK" (CLOSURE origin, dest (Edges)) };
```

<!-- output: paste from a run. Expected: JFK to ORD, DEN and LAX. -->

`RCLOSURE` additionally pairs each node with itself, for when "stay put" is a valid
route.

## Recipe 2: within a hop budget, with distance (PATH)

When the question caps the distance — *within two hops* — and wants to know **how far**,
`PATH` takes a hop window and stamps each pair with its shortest distance:

```relix
query { τ hops, dest (σ origin = "JFK" (PATH origin, dest HOPS 1 TO 2 AS hops (Edges))) };
```

<!-- output: paste from a run. Expected: JFK→LAX and JFK→ORD at 1 hop (LAX is a direct flight), JFK→DEN at 2 hops. Anything 3+ hops away is excluded. -->

The distance is the **shortest** path length, so `(from, to)` is a candidate key — a
pair never appears at two depths. As with `CLOSURE`, a σ on an endpoint seeds the
traversal at that node.

## Recipe 3: the optimal route, and its stops (TRACE)

Reachability says *whether*; `TRACE` says *how, and how much*. Give it a weight column
and `MINIMIZE`/`MAXIMIZE`, and it returns the best route for every reachable pair as an
ordered array of nodes:

```relix
query { τ origin, dest (TRACE origin, dest VIA cost MINIMIZE AS route (Flights)) };
```

<!-- output: paste from a run. Expected: JFK→LAX costs 350 via [JFK, ORD, LAX], beating the 500 direct hop; each pair appears once, holding its optimum, with the route column naming the stops. -->

`TRACE` uses the whole `Flights` relation (it needs the weight), not the two-column
`Edges`. Explode the route into rows with `μ route (…)` when you want one stop per row.

## Variations

- **Undirected traversal** — union the reversed edges into the input first, or use the
  `↔` separator (`CLOSURE from ↔ to`, `PATH from ↔ to`), which reads each edge both
  ways.
- **Weighted reachability without the route** — `--provenance --semiring tropical`
  gives shortest-path *costs* through `CLOSURE` without materialising the paths.
- **A rule the two-column form cannot express** — extra columns, a filter or a join
  each round — is [general recursion](recursion.md) (`FIX`).

## Pitfalls

- **`CLOSURE` and `PATH` are two-column, and directed.** Project to exactly the edge
  columns first (`Edges` above), and remember the edge direction — `from → to` is not
  the same graph as `to → from`. Use `↔` for undirected.
- **`CLOSURE` has no distance; `PATH` has no route; `TRACE` has both cost and route.**
  Reaching for `CLOSURE` when you needed the hop count, or `PATH` when you needed the
  actual stops, is the common miss — pick by how much you need to know.
- **Scope with a σ, not by hand.** The start node is deliberately not part of the
  syntax: `σ origin = c` above the operator is folded into a single-source search. A
  predicate on the *distance* or an inequality stays as a residual σ (correct, just not
  pushed).
- **Dense graphs are large, and these block.** All-pairs reachability can be quadratic;
  the operators buffer and never push down, and `--max-fixpoint-rounds` guards a
  runaway.
- **`TRACE` weights should be whole units for an exact total** — scale money to cents,
  since floating-point sums can differ in their last bits.

## Check it

- Put a **cycle** in the edges and confirm `CLOSURE` still terminates (a derived pair is
  never re-added) — closure over cyclic data is exactly where a hand-rolled join loops
  forever.
- Put a pair reachable **two ways** (JFK→LAX direct and via ORD) and confirm `CLOSURE`
  lists it once, `PATH` reports the *shortest* distance, and `TRACE` keeps the *cheapest*
  route.
- Check a node just outside the hop window is excluded by `PATH` and included by
  `CLOSURE`.

## Related

- [Things that belong together](connected-groups.md) — undirected connectivity with
  `CLUSTER`, the other half of the graph class.
- [When the rule is recursive](recursion.md) — `FIX`, the general form these specialise.
- [As columns, as a list, as a tree](reshaping.md) — `TREE` folds the same parent edges
  into nested documents; `μ` explodes a `TRACE` route.
- Reference pages (`docs/reference`): `closure`, `path`, `trace`.
