# Name: source … from json (JSON file source)

# Syntax:
source <Name> from json("<path>");
source <Name> from json("<path>") { records: "<path to the array>" };

source People from json("people.json");
source Items  from json("people.json") { records: "data.items" };

# Description:
A JSON source reads a JSON file as a relation: each object becomes a row. You do not
declare columns. The file is the schema — a row has whatever fields its object has —
so a source over a file whose objects do not all carry the same fields is fine, and
a field an object lacks is NULL in that row.

By default the file is expected to hold an array of objects, or a single object (one
row). When the objects sit inside a larger document, `records` says where.

# Technical Description:
A JSON source is **open**: it declares no `schema`, and its heading is not known
until a query reads it. A query may name any field; one that no object carries is NULL
in every row rather than an error, so a misspelt column does not fail the analysis.
Declare a schema instead (a `connection` over a database, or a CSV file) when you want
the names and types checked before the query runs.

`path` is resolved like a CSV source's: relative to the script file that declares it.
It must be a local file; for a document fetched over HTTP use an
[http source](http-source.md).

`records` is a dotted path with optional array positions — `"data.items"`,
`"batches[1].rows"` — locating the array of objects within the document. Without it:

- a top-level array gives one row per element;
- a top-level object gives one row.

A nested object or array in a record stays nested, as a struct or an array. Read
a field of a nested object with a dotted name (`π a.b → b (T)`), or give each element
of an array its own row with [unnest](../operators/unnest.md). The values keep their
JSON types: numbers are NUMBER, `true`/`false` BOOLEAN, text STRING.

A file that cannot be read, or is not valid JSON, fails the query, naming the file.

Like every source kind, a JSON source accepts a `references:` block declaring
relationships from its fields to other relations (see [source](source.md)).

# Examples:
`people.json` holds documents that do not all carry the same fields:

```
{"meta": {"count": 3}, "data": {"items": [
  {"id": 1, "fullname": "Ada Lovelace", "city": "London"},
  {"id": 2, "fullname": "Alan Turing"},
  {"id": 3, "fullname": "Grace Hopper", "city": "New York"}]}}
```

`records` locates the array of people inside the document:

```relix
source People from json("people.json") { records: "data.items" };

query { π id, fullname, city (People) };
```

Alan Turing's object has no `city`, so that cell is NULL:

```
 id  fullname      city
 ──  ────────────  ────────
  1  Ada Lovelace  London
  2  Alan Turing   NULL
  3  Grace Hopper  New York
(3 rows)
```

A file that is simply an array of objects needs no `records`:

```relix
source Events from json("events.json");
```

# Limitations:
Because the heading is open, a column name the file does not carry is NULL instead of
an error, and the types of a field are those the file gives it. A source that must be
analysed without its file, or before it exists, cannot be a JSON source.

# Alternatives:
A CSV file, whose columns and types are checked (see [source](source.md)); an
[http source](http-source.md) when the document is behind an API.

# See Also:
[source](source.md), [http source](http-source.md), [rename](../operators/rename.md), [unnest](../operators/unnest.md)

# Notes:
The whole file is read to find its records, so a very large JSON document is held in
memory while it is read. For a large data set prefer CSV.
