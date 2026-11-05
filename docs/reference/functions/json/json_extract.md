# Name: JSON_EXTRACT (any value read out of JSON text)

# Syntax:
JSON_EXTRACT(<json text>, <path>)

π id, JSON_EXTRACT(doc, '$.total') → total (OrderDocs)

# Description:
JSON_EXTRACT reads whatever value a path reaches in a string that holds a JSON
document, and gives it as the engine's value of that kind: a JSON number as a number,
a string as a string, a boolean, an object as a struct and an array as an array. It is
MySQL's and SQLite's spelling, for a query that does not know or care which kind of
value it will find.

The path is [JSON_VALUE](json_value.md)'s: `$` the document, `.name` a member, `[n]` an
element.

# Technical Description:
JSON_EXTRACT(doc: STRING, path: STRING) → the value at `path`, typed as JSON types it,
so its column is of no one type. PURE, DETERMINISTIC. The answer is NULL when `doc` is
not JSON, when the path reaches nothing, and when it reaches JSON's `null`. A NULL
argument gives NULL; a malformed path is refused, and so is a document nested more than 512 levels deep.

Where the kind is known, the other two say so: JSON_VALUE gives text, and JSON_QUERY
an object or array.

# Examples:
A total held as a JSON number, compared as a number:
  σ JSON_EXTRACT(doc, '$.total') > 100 (OrderDocs)

# Worked Example:
A number in the document is a number in the result, so it compares as one:

```relix
Orders := [
| id | doc                         |
|----|-----------------------------|
| 1  | {"total": 250, "paid": true} |
| 2  | {"total": 40, "paid": false} |
];

query { σ JSON_EXTRACT(doc, '$.total') > 100 (Orders) };
```

```
 id  doc
 ──  ────────────────────────────
  1  {"total": 250, "paid": true}
(1 row)
```

# Pushdown:
None: MySQL's and SQLite's own `json_extract` answer JSON text, or a value typed by
the database, so it runs in the engine over the rows the database returns.

# Limitations:
A path reaches one value: there is no wildcard.

# SQL names:
`json_extract(doc, path)` in MySQL and SQLite; `doc -> path` in both. In Relix `->`
names a column, so the call is written out.

# See Also:
[json_value](json_value.md), [json_query](json_query.md)
