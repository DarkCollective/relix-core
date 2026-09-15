# Name: Why (WHY / ω)

# Syntax:
WHY (Relation)

ω (π region, amount (Orders ⋈ Orders.customer_id = Customers.customer_id Customers))

# Description:
WHY **reifies a tuple's lineage provenance as ordinary data**: it emits every result
tuple of its input unchanged, plus one added nested column, `provenance`, that records
*which source rows produced that tuple and how they combined*. Provenance is thereby
promoted from a terminal side-channel report (`--provenance` JSON) to a first-class
`relation → relation` operator, so the rest of the algebra — `σ`, `π`, `μ` — can slice
lineage like any other column.

WHY is a **self-terminating reification boundary**: the lineage semiring is threaded
*below* it (exactly as the `--provenance --semiring lineage` side-channel does), frozen
into the `provenance` column *at* it, and inert ordinary data *above* it. Nothing above
WHY re-threads the annotation, so the rest of a query operates on plain nested values,
not live bookkeeping.

# Technical Description:
`WHY(R)` evaluates `R` as a why-provenance K-relation over the polynomial-lineage
semiring `ℕ[X]` (inlining views and reading every non-positive operator — `γ`, `τ`, `δ`,
difference, outer joins, a nested `WHY`, … — as an opaque base relation). Each
base-tuple occurrence is lifted to a distinct provenance variable carrying its
**`SourceRef`**: the producing relation, a 1-based occurrence ordinal, and the base
tuple's captured column values.

The output schema is the input schema plus one column, `provenance`, of type ANY
(schema-on-read). For each result tuple the value is the tuple's lineage polynomial
reified as a **faithful two-level document** — an array with one element per monomial
(distinct derivation):

```
provenance = [ { coefficient : <number>,
                 variables   : [ { relation : <string>,
                                   ordinal  : <number>,
                                   columns  : { <captured base columns…> } }, … ],
                 truncated   : <boolean> }, … ]
```

This preserves the two ways derivations combine:

  - **Joint** derivation (`·`, an AND — tuples combined by a join/product) → **one
    monomial** with **several `variables`**.
  - **Alternative** derivation (`+`, an OR — the same result reachable via a union or a
    duplicate) → **several monomials**.

`coefficient` is the derivation's multiplicity. `columns` is the contributing source
tuple's captured values, so a consumer can match it back to the exact row. The
polynomial-level `truncated` flag rides every derivation element (so it survives a
`μ provenance` explode); it is `true` when the polynomial exceeded the representation
cap (`MAX_MONOMIALS = 256`) and some derivations were dropped — a sound
under-approximation: the surviving terms are real, but the list is incomplete.

WHY is **blocking** (the canonical K-relation is built before output), a **hard
optimizer barrier** (no pass rewrites inside or across it — a column-dropping `π` that
merged duplicates, a `δ`-elimination, or view inlining that replaced a reference with an
aliased body, would silently change the reified lineage), and is **never pushed down** to
a SQL or MongoDB backend.

## The aggregation wall
WHY reifies only what the lineage evaluator can thread — the positive operators. A `γ`
(or any non-positive operator) below WHY is an opaque boundary: its output rows are read
as a fresh source (`Aggregation#n`), not lineage threaded through the grouping.
Deep-through-aggregation lineage is a documented post-v1 follow-on.

# Examples:

## Example 1 — base relation: each tuple names its own source row
```
Orders := [| id | region |
           | 1  | west   |
           | 2  | east   |];

WHY (Orders)
```

Each output row is the input row plus its (single-derivation, single-variable)
provenance:

```
-- id | region | provenance
-- 1  | west   | [ { coefficient: 1,
--                   variables: [ { relation: Orders, ordinal: 1,
--                                  columns: { id: 1, region: west } } ],
--                   truncated: false } ]
-- 2  | east   | [ { coefficient: 1,
--                   variables: [ { relation: Orders, ordinal: 2,
--                                  columns: { id: 2, region: east } } ],
--                   truncated: false } ]
```

## Example 2 — joint vs. alternative derivations
A **join** combines two tuples into one result row → **one monomial, two variables**:

```
-- Orders(oid:1, cid:7)  ⨝  Customers(cid:7, name:Ada)
WHY (Orders ⨝ Orders.customer_id = Customers.customer_id Customers)

-- provenance of the single result row:
--   [ { coefficient: 1,
--       variables: [ { relation: Orders,    ordinal: 1, columns: {oid: 1, cid: 7} },
--                    { relation: Customers, ordinal: 1, columns: {cid: 7, name: Ada} } ],
--       truncated: false } ]
```

A **union of a duplicated value** yields the same tuple by two routes → **two
monomials**:

```
-- Left(x:1)  ⊎  Right(x:1)   (the value 1 is produced by both)
WHY (Left ⊎ Right)

-- provenance of the single result row (x = 1):
--   [ { coefficient: 1, variables: [ { relation: Left,  ordinal: 1, columns: {x: 1} } ], truncated: false },
--     { coefficient: 1, variables: [ { relation: Right, ordinal: 1, columns: {x: 1} } ], truncated: false } ]
```

## Example 3 — fully worked: which source rows produced each `west` result
The reified column is ordinary data, so `μ` (UNNEST) explodes its arrays and path
navigation reads its fields — no new operators needed.

Source data:

```
Orders    := [| oid | cid | region | amount |
              | 100 | 7   | west   | 40     |
              | 101 | 7   | west   | 25     |
              | 102 | 9   | east   | 10     |];
Customers := [| cid | name  |
              | 7   | Ada   |
              | 9   | Grace |];
```

Reify the lineage of a joined, projected view, then flatten it — worked through
with real output below:

```relix
Aug      := { WHY (π region, amount
                    (Orders ⨝ Orders.customer_id = Customers.customer_id Customers)) };
PerDeriv := { μ provenance (Aug) };
Lifted   := { π region, amount, provenance.variables → variables (PerDeriv) };
PerVar   := { μ variables (Lifted) };
West     := { π variables.relation, variables.ordinal, variables.columns
                (σ region = "west" (PerVar)) };
```

(`μ` takes a column, not a path, which is why the array is lifted out of the
derivation with `π` before it is exploded.)

`Aug` has three rows (the three orders, each joined to its customer), each carrying a
one-monomial provenance with two variables (the `Orders` row and the `Customers` row).
`μ provenance` then `μ variables` flatten that to one row per contributing source tuple;
the final `σ region = "west"` + `π` names exactly the source rows behind every `west`
result:

```
-- West:
--   variables.relation | variables.ordinal | variables.columns
--   Orders             | 1                 | { oid: 100, cid: 7, region: west, amount: 40 }
--   Customers          | 1                 | { cid: 7, name: Ada }
--   Orders             | 2                 | { oid: 101, cid: 7, region: west, amount: 25 }
--   Customers          | 1                 | { cid: 7, name: Ada }
```

Data flow through the operator and its `μ`/`σ`/`π` consumers:

```
  Orders ⋈ Customers ── π region, amount ──┐
                                           ω  (freeze lineage into `provenance`)
                                           │
            Aug rows: (region, amount, provenance:[ {coeff, variables:[…]} ])
                                           │
                         μ provenance ─────┤  one row per derivation (monomial)
                         μ variables ──────┤  one row per contributing source tuple
                 σ region = "west" ────────┤  keep the `west` results
       π variables.relation, …columns ─────┘  name the exact source rows
```


# Worked Example:
Two customers, three orders, and the question every lineage feature exists to
answer: *which source rows produced this result row?*

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

Every result row came through unchanged, with one column added. The table format
elides a nested value at the column width — `provenance` is an array of
derivations, each an array of contributing source tuples — so reading it in a
terminal means taking it apart, which is the point of reifying it as data in the
first place.

`μ` explodes the array, giving one row per *derivation* rather than per result
row — provenance is ordinary data, and the ordinary operators apply to it:

```relix
query { μ provenance (Aug) };
```

```
 region  amount  provenance
 ──────  ──────  ──────────────────────────────
 west        40  {coefficient: 1, variables: […
 west        25  {coefficient: 1, variables: […
 east        10  {coefficient: 1, variables: […
(3 rows)
```

Three rows still, because each of these results has exactly one derivation: a
single join path produced it. A result reachable two ways — the same tuple coming
from both sides of a `⊎`, say — would appear once per derivation here, which is
what makes the exploded form the one to count and group over.

**Naming the source rows.** One more level down is the list of contributing
tuples. `μ` explodes an array held in a *column*, so the array is lifted out of the
derivation struct with `π` first, and then the fields of each variable are read the
same dotted way:

```relix
PerDeriv := { μ provenance (Aug) };
Lifted := { π region, amount, provenance.variables → variables (PerDeriv) };
PerVar := { μ variables (Lifted) };

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

Each `west` result names the two source tuples that combined to produce it — one
from `Orders`, one from `Customers` — because a join is a *joint* derivation: one
monomial, two variables. The `ordinal` distinguishes occurrences, so the two west
rows point at different `Orders` tuples while sharing the same `Customers` one.

Nothing in that query is special to provenance. `π`, `μ` and `σ` are doing to the
`provenance` column exactly what they do to any nested column — which is the whole
argument for reifying lineage as data instead of reporting it out of band.

# Limitations:
The added column needs a name the input does not already use. Over a
schema-on-read source (JSON, HTTP, MongoDB) there is no declared heading to
clash with, so the operator always runs; if a document turns out to carry a
field of that name, the added column replaces it.

The `provenance` column is typed ANY, so paths into it (`variables.relation`,
`variables.columns.oid`) are not statically type-checked until a Map type / field-access
operand lands. v1 reifies the **lineage** semiring only; the cheap semirings
(boolean / counting / tropical / cost) stay on the `--provenance --semiring …`
side-channel. Lineage stops at the nearest non-positive operator (the aggregation wall).
A polynomial past `MAX_MONOMIALS` (256) is truncated and flagged. WHY is a hard barrier
and is never pushed to SQL/MongoDB.

# Alternatives:
`--provenance --semiring lineage` is the streaming **side-channel sibling**: the same
lineage, surfaced as a terminal report rather than a data column. Use WHY when you want
to *query* lineage with ordinary views; use the side-channel for a one-shot dump or for
the cheap semirings (reachability, path count, shortest path, cheapest route). The
reflective `relix.plan` relation is the analogous surface for *plan* metadata
(`EXPLAIN`-as-data) rather than data provenance.

# See Also:
[unnest](unnest.md), [project](project.md), [select](select.md)

# Notes:
WHY adds no new evaluation concepts — it reuses the provenance evaluator,
`μ` (UNNEST), and `ValuePath` navigation. Annotations are a side-channel by default,
reified into a data column **only** through the explicit WHY boundary. `provenance`
is a reserved output column name; a clash with an existing input column is a
validation error.
