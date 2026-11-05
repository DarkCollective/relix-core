# Name: Logical plan catalog (`relix.plan` and `relix.dependencies`)

# Syntax:
relix.plan          -- every node of every view's expression tree, one row each
relix.dependencies  -- which view reads which relation (dependent, depends_on)

# Description:
`relix.plan` is the shape of the script's views as data. A view is a tree of operators —
a projection over a selection over a join — and `relix.plan` has one row for each
node of each such tree, with the row's parent, so the whole tree can be rebuilt, walked
or counted with ordinary queries. It is the table form of what the REPL's `:tree` prints.

`relix.dependencies` is the same information flattened to the one fact people ask for:
which view reads which relation. It has a row for each pair, and joined to itself with a
closure it gives the full lineage of anything in the script.

Columns of `relix.plan`:

- `query` (`STRING`) — the view whose tree the node belongs to.
- `node_id` (`NUMBER`) — the node's number within its view, from 0 at the root, assigned
  in a depth-first walk that visits parents before children.
- `parent_id` (`NUMBER`) — the `node_id` of the node's parent, NULL for the root.
- `ordinal` (`NUMBER`) — the node's position among its parent's children, from 0.
- `op` (`STRING`) — the kind of operator: `Projection`, `Selection`,
  `NaturalJoin`, `Relation` (a reference to a relation, always a leaf), and so on.
- `label` (`STRING`) — the node as `:tree` writes it: `⋈` for a natural join, `π customer`
  for a projection, `Big [QR]` for a reference to a view.
- `relation` (`STRING`) — for a `Relation` node, the name of the relation it reads;
  NULL for every other node.
- `schema_code` (`STRING`) — the node's output heading in the type codes
  [`relix.columns`](introspection-columns.md) uses, as `{customer:S,total:N}`; `?` for an
  open heading, NULL where it could not be worked out.
- `materialization` (`STRING`) — how the node delivers its rows: `stream`, `bag`, `set`
  or `sort`.
- `depth` (`NUMBER`) — how far the node is from the root, which is 0.

Columns of `relix.dependencies`:

- `dependent` (`STRING`) — a view.
- `depends_on` (`STRING`) — a relation that view reads.

# Technical Description:
`relix.dependencies` is not a separate list: it is the query
`δ π query → dependent, relation → depends_on (σ op = "Relation" (relix.plan))`, so the
two cannot disagree. Names are as written in the script, which is what lets
`CLOSURE dependent, depends_on (relix.dependencies)` follow a chain of views down to
the sources.

Only views have bodies, so only views appear as `query` or `dependent`. An inline table
or a source is a leaf: it shows up as a `depends_on`, never as a `dependent`. A
view that reads the same relation twice has one row in `relix.dependencies` and two in
`relix.plan`.

The `relix.*` relations are not described by `relix.plan`, and a `query { … }`
statement is not a view, so neither contributes rows.

# Examples:
The tree of every view, in order:

```relix
query { τ query, node_id (relix.plan) };
```

How many operators each view has:

```relix
query { γ query, COUNT(*) → nodes (relix.plan) };
```

Which views contain a natural join:

```relix
query { δ (π query (σ op = "NaturalJoin" (relix.plan))) };
```

The relations everything named `Report` ultimately reads:

```relix
query { π depends_on (σ dependent = "Report" (CLOSURE dependent, depends_on (relix.dependencies))) };
```

Render a view's tree as a nested document:

```relix
query { TREE node_id BY parent_id ORDER ordinal AS children (σ query = "Report" (relix.plan)) };
```

# Limitations:
The tree is the one the script was written with, before the optimizer has rewritten
it; `relix explain` shows what was planned.

A view that calls a table-valued function shows the call as a node, not the function's
body.

# See Also:
[tree](../operators/tree.md), [closure](../advanced/closure.md), [relation catalog](introspection-relations.md), [introspection stdlib](introspection-stdlib.md), [reserved namespace](introspection-catalog.md)

# Notes:
`relix.unused`, `relix.deps` and `relix.impact` in the [introspection stdlib](introspection-stdlib.md)
are written over `relix.dependencies`.
