# Name: Distinct (δ / DISTINCT)

# Syntax:
δ (Relation)
DISTINCT (Relation)

δ (Users)
DISTINCT (Users JOIN Orders)

# Description:
Distinct removes duplicate rows, so each unique row appears exactly once. It is
the de-duplication step you reach for after a projection or a join has produced
repeated rows — for example, "the list of distinct cities our customers live in".

# Technical Description:
δ(R) collapses R to set semantics: rows equal across all columns are merged to
one. Two rows are equal when their values are, column by column, and a number is
one value however it was written: `5` and `5.0` are the same number, so δ keeps
one of them. Grouping, the set operations and a join key all read the same rule.

**A column's type decides what makes two of its values one value.** In a column
declared `STRING`, the values are strings, so `'2024-01-15T10:00:00Z'` and
`'2024-01-15T11:00:00+01:00'` are two values and δ keeps both — even though `=`
matches either against the `TIMESTAMP` they denote, because that is a different
question. Where a column is `ANY`, the type has settled nothing, so identity
follows the same coercion `=` does: a column holding both `true` and the string
that spells it holds one value, and δ keeps one row. This is the case that arises
over schema-on-read sources, where one field can hold either. It is normally a blocking ([set]) operator, but becomes a single streaming
pass (adjacent-distinct) when the input already delivers an ordering whose keys
cover the whole row. The optimizer eliminates a δ over an input it can prove is
already duplicate-free (DIST-001) — e.g. directly over a γ, a set operation, or a
candidate-key-bearing source — and eliminates one *below* an aggregation that
cannot tell duplicates apart (DIST-002).

Over a SQL source the δ is **pushed to the database** as `SELECT DISTINCT`, so the
duplicates are never sent over the wire and the engine buffers no hash set. It
folds after the select list (`DISTINCT` deduplicates the projected rows) and
before `ORDER BY`/`LIMIT`, which apply to the deduplicated result. A δ over a
`GROUP BY` is not rendered — γ already emits one row per key, so DIST-001 has
removed it.

# Examples:
The distinct set of cities customers live in:
  δ (π city (Customers))

Unique (product, region) pairs that have ever sold:
  δ (π product, region (Sales))

Drop duplicates created by a join:
  δ (π name (Customers ⋈ Orders))

# Worked Example:
Four rows of sales, two of them with no `bonus` recorded — the NULLs are there deliberately, because they are what makes the rules below visible.

```relix
Sales := [
| region | rep  | amount | bonus |
|--------|------|--------|-------|
| east   | Ada  | 120    | 10    |
| east   | Bo   | 80     | NULL  |
| west   | Cy   | 200    | 25    |
| west   | Dee  | 50     | NULL  |
];

query { δ (π region (Sales)) };
```

```
 region
 ──────
 east
 west
(2 rows)
```

Four rows collapse to two. Note the order of operations: the projection to
`region` comes **first**, creating the duplicates that `δ` then removes — over the
full `Sales` relation every row is already unique, so `δ (Sales)` would change
nothing. That is the usual shape: project to the columns you care about, then
deduplicate.

# Limitations:
Distinct compares whole rows — to dedupe on a subset of columns, project to
those columns first. Over an unbounded generator δ needs a bound below it unless
the input is already ordered.

# Alternatives:
γ (GROUP) on the columns you want unique achieves the same and lets you aggregate
at the same time. UNION (∪) deduplicates as part of combining two relations.

# See Also:
[project](project.md), [group](group.md), [union](../set-operations/union.md), [sort](sort.md)

# Notes:
The optimizer often deletes a redundant δ entirely; check `--optimize` output for
a DIST-001 or DIST-002 record. When kept over a database source it becomes
`SELECT DISTINCT` (visible in `--explain` as a `PushedScan`); when kept in the
engine, an ordered input turns it into a cheap streaming pass (`Distinct
streaming`).
