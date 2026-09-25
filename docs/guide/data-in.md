# Bringing your own data and code

"Here is my in-memory data, joined against my database table" is one of the reasons to
embed a query engine at all. Five calls cover it. The first three differ in one axis —
how much the program hands over, and how late — and the last two hand over code rather
than rows.

| Call | For |
|---|---|
| `table(name, rows)` | Rows already in memory — reference data, fixtures, a result computed elsewhere |
| `source(name, schema, supplier)` | Anything lazy or large: a stream the program produces per scan |
| `materialize(name, relation)` | A result this session computed, kept under a name and queried again |
| `connector(connector)` | A backend of the program's own, read through the connector SPI |
| `functions(library)` | Scalar or aggregate functions of the program's own |

Everything on this page composes with everything else a session declares. A supplied
relation is a relation: it joins against a database table, it optimises, it pushes what
can be pushed and evaluates the rest in-engine.

## Rows you already hold

`table` is the small end, and [Getting started](getting-started.md) opens with it. State
the heading, then the rows as maps. Each row is read **by name**, so the maps need no
order of their own and `Map.of` is safe here:

```java
Relix relix = Relix.open();

relix.table("Carriers", List.of("carrier", "mode"), List.of(
        Map.of("carrier", "Rail", "mode", "ground"),
        Map.of("carrier", "Air", "mode", "air")));

System.out.println(relix.relation("Carriers").count() + " carriers");
```

```
2 carriers
```

**Types are inferred** — a column of numbers is `NUMBER`, everything else is `STRING`. A
column a row does not mention is NULL for that row, which is how an optional column is
expressed; a key naming no column is refused rather than silently dropped. With the
heading stated, a table with no rows at all is expressible too.

There is a two-argument form that takes the heading from the first row's key order, for
when the maps are built rather than written out. The first row then has to be a map that
*has* an order — a `LinkedHashMap`, not a `Map.of`, whose iteration order is randomised
per JVM, so a heading taken from one would differ between runs of the same program. That
is refused rather than accepted arbitrarily; a one-column row is exempt, since one key is
in only one order.

That inference is the reason `table` is the small end. When a column has to be a real
`TIMESTAMP`, or the rows are too many to hold, the heading has to be declared — which is
the next call.

## Rows you produce on demand

`source` takes a heading and a supplier. The heading is *declared*, so a column is the
type you gave it; the supplier is called **once per scan**, so nothing is produced until a
query reads it, and a second query gets a second stream.

```java
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;

Schema shipments = new Schema(List.of(
        new ColumnDefinition("order_id", ScalarType.NUMBER),
        new ColumnDefinition("carrier", ScalarType.STRING)));

relix.source("Shipments", shipments, () -> Stream.of(
        Row.of(shipments, NumberValue.of("1"), new StringValue("Rail")),
        Row.of(shipments, NumberValue.of("2"), new StringValue("Air"))));

for (Row row : relix.relation("Shipments").toList()) {
    System.out.println(row.get("order_id").asDisplayString()
            + " " + row.get("carrier").asDisplayString());
}
```

```
1 Rail
2 Air
```

Rows are built with `Row.of(schema, values)`, in the heading's column order. The
engine closes the stream it is handed, so a supplier holding a resource releases it
through `Stream.onClose`.

The engine treats a supplied source as **finite**, so the collecting terminals read it to
the end. A supplier whose stream does not terminate is read with `stream()`, which is
lazy and stops the moment you stop pulling.

## A result you want to keep

The three calls above bring in data from outside. `materialize` brings in data the session
computed itself: it takes a relation, runs it, and registers the rows under a name, so the
next query reads a result instead of computing it again.

```java
relix.materialize("ByCarrier",
        relix.relation("γ carrier, COUNT(*) → shipments (Shipments)"));

for (Row row : relix.relation("τ carrier (ByCarrier)").toList()) {
    System.out.println(row.get("carrier").asDisplayString()
            + " " + row.get("shipments").asDisplayString());
}
```

```
Air 1
Rail 1
```

**This is the one call on a session that runs anything.** Everywhere else a declaration is
a declaration and building a relation executes nothing; here the argument is drained before
the call returns, because computing once and reading the result many times is the whole
point of it. A relation that provably never ends is refused, exactly as `toList()` refuses
one.

What it registers carries the relation's **own** heading — which is the difference between
this and draining the rows into maps and passing them to `table`. There the types would be
inferred back to `NUMBER` or `STRING`, so a `TIMESTAMP` column would come back a string.

The rows are a snapshot. Whatever they were computed from may move on; the named relation
holds what was true when it was drained.

## A backend of your own

`connector` installs an implementation of the connector SPI — the same seam the shipped
CSV, JSON, HTTP and JDBC connectors are discovered through. The point of doing it this way
rather than through a `META-INF/services` declaration is that a program exercising the SPI
inside itself should not have to package itself as a service provider.

A connector claims one or more **type tokens**. Declare a connection of that type, and the
session's sources over it are read through your code:

```java
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.connector.RelixConnector;
import java.util.Set;

class Ledger implements RelixConnector {
    public Set<String> handles() {
        return Set.of("ledger");
    }

    public Stream<Row> open(ConnectorConfig config, String table, Schema schema) {
        // Both halves of the declaration arrive here: the connection's own properties as
        // the config, and the source's `table:` as the name to read.
        return Stream.of(Row.of(schema, NumberValue.of("1"), NumberValue.of("100"),
                new StringValue(config.require("origin") + "/" + table)));
    }
}

relix.connector(new Ledger());
relix.define("""
        connection books from ledger { origin: "in-process" };
        source Payments from books { table: "payments",
            schema: { order_id: NUMBER, paid: NUMBER, ledger_ref: STRING } };
        """);

System.out.println(relix.relation("Payments").toList()
        .getFirst().get("ledger_ref").asDisplayString());
```

```
in-process/payments
```

An installed connector is consulted before the discovered ones, so it wins a token a
shipped connector also claims. It is **not** closed with the session: it is yours, exactly
as a `DataSource` you bind is.

## Functions of your own

`functions` installs a function library, and the engine calls it exactly as it calls the
bundled one — the same resolution during analysis, the same invocation during execution,
the same eligibility for pushdown when the library says how to spell it.

The properties a signature declares are part of that. `PURE` and `DETERMINISTIC` below are
what let the call be folded into a backend query at all: a function whose value depends on
when it runs is evaluated here, however it spells itself, because the backend would answer
from its own clock rather than the session's.

```java
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.function.FunctionLibrary;
import com.darkcollective.relix.function.FunctionSignature;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.function.StrictScalarFunction;
import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.ParameterDefinition;
import java.math.BigDecimal;

class WithVat implements StrictScalarFunction {
    public FunctionSignature signature() {
        return FunctionSignature.of("WithVat", ScalarType.NUMBER, "math",
                Set.of(FunctionProperty.PURE, FunctionProperty.DETERMINISTIC),
                new ParameterDefinition("amount", ScalarType.NUMBER));
    }

    public Value invoke(FunctionContext context, List<Value> arguments) {
        BigDecimal amount = ((NumberValue) arguments.getFirst()).value();
        return new NumberValue(amount.multiply(new BigDecimal("1.20")));
    }
}

class Pricing implements FunctionLibrary {
    public String name() {
        return "pricing";
    }

    public List<ScalarFunction> scalarFunctions() {
        return List.of(new WithVat());
    }
}

relix.functions(new Pricing());

System.out.println(relix.relation("π WithVat(paid) → gross (Payments)").toList()
        .getFirst().get("gross").asDisplayString());
```

```
120
```

Installed libraries are merged with the discovered ones rather than replacing them, so the
bundled functions are still there and an installed name wins a clash.

## What this does not add

There is no write path. Relix reads: a supplied relation is an input to a query, and
nothing here sends anything back — `materialize` included, whose rows land in this
session's memory and nowhere else.
