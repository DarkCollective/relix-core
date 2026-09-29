# As columns, as a list, as a tree

> **Grain:** changes on purpose — per group (nest/pivot), per element (unnest), per root (tree) · **Class:** Reshaping · **Signals:** as columns, as a list, gather, flatten, as a tree, wide, nested · **Operators:** `PIVOT`/`UNPIVOT`, `COLLECT`, μ, `TREE`

## The problem

*"Turn the long quarterly sales table into one column per quarter. Gather each
customer's order ids into a list. Flatten a stored list back into rows. And fold an
org chart's parent pointers into a nested tree."*

## How to recognise it

The question is about **shape**, not selection or summary — *as columns*, *as a list*,
*flatten*, *as a tree*, *wide instead of long*. The tell is that the **grain changes
on purpose**: reshaping deliberately turns one row-per-thing into a different
one-row-per-something. Which operator depends on the target shape:

- **Rows → columns** (long to wide) — `PIVOT` (and `UNPIVOT` back).
- **Many rows → one list** — `COLLECT`, inside a γ. Its inverse, **list → many rows**,
  is `μ` (unnest).
- **Parent pointers → a nested tree** — `TREE`, the recursive generalisation of
  `COLLECT`.

## Recipe 1: rows to columns (PIVOT)

`PIVOT value BY key` turns each distinct value of the key column into an output
column. A group with no row for a key gets NULL there.

```relix
QuarterlySales := [
| region | quarter | revenue |
|--------|---------|---------|
| East   | q1      | 100     |
| East   | q2      | 120     |
| West   | q1      | 80      |
];

query { PIVOT revenue BY quarter PER region (QuarterlySales) };
```

<!-- output: paste from a run. Expected: one row per region; columns q1, q2; West's q2 is NULL (no such row). -->

The column headers come from the **data**, so the output schema is *open* — downstream
operators that need named columns may need a ρ. `UNPIVOT` is the reverse, folding
listed columns back into rows.

## Recipe 2: many rows to a list (COLLECT), and back (μ)

`COLLECT`, inside a γ, gathers a group's values into an array instead of reducing them
to one number:

```relix
Orders := [
| customer | order_id |
|----------|----------|
| Ann      | 1        |
| Ann      | 2        |
| Bo       | 3        |
];

Gathered := { γ customer, COLLECT(order_id) → order_ids (Orders) };
query { Gathered };
```

<!-- output: paste from a run. Expected: Ann → [1, 2], Bo → [3]. -->

`μ` (unnest) is the exact inverse — it explodes an array into one row per element, and
`WITH ORDINALITY` records each element's position:

```relix
query { μ order_ids WITH ORDINALITY pos (Gathered) };
```

<!-- output: paste from a run. Expected: Ann/1/pos 1, Ann/2/pos 2, Bo/3/pos 1 — the ordinal restarts per input row. -->

`μ` is also how you flatten a source that already stores an array (a JSON field of
line items, say) into flat rows.

## Recipe 3: parent pointers to a tree (TREE)

An adjacency table — each row pointing at its parent — folds into a forest of nested
documents in one pass. `TREE key BY parentKey` follows the edge to any depth; a row
whose parent is absent (or NULL) is a root.

```relix
Employees := [
| id | manager_id | name |
|----|------------|------|
| 1  | 0          | Ada  |
| 2  | 1          | Bob  |
| 3  | 1          | Cara |
| 4  | 2          | Dan  |
];

query { TREE id BY manager_id ORDER id ASC AS reports (Employees) };
```

<!-- output: paste from a run. Expected: one row (root Ada), whose reports nests Bob (with Dan under him) and Cara. Render with --format json to see the whole nested value. -->

One row per root, each carrying its whole subtree in the added column — the recursive
version of `COLLECT`, which nests a single level. From there it is ordinary nested
data: `μ reports` walks one level, `π reports` reads the column.

## Variations

- **Aggregate before pivoting.** If a group has several rows for one key, `PIVOT` picks
  one non-deterministically — γ-sum first (`PIVOT total BY key PER g (γ …)`) when you
  want the total.
- **`COLLECT` of a struct** — `COLLECT({order_id, amount})` bundles whole objects, not
  just one field.
- **`TREE` for any hierarchy** — org charts, bills of materials, threaded comments, a
  query's own plan (`TREE(relix.plan)`).

## Pitfalls

- **`PIVOT`'s schema is open and data-dependent.** The columns and their order come from
  the runtime key values, so a downstream query cannot assume a name exists; rename or
  test defensively.
- **`COLLECT` keeps NULLs; the other aggregates skip them.** `COLLECT(bonus)` yields
  `[10, NULL]`, one element per row — matching `array_agg`. If you did not want the
  NULLs, filter them first.
- **A terminal elides nested values.** A table view shows an array or struct truncated
  at the column width; use `--format json` (or project into the nesting) to see a
  `COLLECT` or `TREE` result whole.
- **`TREE` needs a well-formed forest.** A duplicate key or a cycle is a runtime error,
  not a silently-dropped row — which is a feature (it refuses to loop forever), but
  clean the adjacency first.
- **Order within a nested/exploded result is not guaranteed** unless you ask: `ORDER`
  on `TREE`, a τ below `COLLECT`, and remember `μ`'s ordinal restarts per input row.

## Check it

- **The grain changed on purpose — state the new one.** After `COLLECT` it is one row
  per group; after `μ` one row per element; after `TREE` one row per root. Count against
  that.
- `COLLECT` then `μ` should **round-trip** back to the original rows (the optimiser even
  collapses the pair). Try it on tiny data.
- Put a group with a **missing** pivot cell (West's q2) and confirm it is NULL, not
  absent.
- Give `TREE` a **second root** and confirm you get two output rows — it is a forest,
  not a single tree.

## Related

- [How many, how much, per what](summary.md) — γ with a scalar aggregate collapses a
  group to a number; `COLLECT` keeps the list.
- [Connected, reachable, via](connected-groups.md) — `CLOSURE`/`PATH` traverse the same
  parent edges `TREE` folds, but produce reachability, not nested structure.
- Reference pages (`docs/reference`): `pivot`, `unpivot`, `collect`,
  `unnest`, `tree`.
