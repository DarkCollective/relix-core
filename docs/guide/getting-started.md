# Getting started

Relix is a relational algebra engine. This guide is about using it from Java: building
queries, looking at what the engine makes of them, and running them.

Everything below is real code. Every example is compiled against the shipped API and run,
and the block underneath it is what it actually printed.

## A session and a relation

Two types carry the whole API.

A **`Relix` session** holds what a script would declare — sources, connections, views,
functions — together with the bindings that connect them outward, such as a live
`DataSource`. A **`Relation`** is a value: an expression together with the analysis it
resolves against.

```java
import com.darkcollective.relix.embed.Relix;
import com.darkcollective.relix.embed.Relation;

try (Relix relix = Relix.open()) {
    relix.define("""
            source Orders from csv("./orders.csv") {
                header: true,
                schema: { order_id: NUMBER, customer: STRING, status: STRING, amount: NUMBER }
            };
            """);

    Relation open = relix.relation("σ status = 'OPEN' (Orders)");
    System.out.println(open.render());
}
```

```
σ status = "OPEN" (Orders)
```

Nothing was read. `Relix.open()` reaches no database and that CSV file does not exist —
the source *declares* its own schema, so the session has everything it needs to resolve
the name, infer the heading, and hand back a relation.

That is the shape of the whole API: **a relation is a value, not a pending execution**.
Composing one, rendering it, optimising it and asking for its schema are whole uses of
the engine, and none of them runs anything.

Note also what `render()` gave back: `'OPEN'` came out as `"OPEN"`. Rendering is not an
echo of what you typed — it prints the tree, so formatting and the spelling of a literal
are normalised. What it guarantees is that the text parses back to the same expression.

A session is `AutoCloseable`, and closing it releases the database connections it opened.
The `DataSource` you hand it stays yours and is left alone.

## Data the program already holds

Reference data, fixtures, a result computed elsewhere — rows a Java program is holding go
in directly, and are queryable beside everything else.

The rest of this page shares one session and one small table. A session is usually
long-lived — a field closed at shutdown, rather than a `try` block per query — so here it
is opened plainly:

```java
Relix relix = Relix.open();

relix.table("Orders", List.of("order_id", "customer", "status", "amount"), List.of(
        Map.of("order_id", 1, "customer", "Ada", "status", "OPEN", "amount", 100),
        Map.of("order_id", 2, "customer", "Grace", "status", "SHIPPED", "amount", 250),
        Map.of("order_id", 3, "customer", "Ada", "status", "OPEN", "amount", 75)));
```

The heading is stated first, and each row is read by name — so the maps need no order of
their own, and a row that omits a column has NULL for it. Types are **inferred** — a
column of numbers is `NUMBER`, everything else is `STRING`. Where the types matter,
declare a source with a schema instead ([Bringing your own data](data-in.md)).

## Writing the query

There are two ways to say what you want, and they build the same tree.

The first is the language. `relation(String)` takes any relational expression, in either
spelling Relix accepts — the operator glyph or its ASCII keyword:

```java
System.out.println(relix.relation("SELECT status = 'OPEN' (Orders)").render());
```

```
σ status = "OPEN" (Orders)
```

The second is the combinators. Every operator that takes an input relation is a method on
`Relation`, named as the reference manual names it:

```java
Relation open = relix.relation("Orders")
        .select(eq(attr("status"), str("OPEN")))
        .project("customer", "amount");

System.out.println(open.render());
```

```
π customer, amount (σ status = "OPEN" (Orders))
```

`eq`, `attr` and `str` come from `Expr`, which is where an expression is built out of Java
values rather than out of source text. **That is how a value is bound to a query**, and
the reason no program here has to assemble one by concatenating strings:

```java
long minimum = 90;
Relation large = relix.relation("Orders").select(gt(attr("amount"), num(minimum)));

System.out.println(large.render());
```

```
σ amount > 90 (Orders)
```

## Getting the rows out

Three terminals, and the first is the one to reach for.

`toList()` drains the result and closes it, so the common case — a result that fits in
memory — cannot leak the database connection an open result holds.

`stream()` is the opt-in for a caller who wants the engine's laziness and accepts what
comes with it: **the stream is a resource, and you close it**. Closing early is also how a
partial read is cancelled, because the engine is pull-based from end to end — once nobody
pulls, nothing further is computed.

`count()` answers how many rows there are without materialising them. It is not a special
counting mode: it is `γ COUNT(*)` over the relation, an operator that pushes down like any
other, so against a database the backend does the counting and one row crosses the wire.

```java
Relation orders = relix.relation("Orders");

System.out.println("count: " + orders.count());

for (Tuple row : relix.relation("σ status = 'OPEN' (Orders)").toList()) {
    System.out.println(row.string("customer") + " " + row.decimal("amount"));
}

try (Stream<Tuple> rows = orders.stream()) {
    System.out.println("streamed: " + rows.count());
}
```

```
count: 3
Ada 100
Ada 75
streamed: 3
```

Some relations never end — a generator over the natural numbers has no last row.
`stream()` will happily read one, which is what generators are for; `toList()` refuses it
rather than hanging.

## Reading a row

A row is a `Tuple`: the engine's row, plus the conversions to Java types. `string`,
`decimal`, `longValue`, `doubleValue`, `booleanValue`, `instant`, `date`, `time`,
`duration`, `array` and `struct` each name a type and give you that type — **or `null`,
if the value is NULL**, which is why none of them returns a primitive.

An accessor that meets a value of the wrong type raises rather than coercing. Coercion
would make the accessor a second, weaker statement of what the column holds, and the
failure would then arrive as a wrong answer instead of a wrong call:

```java
Tuple first = relix.relation("Orders").toList().getFirst();

System.out.println(first.string("customer") + " / " + first.longValue("order_id"));

try {
    first.longValue("customer");
} catch (RelixException e) {
    System.out.println(e.getMessage());
}
```

```
Ada / 1
column 'customer' holds string, not number — read it with get(...) and match on the type, or render it with get(...).asDisplayString()
```

For a column whose type genuinely varies — an `ANY` column over schema-on-read data — the
accessors are the wrong tool and `Value` is a sealed hierarchy, so a `switch` over it is
exhaustive and the compiler names the case you forgot:

```java
Value amount = relix.relation("Orders").toList().getFirst().get("amount");

System.out.println(switch (amount) {
    case com.darkcollective.relix.value.NumberValue n -> "number " + n.value();
    case com.darkcollective.relix.value.StringValue s -> "string " + s.value();
    default -> amount.asDisplayString();
});
```

```
number 100
```

That is the authoritative route, and the accessors are a convenience over the common case
rather than a replacement for it.

## Seeing what the engine did

The engine's phases are worth looking at, so they are terminals rather than internals.

`optimized()` hands back the rewritten relation, and `events()` says which rules produced
it:

```java
Relation written = relix.relation("σ status = 'OPEN' (σ amount > 50 (Orders))");
Relation rewritten = written.optimized();

System.out.println("as written: " + written.render());
System.out.println("rewritten:  " + rewritten.render());
rewritten.events().forEach(event -> System.out.println("  " + event.code()));
```

```
as written: σ status = "OPEN" (σ amount > 50 (Orders))
rewritten:  σ (status = "OPEN") ∧ (amount > 50) (Orders)
  SEL-002
```

`events()` is what the rewriter did. To see the whole run — the rewrite, the planner's
choices and the execution — hand a listener to `stream()`, which reports as it goes
rather than collecting the rows first:

```java
import com.darkcollective.relix.events.QueryEvent;

List<QueryEvent> feed = new java.util.ArrayList<>();
try (Stream<Tuple> rows = relix.relation("σ amount > 50 (Orders)").stream(feed::add)) {
    rows.forEach(row -> { });
}

feed.forEach(event -> System.out.println(event.stage() + " " + event.code()
        + " " + event.description()));
```

```
EXECUTE SCAN scanned 3 rows
```

That is also how a query too large to hold is traced: `run()` gives you the rows and the
feed together, and this gives you the feed alone.

There is one rule behind all of this: **inspection is staged, execution is not**.
`render()` and `optimized().render()` differ, because the first shows what you wrote.
`stream()` and `optimized().stream()` do not differ, because every execution terminal
optimises first. `optimized()` is there to let you *see* the rewrite, not to switch it on.

`explain()` shows the physical plan — join algorithms, what was folded into a database
query, and each step's estimated row count. Like `render()`, it is staged: this is the
plan for the query as written.

```java
System.out.println(relix.relation("σ status = 'OPEN' (Orders)").explain());
```

```
Select  ~2 rows
└─ Scan Orders  ~3 rows
```

## Errors

Everything the facade refuses, it refuses by throwing `RelixException` — with one
exception, and the exception is the interesting case.

```java
try {
    relix.relation("σ status = 'OPEN' (NoSuchRelation)");
} catch (RelixException e) {
    System.out.println("refused");
}
```

```
refused
```

A typo in a query you wrote is a bug, and a bug should throw. A query assembled from
someone else's input is different: there an error is an expected outcome to render, not a
defect to raise. `validate` is for that, and returns the diagnostics as data:

```java
for (Diagnostic problem : relix.validate("Bad := { σ nope = 1 (Orders) };")) {
    System.out.println(problem.message());
}
```

```
Selection σ: attribute 'nope' not found in input schema (available: order_id, customer, status, amount)
```

A failure that only appears once rows are moving is a third case: it arrives as the
engine's own exception rather than as a `RelixException`, because there is nothing the
facade could usefully add to it. [The session](sessions.md) has the detail.

## Where to go next

What each operator *means*, and the full syntax of the language `relation(String)`
accepts, are in the language reference. Every combinator on `Relation` carries the name
its reference page gives the operator, so the two read together.
