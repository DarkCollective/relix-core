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

