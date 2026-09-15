# Name: Candidate keys (`relix.keys`)

# Syntax:
relix.keys   -- the candidate keys of every relation (relation, key, ordinal, column)

# Description:
`relix.keys` is the collected candidate keys as data: one row per *column* of every
candidate key of every relation.

It is the one statistic with no flat column to sit in. The scalar figures live
where they belong — `row_count` on `relix.relations`, `distinct_count` and
`null_count` on `relix.columns` — because a key is neither scalar nor per-column:
it is a *list* of columns, and one relation may have several.

Columns:

- `relation` (`STRING`) — the relation the key belongs to.
- `key` (`NUMBER`) — which candidate key, numbered from 0 within each relation.
- `ordinal` (`NUMBER`) — the column's position *within* that key, from 0.
- `column` (`STRING`) — the column name.

A relation with one single-column key contributes one row, with `key` and `ordinal`
both 0. A two-column key contributes two rows sharing a `key` and differing in
`ordinal`.

## Column order is data, not row order

`ordinal` is meaningful. The columns of a composite key are ordered, and the order
is the one an index on that key would be built in — which decides whether a lookup
on the first column alone can use it. Reading the columns of a key back in the
order the rows happen to arrive would lose that, so the position is a column.

## Where the keys come from

Two sources, and they make different claims:

- **An inline relation** contributes keys computed exactly from its rows: every
  column with no NULLs whose values are all distinct. This is a fact about *the
  data present*, not a constraint — in a two-row table, a column is a candidate key
  as soon as its two values differ.
- **A connection-backed table** contributes the key the database declares, read
  from the catalog when its schema is introspected. That one *is* a constraint.

The relation does not distinguish the two, because nothing in the collected
statistics records which it was. Read a key over inline data as "nothing in these
rows contradicts it".

## Absent is not empty

A relation whose statistics were never collected contributes no rows — a view, a
CSV or HTTP source that was not scanned. That is not the claim that it has no key;
it is the absence of a claim. `relix.relations` tells the two apart: a relation with
a `row_count` was measured.

# Technical Description:
The rows are read from the same `RelationStatistics` the cost model uses, so a key
listed here is a key the optimizer is entitled to act on — whole-row distinctness
and candidate-key derivation both consume it, which is what lets a redundant `δ` be
removed over an input already known to be duplicate-free.

The extent is fixed at analysis time, like every structural catalog: it describes
the script being analysed, and no query over it runs anything against a source.

Like the other catalogs, it describes only relations *outside* the reserved `relix`
namespace. Use `relix.catalog` to see the namespace itself.

# Examples:
Every candidate key in the script:

```relix
query { relix.keys };
```

The keys of one relation, in key and column order:

```relix
query { τ key, ordinal (σ relation = "Orders" (relix.keys)) };
```

Which relations have a *composite* key — more than one column in the same key:

```relix
query { σ columns > 1 (γ relation, key, COUNT(column) → columns (relix.keys)) };
```

Whether a particular column takes part in any key:

```relix
query { δ (π relation (σ column = "customer_id" (relix.keys))) };
```

A relation that was measured but has no key at all — a join of `relix.relations`
against the keys, keeping the rows with no match:

```relix
query {
  π name (
    σ row_count ≥ 0 (relix.relations) ▷ relix.relations.name = relix.keys.relation relix.keys
  )
};
```

# Limitations:
A key over inline data is observed rather than declared; see *Where the keys come
from* above.

Only relations whose statistics were collected appear. A view never does: its
distinctness is derived from its inputs by the optimizer rather than stored.

Boundedness is not here. Whether a relation is finite is not a statistic — it is
proved rather than collected — and it is a scalar, so it sits on
[`relix.relations`](introspection-relations.md) as its own column.

# See also:
- [relation catalog](introspection-relations.md) — `relix.relations`, which carries
  the scalar figures a key is not: `row_count` and `boundedness`.
- [introspection stdlib](introspection-stdlib.md) — `relix.schema(rel)`, the
  columns of one relation, which pairs naturally with its keys.
- [reserved namespace](introspection-catalog.md) — `relix.catalog`.
- `relix help catalog` — every `relix.*` relation and its columns.
