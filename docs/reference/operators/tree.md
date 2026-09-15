# Name: Tree (TREE)

# Syntax:
TREE key BY parentKey [ORDER col [ASC|DESC], ...] AS childrenColumn (Relation)

TREE node_id BY parent_id ORDER ordinal AS children (relix.plan)

# Description:
TREE folds a self-referential **adjacency relation** into a **forest of nested
documents**: one output row per *root*, each carrying its whole subtree in an added
nested array column. It is the recursive generalisation of COLLECT — where COLLECT
(NEST) gathers a single level of children into an array, TREE follows the
`key → parentKey` edge to fixpoint and assembles an unbounded-depth tree in one pass.

Its marquee use is dogfooding the engine's own IR — `TREE(relix.plan)` renders a
view's logical plan as a single nested document — but it is general: org charts,
bills-of-materials, threaded comments, file trees, any parent-pointer hierarchy.

# Technical Description:
Given an input relation R with a node-identity column `key` and a parent-pointer
column `parentKey`:

  - A row whose `parentKey` is NULL, or references a `key` value absent from R, is a
    **root** (forest semantics — there may be several).
  - Every other row is a **child** of the row whose `key` equals its `parentKey`.

The output schema is the input schema plus one column, `childrenColumn`, of type ANY
(schema-on-read; there is no recursive static type in v1). TREE emits one row per
root: the root's own (flat) input columns, followed by `childrenColumn` holding an
array of child **documents**. Each child document is a struct of that child's input
columns plus its own `childrenColumn` array, recursively. A leaf carries an empty
children array `[]`.

Sibling order within every level — and the order of the roots themselves — follows
the optional `ORDER` clause (multiple keys, each `ASC` (default) or `DESC`); with no
`ORDER` clause, input order is preserved.

TREE is **blocking** (it must see the whole relation to build the forest) and is
never pushed down to a source backend. Over a provably unbounded input it is a
plan-time error, like other blocking operators.

## Well-formedness
A tree assumes an acyclic, single-parent forest. TREE rejects malformed input at
evaluation time:

  - A **duplicate** `key` value is a key violation → evaluation error.
  - A **cycle** in the `key → parentKey` graph (a component with no root) is a user
    error → evaluation error, rather than looping forever.

A **NULL `key`** is neither, and is not refused. Such a row is placed by its parent
pointer like any other, so it appears wherever that pointer puts it — but nothing
can ever be its child, because a NULL is not an identity and a parent cell holding
NULL means "root" rather than "points at the keyless row". It is therefore always a
leaf.

# Examples:

## Example 1 — dogfood the IR (`relix.plan`)
`relix.plan` exposes every view's logical plan as flat adjacency — one row per
node, with `(node_id, parent_id)` encoding the tree. TREE nests it back into the
shape it describes. Consider the view `Revenue := π region, amount (Orders ⋈ Customers)`,
whose four plan rows (filtered to this query) are:

```
-- σ query = "Revenue" (relix.plan)
-- query    | node_id | parent_id | ordinal | op          | label
-- Revenue  | 0       | (none)    | 0       | Projection  | π region, amount   ← root
-- Revenue  | 1       | 0         | 0       | NaturalJoin | ⋈
-- Revenue  | 2       | 1         | 0       | Relation    | Orders
-- Revenue  | 3       | 1         | 1       | Relation    | Customers
```

(node 0's `parent_id` is absent from the result, so it is the root.)

```relix
TREE node_id BY parent_id ORDER ordinal AS children
  (σ query = "Revenue" (relix.plan))
```

```
-- Output: one row (the Projection root) whose `children` nests the whole tree:
--   node_id | op         | label            | children
--   0       | Projection | π region, amount | [ { node_id: 1, op: NaturalJoin, label: ⋈,
--                                                children: [ { node_id: 2, op: Relation,
--                                                              label: Orders,    children: [] },
--                                                            { node_id: 3, op: Relation,
--                                                              label: Customers, children: [] } ] } ]
```

ASCII view of the same fold (flat adjacency → nested forest):

```
0 ──┐                       0  (Projection)
1 ──┘ parent 0      ⇒       └─ 1  (NaturalJoin)
2 ──┐ parent 1                 ├─ 2  (Orders)
3 ──┘ parent 1                 └─ 3  (Customers)
```

## Example 2 — org chart
Employees with a manager pointer; a top exec whose `manager_id` is `NULL` or
points at a non-existent id becomes a root.

```
-- Input Employees: id | manager_id | name
--                   1  | 0          | Ada      (manager 0 is absent → root)
--                   2  | 1          | Grace
--                   3  | 1          | Lin
--                   4  | 2          | Edsger
```

  TREE id BY manager_id ORDER name AS reports (Employees)

```
-- Output (one row, the root Ada):
--   id | manager_id | name | reports
--   1  | 0          | Ada  | [ { id: 2, name: Grace,
--                              reports: [ { id: 4, name: Edsger, reports: [] } ] },
--                            { id: 3, name: Lin, reports: [] } ]
```

From there it is ordinary nested data: `μ reports` walks a level (UNNEST), and
`π reports` reads the nested column.

## Example 3 — threaded comments
A comment thread is an adjacency relation (`parent_id` is the comment replied to);
TREE rebuilds the reply tree, oldest-first, in one pass — no per-level self-join.

```
-- Input Comments: id | parent_id | author | posted_at
--                  10 | (none)    | ann    | 09:00      (top-level → root)
--                  11 | 10        | bo     | 09:05
--                  12 | 11        | ann    | 09:10
--                  13 | 10        | cy     | 09:07
```

  TREE id BY parent_id ORDER posted_at AS replies (Comments)

```
-- Output (one row, comment 10):
--   id | author | replies
--   10 | ann    | [ { id: 11, author: bo,
--                    replies: [ { id: 12, author: ann, replies: [] } ] },
--                  { id: 13, author: cy, replies: [] } ]
```


# Worked Example:
An org chart is the shape everyone already has an intuition for: each row points
at its manager, and Ada's pointer goes nowhere, which makes her a root.

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

```
 id  manager_id  name  reports
 ──  ──────────  ────  ──────────────────────────────
  1           0  Ada   [{id: 2, manager_id: 1, name:…
(1 row)
```

One row out of four, because there is one root — and that row carries the entire
hierarchy in `reports`. The table format elides a nested value at the column
width, which is the first thing to know about querying nested data from a
terminal: use `--format json` (or project into the nesting) to see it whole. Laid
out, that one cell holds

    Ada
    ├─ Bob
    │  └─ Dan
    └─ Cara

nested to whatever depth the data has. The fold followed the `id → manager_id` edge to fixpoint in a
single pass; nothing in the query says how deep the tree is, and nothing needs to.

`ORDER id ASC` fixes the sibling order. Without it, siblings appear in whatever
order the input arrived in, which is rarely what a report wants.

# Limitations:
The added column needs a name the input does not already use. Over a
schema-on-read source (JSON, HTTP, MongoDB) there is no declared heading to
clash with, so the operator always runs; if a document turns out to carry a
field of that name, the added column replaces it.

The nested children column is typed ANY — there is no recursive static type in v1.
TREE is blocking and is never pushed down to SQL or MongoDB (a Postgres `RECURSIVE`
CTE + `json_agg` lowering is a documented follow-on). Cyclic or duplicate-key input
is a runtime error, not a silently-dropped subset.

# Alternatives:
COLLECT (inside γ) nests a *single* level of children into an array; μ (UNNEST) is
the one-level inverse. CLOSURE / PATH / FIX traverse the same key edges but produce
reachability pairs or paths rather than nested structure.

# See Also:
[unnest](unnest.md), [COLLECT](../aggregates/collect.md)

# Notes:
TREE reuses the recursion engine's cycle guard, COLLECT-style nesting, and the ANY
schema-on-read document model. When the input schema is open
(schema-on-read), the output rows are open documents with the children field added.
