# Name: Entries (read an object's fields as rows)

# Syntax:
Entries(<object>)

π order_id, Entries(attributes) → a (Orders)

# Description:
Entries turns an object whose keys are data into something you can query. It
returns one entry per field, each carrying the field's name as key and the
field's value as value, so unnesting the result gives you one row per field.

Use it when a document holds counts, settings or attributes keyed by a name the
schema does not know in advance — `{"gift_wrap": "yes", "channel": "web"}` rather
than a fixed set of columns. Unnest explodes arrays, so an array-valued field
reaches the algebra on its own; this is what lets a keyed object reach it too.

# Technical Description:
Entries(object) → ANY, an array of `{key: STRING, value: ANY}` structs, one per
field of the argument, in the object's own field order. Anything that is not an
object — NULL, a scalar, an array — returns NULL, so an inner `μ` drops the row.
An object with no fields returns an empty array, which an inner `μ` also drops.
PURE, DETERMINISTIC. Never pushed down.

# Examples:
One row per keyed field, reading the key and the value as columns:

```relix
query {
  π order_id, a.key → attribute, a.value → value (
    μ a ( π order_id, Entries(attributes) → a (Orders) )
  )
};
```

Given an order with `attributes` of `{"gift_wrap": "yes", "channel": "web"}`, that
returns two rows for it — one naming `gift_wrap`, one naming `channel`.

Which attribute names the data uses at all, which no heading can answer:

```relix
query {
  δ ( π a.key → attribute (
        μ a ( π Entries(attributes) → a (Orders) ) ) )
};
```

How often each is used, once the keys are rows like any other:

```relix
query {
  γ attribute, COUNT(*) → orders (
    π a.key → attribute ( μ a ( π Entries(attributes) → a (Orders) ) )
  )
};
```

# Limitations:
Reads objects only. An array-valued field is unnested directly with `μ` and
returns NULL here, so that there is one way to explode an array rather than two.

Entry values are typed ANY, because an object whose keys are data has no declared
shape for its values either. A path into an entry value resolves at runtime, so a
field name that is not there reads as NULL rather than failing analysis.

Runs in the engine. MongoDB's `$objectToArray` is the same idea with different
entry field names, so a pushed query would not return the same values.

# Alternatives:
`μ` / UNNEST explodes an array-valued field, which is the same move for the shape
whose positions rather than names are the data. For a struct field whose name you
know, read it with projection (`π s.field`) and no unnest at all. COLLECT gathers
a group's values back into an array.

# See Also:
[unnest](../../operators/unnest.md), [collect](../../aggregates/collect.md), [isnull](../typecheck/isnull.md)
