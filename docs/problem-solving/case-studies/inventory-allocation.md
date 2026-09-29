# Inventory allocation

*Summary + Optimization*

## The problem

*"Each region has more orders than it can ship this week. Show the demand per region,
then choose the orders to fulfil that deliver the most value without exceeding each
region's shipping capacity."*

## Classifying it

- *"the demand per region"* — a **[summary](../recipes/summary.md)**: one row per region,
  γ over the orders.
- *"choose the orders … most value … without exceeding capacity"* — an
  **[optimization](../recipes/optimization.md)**: a per-region knapsack, `OPTIMIZE` with a
  capacity constraint.

The tell that the second half is optimization, not ranking: the orders **compete** for a
shared capacity, so no per-order sort finds the answer — the value of shipping one order
depends on which others you shipped.

## The data

```relix
Orders := [
| order | region | value | units |
|-------|--------|-------|-------|
| O1    | North  | 100   | 30    |
| O2    | North  | 120   | 40    |
| O3    | North  | 60    | 20    |
| O4    | South  | 90    | 25    |
| O5    | South  | 50    | 15    |
];
```

Each region can ship **50 units** this week.

## Stage 1: the demand (summary)

```relix
query { γ region, COUNT(*) → orders, SUM(units) → demanded, SUM(value) → value (Orders) };
```

<!-- output: paste from a run. Expected: North 3 orders, 90 units demanded, 280 value; South 2 orders, 40 units, 140 value. North is over capacity (90 > 50); South is not. -->

## Stage 2: what to ship (optimization)

`OPTIMIZE` picks the best subset per region under the capacity constraint — `PER region`
solves an independent knapsack for each:

```relix
Fulfil := { OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(units) <= 50 PER region (Orders) };
query { τ region, order (Fulfil) };
```

<!-- output: paste from a run. Expected: North ships O1 + O3 (50 units, 160 value — beating O2 alone at 120); South ships O4 + O5 (it is within capacity, so all of it). -->

## Stage 3: what it comes to (summary again)

The chosen set is an ordinary relation, so summarise it the same way:

```relix
query { γ region, SUM(value) → shipped_value, SUM(units) → shipped_units (Fulfil) };
```

<!-- output: paste from a run. Expected: North 160 value at 50 units (full); South 140 value at 40 units (under capacity). -->

## What this shows

Summary brackets the optimization on both sides: γ *frames* the problem (what is demanded,
where the pressure is) and γ *reports* the answer (what got shipped). The optimiser sits
in the middle doing the one thing a γ cannot — choosing under a constraint.

Note North's result is where ranking would have gone wrong: `TOP 1 value PER region` picks
O2 (value 120), but O1 + O3 together deliver 160 within the same 50 units. Competing for a
budget is the signature of optimization, and the reason
[ranking](../recipes/ranking.md) is the wrong class here.

South is infeasible-free — it fits. Watch for a region whose every order exceeds capacity:
`OPTIMIZE` drops such a group silently rather than erroring, so a region missing from
`Fulfil` is a result, not a bug.

## Recipes drawn on

- [How many, how much, per what](../recipes/summary.md) — the framing and the reporting γ.
- [The best combination within limits](../recipes/optimization.md) — `OPTIMIZE … PER`.
- [The most, the top N, the latest](../recipes/ranking.md) — the trap this problem is not.
