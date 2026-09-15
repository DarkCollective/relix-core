# Reading results

Four terminals move rows out of the engine, and a fifth counts them without moving any.
They differ in one thing: who owns the lifecycle of the result.

| Terminal | Gives back | Who closes it |
|---|---|---|
| `count()` | a `long` | nothing to close |
| `toList()` | `List<Tuple>` | the terminal, before it returns |
| `run()` | `Rows` — the rows and the run's events | the terminal |
| `stream()` | `Stream<Tuple>`, lazily | **you** |
| `stream(listener)` | the same, with the run reported as it happens | **you** |

`toList` and `stream` each have a typed form that reads rows straight into a record of
yours — see [Rows as your own types](#rows-as-your-own-types).

```java
Relix relix = Relix.open();

relix.table("Orders", List.of("order_id", "customer", "status", "amount"), List.of(
        Map.of("order_id", 1, "customer", "Ada", "status", "OPEN", "amount", 100),
        Map.of("order_id", 2, "customer", "Grace", "status", "SHIPPED", "amount", 250),
        Map.of("order_id", 3, "customer", "Ada", "status", "OPEN", "amount", 75)));
```

## The one to reach for

`toList()` drains the result and closes it. That is the whole reason it is the default: a
result stream may hold a live database cursor, and a terminal that closes it cannot leak
one.

```java
Relation open = relix.relation("σ status = 'OPEN' (Orders)");

List<Tuple> rows = open.toList();
System.out.println(rows.size() + " rows");
System.out.println(rows.getFirst());
```

```
2 rows
(order_id=1, customer=Ada, status=OPEN, amount=100)
```

`count()` is not a special counting mode. It is γ COUNT(\*) over the relation — an ordinary
operator, which is why it pushes down like any other and a database counts its own rows:

```java
System.out.println(open.count());
```

```
2
```

## Reading a row

A `Tuple` is the engine's row plus the conversions to Java types. Each accessor names a
type and returns that type, or `null` when the value is NULL — which is why none of them
returns a primitive:

```java
Tuple first = relix.relation("Orders").toList().getFirst();

System.out.println(first.string("customer"));
System.out.println(first.longValue("order_id"));
System.out.println(first.decimal("amount"));
System.out.println(first.columnNames());
System.out.println(first.isNull("status"));
```

```
Ada
1
100
[order_id, customer, status, amount]
false
```

An accessor that meets a value of another type **refuses** rather than coercing:

```java
try {
    first.longValue("customer");
} catch (RelixException e) {
    System.out.println(e.getMessage());
}
```

```
column 'customer' holds string, not number — read it with get(...) and match on the type, or render it with get(...).asDisplayString()
```

That is deliberate. A coercing accessor is a second, weaker statement of what a column
holds, and its failure arrives as a wrong answer rather than as a wrong call. For the same
reason `longValue` refuses a fractional number instead of truncating it.

For a column whose type genuinely varies — an `ANY` column over schema-on-read data — the
accessors are the wrong tool. `Value` is a sealed hierarchy, so a `switch` over it is
exhaustive and the compiler names the case you forgot:

```java
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;

for (Tuple row : relix.relation("π customer, amount (Orders)").toList()) {
    for (String column : row.columnNames()) {
        String described = switch (row.get(column)) {
            case NumberValue n -> "number " + n.value();
            case StringValue s -> "string '" + s.value() + "'";
            default -> row.get(column).asDisplayString();
        };
        System.out.println(column + " → " + described);
    }
}
```

```
customer → string 'Ada'
amount → number 100
customer → string 'Grace'
amount → number 250
customer → string 'Ada'
amount → number 75
```

That is the authoritative route; the accessors are a convenience over the common case.

## Rows as your own types

The lambda that turns a row into a record of yours is mechanical, is written once per
query per program, and is exactly where a renamed column becomes a runtime failure. So
`toList` and `stream` will write it: hand either one a record, and each component is read
from the column of its own name, through the accessor its type names.

```java
import java.math.BigDecimal;

record Order(long order_id, String customer, BigDecimal amount) { }

for (Order order : relix.relation("σ status = 'OPEN' (Orders)").toList(Order.class)) {
    System.out.println(order);
}
```

```
Order[order_id=1, customer=Ada, amount=100]
Order[order_id=3, customer=Ada, amount=75]
```

Nothing is coerced: the accessors are the same ones you would have called, so a component
of the wrong type fails exactly as `longValue("customer")` does above. What is different is
**when**. The heading is known without running anything, so the whole binding is checked at
the call, before a single row moves:

```java
record Wrong(long order_id, String custmer) { }

try {
    relix.relation("Orders").toList(Wrong.class);
} catch (RelixException e) {
    System.out.println(e.getMessage());
}
```

```
no column named 'custmer' for Wrong.custmer; the relation has [order_id, customer, status, amount] — rename it in the query (π old → custmer) or rename the component
```

Two things the heading cannot settle, and they are settled at the row instead: a column
typed `ANY` has no declared type to check against, and whether a value is NULL is not a
property of a heading. A NULL reaching a primitive component is refused there, naming the
component and asking for the boxed type — so a column that can be NULL wants `Long`, not
`long`.

Names must match, and the match is case-insensitive. There is no annotation for saying a
component means some other column: the query already renames one, and `π amount → total`
says it where a reader of the query will look for it. A component may be a `String`, a
`BigDecimal`, `Long`/`long`, `Integer`/`int`, `Double`/`double`, `Boolean`/`boolean`, an
`Instant`, `LocalDate`, `LocalTime` or `Duration`, a `List` for an array column, a `Map`
for a struct column, or a `Value` — which is the one for a column whose type varies, and
the only one that receives a NULL as a `NullValue` rather than as Java `null`.

The record is built through its canonical constructor, reflectively. A record declared in
a named module therefore has to be reachable — public in an exported package, or its
package opened to `com.darkcollective.relix.embed` — and says so if it is not.

## Nested values

A column can hold a struct or an array, and `struct` and `array` are the accessors for
them. `COLLECT` is how an array gets there — it gathers a group's values instead of
reducing them:

```java
import com.darkcollective.relix.ast.AggregateOperator;

Relation gathered = relix.relation("Orders")
        .aggregate(List.of("customer"), List.of(agg(AggregateOperator.COLLECT, "amount", "amounts")));

for (Tuple row : gathered.toList()) {
    System.out.println(row.string("customer") + " → "
            + row.array("amounts").stream().map(Value::asDisplayString).toList());
}
```

```
Ada → [100, 75]
Grace → [250]
```

`unnest` is the inverse — it flattens an array column back into one row per element, so
`COLLECT` and `unnest` round-trip:

```java
gathered.unnest("amounts").toList().forEach(System.out::println);
```

```
(customer=Ada, amounts=100)
(customer=Grace, amounts=250)
(customer=Ada, amounts=75)
```

A struct is read the same way, as a map of its fields:

```java
Relation boxed = relix.relation("Orders").project(List.of(
        projected(attr("order_id")),
        projected(structOf(field("who", attr("customer")), field("how_much", attr("amount"))),
                  "detail")));

Tuple boxedFirst = boxed.toList().getFirst();
System.out.println(boxedFirst.struct("detail").get("who").asDisplayString());
System.out.println(boxedFirst.get("detail").asDisplayString());
```

```
Ada
{who: Ada, how_much: 100}
```

## Streaming

`stream()` is the opt-in for a caller who wants the engine's laziness and accepts what
comes with it: **the stream is a resource and you close it.** Use it in a
try-with-resources, every time.

```java
try (Stream<Tuple> lazy = relix.relation("Orders").stream()) {
    lazy.limit(2).forEach(row -> System.out.println(row.string("customer")));
}
```

```
Ada
Grace
```

Closing early is also how a partial read is cancelled. The engine is pull-based end to end,
so once nobody pulls, nothing further is computed — which is what makes a lazy stream the
right tool for a result too large to hold, and for one that never ends at all:

```java
relix.define("""
        source Naturals from generator { name: "Naturals" };
        """);

try (Stream<Tuple> forever = relix.relation("Naturals").stream()) {
    System.out.println(forever.limit(5).map(row -> row.longValue("n").toString()).toList());
}
```

```
[0, 1, 2, 3, 4]
```

`toList()` refuses that relation rather than hanging. A collecting *consumer* is not part
of the query tree, so it is the terminal — not the planner — that has to ask whether the
relation ever ends:

```java
try {
    relix.relation("Naturals").toList();
} catch (RuntimeException e) {
    System.out.println(e.getClass().getSimpleName() + ": " + e.getMessage());
}
```

```
BoundednessException: cannot collect an unbounded relation into a list; add a bound (e.g. limit(n)), or stream it instead
```

### A stream that got away

A stream you never close goes on holding what it reads through — a live `ResultSet`, its
statement, and the pooled database connection behind them. That is the cost of the
laziness, and it is why every example above is in a try-with-resources. You do not have to
guess whether one escaped: the session counts them.

```java
Stream<Tuple> escaped = relix.relation("Orders").stream();
System.out.println(escaped.findFirst().map(row -> row.string("customer")).orElseThrow());
System.out.println("open: " + relix.openStreams());

escaped.close();
System.out.println("open: " + relix.openStreams());
```

```
Ada
open: 1
open: 0
```

Reading a row is not closing: `findFirst()` takes one and leaves the rest of the query
standing. A collecting terminal is different — `toList()`, `count()` and `run()` drain and
close, so they never leave anything behind, and a non-zero count is always a `stream()`
result.

Closing the session closes whatever is still open and warns with the count, so the
connection goes back rather than out with the process. Asserting the count is zero before
you close is how that stops being a warning you read and starts being a test that fails.

## Size, and what fits in memory

Streaming bounds what a *terminal* holds. It does not bound what the *query* holds, and
the difference is where an embedded engine surprises people.

Most operators stream: σ, π, ρ, λ and every join except the full outer one pass rows
through as they arrive. The rest have to see all their input before they can emit their
first row — γ groups, τ sorts, δ and the deduplicating set operations ∪, ∩ and −
compare against everything seen so far, ⊎ and ÷ and ⟗ buffer a side. A hash join buffers
its build side. Those buffers live in the JVM heap and **nothing spills to disk**: the
memory a query needs is set by the largest thing it buffers, which may be far larger than
the result you asked for. Two things help — a cap that stops a runaway buffer with a
useful error, and a report of what each one actually held.

The engine knows which operators block, and refuses the combination it can prove is
hopeless — a blocking operator over a relation with no end:

```java
try {
    relix.relation("τ n (Naturals)").stream();
} catch (RuntimeException e) {
    System.out.println(e.getClass().getSimpleName() + ": " + e.getMessage());
}
```

```
BoundednessException: cannot materialise unbounded relation for blocking operator τ (SORT); add a bound (e.g. λ n) below it
```

That check is about *boundedness*, not size. A table with fifty million rows is
perfectly bounded, so sorting it in-engine is a plan the engine will happily make. From
the plan's point of view there is nothing wrong, which is why size is a question you
answer for yourself, in two places: a cap you set before the run, and the plan you read.

### A cap on what one operator buffers

`maxMaterializedRows` bounds how many rows a single blocking operator may hold. Cross it
and the query stops with an error naming the operator, instead of running on until the
heap is gone and failing with an `OutOfMemoryError` that names nothing:

```java
try (Relix capped = Relix.builder().maxMaterializedRows(2).build()) {
    capped.table("Wide", List.of("n"), List.of(
            Map.of("n", 1), Map.of("n", 2), Map.of("n", 3)));
    try {
        capped.relation("τ n (Wide)").toList();
    } catch (RuntimeException e) {
        System.out.println(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
}
```

```
EvaluationException: Sort buffered more than 2 rows; a blocking operator holds its whole input in memory. Reduce what reaches it (a σ or λ below it, or a pushdown), or raise maxMaterializedRows
```

Read the number as a guard rail rather than a memory limit. It bounds **one** operator, not
the sum of every buffer in the run — the same shape `maxFixpointRounds` has, and for the
same reason: a recursion that buffers the same thousand rows on each of a hundred rounds
never holds more than a thousand, and a running total would refuse it. So a plan with many
blocking operators can exceed the cap in aggregate while no single operator does. What it
converts is the failure that actually happens — one operator swallowing a table.

**It is set by default, to ten million rows per operator**, and the number is chosen to be
one no reasonable query reaches and every runaway does. What it buys is not memory — ten
million rows is a great deal of memory — but attribution: past it the failure names the
operator and this knob, where an `OutOfMemoryError` names nothing and takes your process
with it. Pass `ExecutionContext.UNLIMITED_MATERIALIZED_ROWS` to turn it off.

`maxFixpointRounds` is deliberately **not** capped by default, and the asymmetry is the
point. How many rounds a legitimate recursion needs is a property of your data — a
transitive closure over a long chain needs one round per hop — so any default number
refuses some correct query, and a truncated answer is harder to notice than a slow one.
Set it when you know what your recursion should cost.

### Stopping a query

Interrupt the thread running it. The engine checks between rows, and between each row a
blocking operator pulls while filling its buffer, so a cancellation lands promptly rather
than at the end of a drain:

```java
Thread worker = new Thread(() -> relix.relation("τ amount (Orders)").toList());
worker.start();
worker.interrupt();
```

That is the mechanism the platform already routes everything through — `Future.cancel(true)`,
`ExecutorService.shutdownNow`, and a pool being torn down all raise the same flag — so
there is nothing new to hold. The query stops with an `EvaluationException` and the flag is
left raised, so a thread you hand back to a pool still knows it was interrupted.

One limit is worth stating plainly: a thread blocked inside a JDBC driver is not
interruptible. Where the query has been pushed down, the engine is waiting on the database
and notices only when it answers. Closing the stream early is the other lever, and it works
for the same reason — the engine is pull-based end to end, so nothing runs that nobody
asked for.

### What a run actually buffered

`run()` reports each buffer as it completes, so you need not guess which operator was the
expensive one:

```java
relix.relation("γ customer, SUM(amount) → total (Orders)").run().events().stream()
        .filter(e -> e.code().equals("MATERIALIZE"))
        .forEach(e -> System.out.println(
                e.description() + " (" + e.metrics().rows().getAsLong() + ")"));
```

```
buffered 3 rows for Aggregate (3)
```

The row count is on the event as a number as well as in the sentence, so a host can compare
it against the estimate the plan carried rather than parse it back out of prose.

### Reading the plan

`explain()` draws the boundary:
anything below a `PushedScan` happened in the backend, and anything above it happened
here, on this heap.

```java
System.out.println(relix.relation("γ status, SUM(amount) → total (Orders)").explain());
```

```
Aggregate  ~2 rows
└─ Scan Orders  ~3 rows
```

That γ is above a scan of a table this program is holding, so it groups here — fine for
three rows, and the same shape over a database table is the query to worry about. Three
things move the boundary:

- **Let the backend do the blocking work.** A γ, τ or δ that folds into a `PushedScan`
  is buffered by the database, which has disk to spill to. The language reference's
  *Pushdown* page says exactly which operators fold and where they stop folding.
- **Bound the input, not the output.** A λ above a sort still sorts everything. Put the
  limit where it can be pushed, and check with `explain()` that it was.
- **Ask for the number rather than the rows.** `count()` is γ COUNT(\*) and pushes down,
  so it crosses the wire as one row — `toList().size()` fetches the whole result to
  measure it.

`toList()` refuses a relation with no end, as the section above shows. It does not refuse
a large one: a collecting consumer cannot tell fifty million rows from five until it has
read them, which is what the cap is for.

## The rows and what produced them

`run()` is `toList()` with the run's own event feed attached — the rules that fired, the
planner's choices, what was pushed to a backend, and the row count the run ended with:

```java
import com.darkcollective.relix.embed.Rows;

Rows result = relix.relation("σ amount > 50 (Orders)").run();

System.out.println(result.size() + " rows");
result.events().forEach(event ->
        System.out.println("  " + event.stage() + " " + event.code() + " " + event.description()));
```

```
3 rows
  EXECUTE SCAN scanned 3 rows
  EXECUTE ROWS query delivered 3 rows
```

The row count arrives as an event rather than as a separate field, because a row stream's
length is not known until something drains it — so the terminal that drains it is the one
that can say so, and it says so on the feed everything else is already on.

For a result too large to collect, `stream(listener)` is the same feed without the
collecting:

```java
import com.darkcollective.relix.events.QueryEvent;

List<QueryEvent> feed = new java.util.ArrayList<>();
try (Stream<Tuple> traced = relix.relation("Orders").stream(feed::add)) {
    traced.forEach(row -> { });
}

feed.forEach(event -> System.out.println(event.stage() + " " + event.code()));
```

```
EXECUTE SCAN
```

## Where the time went

An embedded engine has no profiler attached to it, so the feed is the profiler. Every event
that counts rows also times the step that produced them:

```java
Rows timed = relix.relation("γ status, SUM(amount) → total (Orders)").run();

timed.events().stream()
        .filter(event -> event.metrics().duration().isPresent())
        .forEach(event -> System.out.println(event.code() + " — " + event.description()));
```

```
SCAN — scanned 3 rows
MATERIALIZE — buffered 3 rows for Aggregate
ROWS — query delivered 2 rows
```

The times themselves are wall clock — they move with the machine, the page cache and
whatever else the host is doing — which is why the example above prints *which* steps were
timed rather than how long they took. Read them to find the step worth looking at, not to
assert that anything is fast enough.

What each one claims is worth knowing, because the three do not measure alike. `ROWS` is
the query's own elapsed time and is **inclusive**: it covers every scan, buffer and join
done on the query's behalf, since a pull-based engine does that work when it is asked and
there is no interval that leaves the asking out. `SCAN` and `MATERIALIZE` are **self**
time — only the pull is timed, so a scan reports what producing its rows cost and not what
the operator above it then did with them.

That is the difference that makes the numbers usable. Timed inclusively, a scan under a
join would report very nearly the whole query, and every plan would look like its leaves
were the problem. Timed as they are, the parts nest inside the total, and the question
*is the join the problem, or one of the scans?* is answered by comparing them:

```java
java.time.Duration whole = timed.events().stream()
        .filter(event -> event.code().equals("ROWS"))
        .findFirst().orElseThrow().metrics().duration().orElseThrow();
java.time.Duration parts = timed.events().stream()
        .filter(event -> !event.code().equals("ROWS"))
        .flatMap(event -> event.metrics().duration().stream())
        .reduce(java.time.Duration.ZERO, java.time.Duration::plus);

System.out.println("the parts fit inside the whole: " + (parts.compareTo(whole) <= 0));
```

```
the parts fit inside the whole: true
```

When most of a query's time is unaccounted for by its scans and buffers, the work is in the
operators between them.

## What a drained read tells the engine

A terminal that reads to the end has measured something true, and the engine keeps it: the
next plan over that relation prefers the measured count to the one it would have estimated.

The condition is exactly "read to the end". A caller who takes three rows and closes the
stream has measured their own patience rather than the relation, so a partial read reports
nothing — which is why `stream()`, the terminal that hands you the lifecycle, usually
measures only whatever it happened to exhaust. [Working without a database](offline.md)
follows what happens to those numbers next.
