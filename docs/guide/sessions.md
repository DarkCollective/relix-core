# The session

A `Relix` session holds what a script would declare — sources, connections, views,
functions — together with the bindings that connect them outward: a live `DataSource`, a
catalog, a clock. It is the thing a program keeps.

A session is meant to be **long-lived**. The declarations it accumulates and the
connections it pools are both worth keeping, so the usual shape is a field created at
startup and closed at shutdown rather than a `try` block per query.

```java
Relix relix = Relix.open();
```

`Relix.open()` is the everything-default session: no database bound, no catalog, the system
clock, and the bundled functions and connectors installed. `Relix.builder()` is the same
thing with the knobs exposed.

## Declaring things

`define` takes declarations and returns the session, so it chains. It accumulates: two
calls are one session, not two.

```java
relix.define("""
        source Orders from csv("./orders.csv") { header: true, schema: {
            order_id: NUMBER, customer_id: NUMBER, status: STRING, amount: NUMBER } };
        """);

relix.define("OpenOrders := { σ status = 'OPEN' (Orders) };");

Relation openOrders = relix.relation("OpenOrders");
System.out.println(openOrders.render() + " — " + openOrders.schema().columns().size() + " columns");
```

```
OpenOrders — 4 columns
```

The view resolves against the source declared in the previous call, and its heading is the
source's — one session, not two.

`define` refuses a `query` statement, and the refusal is the useful part: a query is not a
declaration, it is a result, and `script` is what hands those back.

```java
try {
    relix.define("query { σ amount > 10 (Orders) };");
} catch (RelixException e) {
    System.out.println(e.getMessage());
}
```

```
define() takes declarations; this text has 1 query statement(s). Use script(...) to get a Relation for each.
```

## Running a whole script

`script` is what a `.relix` file goes through. It installs the declarations and returns one
`Relation` per `query` statement, in source order:

```java
List<Relation> results = relix.script("""
        Shipped := { σ status = 'SHIPPED' (Orders) };
        query Shipped;
        query { π order_id (Orders) };
        """);

for (Relation result : results) {
    System.out.println(result.label().orElse("(unlabelled)") + " → " + result.render());
}
```

```
Shipped → Shipped
<expression 2> → π order_id (Orders)
```

`label()` is how a result names itself: the name for a `query Shipped;`, and a positional
`<expression N>` for an unnamed one. It is the same rule the engine's own event feed uses,
so a printed heading and a traced event agree about what to call a result.

## Reading the session back

`definitions()` renders the session as `.relix` text, and `statements()` gives the same
content as AST statements:

```java
System.out.println(relix.definitions());
System.out.println(relix.statements().size() + " statements");
```

```
source Orders from csv("./orders.csv") { header: true, schema: { order_id: number, customer_id: number, status: string, amount: number } };
OpenOrders := { σ status = "OPEN" (Orders) };
Shipped := { σ status = "SHIPPED" (Orders) };

3 statements
```

A session that renders back as text is a session that can be saved, diffed, or handed to
someone as a reproduction — which is why the printer exists at all.

## Errors, and the one place they are not thrown

Everything the facade refuses, it refuses by throwing `RelixException`. A typo in a query
your own program wrote is a bug, and a bug should stop the program:

```java
try {
    relix.relation("σ status = 'OPEN' (NoSuchRelation)");
} catch (RelixException e) {
    System.out.println(e.getMessage());
}
```

```
Undefined relation: 'NoSuchRelation'
```

A query assembled from someone *else's* input is a different situation. There an error is
an expected outcome to render, not a defect to raise, and `validate` returns the
diagnostics as data instead:

```java
import com.darkcollective.relix.embed.Diagnostic;

for (Diagnostic problem : relix.validate("Bad := { σ nope = 1 (Orders) };")) {
    System.out.println(problem.severity() + ": " + problem.message());
}
```

```
ERROR: Selection σ: attribute 'nope' not found in schema (available: order_id, customer_id, status, amount)
```

Check `severity()` rather than treating every diagnostic as a refusal — a warning is a
diagnostic too, and a caller that stops on one stops on things that were fine.

```java
System.out.println("errors only: "
        + relix.validate("Bad := { σ nope = 1 (Orders) };").stream()
                .filter(Diagnostic::isError)
                .count());
```

```
errors only: 1
```

`validate` also changes nothing: the text is analysed and discarded, so a session is never
half-modified by a script that turned out not to analyse.

One thing does *not* come back as `RelixException`. A failure that only appears once rows
are moving — a fixpoint that will not converge, a backend that rejects a query — arrives as
the engine's own exception, because flattening every cause into one type would take the
diagnosis away. Catch `RuntimeException` around a terminal if you mean to catch everything.

## Configuring a session

`Relix.builder()` exposes what a session can be given. Each is a binding to the outside
world rather than a tuning parameter:

| Method | What it supplies |
|---|---|
| `jdbc(name, dataSource)` | a live database handle, under a connection name |
| `catalog(provider)` | where schemas and statistics come from — live, or a snapshot |
| `clock(clock)` | what `NOW()` and `CURRENT_DATE` read |
| `baseDirectory(path)` | what a relative file path in a source resolves against |
| `functions(library)` | functions of your own, merged with the bundled ones |
| `connector(connector)` | a backend of your own |
| `scriptLoader(loader)` | what an `import` statement resolves through |
| `maxFixpointRounds(n)` | the recursion safety valve |
| `provisioners(drivers, connectors)` | permission to download a missing driver or plugin |
| `relationships(graph)` | join edges the schema does not carry |
| `sessionEvents(events)` | the feed `relix.events` reports |
| `allowUnresolved()` | compose against names no catalog can resolve |

The clock is the one worth showing, because it is what makes a query over "now"
reproducible in a test:

```java
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

try (Relix pinned = Relix.builder()
        .clock(Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC))
        .build()) {

    System.out.println(pinned.relation("π NOW() → t, CURRENT_DATE() → d (UNIT)").toList());
}
```

```
[(t=2026-03-01T12:00:00Z, d=2026-03-01)]
```

`UNIT` there is the one-row, no-column relation — the algebra's way of saying "evaluate
this expression once", which is what a scalar query is.

Two defaults are deliberate and worth knowing about, because both are cases where a library
should not surprise its caller:

- **A session downloads nothing.** A missing JDBC driver is an error, not a fetch into the
  user's home directory. `provisioners` is how an application that means to offer that says
  so.
- **A session serves imports from nothing.** It is assembled in memory and has no file, so
  an `import` resolves against whatever `scriptLoader` supplies and against nothing
  otherwise.

## Concurrency

A session is meant to be long-lived, and in a server that usually means one field shared
by every request thread. So the division matters: **the session is not thread-safe, and
the relations it hands out are.**

`define` mutates the session and the analysis is cached, so two threads declaring into
one session — or one declaring while another mints a relation — race. Declare from one
thread, at startup, before the session is shared.

A `Relation` is a different thing. It pins the model it was analysed against, which is
what makes it a value rather than a view onto the session's mutable state, and that is
exactly what makes it safe to hold, cache and hand to another thread. Draining two
relations from two threads is in contract:

```java
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

// Declared once, before anything is shared — which is the rule, not the example's
// convenience.
relix.table("Payments", List.of(
        Map.of("status", "OPEN"), Map.of("status", "OPEN"), Map.of("status", "PAID")));

Relation openPayments = relix.relation("σ status = 'OPEN' (Payments)");
Relation paid = relix.relation("σ status = 'PAID' (Payments)");

try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
    var counts = List.of(threads.submit(openPayments::count), threads.submit(paid::count));
    for (var pending : counts) {
        System.out.println(pending.get());
    }
}
```

```
2
1
```

Both relations were minted before the threads started, and each carries the analysis it
needs. Nothing in the two terminals coordinates, and nothing has to.

Two consequences are worth spelling out, because they are what the rule costs in
practice:

- **A relation minted before a `define` does not see it.** That is the pinning rule, not
  a race, and it is the same behaviour on one thread. Mint the relations you intend to
  share after the declarations they depend on.
- **Terminals write to the session.** A drained read records what it measured, so the
  execution path touches session state without your code touching the session at all.
  That path is safe to run concurrently; it is `define` that is not.

If a request thread genuinely needs to declare something of its own, give it its own
session. A session is cheap: what is expensive is the database connection pool, and a
`DataSource` you bind is shared rather than copied, so several sessions over one handle
pool independently against connections you still own.

## Closing

Closing a session releases the database connections it opened. The `DataSource` you handed
it is yours and is left alone, and everything else it holds is ordinary garbage:

```java
relix.close();

// Still a value: building one and rendering it never needed the session's bindings.
System.out.println(relix.relation("OpenOrders").render());

try {
    relix.relation("Orders").toList();
} catch (RelixException e) {
    System.out.println(e.getMessage());
}
```

```
OpenOrders
this session is closed
```

That is the division the whole API is built on, seen at the end of a session's life: a
relation is a value, and only the execution terminals need anything from the outside
world.
