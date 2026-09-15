# The engine describes itself

Everything a program might want to know about a session — which relations exist, what
columns they carry, which functions are installed, what the last run did — is exposed in
the reserved `relix.*` namespace **as relations**.

That is a deliberate choice rather than a shortcut. There is no Java accessor beside these
returning the same facts, because a second surface is a second thing to keep true, and two
answers to one question eventually disagree. Making the catalog a relation means it is
filtered, joined, aggregated and sorted with the operators already learned, and a catalog
query composes with an ordinary one because there is no seam between them.

```java
Relix relix = Relix.open();

relix.define("""
        source Orders from csv("./orders.csv") { header: true, schema: {
            order_id: NUMBER, customer_id: NUMBER, amount: NUMBER, status: STRING } };
        source Customers from csv("./customers.csv") { header: true, schema: {
            customer_id: NUMBER, name: STRING, region: STRING } };
        source Products from csv("./products.csv") { header: true, schema: {
            sku: STRING, price: NUMBER } };

        Open      := { σ status = "OPEN" (Orders) };
        ByRegion  := { γ region, SUM(amount) → revenue (Open ⋈ Customers) };
        Stale     := { σ amount < 0 (Orders) };
        """);
```

Nothing there reads a file. Both sources declare their schemas, so everything on this page
is answered from what the session was told.

## What is actually installed

Providers — connectors, function libraries, solvers, JDBC drivers — are found by a
`ServiceLoader` scan. A missing one fails nothing a compiler can see and nothing a unit
test can see; it fails the first query that needs it, at run time, in production.
`relix.version` is how a program asks instead of assuming:

```java
relix.relation("τ kind, component (relix.version)").toList().forEach(System.out::println);
```

```
(component=csv, kind=connector, version=1.0-SNAPSHOT)
(component=org.h2.Driver, kind=driver, version=2.2)
(component=relix-engine, kind=engine, version=1.0-SNAPSHOT)
(component=relix-embed, kind=facade, version=unknown)
(component=relix-builtin, kind=function-library, version=1.0-SNAPSHOT)
(component=ojAlgo, kind=solver, version=1.0-SNAPSHOT)
```

One row per component the process has loaded. `engine` and `facade` are Relix's own; the
rest were contributed by whatever assembled the classpath.

Because it is a relation, the startup check a program actually wants is a query:

```java
List<String> connectors = relix.relation("π component (σ kind = 'connector' (relix.version))")
        .toList().stream().map(row -> row.string("component")).sorted().toList();

System.out.println("connectors: " + connectors);
System.out.println("this session can read CSV: " + connectors.contains("csv"));
```

```
connectors: [csv]
this session can read CSV: true
```

That is a real check rather than a decorative one: the tokens listed are the ones a
`connection` declaration may name, and a session whose classpath is missing the connector
it needs fails at the first query that wants it — unless it asks first.

A `version` of `unknown` is not a failure — it means the packaging carries none, which is
normal for loose classes on a classpath. The name is the answer; the version sharpens it.

## What the session knows about

`relix.relations` has one row per relation the session can name, and `relix.columns` one
row per column of each:

```java
relix.relation("π name, kind, materialization (τ name (relix.relations))")
        .toList().forEach(System.out::println);
```

```
(name=ByRegion, kind=QR, materialization=bag)
(name=Customers, kind=SRC, materialization=stream)
(name=Open, kind=QR, materialization=stream)
(name=Orders, kind=SRC, materialization=stream)
(name=Products, kind=SRC, materialization=stream)
(name=Stale, kind=QR, materialization=stream)
```

`kind` distinguishes what a relation *is* — `SRC` a declared source, `QR` a view defined in
the session, `SYS` one of these catalogs. A tool built on Relix enumerates what it can
query by reading exactly this.

The two obvious questions about columns are the same relation read from its two sides, and
both ship as table-valued functions:

```java
relix.relation("relix.schema(\"Customers\")").toList().forEach(System.out::println);
relix.relation("relix.find(\"customer_id\")").toList().forEach(System.out::println);
```

```
(column=customer_id, type=N, ordinal=0)
(column=name, type=S, ordinal=1)
(column=region, type=S, ordinal=2)
(relation=Orders, type=N)
(relation=Customers, type=N)
(relation=Open, type=N)
(relation=Stale, type=N)
```

`relix.schema(r)` asks what columns a relation has; `relix.find(c)` asks which relations
have a column. Both are ordinary views over `relix.columns` — there is no new language
construct, and you can read their definitions the same way you read your own.

## Composing a catalog query with an ordinary one

The catalogs being relations is not a stylistic point. It means a program can write a rule
about its own schema and *evaluate* it, rather than walking an object graph in Java:

```java
Relation missingKey = relix.relation("""
        (π name (σ kind = "SRC" (relix.relations))) − (π relation → name (relix.find("customer_id")))
        """);

System.out.println(missingKey.render());
List<String> offenders = missingKey.toList().stream().map(t -> t.string("name")).toList();
System.out.println(offenders.isEmpty()
        ? "every source carries customer_id"
        : "sources missing customer_id: " + offenders);
```

```
(π name (σ kind = "SRC" (relix.relations))) − (π relation → name (relix.find("customer_id")))
sources missing customer_id: [Products]
```

A set difference *is* the rule: subtract the relations that have the column from the ones
that should, and whatever is left is the violation. `Products` is a catalogue of stock and
genuinely has no customer, which is the other half of a rule like this — it tells you where
to look, and you decide. The same shape answers "which relations does nothing reference?" and "does anything depend on this
one?" — which ship as views too, over the dependency graph the analyser already built:

```java
System.out.println("unused:   " + relix.relation("τ name (relix.unused)").toList());
System.out.println("upstream: " + relix.relation("relix.deps(\"ByRegion\")").toList());
System.out.println("impact:   " + relix.relation("relix.impact(\"Orders\")").toList());
System.out.println("cycles:   " + relix.relation("relix.cycles").toList());
```

```
unused:   [(name=ByRegion), (name=Products), (name=Stale)]
upstream: [(depends_on=Open), (depends_on=Customers), (depends_on=Orders)]
impact:   [(dependent=Open), (dependent=Stale), (dependent=ByRegion)]
cycles:   []
```

`Stale` is defined and referenced by nothing, which is what `relix.unused` is for; the
top-level outputs appear there too, by construction. `relix.impact` is the blast radius of
a change — everything that would have to be re-checked if `Orders` moved.

## The installed functions

`relix.functions` carries one row per function the session resolves against, with the
properties the optimizer reads:

```java
relix.relation("γ category, COUNT(*) → n (relix.functions)")
        .sort(asc("category"))
        .toList()
        .forEach(System.out::println);
```

```
(category=conditional, n=3)
(category=conversion, n=3)
(category=datetime, n=16)
(category=math, n=15)
(category=nested, n=1)
(category=string, n=13)
(category=typecheck, n=2)
```

The properties are the interesting columns, because they are what decides whether a call
can be folded into a backend query or hoisted out of a loop. A function that reads the
clock or the random-number generator is neither pure nor deterministic, and the catalog
says so:

```java
relix.relation("""
        π name, pure, deterministic
          (σ pure = "false" ∨ deterministic = "false" (relix.functions))
        """).sort(asc("name")).toList().forEach(System.out::println);
```

```
(name=CURRENT_DATE, pure=false, deterministic=false)
(name=CURRENT_TIME, pure=false, deterministic=false)
(name=NOW, pure=false, deterministic=false)
(name=Rand, pure=false, deterministic=false)
```

A library of your own appears here exactly as the shipped ones do — same columns, same
resolution, its own category:

```java
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.function.FunctionLibrary;
import com.darkcollective.relix.function.FunctionSignature;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.function.StrictScalarFunction;
import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import java.math.BigDecimal;
import java.util.Set;

class WithVat implements StrictScalarFunction {
    public FunctionSignature signature() {
        return FunctionSignature.of("WithVat", ScalarType.NUMBER, "pricing",
                Set.of(FunctionProperty.PURE, FunctionProperty.DETERMINISTIC),
                new ParameterDefinition("amount", ScalarType.NUMBER));
    }

    public Value invoke(FunctionContext context, List<Value> arguments) {
        return new com.darkcollective.relix.value.NumberValue(
                ((com.darkcollective.relix.value.NumberValue) arguments.getFirst())
                        .value().multiply(new BigDecimal("1.20")));
    }
}

relix.functions(new FunctionLibrary() {
    public String name() {
        return "pricing";
    }

    public List<ScalarFunction> scalarFunctions() {
        return List.of(new WithVat());
    }
});

relix.relation("σ category = 'pricing' (relix.functions)").toList()
        .forEach(System.out::println);
```

```
(name=WithVat, category=pricing, arity=1, max_arity=1, return_type=N, pure=true, deterministic=true, idempotent=false, is_builtin=true)
```

Being indistinguishable from a shipped function is the provider seam working rather than an
oversight: anything the bundled library can do, an installed one can. The line `is_builtin`
draws is a different one — between a function the session **resolved**, shipped or installed
alike, and one the script **declared**:

```java
relix.define("def Discount(price: NUMBER) : NUMBER := { price * 0.9 };");

relix.relation("""
        π name, category, is_builtin (σ category ∈ {"pricing", "user"} (relix.functions))
        """).sort(asc("name")).toList().forEach(System.out::println);
```

```
(name=Discount, category=user, is_builtin=false)
(name=WithVat, category=pricing, is_builtin=true)
```

## What the last run did

The other catalogs answer *what is*. `relix.events` answers *what happened* — the same feed
of engine decisions that a trace prints, as rows.

It is necessarily the **previous** run's feed, and the reason is worth understanding rather
than working around. A statement's events do not exist until it has been optimized, planned
and executed, and all three happen after the analysis that would have to register the
relation the statement is selecting from. A statement therefore cannot observe itself, and
a session that has run nothing has nothing to show:

```java
System.out.println(relix.relation("relix.events").count() + " events");
```

```
0 events
```

In Java that is rarely the route you want anyway, because a run hands its own feed straight
back. `run()` returns the rows **and** the events that produced them:

```java
relix.table("Sales", List.of("region", "amount"), List.of(
        Map.of("region", "North", "amount", 100),
        Map.of("region", "South", "amount", 250)));

Rows result = relix.relation("γ region, SUM(amount) → total (Sales)").run();

System.out.println(result.rows());
result.events().forEach(e -> System.out.println(e.stage() + " " + e.code() + " — " + e.description()));
```

```
[(region=North, total=100), (region=South, total=250)]
EXECUTE SCAN — scanned 2 rows
EXECUTE MATERIALIZE — buffered 2 rows for Aggregate
EXECUTE ROWS — query delivered 2 rows
```

Every stage the run actually reached is in there. That one only scanned, buffered and
executed, since there was nothing to rewrite; the query below has a view in it, so its feed
carries the `OPTIMIZE` rows too.

The middle line is the one to look for when a query is heavier than it should be. A `γ`
cannot emit its first row until it has read its last, so it holds its whole input in memory,
and `MATERIALIZE` says which operator did that and how many rows it held — the count is on
the event as a number (`metrics().rows()`), not only in the sentence, and beside it
`metrics().duration()` says how long that operator spent filling the buffer. Two rows here;
on a query that is about to exhaust the heap it is the line that names the operator
responsible.
[Size, and what fits in memory](results.md#size-and-what-fits-in-memory) covers the cap that
stops one before it does.

The catalog relation earns its place when the feed has to be *queried* rather than read —
which is what an interactive host does, keeping one statement's events and handing them to
the next. `sessionEvents` on the builder is that seam:

```java
relix.define("Totals := { γ region, SUM(amount) → total (Sales) };");
Rows report = relix.relation("σ total > 50 (Totals)").run();

try (Relix next = Relix.builder().sessionEvents(report.events()).build()) {
    next.relation("γ stage, COUNT(*) → events (relix.events)")
            .sort(asc("stage"))
            .toList()
            .forEach(System.out::println);

    next.relation("π code, description (relix.rules)").toList()
            .forEach(System.out::println);
}
```

```
(stage=EXECUTE, events=3)
(stage=OPTIMIZE, events=2)
(code=INLINE-001, description=view 'Totals' inlined)
(code=RENAME-002, description=rename removed — nothing references the alias Totals)
```

`relix.rules` is the `OPTIMIZE` slice of the same feed — defined as a view over it rather
than computed separately, so the two cannot disagree about what fired. Sorting, grouping and
filtering a trace is what having it as rows buys; reading one run's decisions in Java is
`events()` and `rewrites()` on the relation itself, which
[Looking at what the engine did](inspecting.md#the-rewrite) covers.

One consequence of the scoping catches everyone once: **the query that reads the feed is
itself a run**, so a feed is readable once. Take the slices you want out of it in one query,
or re-run the statement that produced it.
