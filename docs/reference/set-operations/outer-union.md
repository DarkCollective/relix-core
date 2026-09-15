# Name: Outer-union / heterogeneous merge (⊔ / OUNION)

# Syntax:
Relation1 ⊔ Relation2
Relation1 OUNION Relation2

People ⊔ Places

# Description:
Outer-union merges two relations that need **not** have the same columns. Columns
that appear in both inputs are lined up by name; a column that appears in only one
input is filled with NULL for the rows that came from the other input. Duplicate
result rows are removed. Use it to stack records from differently-shaped sources
into one table — exactly the situation a plain UNION rejects.

# Technical Description:
`R ⊔ S` produces the **column-union** of the two schemas: every column of `R`,
followed by every column of `S` whose name is not already present (matched
case-insensitively). A column present in both inputs whose types disagree is
widened to `ANY`. Each input row is re-projected onto this merged schema —
columns the input does not carry are NULL-padded — and the combined rows are
de-duplicated (set semantics). It is a blocking (`[set]`) operator and is never
pushed down to a backend (there is no portable heterogeneous-merge SQL form).

Unlike `∪`/`⊎`/`∩`/`−`, an outer-union imposes **no** union-compatibility
requirement: the inputs may differ in both width and column names. If either
input has an open (schema-on-read) schema, the result is open too.

# Examples:
Two sources describing overlapping-but-different facts, stacked into one table
(worked through with real output below):

  People ⊔ Places

Combine event streams from systems that log different attributes:
  WebEvents ⊔ MobileEvents ⊔ KioskEvents

(Outer-union is left-associative and shares the precedence tier of `∪`/`⊎`/`−`,
so a chain of `⊔` merges all of them.)


# Worked Example:
Two relations that a plain `∪` would reject: they share only `id`.

```relix
People := [
| id | name  |
|----|-------|
| 1  | Alice |
| 2  | Bob   |
];

Places := [
| id | city   |
|----|--------|
| 2  | Berlin |
| 3  | Lisbon |
];

query { People ⊔ Places };
```

```
 id  name   city
 ──  ─────  ──────
  1  Alice  NULL
  2  Bob    NULL
  2  NULL   Berlin
  3  NULL   Lisbon
(4 rows)
```

The merged heading is `(id, name, city)` — every column of the left input, then
every column of the right that is not already there. No row is *combined*: `id` 2
appears twice, once from each side, because an outer union stacks rows rather
than matching them. That is the difference from a join, and the reason each row
is NULL in exactly the columns its own input does not have.

# Limitations:
Set semantics only — duplicate rows are removed (there is no "outer-union all"
variant). Never pushed down to a source; the merge runs in-engine. A type
conflict on a shared column resolves to `ANY` rather than raising an error.

# Alternatives:
Union (∪) when the inputs are already union-compatible — it is cheaper and checks
the schemas match. Natural join (⋈) when you want to *combine* matching rows
side-by-side rather than stack them.

# See Also:
[union](union.md), [union-all](union-all.md), [intersection](intersection.md), [difference](difference.md), [distinct](../operators/distinct.md)

# Notes:
Because the output schema is computed from both inputs' static schemas, an
outer-union stays in the closed-schema core whenever its inputs do — the
dynamic (schema-on-read) machinery only engages if an input is itself open.
