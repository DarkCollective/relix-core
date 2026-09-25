# Name: connection (shared database connection)

# Syntax:
connection <Name> from <type> { url: "...", ... };

connection analytics from database { url: "${ANALYTICS_DB}" };

-- then reference tables by dotted name:
query { σ amount > 100 (analytics.orders) };

# Description:
A connection declares one named link to a database that many tables can share,
instead of repeating the URL and credentials in every source. After declaring it,
you refer to its tables by a dotted name (`analytics.orders`), and relix
introspects each table's columns for you.

# Technical Description:
`connection X from <type> { … }` declares a database connection. The connector
type after `from` is the registry dispatch key — `database` is a permanent alias
for `jdbc`; other tokens (e.g. `mongodb`) dispatch to the matching connector. JDBC
connections accept `url`, `user`, `password`, `dialect` and `collation`, and require
`url`. Tables are referenced as
binding-form sources (`source T from conn { table, schema }`) or dotted refs
(`conn.table`), the latter resolved by introspecting the live catalog. Declared
connections also appear, redacted, in the `relix.connections` system catalog
(name + dialect only — never the URL/credentials).

**A file connection** — `csv`, `gedcom`, `log` — names one file or directory, by
`path` or by `url`, never both. A relative `path` resolves against the script's
directory, the rule a `csv("…")` source follows. A `url` is either `file:` (a path
written as a URL) or `https:`, which is fetched. A fetched file is kept under
`~/.relix/cache/files` and is fetched at most once per session, so every scan in a
session reads the same bytes even if the file changes on the server. A later session
asks the server whether the file changed (`ETag`/`Last-Modified`) and reuses the cached
copy when it did not, or when the server cannot be reached. `sha256: "<hex>"` pins the
file's content, and a file whose digest differs is refused. A compressed (gzip) file is
read as its content. A program embedding relix can forbid fetching; a connection naming
an `https` file is then an error.

# Examples:
Declare once, query many tables:
```relix
connection shop from database { url: "${SHOP_DB}" };
query { shop.customers ⋈ shop.orders };
```

Mongo connection (registry dispatch by type token):
  connection events from mongodb { url: "${MONGO_URL}" };

A file served over https, pinned to the content the query was written against:
```relix
connection sample from log {
    url:    "https://example.org/logs/access.log",
    sha256: "7d8f3a1c0b9e2f4d5a6c7b8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f80",
    format: "combined"
};
```

Bind a specific table with an explicit schema:
```relix
source Orders from shop { table: "orders",
    schema: { id: NUMBER, amount: NUMBER } };
```

A MySQL connection whose string columns are declared with a binary collation, so
string comparison may be pushed down as written:
```relix
connection shop from database { url: "${SHOP_DB}", collation: exact };
```

# Limitations:
`collation` is a claim about the database that relix cannot check, because a
collation belongs to each column and nothing the planner reads reports it. Getting
it wrong costs correctness rather than speed: declaring `exact` for columns that
are not makes a pushed string comparison answer under the database's collation
rather than the engine's. Leaving it out is always safe.

Dotted-ref tables rely on the connector being able to introspect the catalog. The
system catalog deliberately redacts URL/user/password. Non-JDBC connector types
validate their own key sets.

# Alternatives:
A standalone `source … from database` declaration when you only need one table and
prefer to spell out its schema inline.

# See Also:
[source](source.md), [namespace](namespace.md), [import](import.md)

# Notes:
`collation` says whether this database compares strings the way relix does —
`exact` (compare character for character) or `database` (whatever the column's
collation says). The default is `exact` for every dialect but MySQL, whose default
collation is case- and accent-insensitive. It only affects which comparisons may be
pushed down and how they are written; the answer relix gives is the same either way.
See [pushdown](../advanced/pushdown.md).

Same-connection joins and filters can be pushed down into the database as one SQL
query; cross-connection work is combined in the engine (federation). A `mongodb`
connection pushes σ, μ, and cheap π/λ into an aggregation pipeline; τ and γ are
evaluated in the engine over the rows the pipeline returns.
