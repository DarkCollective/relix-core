# Name: Glossary (the vocabulary)

# Syntax:
R, S            stand for whole relations (or expressions producing one)
a, b, c         stand for attributes — the columns of a relation
σ π ρ γ τ λ δ μ  the unary operators: one relation in, one relation out
⋈ ⨝ ⟕ ⟖ ⟗ ⋉ ▷    the joins: two relations in, one out
∪ ⊎ ∩ − × ÷ ∘ ∆  the set operations
∧ ∨ ¬            the connectives that build a condition

# Description:
The terms this manual uses, in the order you meet them. Every one links to the
page that covers it properly. If you are here before writing anything, read
[getting started](getting-started.md) first — it uses most of these words in a
single page of script.

## The data model

- **Relation** — a table: a set of rows that all have the same named columns. It
  is the only kind of value an operator takes and the only kind it returns, which
  is why operators nest freely.
- **Tuple**, or **row** — one member of a relation: a value for each column.
- **Attribute**, or **column** — one named, typed field of every row.
- **Heading**, or **schema** — a relation's column names and types. Relix works
  out the heading of every expression before running it, so an operator that
  cannot apply to its input is an error at analysis time.
- **Degree** — how many columns a relation has. **Cardinality** — how many rows.
- **Type** — what a column holds: `NUMBER`, `STRING`, `BOOLEAN`, the temporal
  types [`DATE`/`TIME`/`TIMESTAMP`/`DURATION`](literals/date-literal.md), a
  [struct](literals/struct-construction.md), an
  [array](literals/array-construction.md), or `ANY` for data whose shape is only
  known when it is read.
- **NULL** — a missing value, not a zero or an empty string. Comparing it to
  anything yields neither true nor false, so `σ` drops the row; test for it with
  [`IS NULL`](predicates/is-null.md).
- **Bag** and **set** — a bag may hold the same row twice, a set may not. Relix
  keeps duplicates unless you ask for them to go: `∪` deduplicates,
  [`⊎`](set-operations/union-all.md) does not, and
  [`δ`](operators/distinct.md) removes them from anything.
- **Key** — a set of columns whose values identify a row uniquely. The engine
  derives keys where it can and uses them to skip work, such as a
  [`δ`](operators/distinct.md) over rows that are already distinct.
- **Nested relation (NF²)** — a relation whose columns may themselves hold
  structs, arrays, or whole relations. [`COLLECT`](aggregates/collect.md) builds
  one and [`μ`](operators/unnest.md) flattens it back.

## Writing a script

- **Statement** — one declaration, assignment or `query`, terminated by `;`.
- **Source** — a [declaration](language/source.md) binding a name to external
  data: a database table, a CSV or JSON file, an HTTP endpoint.
- **Base relation** — a relation the data comes from directly (a source or an
  [inline table](language/inline-table.md)), as opposed to one computed from
  others.
- **View** — a name given to an expression with
  [`:=`](language/assignment.md). It stores no rows; the optimizer folds the
  expression into whatever reads it, so naming the steps of a long query is free.
- **Query statement** — [`query { … }`](language/assignment.md), which marks a
  result you want back. A script with no `query` computes nothing.
- **Namespace** and **import** — how one script's
  [names](language/namespace.md) are grouped and how another script
  [reuses](language/import.md) them.
- **Scalar function** — a function over single values, `Len(name)`, either
  built in or [defined](language/def.md) in the script.
- **Table-valued function (TVF)** — a
  [function returning a relation](language/def-relation.md), callable anywhere a
  relation is expected.
- **Relationship** — a named, bounded link between two relations, declared with
  [`relate`](language/relate.md) or a source's `references:` block. Together they
  form the **schema graph**, which is what lets a join be resolved from the names
  alone.
- **Catalog relation** — a read-only `relix.*` relation describing the script or
  the engine itself, queried like any other: the
  [introspection stdlib](language/introspection-stdlib.md) and the
  [observability feed](language/introspection-events.md).

## Operators and expressions

- **Operator** — a function from relations to a relation. Each has a symbol and
  an equivalent keyword (`σ` = `SELECT`); the two parse identically.
- **Predicate** — a condition evaluated per row, built from
  [comparisons](predicates/comparison.md),
  [`IN`](predicates/in.md), [`LIKE`](predicates/like.md) and the connectives
  `∧ ∨ ¬`. It is what goes inside a `σ` or a join.
- **Selection** ([`σ`](operators/select.md)) keeps rows; **projection**
  ([`π`](operators/project.md)) keeps, reorders, renames and computes columns;
  **rename** ([`ρ`](operators/rename.md)) renames a relation or its columns.
- **Aggregate** — a function reducing a group of rows to one value
  ([`SUM`](aggregates/sum.md), [`COUNT`](aggregates/count.md)). **Grouping**
  ([`γ`](operators/group.md)) splits a relation by key and applies aggregates to
  each group.
- **Join** — combining two relations by matching rows.
  [Natural](joins/natural-join.md) matches on shared column names,
  [theta](joins/theta-join.md) on an explicit condition,
  [outer](joins/left-outer-join.md) keeps unmatched rows with NULLs, and
  [semi](joins/semi-join.md)/[anti](joins/anti-join.md) filter one side by
  whether a match exists rather than combining columns.
- **Set operations** — [`∪`](set-operations/union.md),
  [`∩`](set-operations/intersection.md), [`−`](set-operations/difference.md) over
  rows of the same shape; [`×`](set-operations/cross.md) pairs every row with
  every row; [`÷`](set-operations/division.md) answers "for *all* of these";
  [`∘`](set-operations/composition.md) joins and then drops the matched columns.
- **Quantification** ([`∀`](operators/forall.md)) — keeps the groups in which
  *every* row satisfies a condition, as opposed to `σ`, which asks about one row
  at a time.
- **Recursion** — [`CLOSURE`](advanced/closure.md) walks a graph edge relation to
  reachability; [`FIX`](advanced/fix.md) is the general least-fixpoint form.
- **Window** — an aggregate or ranking computed over the rows *around* each row
  rather than collapsing them: [`ROLLING`](operators/rolling.md),
  [`WINDOW`](operators/window-ranking.md).
- **Provenance**, or **lineage** — which input rows produced an output row.
  [`ω`](operators/why.md) reifies it as a queryable column.

## How a query runs

- **Analysis** — the pass that resolves names, infers every expression's heading
  and reports errors, before any data is read. Its output is the
  **semantic model** the later stages read.
- **Logical plan** — the tree of operators the script describes. **Physical
  plan** — what the engine will actually execute: join algorithms chosen, scans
  bound to connectors, work ordered.
- **Optimizer rule** — a rewrite that replaces part of the logical plan with a
  cheaper equivalent, each with a code (`SEL-001`, `PROJ-004`) you can see fire.
  See [the optimizer](advanced/optimizer.md).
- **Pushdown** — sending work to the system holding the data, so a filter becomes
  a SQL `WHERE` clause or a Mongo `$match` and the rows never travel.
- **Streaming** and **blocking** — a streaming operator emits a row as soon as it
  has one; a blocking operator (sort, grouping, the deduplicating set operations)
  must see all its input first. **Materialisation** is that buffering.
- **Cardinality estimate** — the planner's guess at how many rows an operator
  will produce, used to choose between plans. It is a guess, and `:explain` shows
  it as `~N rows`.
- **Shared sub-plan** — one sub-expression read by several parts of a plan and
  evaluated once, rather than recomputed per reader.
- **Boundedness** — whether a relation is known to be finite. A
  [generator](advanced/cover.md) can be unbounded, and an operator that must see
  every row cannot run over one.
- **Connector** — the component that reads an external system (JDBC, CSV, JSON,
  HTTP, MongoDB) and, where it can, executes pushed-down work on the engine's
  behalf.

# Examples:
Every part of one small query, named:

```relix
Orders := [
| order_id | customer_id | amount |
|----------|-------------|--------|
| 100      | 1           | 120    |
| 101      | 1           | 80     |
| 102      | 2           | 45     |
];

BigSpenders := { τ total DESC (γ customer_id, SUM(amount) → total (σ amount ≥ 50 (Orders))) };

query { BigSpenders };
```

Reading it from the inside out: `Orders` is a **base relation**, here an **inline
table**. `σ amount ≥ 50` is a **selection** whose **predicate** is a comparison.
`γ customer_id, SUM(amount) → total` is a **grouping**: `customer_id` is the
grouping key, `SUM` the **aggregate**, `total` the output **attribute**.
`τ total DESC` **sorts**, and is a **blocking** operator — it cannot emit its
first row until it has seen the last. `BigSpenders :=` names the whole expression
as a **view**, storing nothing, and `query { … }` asks for it back.

Had `Orders` been a database source rather than an inline table, the optimizer
would have pushed the selection, the grouping and the sort into one SQL statement
— **pushdown** — and the engine would have read only the three result rows.

# See Also:
[getting started](getting-started.md), [optimizer](advanced/optimizer.md),
repl, [assignment & query](language/assignment.md),
[relate](language/relate.md)

# Notes:
Relix follows the standard relational-algebra vocabulary, so a textbook's
"relation, tuple, attribute" mean here exactly what they mean there. Where SQL
uses a different word for the same idea — table, row, column — the two are used
interchangeably in this manual.
