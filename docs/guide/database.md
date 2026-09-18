# Against a database

A session reads a database through a connection. There are two ways to give it one: let
the script declare a JDBC URL, or hand the session a `DataSource` the program already has
— a pooled one from the application's own configuration, most usefully.

This page uses an in-memory H2 database so that it runs with nothing installed. Everything
it shows works the same against any JDBC backend; only the URL changes.

```java
import org.h2.jdbcx.JdbcDataSource;
import java.sql.Connection;

JdbcDataSource warehouse = new JdbcDataSource();
warehouse.setURL("jdbc:h2:mem:guide;DB_CLOSE_DELAY=-1");

try (Connection setup = warehouse.getConnection()) {
    setup.createStatement().execute("""
            DROP TABLE IF EXISTS orders;
            CREATE TABLE orders (
                order_id INT, customer_id INT, status VARCHAR(16), amount DECIMAL(10,2));
            INSERT INTO orders VALUES
                (1, 10, 'OPEN', 100), (2, 11, 'SHIPPED', 250), (3, 10, 'OPEN', 75);
            """);
}
```

## Binding a handle

`jdbc(name, dataSource)` binds the handle under a connection name. The session declares
the connection on your behalf, so nothing in the script has to name a URL — and nothing in
the script *can* leak one:

```java
Relix relix = Relix.builder().jdbc("warehouse", warehouse).build();

System.out.println(relix.definitions());
```

```
connection warehouse from jdbc {  };
```

That is the session rendered back as `.relix` text, and it is worth looking at: the
connection is there, and the URL is not, because there isn't one — the handle is held by
the session rather than described by the script.

## Reading a table

A **dotted reference** names a table in a connection and asks the database what its columns
are:

```java
Relation orders = relix.relation("warehouse.orders");

System.out.println(orders.render());
orders.schema().columns().forEach(column ->
        System.out.println("  " + column.name() + " : " + column.type().display()));
```

```
warehouse.orders
  ORDER_ID : number
  CUSTOMER_ID : number
  STATUS : string
  AMOUNT : number
```

Nothing declared those types. They come from the database's own catalog, read through the
handle when the name was resolved — which is why a dotted reference is the form to use when
the database is the authority on the shape of the data.

The case of those names comes from the database too. H2, like most SQL databases, folds an
unquoted identifier to a canonical case, so a table created as `order_id` reports itself as
`ORDER_ID` — and Relix repeats what it was told rather than normalising it, because a
column name it invented would not be the one a hand-written query would use. Quote the
identifiers in your DDL if you would rather keep them lowercase.

The rows come back through the terminals like any others:

```java
orders.toList().forEach(System.out::println);
```

```
(ORDER_ID=1, CUSTOMER_ID=10, STATUS=OPEN, AMOUNT=100)
(ORDER_ID=2, CUSTOMER_ID=11, STATUS=SHIPPED, AMOUNT=250)
(ORDER_ID=3, CUSTOMER_ID=10, STATUS=OPEN, AMOUNT=75)
```

The alternative is to declare a source, which states the schema in the script and asks the
database nothing:

```java
relix.define("""
        source OpenOrders from warehouse { table: "orders",
            schema: { ORDER_ID: NUMBER, STATUS: STRING, AMOUNT: NUMBER } };
        """);

System.out.println(relix.relation("π ORDER_ID, AMOUNT (σ STATUS = 'OPEN' (OpenOrders))")
        .toList());
```

```
[(ORDER_ID=1, AMOUNT=100), (ORDER_ID=3, AMOUNT=75)]
```

Declaring it is what makes a query analyse with the database unreachable, and a schema
written down is a schema that can go stale. Which to prefer is the subject of
[Working without a database](offline.md).

## What the database does, and what the engine does

The interesting part of embedding a query engine over a database is the division of labour,
and `explain()` is where it is visible:

```java
System.out.println(relix.relation(
        "γ CUSTOMER_ID, SUM(AMOUNT) → total (σ STATUS = 'OPEN' (warehouse.orders))")
        .explain());
```

```
PushedScan [jdbc/warehouse] SELECT CUSTOMER_ID, SUM(AMOUNT) FROM orders WHERE (STATUS = 'OPEN') GROUP BY CUSTOMER_ID  ~2 rows
```

The whole query became one `SELECT`. The filter, the grouping and the aggregate were folded
into it, so the database does the work and the aggregated rows are what cross the wire —
which is the difference between embedding an engine and fetching a table into one.

Not everything folds, and the plan says so plainly. A function the backend cannot spell
leaves the work in the engine:

```java
System.out.println(relix.relation(
        "σ r > 0.5 (π ORDER_ID, Rand() → r (warehouse.orders))").explain());
```

```
Select  ~1 rows
└─ Project  ~3 rows
   └─ PushedScan [jdbc/warehouse] SELECT ORDER_ID, CUSTOMER_ID, STATUS, AMOUNT FROM orders  ~3 rows
```

Read the plan as a boundary: everything below the pushed scan happened in the database,
everything above it happened here. Nothing is lost when a fold fails — the answer is the
same, and the plan tells you what it cost.

## The headline case: your data, joined to theirs

Rows a Java program is holding go in with `table` and join against a database table like
anything else. This is the thing an embedded engine can do that a database client cannot:

```java
relix.table("Regions", List.of("CUSTOMER_ID", "region"), List.of(
        Map.of("CUSTOMER_ID", 10, "region", "North"),
        Map.of("CUSTOMER_ID", 11, "region", "South")));

Relation enriched = relix.relation("warehouse.orders")
        .join(relix.relation("Regions"),
              eq(attr("orders.CUSTOMER_ID"), attr("Regions.CUSTOMER_ID")))
        .project("ORDER_ID", "region", "AMOUNT");

enriched.toList().forEach(System.out::println);
```

```
(ORDER_ID=1, region=North, AMOUNT=100)
(ORDER_ID=2, region=South, AMOUNT=250)
(ORDER_ID=3, region=North, AMOUNT=75)
```

The join is evaluated here, because half its input is in this program's heap. What is
pushed is the part that can be: the scan of `orders`.

## Connections, and who closes what

A session opens database connections lazily, pools them, and releases them when it closes.
The `DataSource` you handed it is yours and is left alone:

```java
relix.close();

try {
    relix.relation("warehouse.orders").toList();
} catch (RelixException e) {
    System.out.println(e.getMessage());
}

try (Connection stillMine = warehouse.getConnection()) {
    System.out.println("the DataSource is untouched: " + !stillMine.isClosed());
}
```

```
this session is closed
the DataSource is untouched: true
```

Between those two, the rule is: close the session, keep the handle. A session is meant to
be long-lived — a field closed at shutdown — because the declarations it accumulates and
the connections it pools are both worth keeping.

One thing does need care in a long-lived session. `toList()` drains and closes; `stream()`
hands you a live cursor, and a stream you do not close holds a pooled connection until the
session ends. Use it in a try-with-resources, every time.

## More than one backend at once

A session can hold several connections, of different kinds, and a query may span them. The
engine pushes what each backend can do and joins the results here:

```java
JdbcDataSource archive = new JdbcDataSource();
archive.setURL("jdbc:h2:mem:guide_archive;DB_CLOSE_DELAY=-1");

try (Connection setup = archive.getConnection()) {
    setup.createStatement().execute("""
            DROP TABLE IF EXISTS closed;
            CREATE TABLE closed (order_id INT, closed_on VARCHAR(10));
            INSERT INTO closed VALUES (2, '2026-01-04');
            """);
}

try (Relix federated = Relix.builder()
        .jdbc("warehouse", warehouse)
        .jdbc("archive", archive)
        .build()) {

    Relation matched = federated.relation("ρ O (warehouse.orders)")
            .join(federated.relation("ρ C (archive.closed)"),
                    eq(attr("O.ORDER_ID"), attr("C.ORDER_ID")));

    Relation written = federated.relation("""
            (ρ O (warehouse.orders)) ⨝ O.ORDER_ID = C.ORDER_ID (ρ C (archive.closed))
            """);

    System.out.println(matched.project("ORDER_ID", "STATUS", "CLOSED_ON").toList());
    System.out.println(written.project("ORDER_ID", "STATUS", "CLOSED_ON").toList());
    System.out.println(matched.explain());
}
```

```
[(ORDER_ID=2, STATUS=SHIPPED, CLOSED_ON=2026-01-04)]
[(ORDER_ID=2, STATUS=SHIPPED, CLOSED_ON=2026-01-04)]
Join INNER/HASH build=RIGHT  ~3 rows
├─ Rename  ~3 rows
│  └─ PushedScan [jdbc/warehouse] SELECT ORDER_ID, CUSTOMER_ID, STATUS, AMOUNT FROM orders  ~3 rows
└─ Rename  ~1 rows
   └─ PushedScan [jdbc/archive] SELECT ORDER_ID, CLOSED_ON FROM closed  ~1 rows
```

Each side is a separate database that knows nothing of the other. The plan shows the
division: two pushed scans, one per connection, and a join the engine performs over what
they return.

Both forms are shown because both work, and the second line of output is there to say so.
Each side is a relation in its own right, built and pinned separately — and each
introspected its own table as it was built, so the two know different names. Composing
them resolves the result against what *both* know, which is why a combinator spans two
backends exactly as a single expression does.

One detail makes either form read the way it does: both sides are renamed with ρ, because
the qualifier of a dotted reference is the whole dotted name — `warehouse.orders`, not
`orders` — and a short alias is easier to write a join condition against.


## What a federated query does not promise

Two databases, a file and an API are four independent systems, and one query across them is
four independent reads. Nothing coordinates them, and three consequences follow that are
worth knowing before such a query carries any weight.

### Nothing is contacted until rows are asked for

A session can be built, a source declared and a plan produced with the backend entirely
unreachable. Planning asks the catalog and the cost model and reads no rows — and where the
script declares the schema, as here, it asks the database nothing at all:

```java
import com.darkcollective.relix.embed.QueryExecutionException;

JdbcDataSource offline = new JdbcDataSource();
offline.setURL("jdbc:h2:tcp://127.0.0.1:1/unreachable");

try (Relix down = Relix.builder().jdbc("remote", offline).build()) {
    down.define("""
            source Remote from remote { table: "orders", schema: { id: NUMBER } };
            """);

    System.out.println(down.relation("Remote").explain().strip());

    try {
        down.relation("Remote").toList();
    } catch (QueryExecutionException e) {
        System.out.println(e.getMessage().split(":")[0]);
    }
}
```

```
PushedScan [jdbc/remote] SELECT id FROM orders
JDBC error running pushed-down query on connection 'remote'
```

The plan is not a guess: it is the statement that database would have been sent. What fails
is the terminal, and the message names the connection the failure came from — every source
does this, a JDBC one naming its connection and table, an HTTP or file source naming the
relation. The text before the colon is the engine's; what follows it is the driver's, and
varies with the driver.

The type says which kind of wrong this is. A `QueryExecutionException` means the query was
runnable and something outside it gave way, which is a fact about the world and may be worth
retrying; a plain `RelixException` means the query was never runnable, and retrying a
malformed query changes nothing. Both are unchecked, and both are `RelixException`, so a
caller who wants neither distinction catches that and is covered.

A collecting terminal has a third answer: `UnboundedRelationException`, for a relation that
never ends. Nothing failed there — it is the shape of the question that a list cannot
answer, so the remedy is a bound or `stream`.

### One input is read to its end before the next is opened

A join has to have one side in hand before it can match the other against it. So the two
reads do not overlap — the engine drains one input completely, then opens the second:

```java
import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.value.NumberValue;
import java.util.ArrayList;
import java.util.stream.IntStream;

List<String> reads = new ArrayList<>();

Schema left = new Schema(List.of(new ColumnDefinition("id", ScalarType.NUMBER)));
Schema right = new Schema(List.of(new ColumnDefinition("rid", ScalarType.NUMBER)));

try (Relix timed = Relix.open()) {
    timed.source("Left", left, () -> {
        reads.add("opened Left");
        return IntStream.rangeClosed(1, 2).mapToObj(i -> {
            reads.add("  read Left " + i);
            return ArrayRow.of(left, NumberValue.of(String.valueOf(i)));
        });
    });
    timed.source("Right", right, () -> {
        reads.add("opened Right");
        return IntStream.rangeClosed(1, 2).mapToObj(i -> {
            reads.add("  read Right " + i);
            return ArrayRow.of(right, NumberValue.of(String.valueOf(i)));
        });
    });

    timed.relation("Left >< Left.id = Right.rid Right").toList();
}

reads.forEach(System.out::println);
```

```
opened Right
  read Right 1
  read Right 2
opened Left
  read Left 1
  read Left 2
```

Which side goes first is the planner's build-side choice and can change with the
statistics; that one of them is finished before the other starts cannot. A natural join
buffers both sides rather than streaming the second, which changes how much is held, not
the ordering.

The consequence is the one to carry away: **a federated result is not a snapshot.** Each
source is current as of the moment *it* was read, and those moments are different — far
apart, if the first read is large or the second backend is slow. Where that matters, the
answer is a column the data already carries — an as-of timestamp to join on, or a version
to filter by — rather than anything the engine can arrange across systems it does not
control.

### What the caller has already seen depends on the terminal

A failure aborts the query. Whether the application has already acted on part of the
answer when that happens is decided by which terminal it used, because `toList` finishes
before it returns anything and `stream` hands rows over as they are produced:

```java
import java.util.function.Supplier;

Schema flaky = new Schema(List.of(new ColumnDefinition("id", ScalarType.NUMBER)));
Supplier<Stream<Row>> failsAfterTwo = () -> Stream.concat(
        IntStream.rangeClosed(1, 2).mapToObj(i ->
                (Row) ArrayRow.of(flaky, NumberValue.of(String.valueOf(i)))),
        Stream.generate(() -> { throw new IllegalStateException("connection reset"); }));

try (Relix partial = Relix.open()) {
    partial.source("Flaky", flaky, failsAfterTwo);

    try {
        List<Tuple> all = partial.relation("Flaky").toList();
        System.out.println("toList: caller saw " + all.size() + " rows");
    } catch (RuntimeException e) {
        System.out.println("toList: caller saw nothing");
    }

    List<String> streamed = new ArrayList<>();
    try (Stream<Tuple> rows = partial.relation("Flaky").stream()) {
        rows.forEach(row -> streamed.add(row.decimal("id").toString()));
    } catch (RuntimeException e) {
        System.out.println("stream: caller saw " + streamed);
    }
}
```

```
toList: caller saw nothing
stream: caller saw [1, 2]
```

Neither is the safe one in general. `toList` is all-or-nothing and needs the whole result in
memory; `stream` is bounded but can hand over a prefix of an answer that never completes, so
an application doing something irreversible per row — posting a payment, sending a message —
is doing it to rows that may turn out to be part of a failed query. Collect first, or make
the per-row work replayable.
