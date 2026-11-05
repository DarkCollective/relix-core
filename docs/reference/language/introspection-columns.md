# Name: Column catalog (`relix.columns`)

# Syntax:
relix.columns   -- every column of every relation (relation, column, type, ordinal, distinct_count, null_count)

# Description:
`relix.columns` is the headings of the script's relations as data: one row for every
column of every relation the script can name. Where [`relix.relations`](introspection-relations.md)
answers "what relations are there?", `relix.columns` answers "what is in them?" — which
relations carry a column called `customer_id`, what type a column has, how many distinct
values it holds.

Columns:

- `relation` (`STRING`) — the relation the column belongs to, by its declared name.
- `column` (`STRING`) — the column's name.
- `type` (`STRING`) — the column's type, written as a short code: `N` for a number,
  `S` for a string, `B` boolean, `D` date, `T` time, `TS` timestamp, `TSZ` timestamp with a
  time zone, `DUR` duration, `?` for a value of any type. A nested column writes its
  shape out, as `{name:S,city:S}` for a struct.
- `ordinal` (`NUMBER`) — the column's position in the relation's heading, from 0.
- `distinct_count` (`NUMBER`) — how many distinct values the column holds, or NULL when
  that was not collected.
- `null_count` (`NUMBER`) — how many NULLs the column holds, or NULL when that was not
  collected.

# Technical Description:
The relations covered are the ones `relix.relations` lists: inline tables, sources,
connection tables and views. The `relix.*` relations themselves are not.

A relation with an open heading — a [JSON source](json-source.md) — contributes a
single row with `column` `*` and `type` `?`, because it has no columns to list until a
query reads it.

`distinct_count` and `null_count` are exact for an inline table and are the database's
own figures for a table introspected from a connection. They are NULL for a view's
columns, for a CSV or HTTP source that was not scanned, and for an open heading. NULL
here means the figure was not collected, not that the count is zero.

The [introspection stdlib](introspection-stdlib.md)'s `relix.find` and `relix.schema`
are views over this relation.

# Examples:
Every column in the script:

```relix
query { relix.columns };
```

Which relations have a `customer_id`, and as what type:

```relix
query { π relation, type (σ column = "customer_id" (relix.columns)) };
```

Columns known to hold no NULLs, which makes them candidates for a key:

```relix
query { π relation, column (σ null_count = 0 (relix.columns)) };
```

The columns with the most distinct values:

```relix
query { λ 5 (τ distinct_count DESC (σ distinct_count IS NOT NULL (relix.columns))) };
```

# Limitations:
Only user relations appear. The two count columns are present only where statistics
were collected (see above), so a query filtering on them leaves out every relation
without any.

# See Also:
[relation catalog](introspection-relations.md), [candidate keys](introspection-keys.md), [introspection stdlib](introspection-stdlib.md), [reserved namespace](introspection-catalog.md)

# Notes:
`relix.schema(rel)` is the same rows for one relation, without the count columns.
