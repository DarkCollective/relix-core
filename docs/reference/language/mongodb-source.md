# Name: connection … from mongodb (MongoDB collection source)

# Syntax:
connection <Name> from mongodb { uri: "<connection string>", database: "<database>" };

source <Name> from <connection> { table: "<collection>",
                                  schema: { <col>: <TYPE>, … } };

connection events from mongodb { uri: "${MONGO_URI}", database: "telemetry" };

# Description:
MongoDB stores documents rather than rows, and Relix reads a collection as a
relation: each document becomes a row, and each column you declare is read from the
field of the same name. You declare the server once as a **connection**, then bind
each collection you want to a name with a `source`.

The connection needs two settings, and both are required: `uri`, the MongoDB
connection string, and `database`, the database that holds the collections.

# Technical Description:
The connector is a plugin rather than part of the engine jar. It is installed in
`~/.relix/connectors/` (the `relix connectors install mongodb` command does it, as
does copying the plugin's jar there), and a `mongodb` connection fails with a message
naming the missing connector until it is. The type token `mongodb` is what selects it.

`table` names the collection. A document store has no catalog to read a heading
from, so **a source over a collection declares its `schema`**: the declared columns
are the relation's heading.

Each declared column is read from the document by name, case-sensitively, and
converted to the column's type:

| Column type | Reads |
|---|---|
| `NUMBER` | any BSON number, or text that holds one; anything else is NULL |
| `STRING` | the value's text |
| `BOOLEAN` | a BSON boolean, or the text `true` or `false`; anything else is NULL |
| `DATE`, `TIME`, `TIMESTAMP`, `TIMESTAMPTZ` | a BSON date (an instant, taken at UTC), or ISO-8601 text |
| `DURATION` | an ISO-8601 duration |
| a struct `{ f: TYPE, … }` | a sub-document, one declared field at a time |
| an array `[TYPE]` | a BSON array, each element converted to `TYPE` |
| `ANY` | whatever is there: sub-documents and arrays keep their structure |

A field the document does not have, or holds as null, is NULL. A field the schema
does not declare is not read, so a collection whose documents vary widens no relation
behind a query's back. A value that cannot be read as its column's type is NULL for
numbers and booleans, and an error for dates.

A query over a `mongodb` source is partly run by the database: selection (σ), unnest
(μ), cheap projection (π) and limit (λ) over the collection are folded into one
aggregation pipeline, and the rest of the query runs in the engine over the documents
the pipeline returns. Sorting, grouping and joins are the engine's. See
[pushdown](../advanced/pushdown.md) for what folds and what does not.

# Examples:
Declare the server, then bind a collection with the columns you want:

```relix
connection telemetry from mongodb {
    uri:      "${MONGO_URI}",
    database: "ops"
};

source Readings from telemetry { table: "readings",
    schema: { sensor: STRING, value: NUMBER, taken_at: TIMESTAMP } };
```

A sub-document and an array are declared with the shape they have:

```relix
connection shop from mongodb { uri: "${MONGO_URI}", database: "shop" };

source Orders from shop { table: "orders",
    schema: { order_id: NUMBER,
              customer: { name: STRING, city: STRING },
              items: [STRING] } };
```

Once bound, a source is queried like any other relation, and the selection below is
run by MongoDB:

  σ value > 100 (Readings)

# Limitations:
Only `uri` and `database` are read. Credentials belong in the connection string,
and `${VAR}` placeholders keep them out of the script (see [env](env.md)).

Sorting, grouping, joins and a negated predicate (`¬`) are evaluated by the engine
over the rows the pipeline returns, not by MongoDB.

# See Also:
[connection](connection.md), [source](source.md), [pushdown](../advanced/pushdown.md), [env](env.md)

# Notes:
The connector keeps one client for a session, and closes it when the session does.
