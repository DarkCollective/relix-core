# Name: Array Construction ([ ... ])

# Syntax:
[ expr, ... ]

π label, [x, y] → coords (Points)

# Description:
Array construction builds a list value inside a projection — gathering several
expressions into one ordered array column. For example, packing `x` and `y` into a
single `coords` array. It is the per-row counterpart to COLLECT (which builds an
array across the rows of a group).

# Technical Description:
`[ expr, … ]` builds an ArrayValue (ArrayConstruction operand) from the listed
expressions in order. It is parsed in operand position only. It evaluates to a
nested array value and is never pushed down to SQL. Its type is an `ArrayType` of
the type its elements agree on — `[x, y]` over two NUMBER columns infers as `[N]`
— falling back to `[?]` when they disagree or the array is empty, since a
heterogeneous list has no element type to name.

# Examples:
Pack coordinates into an array:
  π label, [x, y] → coords (Points)

Build a fixed-size feature vector:
  π id, [height, weight, age] → features (Subjects)

Then explode it back with UNNEST:
  μ coords (π label, [x, y] → coords (Points))

# Worked Example:
A weather station records three readings per site and the analysis wants them as
one ordered vector per row, rather than as three columns that every downstream
query has to name individually.

```relix
Readings := [
| site   | dawn | noon | dusk |
|--------|------|------|------|
| north  | 4    | 17   | 9    |
| south  | 8    | 21   | 13   |
];

Vectors := { π site, [dawn, noon, dusk] → temps (Readings) };

query { Vectors };
```

```
 site   temps
 ─────  ───────────
 north  [4, 17, 9]
 south  [8, 21, 13]
(2 rows)
```

The array's type is the type its elements agree on — all three are NUMBER here, so
the column is an array of NUMBER rather than an array of anything:

```relix
query { π column, type (σ relation = "Vectors" (relix.columns)) };
```

```
 column  type
 ──────  ────
 site    S
 temps   [N]
(2 rows)
```

Ordering is positional and preserved, which is what makes `μ … WITH ORDINALITY`
meaningful over it: the index is the reading's place in the day.

```relix
query { μ temps WITH ORDINALITY slot (Vectors) };
```

```
 site   temps  slot
 ─────  ─────  ────
 north      4     1
 north     17     2
 north      9     3
 south      8     1
 south     21     2
 south     13     3
(6 rows)
```

Mix the element types and there is no common type left to name, so the element
type falls back to `?`:

```relix
Mixed := { π site, [dawn, site] → pair (Readings) };
query { π column, type (σ relation = "Mixed" (relix.columns)) };
```

```
 column  type
 ──────  ────
 site    S
 pair    [?]
(2 rows)
```

# Limitations:
Produces a nested value that does not push down to SQL. Recognised only in operand
position within a projection. Elements may be of mixed type, and an array of mixed
elements infers as an array of ANY — the element type is the one thing the
construction cannot state when its elements disagree.

# Alternatives:
COLLECT (inside γ) builds an array from many rows of a group, not from columns of
one row. Struct construction ({ … }) for named fields rather than an ordered list.

# See Also:
[struct-construction](struct-construction.md), [collect](../aggregates/collect.md), [unnest](../operators/unnest.md), [project](../operators/project.md)

# Notes:
Array construction (per row) and COLLECT (per group) are the two ways to produce
array columns; UNNEST (μ) flattens either back into rows.
