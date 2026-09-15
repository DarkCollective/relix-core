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

# Examples:
Declare once, query many tables:
```relix
connection shop from database { url: "${SHOP_DB}" };
query { shop.customers ⋈ shop.orders };
```

Mongo connection (registry dispatch by type token):
  connection events from mongodb { url: "${MONGO_URL}" };

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
