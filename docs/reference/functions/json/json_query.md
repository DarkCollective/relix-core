# Name: JSON_QUERY (an object or array read out of JSON text)

# Syntax:
JSON_QUERY(<json text>, <path>)

π id, JSON_QUERY(doc, '$.items') → items (OrderDocs)

# Description:
JSON_QUERY reads an object or an array out of a string that holds a JSON document,
and gives it as the engine's own struct or array, so the rest of a query can use it:
an array can be unnested into one row per element, and a struct's fields read by name.
Where [JSON_VALUE](json_value.md) reads a single value, JSON_QUERY reads a whole part
of the document.

The path is JSON_VALUE's: `$` the document, `.name` a member, `[n]` an element.

# Technical Description:
JSON_QUERY(doc: STRING, path: STRING) → a struct for a JSON object, an array for a
JSON array. PURE, DETERMINISTIC. The answer is NULL when `doc` is not JSON, when the
path reaches nothing, and when it reaches a string, number, boolean or `null`, which
JSON_VALUE reads. A NULL argument gives NULL; a document nested more than 512 levels deep is refused; a malformed path is refused.

# Examples:
Each order's items, as an array:
  π id, JSON_QUERY(doc, '$.items') → items (OrderDocs)

One row per item:
  μ items (π id, JSON_QUERY(doc, '$.items') → items (OrderDocs))

# Worked Example:
Order documents whose items are an array of objects:

```relix
Orders := [
| id | doc                                             |
|----|-------------------------------------------------|
| 1  | {"items": [{"sku": "A1"}, {"sku": "B2"}]}        |
| 2  | {"items": "none"}                               |
];

query { π id, JSON_QUERY(doc, '$.items') → items (Orders) };
```

```
 id  items
 ──  ──────────────────────
  1  [{sku: A1}, {sku: B2}]
  2  NULL
(2 rows)
```

Order 2's `items` is a string, which is a value rather than an array, so it reads as
NULL.

# Pushdown:
None: a database answers JSON text where JSON_QUERY answers a struct or an array, so
it runs in the engine over the rows the database returns.

# Limitations:
A path reaches one value: there is no wildcard, and no `WITH WRAPPER` to gather
several into an array.

# SQL names:
`JSON_QUERY(doc, path)` in PostgreSQL 17, SQL Server and Oracle. MySQL's and SQLite's
`json_extract` reads any value: here that is [JSON_EXTRACT](json_extract.md).

# See Also:
[json_value](json_value.md), [json_extract](json_extract.md), [unnest](../../operators/unnest.md)
