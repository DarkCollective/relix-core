# Family history

*Graph + Time*

## The problem

*"From a family tree stored as parent links, list a person's ancestors, say how many
generations back each one is, and show when each was born."*

## Classifying it

- *"a person's ancestors … how many generations back"* — a
  **[graph](../recipes/reachability.md)** question over a parent-pointer relation:
  reachability gives the ancestors, and a bounded traversal gives the generation
  distance.
- *"when each was born"* — a **time** attribute, joined back once the ancestors are
  known. (In a real tree the source is a GEDCOM file, read through the engine's GEDCOM
  connector; the shape below is the same, with the individuals and links as inline
  stand-ins.)

A parent-pointer tree is the same shape as the fraud graph and the bill of materials —
this is *"follow the edge to any depth"*, and the operator is `CLOSURE` (or `FIX` when the
step needs more than two columns; see [recursion](../recipes/recursion.md)).

## The data

```relix
People := [
| id | name | born |
|----|------|------|
| 1  | Ada  | 1900 |
| 2  | Bob  | 1925 |
| 3  | Cara | 1952 |
| 4  | Dan  | 1978 |
| 5  | Cy   | 1955 |
];

ParentOf := [
| child | parent |
|-------|--------|
| 2     | 1      |
| 3     | 2      |
| 4     | 3      |
| 5     | 2      |
];
```

Dan (4) descends from Cara (3), Bob (2), Ada (1); Cy (5) is Bob's other child — Dan's
uncle.

## Stage 1: the ancestors (graph — reachability)

`CLOSURE child, parent` follows the parent edge to any depth. Scope it to Dan:

```relix
Ancestors := { CLOSURE child, parent (ParentOf) };
query { π parent → ancestor (σ child = 4 (Ancestors)) };
```

<!-- output: paste from a run. Expected: 3, 2, 1 — Cara, Bob, Ada. Cy (5) is not an ancestor; a sibling line is not on the path up. -->

## Stage 2: how many generations back (graph — distance)

`PATH` stamps each ancestor with its distance, so *"grandparent"* is generation 2:

```relix
query {
    τ gen, ancestor (
        π parent → ancestor, gen (
            σ child = 4 (PATH child, parent HOPS 1 TO 5 AS gen (ParentOf))))
};
```

<!-- output: paste from a run. Expected: Cara gen 1, Bob gen 2, Ada gen 3. -->

## Stage 3: with birth years (time)

Join the ancestor set back to `People` — an ordinary [existence](../recipes/existence.md)
semi-join keeps each ancestor once and brings the temporal column along:

```relix
DanAncestors := { δ (π parent → id (σ child = 4 (Ancestors))) };
query { τ born, name (People ⋉ People.id = DanAncestors.id DanAncestors) };
```

<!-- output: paste from a run. Expected: Ada 1900, Bob 1925, Cara 1952 — oldest first. -->

## What this shows

The graph is the whole problem; time is a column you pick up at the end. Note that the
sibling line (Cy) never appears — reachability up the parent edge is exactly *ancestors*,
not *relatives*. For *descendants*, run the closure the other way
(`CLOSURE parent, child`); for *cousins and the rest*, `CLUSTER` the undirected
"related-to" graph, which is the [connected-groups](../recipes/connected-groups.md)
recipe.

Two edges from a real GEDCOM would change nothing here: the connector produces the same
`People` and `ParentOf` relations, and dates arrive as real `DATE`/`TIMESTAMP` values, so
*"ancestors born before 1930"* is a σ on the temporal column.

## Recipes drawn on

- [Reachable, how far, and by what route](../recipes/reachability.md) — `CLOSURE`, `PATH`.
- [When the rule is recursive](../recipes/recursion.md) — `FIX`, when a step needs more
  than two columns.
- [Has at least one](../recipes/existence.md) — the join back for birth years.
