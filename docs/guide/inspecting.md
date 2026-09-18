# Looking at what the engine did

Reading a query, improving it and handing back better text is a whole use of the engine,
and nothing on this page runs anything. The rewrite, the plan, the estimates and the
session's own inventory are all available without a row moving.

```java
Relix relix = Relix.open();

relix.define("""
        source Orders from csv("./orders.csv") { header: true, schema: {
            order_id: NUMBER, customer_id: NUMBER, status: STRING, amount: NUMBER } };
        source Customers from csv("./customers.csv") { header: true, schema: {
            customer_id: NUMBER, name: STRING } };
        BigOpenOrders := { σ status = 'OPEN' (σ amount > 100 (Orders)) };
        """);
```

## The one rule

**Inspection is staged; execution is not.**

`render()` and `optimized().render()` differ, because the first shows what you wrote and
the second shows what the rewriter made of it. `stream()` and `optimized().stream()` do
*not* differ, because every execution terminal optimises first.

So `optimized()` exists to let you **see** the rewrite, never to switch it on. Nothing is
gained by calling it before running a query, and nothing is lost by not calling it.

## The expression

`render()` prints the tree back as Relix text, and `schema()` gives its heading.

```java
Relation query = relix.relation("π customer_id, amount (BigOpenOrders)");

System.out.println(query.render());
query.schema().columns().forEach(column ->
        System.out.println("  " + column.name() + " : " + column.type().display()));
```

```
π customer_id, amount (BigOpenOrders)
  customer_id : number
  amount : number
```

Rendering is not an echo of the input: it prints the tree, so whitespace and the spelling
of a literal are normalised. What it guarantees is that the text parses back to the same
expression — which is what makes "read a query, improve it, hand back better text" work at
all.

## The rewrite

`optimized()` returns the rewritten relation. `events()` says which rules fired, and
`rewrites()` says what each one fired on.

```java
Relation rewritten = query.optimized();

System.out.println(rewritten.render());
rewritten.events().forEach(event ->
        System.out.println("  " + event.code() + " — " + event.description()));
```

```
π customer_id, amount (σ (status = "OPEN") ∧ (amount > 100) (π customer_id, status, amount (Orders)))
  INLINE-001 — view 'BigOpenOrders' inlined
  RENAME-002 — rename removed — nothing references the alias BigOpenOrders
  SEL-002 — two adjacent selections merged into one conjunctive predicate
  PROJ-004 — columns pruned at Orders: 3 of 4 read
```

Two rules are visible there: the view was inlined, and the two selections were merged into
one conjunction. That is the difference between the query a person writes and the query the
engine runs, and it is worth looking at when a query is slower than it should be.

`rewrites()` is the same story as records rather than as a feed. An event says a rule
fired; a record says what it fired on, which is the difference between a trace and a
report:

```java
rewritten.rewrites().forEach(record ->
        System.out.println(record.code().code() + " on " + record.relationName()
                + " — " + record.detail()));
```

```
INLINE-001 on query[1] — view 'BigOpenOrders' inlined
RENAME-002 on query[1] — rename removed — nothing references the alias BigOpenOrders
SEL-002 on query[1] — two adjacent selections merged into one conjunctive predicate
PROJ-004 on query[1] — columns pruned at Orders: 3 of 4 read
```

## The plan

`explain()` shows the physical plan — join algorithms, what was folded into a backend
query, and, where the cost model has something to say, each step's estimated row count.
Like `render()`, it is staged, so this is the plan for the query as written:

```java
Relation joined = relix.relation("Orders")
        .join(relix.relation("Customers"),
              eq(attr("Orders.customer_id"), attr("Customers.customer_id")))
        .select(gt(attr("amount"), num(100)));

System.out.println(joined.explain());
```

```
Select
└─ Join INNER/HASH build=RIGHT
   ├─ Scan Orders
   └─ Scan Customers
```

Planning reads no rows. It asks the catalog for schemas and the cost model for
cardinalities, and that is all — which is why an `explain()` works against a database that
is not reachable, and why the connector a planning call builds refuses every open.

The same plan is available as JSON, for a tool rather than a person:

```java
System.out.println(relix.relation("σ amount > 100 (Orders)").explainJson());
```

```
{"op":"Select","estimatedRows":null,"schema":{"open":false,"columns":[{"name":"order_id","type":"N"},{"name":"customer_id","type":"N"},{"name":"status","type":"S"},{"name":"amount","type":"N"}]},"children":[{"op":"Scan","source":"Orders","estimatedRows":null,"schema":{"open":false,"columns":[{"name":"order_id","type":"N"},{"name":"customer_id","type":"N"},{"name":"status","type":"S"},{"name":"amount","type":"N"}]},"children":[]}]}
```

And as an object, when the interesting part is a number in it rather than the rendering:

```java
var planned = relix.relation("σ amount > 100 (Orders)").plan();

System.out.println(planned.plan().getClass().getSimpleName());
System.out.println(planned.estimates().rows(planned.plan()));
```

```
Select
OptionalLong.empty
```

`estimates()` answers an `OptionalLong`, and the empty case is load-bearing: **unknown is
not zero.** A relation nothing has measured and no statistic describes has no estimate, and
reporting one as `0` would be a confident wrong answer where an absent one is a true one.

## Both at once

`explain(listener)` gives the plan and the planner's decisions from **one** planning pass.
Two calls would report the same work twice, which is the sort of double-counting a trace is
supposed to prevent:

```java
import com.darkcollective.relix.events.QueryEvent;

List<QueryEvent> decisions = new java.util.ArrayList<>();
String plan = joined.explain(decisions::add);

System.out.println(plan.lines().findFirst().orElseThrow());
decisions.forEach(event ->
        System.out.println("  " + event.stage() + " " + event.code() + " " + event.description()));
```

```
Select
  PLAN JOIN INNER join: HASH, build=RIGHT
```

Nothing here is timed, and that is not an omission: planning and rewriting are decisions
taken before a row moves, and a duration on each would be noise on the feed rather than
information. Time is reported by the events a *run* produces — see
[Where the time went](results.md#where-the-time-went).

## The session itself

A session renders back as `.relix` text, so what it holds is inspectable as source rather
than only as objects:

```java
System.out.println(relix.definitions());
```

```
source Orders from csv("./orders.csv") { header: true, schema: { order_id: number, customer_id: number, status: string, amount: number } };
source Customers from csv("./customers.csv") { header: true, schema: { customer_id: number, name: string } };
BigOpenOrders := { σ status = "OPEN" (σ amount > 100 (Orders)) };
```

`statements()` is the same content as AST statements, for a program that would rather match
on it than read it.

`ir()` is the analyser's own report: every symbol with its heading, every view's expression
tree, and the root queries. It is the thing to print when a query resolves to something
other than what you expected, because it shows what each name was taken to mean. Its
symbol table is long — it lists the `relix.*` catalogs alongside your own declarations — so
this prints only the trees:

```java
String report = relix.ir();
System.out.println(report.substring(report.indexOf("── EXPRESSION TREES")));
```

```
── EXPRESSION TREES ────────────────────────────────────────────────────────────
BigOpenOrders [QR]  order_id:N  customer_id:N  status:S  amount:N
  σ status = "OPEN"
  └─ σ amount > 100
     └─ Orders [SRC]

════════════════════════════════════════════════════════════════════════════════
```

## What the engine is made of

`relix.version` is a relation, not a Java accessor, and it lists one row per component of
the running engine — itself, the facade, and every provider actually discovered on the
classpath:

```java
relix.relation("π component, kind (relix.version)").toList()
        .forEach(System.out::println);
```

```
(component=relix-engine, kind=engine)
(component=relix-builtin, kind=function-library)
(component=relix-embed, kind=facade)
(component=csv, kind=connector)
(component=gedcom, kind=connector)
(component=ojAlgo, kind=solver)
(component=org.h2.Driver, kind=driver)
```

Which providers are installed is otherwise unanswerable from inside a running program,
because discovery is a classpath scan and a missing provider fails nothing a compiler can
see. Being a relation rather than an accessor is what lets it be filtered and joined with
the operators already at hand — and `relix.version` is one of several such catalogs, all
queryable the same way. [The engine describes itself](introspection.md) is the tour of them.
