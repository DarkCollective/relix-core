# Working without a database

A relation is a value, and building one runs nothing. So composing a query, rendering it,
asking for its schema and optimising it all work with no database in reach — which is what
makes "read a query, improve it, hand back better text" a whole use of the engine.

That much is free wherever a source **declares its own schema**. This page is about the
two places where it is not free: a name only a database can resolve, and a plan only
statistics can choose well.

## A source that declares itself needs nothing

```java
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.semantic.CatalogProvider;
import com.darkcollective.relix.semantic.CatalogSnapshot;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.ScalarType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

Relix relix = Relix.open();

relix.define("""
        connection warehouse from database { url: "jdbc:postgresql://nowhere/prod" };
        source Orders from warehouse { table: "orders",
            schema: { id: NUMBER, customer: STRING, amount: NUMBER } };
        """);

System.out.println(relix.relation("σ amount > 100 (Orders)").optimized().render());
```

```
σ amount > 100 (Orders)
```

Nothing was opened. The connection is declared, the source names a table in it, and the
schema is written down — so the analyser has everything it needs, and the URL is not read
until something asks for rows.

## A name only the database can resolve

A dotted reference is the other case. `warehouse.orders` has no declared schema by
design: its columns come from the database's catalog, and asking is the point of writing
it that way.

Offline, the analyser reports it as an unresolved name — and a session **rejects that by
default**, because the overwhelmingly common cause is a typo, and a relation built over a
name that resolves to nothing is a failure deferred to a worse moment:

```java
try {
    relix.relation("warehouse.customers");
} catch (RelixException e) {
    System.out.println(e.getMessage());
}
```

```
Cannot resolve schema for table 'customers' in connection 'warehouse': no schema available (declare it with a 'source ... from warehouse' binding, or provide a catalog); Undefined relation: 'warehouse.customers'
```

`Relix.builder().allowUnresolved()` takes the other reading, for the case where composing
and rendering against an unreachable database is the actual goal.

## A snapshot, so offline is full strength

The message names the real fix: *provide a catalog*. Metadata genuinely does come from the
database, so "works offline" and "works well" pull apart unless it can be supplied some
other way — and a `CatalogProvider` is where it is supplied.

`captureCatalog()` asks the live database about every connection-backed table the session
names, once, and hands back something you can write to a file:

```java
// Standing in for the live database, so this page can run with nothing reachable.
// In a program this is the session's own catalog and you do not write it.
class Production implements CatalogProvider {
    public Optional<Schema> tableSchema(ConnectionDeclaration connection, String table) {
        return Optional.of(new Schema(List.of(
                new ColumnDefinition("id", ScalarType.NUMBER),
                new ColumnDefinition("email", ScalarType.STRING))));
    }

    public Optional<RelationStatistics> tableStatistics(
            ConnectionDeclaration connection, String table) {
        return Optional.of(RelationStatistics.of(4_000_000));
    }
}

// The capture time comes from the session's clock, which is pinned here so this page
// prints the same document every time it runs.
Relix live = Relix.builder()
        .catalog(new Production())
        .clock(Clock.fixed(Instant.parse("2026-08-31T09:00:00Z"), ZoneOffset.UTC))
        .build();
live.define("""
        connection warehouse from database { url: "jdbc:postgresql://nowhere/prod" };
        source Customers from warehouse { table: "customers", schema: { id: NUMBER } };
        """);

String captured = live.captureCatalog().toJson();
live.close();

System.out.println(captured);
```

```
{"version":1,"captured":"2026-08-31T09:00:00Z","tables":[{"connection":"warehouse","table":"customers","origin":"introspected","captured":"2026-08-31T09:00:00Z","schema":[{"name":"id","type":{"scalar":"NUMBER"}},{"name":"email","type":{"scalar":"STRING"}}],"statistics":{"rowCount":4000000}}]}
```

Read it back and hand it to a session, and the dotted reference resolves with nothing
reachable — and the cost model sees four million rows, so it makes the same decisions the
live session would:

```java
try (Relix offline = Relix.builder()
        .catalog(CatalogSnapshot.parse(captured))
        .build()) {

    offline.define("""
            connection warehouse from database { url: "jdbc:postgresql://nowhere/prod" };
            """);

    Relation customers = offline.relation("warehouse.customers");
    var planned = customers.plan();

    System.out.println(customers.schema().columns().size() + " columns, ~"
            + planned.estimates().rows(planned.plan()).orElseThrow() + " rows");
}
```

```
2 columns, ~4000000 rows
```

That is the practical shape of it: **tune or validate a production query from a machine
with no access to production**, and do it in CI.

## What a run adds

A snapshot carries what the database *said*. A run carries what was actually there, and the
two are the same artifact — a row count, differing only in where it came from.

So every terminal that drains a relation measures it. A scan read to the end reports the
relation's real size, and the next plan prefers that to whatever it would have estimated:

```java
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.value.NumberValue;
import java.util.stream.Stream;

Schema readings = new Schema(List.of(new ColumnDefinition("id", ScalarType.NUMBER)));

Relix measured = Relix.open();
measured.source("Readings", readings, () -> Stream.of(
        ArrayRow.of(readings, NumberValue.of("1")),
        ArrayRow.of(readings, NumberValue.of("2")),
        ArrayRow.of(readings, NumberValue.of("3"))));

var before = measured.relation("Readings").plan();
System.out.println("estimated: " + before.estimates().rows(before.plan()));

measured.relation("Readings").toList();

var after = measured.relation("Readings").plan();
System.out.println("measured:  " + after.estimates().rows(after.plan()));
measured.close();
```

```
estimated: OptionalLong.empty
measured:  OptionalLong[3]
```

The condition is exactly "read to the end". A caller who takes three rows and closes the
stream has measured their own patience, not the relation, so a partial read reports
nothing — and `stream()`, which hands the caller the lifecycle, usually is a partial read.

A whole expression is measured the same way, keyed by the expression rather than the
relation, because a post-selection count is not a fact about any relation. Two limits come
with that and are worth knowing. It **memoises rather than learns**: measuring
`σ status = 'OPEN'` says nothing about `σ status = 'CLOSED'`, since they are different
expressions. And a query parameterised by a literal mints an entry per literal, so the map
is bounded and evicts — normalising the literal away would throw out the precision that
made the measurement worth keeping.

An expression whose count drifts by construction is not remembered at all. A relation
filtered against `NOW()`, or an unseeded `SAMPLE`, produces a number that was true once.

Measurements of connection-backed tables travel: `captureCatalog()` writes them into the
snapshot as observed entries, which is the loop closing — a run produces the number, and
the snapshot serves it to the next session that cannot reach the database.

## What a snapshot does and does not promise

Every entry records where it came from and when, and a lookup prefers a measured
observation to an introspected estimate, and a recent one to an old one. Entries are never
merged into each other, so a snapshot stays a log of what was seen and when rather than a
picture assembled from several moments.

Statistics are advisory throughout the engine, and a snapshot changes nothing about that:
a stale row count costs a worse plan, never a wrong answer. A stale **schema** is
different — a column that has since been dropped resolves here and fails when the query
runs — which is what the capture time is there to let you check.
