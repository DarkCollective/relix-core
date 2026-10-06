# Name: source (data source declaration)

# Syntax:
source <Name> from database { url: "...", table: "...", schema: { ... } };
source <Name> from csv("<path>") { header: true, schema: { ... } };
source <Name> from csv("<path>") { };                                   -- heading read from the file
source <Name> from csv("<path>") { header: false, columns: [ ... ] };  -- names given, types read

source Orders from database {
    url: "${DB_URL}", table: "orders",
    schema: { order_id: NUMBER, customer_id: NUMBER, amount: NUMBER }
};

# Description:
A source declaration is how external data enters a script. It names a relation
and tells relix where the rows come from — a database table or a CSV file — and
what columns to expect. Once declared, you use the name anywhere a relation is
expected, just like any other table.

# Technical Description:
`source` binds a relation symbol (SourceRelationSymbol) to an external connector.
`from database` requires `url`, `table`, and a `schema`; `from csv("path")` takes
an optional `header:` flag (default true) and an optional `schema`. Column types are NUMBER,
STRING, BOOLEAN, ANY, and the temporal types DATE/TIME/TIMESTAMP/DURATION. A column
may also be **nested**: `{ field: TYPE, … }` declares a
struct and `[TYPE]` an array, composing to any depth. A column or field whose name
is not a plain identifier, such as `unit-price`, is written between backticks, as it
is in a query; a doubled backtick stands for one. `${VAR}` placeholders in URLs
and other string values are resolved from the active environment when a query runs. Other source kinds (json, http, generator) exist for
their respective connectors. A relative path resolves against the directory of the
script file that declares it, so a script read from another directory still finds the
files beside it; a declaration that comes from no file resolves against the directory
the program using Relix chooses.

**A CSV source may leave its schema out**, and its heading is then read from the
file when the script is analysed: each column is named by the header row and typed
as the narrowest type every value in the first 1000 records reads as — NUMBER, then
BOOLEAN (`true`/`false`), then DATE, then TIMESTAMP, else STRING; a column with no
value in those records is a STRING. A file with no header row names its columns with
`columns: [ … ]`, in file order, and their types are read the same way; `columns`
also replaces a header whose names are not wanted, which is then skipped, and the
columns are read by position. A source declares a `schema` or its `columns`, not
both. A headerless file with neither, an empty file, and a file that cannot be read
are each reported when the script is analysed, naming the source and the file,
rather than as a column some query could not find. A value after the first 1000
records that does not read as its column's type fails the query, naming its line;
declare the schema to read such a file. Reading the file at analysis is the one
difference a schema makes to *when* a file is opened: a script that must be analysed
without its files — offline, or before they exist — declares its schema.

In a CSV file an **empty field is a NULL** — `2,Grace,` gives a NULL third column.
A row that simply *ends early* is a different thing and is refused, naming the line
and the column it ran out at, rather than read as a row of NULLs. A row carrying
**more** fields than the schema names is read: a schema may deliberately name fewer
columns than the file carries.

Every source kind (database, connection-table, csv, json) additionally accepts
a `references:` block declaring foreign-key relationships from this source's
columns to other relations: each `col -> Target.col` entry
(composite: `(a, b) -> Target(a, b)`) becomes a named edge of the schema graph,
with an implied upper bound of 1 on the target side. Inline tables take the
same block as a trailing `references { … }` clause. See [relate](relate.md)
for the full relationship model (names, inverse names, bounds, symmetric
self-edges).

# Examples:
A database table:
```relix
source Orders from database {
    url: "${DB_URL}", table: "orders",
    schema: { order_id: NUMBER, customer_id: NUMBER, amount: NUMBER }
};
```

A CSV file read with no schema: its columns are named by its header row and typed by
its values — here `id` and `score` are NUMBERs, `passed` a BOOLEAN and `answered` a
DATE, so a query over them is type-checked like any other:
```relix
source Survey from csv("survey.csv") { };
query { π id, region (σ score > 8 ∧ passed = true (Survey)) };
```

A CSV file with no header row, its columns named and their types read from the file:
```relix
source Visits from csv("visits.csv") { header: false, columns: [day, page, hits] };
query { γ page, SUM(hits) → total (σ day ≥ DATE '2026-03-01' (Visits)) };
```

A source names its columns or declares its schema, not both: `columns` is for a heading
whose types are read from the file, and a `schema` already says them.

A nested column — a struct, and an array of structs:
```relix
source Docs from mg {
    table: "orders",
    schema: {
        oid: NUMBER,
        address: { city: STRING, geo: { lat: NUMBER, lon: NUMBER } },
        items: [{ sku: STRING, qty: NUMBER }]
    }
};
```

The declaration is what types a path through the column: `address.geo.lat` infers as
NUMBER and `μ items` yields the struct, where an `ANY` column would give `ANY` for
both and catch no misspelled field. Declare `ANY` when the shape genuinely varies —
schema-on-read is still what a document store often wants.

A table whose column names are not plain identifiers, named between backticks here
and in the query that reads them:
```relix
source Lines from database {
    url: "${DB}", table: "order-lines",
    schema: { lid: NUMBER, `unit-price`: NUMBER, `line total`: NUMBER }
};
query { σ `unit-price` > 10 (Lines) };
```

Backticks name a column, not a relation. A source's own name must be a plain name:
```relix-invalid
source `order-lines` from database { url: "${DB}", table: "order-lines" };
```

A CSV file with a header row:
```relix
source Responses from csv("./data/responses.csv") {
    header: true,
    schema: { id: NUMBER, region: STRING, score: NUMBER }
};
```

A table with temporal columns:
```relix
source Events from database {
    url: "${DB}", table: "events",
    schema: { id: NUMBER, occurred_at: TIMESTAMP, day: DATE }
};
```

A table declaring its foreign key, so the engine knows how it joins:

```relix
source Customers from database { url: "${DB}", table: "customers",
    schema: { customer_id: NUMBER, name: STRING } };
source Orders from database {
    url: "${DB}", table: "orders",
    schema: { order_id: NUMBER, customer_id: NUMBER, amount: NUMBER },
    references: { customer_id -> Customers.customer_id }
};
```

# Limitations:
Nesting is available on a *column* only — a `def`'s parameters and return type are
scalar. A CSV source reads a
nested-typed column as raw text, since its cells are flat. A heading read from a CSV file reflects its first 1000 records; a JSON
file's source with no schema is open (schema-on-read) rather than read. The schema you declare is
what the engine trusts; mismatched real data is coerced or errors per connector. Secrets in
URLs should come from the environment via ${VAR}, not be hard-coded.

# Alternatives:
A `connection` declaration plus dotted refs (conn.table) shares one set of
credentials across many tables. Inline tables embed small reference data directly.

# See Also:
[http source](http-source.md), [connection](connection.md), [relate](relate.md), [inline-table](inline-table.md), [namespace](namespace.md), [import](import.md)

# Notes:
Use ${VAR} placeholders (see `relix help env`) to keep connection URLs and
credentials out of the script text. The script keeps the placeholder, so nothing that
prints it shows the value.
