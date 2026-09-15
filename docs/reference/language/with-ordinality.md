# Name: WITH ORDINALITY (unnest index clause)

# Syntax:
μ <array-column> WITH ORDINALITY <name> (Relation)
UNNEST <array-column> WITH ORDINALITY <name> (Relation)

μ items WITH ORDINALITY pos (Orders)

# Description:
WITH ORDINALITY is an optional clause on UNNEST that adds a column recording each
element's position as the array is exploded — 1 for the first element, 2 for the
second, and so on, restarting for each input row. Use it when the order of items
matters: line-item numbers, ranked tags, sequence positions.

# Technical Description:
The clause appends a NUMBER column (named by the required `name`) carrying the
1-based index of each unnested element within its source array; numbering restarts
per input row. With the OUTER unnest variant, a no-element row gets a NULL element
and NULL ordinality. The ordinality-preserving form does not push down to Mongo
$unwind (runs in-engine).

# Examples:
Number each exploded line item:
  μ items WITH ORDINALITY line_no (Orders)

Position of each tag on an article:
  μ tags WITH ORDINALITY tag_position (Articles)

Keep the sequence when flattening steps:
  μ steps WITH ORDINALITY step_number (Recipes)


# Worked Example:
Line items gathered into an array and exploded again, numbered as they go.

```relix
OrderItems := [
| order_id | item    |
|----------|---------|
| 100      | widget  |
| 100      | bolt    |
| 100      | bracket |
| 101      | nut     |
];

Bagged := { γ order_id, COLLECT(item) → items (OrderItems) };

query { μ items WITH ORDINALITY line_no (Bagged) };
```

```
 order_id  items    line_no
 ────────  ───────  ───────
      100  widget         1
      100  bolt           2
      100  bracket        3
      101  nut            1
(4 rows)
```

`line_no` restarts at 1 for each input row, so order 101's only item is line 1
rather than line 4 — the numbering is per array, not per result. Without the
clause the same query returns the items with no way to recover the order they
were stored in.

# Limitations:
The ordinality name must not clash with an existing column. Over a schema-on-read source (JSON, HTTP, MongoDB)
there is no declared heading to clash with, so the operator always runs; if a
document turns out to carry a field of that name, the added column replaces it.
The clause is only valid on UNNEST (μ). The position reflects array order, which is only meaningful if
the array itself is ordered.

# Alternatives:
Plain UNNEST (μ) without the clause when element position does not matter.

# See Also:
[unnest](../operators/unnest.md), [collect](../aggregates/collect.md), [array-construction](../literals/array-construction.md)

# Notes:
This mirrors SQL's `UNNEST(...) WITH ORDINALITY` and is the way to recover element
order after flattening nested data.
