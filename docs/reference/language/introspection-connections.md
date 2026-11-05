# Name: Connection catalog (`relix.connections`)

# Syntax:
relix.connections   -- the declared connections (name, dialect)

# Description:
`relix.connections` lists the connections a script declares with
[`connection`](connection.md), one row for each, without revealing anything that would be a
secret. It answers "what does this script talk to?" without anyone having to read the
declarations.

Columns:

- `name` (`STRING`) — the name the connection was declared under.
- `dialect` (`STRING`) — the `dialect` property the connection declares, or `generic`
  when it declares none.

# Technical Description:
Only these two facts are published. The connection's URL, user, password and every
other property are redacted by omission — they are not columns — so the relation is
safe to print, to return to a caller or to put in a report even where the declarations
hold credentials.

The dialect is read from the declaration as written rather than detected from a
driver, so a connection that does not say `dialect` reads `generic` whatever it is
connected to, and the catalog covers connections of every connector type, not only JDBC.

A script that declares no connection gives an empty relation. The tables a connection
supplies are not listed here: each is a source, and appears in
[`relix.relations`](introspection-relations.md).

# Examples:
Every connection:

```relix
query { relix.connections };
```

Connections that did not declare a dialect:

```relix
query { π name (σ dialect = "generic" (relix.connections)) };
```

How many connections use each dialect:

```relix
query { γ dialect, COUNT(*) → connections (relix.connections) };
```

# Limitations:
The relation says nothing about a connection's address or credentials and cannot be
used to recover them.

# See Also:
[connection](connection.md), [relation catalog](introspection-relations.md), [reserved namespace](introspection-catalog.md)

# Notes:
A name here is the one a dotted reference such as `shop.orders` uses.
