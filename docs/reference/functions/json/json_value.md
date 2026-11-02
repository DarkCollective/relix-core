# Name: JSON_VALUE (a scalar read out of JSON text)

# Syntax:
JSON_VALUE(<json text>, <path>)

π id, JSON_VALUE(doc, '$.customer.name') → name (OrderDocs)

# Description:
JSON_VALUE reads one value out of a string that holds a JSON document: the customer's
name in `{"customer": {"name": "Ada"}}`, or the first item's SKU. It is how a column of
JSON text, common in a database table or a CSV file, is read into.

The path names the value. `$` is the whole document, `.name` a member of an object,
and `[n]` the element of an array at position `n`, counted from 0:
`'$.items[0].sku'`. A key holding a space or any other character is quoted:
`'$."order id"'`.

# Technical Description:
JSON_VALUE(doc: STRING, path: STRING) → STRING. The value at `path` in `doc`, as text:
a string as itself, a number as written (`1.50` stays `'1.50'`), a boolean as `'true'`
or `'false'`. Convert it with [CAST](../../language/cast.md) for arithmetic:
`CAST(JSON_VALUE(doc, '$.qty') AS NUMBER)`. PURE, DETERMINISTIC.

The answer is NULL, as SQL's lax mode gives it, when `doc` is not JSON, when the path
reaches nothing, and when it reaches an object, an array or JSON's `null`; read an
object or array with [JSON_QUERY](json_query.md). A NULL argument gives NULL. A path
that is not a path, `'customer.name'` without its `$`, is refused when the call runs.

A key is matched exactly, case included. The path is followed as written: `.sku` on an
array reaches nothing, where PostgreSQL's lax mode would look inside each element.

# Examples:
The customer's name from each order document:
  π id, JSON_VALUE(doc, '$.customer.name') → name (OrderDocs)

Orders whose first item is a given SKU:
  σ JSON_VALUE(doc, '$.items[0].sku') = 'A1' (OrderDocs)

A quantity as a number:
  π id, CAST(JSON_VALUE(doc, '$.items[0].qty') AS NUMBER) → qty (OrderDocs)

# Worked Example:
Order documents held as text, one of them not JSON at all:

```relix
Orders := [
| id | doc                                                      |
|----|----------------------------------------------------------|
| 1  | {"customer": {"name": "Ada"}, "items": [{"sku": "A1"}]}  |
| 2  | {"customer": {"name": "Bo"}, "items": []}                |
| 3  | not json                                                 |
];

query { π id, JSON_VALUE(doc, '$.customer.name') → name,
              JSON_VALUE(doc, '$.items[0].sku') → first_sku (Orders) };
```

```
 id  name  first_sku
 ──  ────  ─────────
  1  Ada   A1
  2  Bo    NULL
  3  NULL  NULL
(3 rows)
```

Order 2 has no first item and order 3 no document, so each reads as NULL rather than
failing the query.

# Pushdown:
SQL: on PostgreSQL 16 or later, `jsonb_path_query_array` over the path read in
`strict` mode with its errors silenced, behind `pg_input_is_valid`, so text that is not
JSON is NULL there too. Elsewhere it runs in the engine: MySQL writes a decimal back as
a double, `1.5` for `1.50`.

# Limitations:
A path reaches one value: there is no wildcard (`[*]`), filter or `last`. The result
is text; a `RETURNING` clause is not read, and CAST converts it instead. SQL's `->>`
is not an operator here, `->` naming a column:

```relix-invalid
query { π doc ->> '$.customer.name' (OrderDocs) };
```

# SQL names:
`JSON_VALUE(doc, path)` in PostgreSQL 17, MySQL, SQL Server and Oracle. MySQL's and
SQLite's `doc ->> path` is the same reading; in Relix `->` names a column, so the call
is written out.

# See Also:
[json_query](json_query.md), [json_extract](json_extract.md), [cast](../../language/cast.md)
