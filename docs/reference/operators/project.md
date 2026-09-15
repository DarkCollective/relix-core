# Name: Projection (π / PROJECT)

# Syntax:
π <columns or expressions> (Relation)
PROJECT <columns or expressions> (Relation)

π name, age (Users)
π price * 1.05 → adjusted_price (Products)

# Description:
Projection chooses which columns appear in the result, in what order, and lets
you rename or compute new ones. It is the "pick / rearrange / calculate columns"
operator — the SELECT list of SQL. The number of rows is unchanged; only the
shape of each row changes.

Any projected expression can be renamed with → (ASCII ->). Unaliased columns
keep their original name, and you can freely mix plain columns with computed
ones.

# Technical Description:
π_L(R) returns R restricted to the attribute list L, where each list entry is an
operand expression with an optional alias. Projection can drop columns, reorder
them, apply scalar functions/arithmetic, and build nested struct/array values.
Under set semantics projection can introduce duplicates (two distinct input rows
may project to the same output row); relix preserves bag semantics unless a
DISTINCT (δ) is applied. It is a streaming operator and pushes down to SQL/Mongo.

# Examples:
Keep only a couple of columns:
  π name, email (Users)

Reorder and rename for a report:
  π id → user_id, name → user_name (Users)

Mix plain and aliased columns:
  π id, name → user_name (Users)

Compute a new column — add 5% to a price:
  π name, price * 1.05 → adjusted_price (Products)

Apply a function while projecting:
  π UCase(name) → name_upper (Customers)

Disambiguate a shared column name above a join with a relation qualifier — here
`rooms.name` names the room's column even though `devices` also has a `name`:
```relix
π devices.name → device, rooms.name → room
  (devices ⨝ devices.room_id = rooms.room_id rooms)
```
A qualified reference resolves to exactly the column it names, or is a validation
error — see [theta-join](../joins/theta-join.md) for the full worked example.

The same dotted spelling reads a **field of a struct column** when it is not a
relation qualifier — `π person.first` — and the two are tried in that order, so a
relation qualifier always wins. See
[struct-construction](../literals/struct-construction.md).

Reshape flat columns into a nested struct:
  π id, { name, age } → person (Users)

Build an array column:
  π label, [x, y] → coords (Points)

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

query { π rep, amount * 2 → doubled (Sales) };
```

```
 rep  doubled
 ───  ───────
 Ada      240
 Bo       160
 Cy       400
 Dee      100
(4 rows)
```

Four rows in, four rows out — projection changes the *columns*, never the row
count. `region` and `bonus` are gone, and `doubled` is computed rather than
copied, which is why projection is more than SQL's column list: any expression
can appear, with `→` naming the result.

# Limitations:
Projection alone does not remove duplicate rows that result from dropping
columns — follow with δ (DISTINCT) if you need unique rows. Nested
struct/array construction is not pushed down to SQL backends.

# Alternatives:
To rename a relation or all its columns without choosing a subset, use ρ
(RENAME). To aggregate rather than compute per-row, use γ (GROUP).

# See Also:
[select](select.md), [rename](rename.md), [distinct](distinct.md), [group](group.md), [struct-construction](../literals/struct-construction.md), [array-construction](../literals/array-construction.md)

# Notes:
The optimizer eliminates a projection that selects every column unchanged,
merges consecutive projections, and pushes projections below selections to prune
columns early.
