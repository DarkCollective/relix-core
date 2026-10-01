# Why is this row here, where did it come from

> **Grain:** one row per result (after `WHY`), then one per contributing source tuple (after μ) · **Class:** Explanation · **Signals:** why, where from, which inputs, how derived, trace back, provenance, justify · **Operators:** `WHY` (ω), μ

## The problem

*"This result row looks wrong — which source rows produced it, and how did they
combine? I want to trace a surprising result back to the exact inputs behind it."*

## How to recognise it

The question is about a **result you already have**, not a new one — *why is this here*,
*where did this come from*, *which inputs does it depend on*, *justify this number*. It
is a debugging and audit question, and the answer is the row's **lineage**: the source
tuples that produced it and the way they combined. `WHY` reifies that lineage as an
ordinary nested column, so the rest of the algebra can slice it like any other data.

The tell that you want `WHY` and not a rerun: you are not trying to change the result,
you are trying to *account for* it.

## The data

```relix
Orders := [
| oid | cid | region | amount |
|-----|-----|--------|--------|
| 100 | 7   | west   | 40     |
| 101 | 7   | west   | 25     |
| 102 | 9   | east   | 10     |
];

Customers := [
| cid | name  |
|-----|-------|
| 7   | Ada   |
| 9   | Grace |
];
```

## Recipe: reify lineage, then read it (WHY, μ)

`WHY (R)` emits every result row of `R` unchanged, plus one `provenance` column
recording which source rows produced it. Wrap the query whose results you want to
explain:

```relix
Aug := { WHY (π region, amount (Orders ⨝ Orders.cid = Customers.cid Customers)) };
query { Aug };
```

```
 region  amount  provenance
 ──────  ──────  ──────────────────────────────
 west        40  [{coefficient: 1, variables: …
 west        25  [{coefficient: 1, variables: …
 east        10  [{coefficient: 1, variables: …
(3 rows)
```

`provenance` is ordinary nested data — an array of *derivations* (the ways the row
could arise), each an array of the source tuples that combined. So the ordinary
operators read it: `μ` explodes a derivation array, and dotted paths read its fields.
To name the exact source rows behind every *west* result, flatten one level to the
derivations, lift the variables out, flatten again, and select:

```relix
PerDeriv := { μ provenance (Aug) };
Lifted   := { π region, amount, provenance.variables → variables (PerDeriv) };
PerVar   := { μ variables (Lifted) };
query {
    π region, amount, variables.relation → source, variables.ordinal → ordinal
        (σ region = "west" (PerVar))
};
```

```
 region  amount  source     ordinal
 ──────  ──────  ─────────  ───────
 west        40  Customers        1
 west        40  Orders           1
 west        25  Customers        1
 west        25  Orders           2
(4 rows)
```

Each result names the two tuples that combined to produce it, because a join is a
*joint* derivation — one derivation, several variables. A result reachable two ways (a
union of a duplicated value) would instead show two derivations. Nothing in that query
is special to provenance: `π`, `μ` and `σ` do to `provenance` exactly what they do to
any nested column, which is the whole point of reifying lineage as data.

## Variations

- **The side-channel** — `--provenance --semiring lineage` reports the same lineage as a
  one-shot dump rather than a queryable column, and also offers the cheap semirings
  (reachability, path count, shortest/cheapest path).
- **`:why` in the REPL** is sugar for `query { WHY(…) }`.
- **Plan, not data** — to explain *how the engine ran* a query rather than where a row
  came from, read `relix.plan` (EXPLAIN-as-data), the reflective sibling of `WHY`.

## Pitfalls

- **Lineage stops at the aggregation wall.** A `γ`, `τ`, `δ`, difference, or outer join
  below `WHY` is read as an opaque base relation — its output rows become a fresh
  source, and lineage is not threaded *through* the grouping. Deep-through-aggregation
  lineage is not available; put `WHY` below the aggregation if you need the inputs to
  it.
- **`WHY` is a hard optimiser barrier and blocks.** No rewrite crosses it (that is
  deliberate — a merged duplicate or an inlined view would change the reified lineage),
  it buffers, and it never pushes down. Use it to explain, not in a hot path.
- **A terminal elides the nested column.** `provenance` is a nested array; read it with
  `--format json` or take it apart with `μ`/`π` — a table view truncates it.
- **`μ` takes a column, not a path.** To explode `provenance.variables`, lift it into a
  column with `π … → variables` first, then `μ variables`.
- **`provenance` is a reserved output name.** A clash with an existing input column is a
  validation error.

## Check it

- Trace a row you **already understand** first — confirm `WHY` names the source tuples
  you expect — before trusting it on the surprising one.
- Count the derivations: a row produced by a single join path has **one** derivation
  with several variables; a row reachable two ways has **several** derivations. The
  exploded form is the one to count over.
- If a row's lineage looks impossibly shallow, check for an **aggregation** below `WHY`
  — the wall is the usual reason.

## Related

- [What changed, what differs](comparison.md) and every other recipe — `WHY` is the
  tool the *Check it* step reaches for when a result is surprising.
- [As columns, as a list, as a tree](reshaping.md) — reading `provenance` uses the same
  `μ`/path navigation as any nested column.
- Reference pages (`docs/reference`): `why`, `unnest`, `provenance`.
