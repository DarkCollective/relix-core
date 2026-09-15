# Name: Unnest (μ / UNNEST)

# Syntax:
μ <array-column> (Relation)
μ <array-column> WITH ORDINALITY <name> (Relation)
UNNEST ...

μ items (Orders)
μ items WITH ORDINALITY pos (Orders)

# Description:
Unnest takes a column that holds an array and expands it into one row per
element — flattening nested data back into flat rows. If an order row has a list
of three items, unnesting the items column turns it into three rows, one per
item. It is the inverse of COLLECT (which gathers values into an array).

The optional WITH ORDINALITY clause adds a column carrying each element's
position (1, 2, 3, …), restarting for each input row.

# Technical Description:
μ_c(R) replaces the array column c with one output row per element of the array,
carrying the element value in place of the array. WITH ORDINALITY appends a
NUMBER column with the 1-based element index. There is an OUTER variant
(programmatic only) that emits a NULL-element row when the array is empty. μ
streams; it pushes down to Mongo as $unwind (inner, non-ordinality form).

# Examples:
One row per line item from an order that stored items as an array:
  μ items (Orders)

Number each element as you explode it:
  μ tags WITH ORDINALITY tag_position (Articles)

Round-trip with COLLECT — group, gather, then explode back:
```relix
Built := { γ customer, COLLECT(order_id) → ids (Orders) };
query { μ ids (Built) };
```

# Worked Example:
`μ` is the inverse of `COLLECT`: where COLLECT folds a group's values into one
array, unnest expands an array back into one row per element. Round-tripping the
two shows it directly.

```relix
Sales := [
| region | rep  | amount | bonus |
|--------|------|--------|-------|
| east   | Ada  | 120    | 10    |
| east   | Bo   | 80     | NULL  |
| west   | Cy   | 200    | 25    |
| west   | Dee  | 50     | NULL  |
];

query { μ reps (γ region, COLLECT(rep) → reps (Sales)) };
```

```
 region  reps
 ──────  ────
 east    Ada
 east    Bo
 west    Cy
 west    Dee
(4 rows)
```

The `γ` produces two rows (`east → [Ada, Bo]`, `west → [Cy, Dee]`); `μ` expands
them back to the original four. The column keeps its name but changes type — `reps`
is an array in the input and a single value in the output.

Add `WITH ORDINALITY` when the position matters:

```relix
Grouped := { γ region, COLLECT(rep) → reps (Sales) };
query { μ reps WITH ORDINALITY pos (Grouped) };
```

```
 region  reps  pos
 ──────  ────  ───
 east    Ada     1
 east    Bo      2
 west    Cy      1
 west    Dee     2
(4 rows)
```

The counter restarts at 1 for each input row, not once across the whole result.

# Limitations:
Unnest works on array-typed columns; over a schema-on-read (open) relation the
column must actually hold an array at runtime. Ordinality-preserving and outer
$unwind do not push down to Mongo (run in-engine).

# Alternatives:
COLLECT (inside γ) is the opposite direction — many rows into one array. For a
struct field whose name you know, use projection (π s.field) rather than unnest.
For an object whose *keys* are the data — counts or settings keyed by a name the
schema does not know in advance — Entries reads it as an array of {key, value}
entries, which this operator then explodes.

# See Also:
[collect](../aggregates/collect.md), [group](group.md), [with-ordinality](../language/with-ordinality.md), [array-construction](../literals/array-construction.md), [entries](../functions/nested/entries.md)

# Notes:
The nest/unnest round-trip laws let the optimizer collapse `μ (γ COLLECT)` back
to a projection (NEST-001) when the shapes line up.
