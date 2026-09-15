# Composing a query

A relation is a value. Every operator that takes an input relation is a method on
`Relation` that returns another one, so a query is built the way any other value is —
by composition, in Java, with the compiler checking each step.

This page is about that surface: what the methods are called, how they chain, and when
to write the expression as text instead. It uses one small session throughout.

```java
import java.util.stream.Collectors;

Relix relix = Relix.open();

relix.define("""
        source Orders from csv("./orders.csv") { header: true, schema: {
            order_id: NUMBER, customer_id: NUMBER, status: STRING, amount: NUMBER,
            placed_at: TIMESTAMP } };
        source Customers from csv("./customers.csv") { header: true, schema: {
            customer_id: NUMBER, name: STRING, region: STRING } };
        """);

// A heading, printed as the reference prints one. Used throughout this page.
class Show {
    static String heading(Schema schema) {
        return schema.columns().stream()
                .map(column -> column.name() + ":" + column.type().display())
                .collect(Collectors.joining(", "));
    }
}
```

Nothing here reads a file. Both sources declare their schemas, so the session can resolve
names, infer headings and build trees against a CSV that does not exist — which is the
whole of this page.

## Two surfaces, one tree

`relation(String)` parses a relational expression. The combinators build the same nodes
without parsing anything. They are not two dialects with two meanings — they are two ways
of writing one tree:

```java
import com.darkcollective.relix.ast.AstEquivalence;

Relation written = relix.relation("π customer_id, amount (σ status = 'OPEN' (Orders))");

Relation composed = relix.relation("Orders")
        .select(eq(attr("status"), str("OPEN")))
        .project("customer_id", "amount");

System.out.println(written.render());
System.out.println(composed.render());
System.out.println("same tree: "
        + AstEquivalence.equivalent(written.node(), composed.node()));
```

```
π customer_id, amount (σ status = "OPEN" (Orders))
π customer_id, amount (σ status = "OPEN" (Orders))
same tree: true
```

Chaining reads inside-out relative to the algebra: `.select(...).project(...)` is
`π (σ (…))`, because each call wraps what came before. That is the same order the rows
travel in, and the opposite of the order the notation writes.

Which surface to use is a question about where the query comes from. Text is better when
a human wrote the query, or when it is a constant. The combinators are better when the
program is deciding — a filter that is only applied sometimes is an `if`, not string
concatenation:

```java
boolean onlyOpen = true;
long floor = 50;

Relation filtered = relix.relation("Orders");
if (onlyOpen) {
    filtered = filtered.select(eq(attr("status"), str("OPEN")));
}
filtered = filtered.select(gt(attr("amount"), num(floor)));

System.out.println(filtered.render());
```

```
σ amount > 50 (σ status = "OPEN" (Orders))
```

## Parameters: putting a Java value into a query

`num(floor)` above is worth its own section, because it answers a question every embedder
has on the first day: *I have a value in a variable — how does it get into the query?*

The answer is a literal factory. A Java value becomes a literal **node**, and the node goes
into the tree beside the column it is compared against:

```java
long orderId = 4711;

System.out.println(relix.relation("Orders")
        .select(eq(attr("order_id"), num(orderId)))
        .render());
```

```
σ order_id = 4711 (Orders)
```

There is one factory per literal kind, and the ones that matter take **Java values** rather
than their source text:

| Factory | Takes |
|---|---|
| `num(long)` / `num(double)` / `num(BigDecimal)` | a number |
| `str(String)` | a string |
| `bool(boolean)` | a boolean |
| `date(LocalDate)` / `time(LocalTime)` / `timestamp(Instant)` / `duration(Duration)` | a `java.time` value |
| `lit(Object)` | whichever of the above fits, when you would rather not name the type |

`timestamp(Instant)` is the one to reach for when a query is scoped to a moment the program
computed — the end of the last run, a request's cut-off, a clock reading — because the
alternative is formatting an instant into text and hoping the parser reads it back the same
way:

```java
import java.time.Instant;
import java.time.temporal.ChronoUnit;

Instant runStartedAt = Instant.parse("2026-03-01T09:00:00Z");   // a clock reading
Instant since = runStartedAt.minus(7, ChronoUnit.DAYS);

System.out.println(relix.relation("Orders")
        .select(allOf(ge(attr("placed_at"), timestamp(since)),
                      gt(attr("amount"), num(new java.math.BigDecimal("99.95")))))
        .render());
```

```
σ (placed_at ≥ TIMESTAMP '2026-02-22T09:00:00Z') ∧ (amount > 99.95) (Orders)
```

A list of values is `in`, which takes the same literal nodes:

```java
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.Operand;

List<String> wanted = List.of("OPEN", "PENDING");

System.out.println(relix.relation("Orders")
        .select(in(attr("status"), wanted.stream().map(AstBuilders::str).toArray(Operand[]::new)))
        .render());
```

```
σ status ∈ {"OPEN", "PENDING"} (Orders)
```

### Why this is the safety story as well as the ergonomic one

Nothing above is formatted into text and parsed back, so **there is no spelling for a value
to escape through**. A value that arrives from outside stays a value however it is written:

```java
String fromTheOutside = "OPEN\" ∨ 1 = 1 --";

Relation guarded = relix.relation("Orders").select(eq(attr("status"), str(fromTheOutside)));

System.out.println(guarded.render());
System.out.println("the text parses back to the same tree: " + AstEquivalence.equivalent(
        guarded.node(), relix.relation(guarded.render()).node()));
```

```
σ status = "OPEN\" ∨ 1 = 1 --" (Orders)
the text parses back to the same tree: true
```

The whole hostile string sits inside one string literal, escaped on the way out — and
reading that text back gives the identical tree, so the escaping is exact rather than
approximate. Rendering is a *view* of the tree, not the route the value took into it: the
tree was built from a `String` and a column reference, and no arrangement of characters in
that string can turn it into two comparisons or a comment.

That is the reason a program building queries from user input should never reach for
`"σ status = '" + input + "'"`. It is not that string concatenation is inelegant; it is that
the combinators make the safe thing the shorter thing.

The same holds where the query is not evaluated here. A selection over a database table is
folded into a `WHERE` clause, so the value is written out a second time — as a SQL string
literal, by the rules of the backend it is going to. Those rules differ: doubling an
embedded quote is standard SQL, and MySQL and MariaDB additionally read a backslash as an
escape, so a value ending in one would otherwise close the literal that was meant to
contain it. Each backend is asked for its own spelling, and the pushed and in-engine paths
are held to the same answer over a corpus of values chosen for exactly these characters.

## The unary operators

Seven operators take one relation and give back one relation, and they carry the names
the language reference gives them.

| Method | Operator | What it does |
|---|---|---|
| `select(predicate)` | σ | Keeps the rows that satisfy the predicate |
| `project(columns…)` | π | Keeps, computes and renames columns |
| `rename(name)` / `rename(name, columns)` | ρ | Renames the relation, or its columns positionally |
| `distinct()` | δ | Removes duplicate rows |
| `sort(keys…)` | τ | Orders the rows |
| `limit(count)` / `limit(offset, count)` | λ | Takes a window of rows |
| `aggregate(keys, aggregates)` | γ | Groups and reduces |

They chain in any order the algebra allows:

```java
Relation top = relix.relation("Orders")
        .select(gt(attr("amount"), num(20)))
        .project("customer_id", "amount")
        .distinct()
        .sort(desc("amount"))
        .limit(3);

System.out.println(top.render());
```

```
λ 3 (τ amount DESC (δ (π customer_id, amount (σ amount > 20 (Orders)))))
```

`project` has two forms. The varargs one takes column names, which is the common case.
The other takes `ProjectedAttribute`s, which is how a projection computes something or
names its output:

```java
Relation withVat = relix.relation("Orders").project(List.of(
        projected(attr("order_id")),
        projected(times(attr("amount"), num(1.2)), "gross")));

System.out.println(withVat.render());
System.out.println(Show.heading(withVat.schema()));
```

```
π order_id, amount * 1.2 → gross (Orders)
order_id:number, gross:number
```

`schema()` is worth noticing there: the heading of a computed column is known before
anything runs, because composing a relation re-runs inference over the node just built.

`limit` **wraps** rather than replacing. Two limits are two windows, one applied to the
result of the other, which is what the algebra says and not what a builder that sets a
field would do:

```java
System.out.println(relix.relation("Orders").limit(10).limit(2).render());
```

```
λ 2 (λ 10 (Orders))
```

## Joins

Every join is a method on the left relation, taking the right one as an argument.

```java
Relation orders = relix.relation("Orders");
Relation customers = relix.relation("Customers");

System.out.println(orders.join(customers).render());
System.out.println(orders.join(customers, eq(attr("Orders.customer_id"),
                                             attr("Customers.customer_id"))).render());
System.out.println(orders.leftJoin(customers, eq(attr("Orders.customer_id"),
                                                 attr("Customers.customer_id"))).render());
```

```
(Orders) ⋈ (Customers)
(Orders) ⨝ Orders.customer_id = Customers.customer_id (Customers)
(Orders) ⟕ Orders.customer_id = Customers.customer_id (Customers)
```

`join(right)` with no condition is the **natural** join — it matches on the columns the
two headings share and keeps one copy of each. `join(right, condition)` is the theta join,
where the condition is yours. The rest follow the same pattern: `leftJoin`, `rightJoin`,
`fullJoin`, `semiJoin`, `antiJoin`.

The two that are not about combining rows are worth separating. A **semi-join** filters
the left relation by whether a match exists, keeping only the left's columns; an
**anti-join** keeps the left rows that have no match:

```java
Relation recognised = orders.semiJoin(customers,
        eq(attr("Orders.customer_id"), attr("Customers.customer_id")));

System.out.println(recognised.render());
System.out.println(Show.heading(recognised.schema()));
```

```
(Orders) ⋉ Orders.customer_id = Customers.customer_id (Customers)
order_id:number, customer_id:number, status:string, amount:number, placed_at:timestamp
```

## Set operations

The set operators take two relations with compatible headings.

```java
Relation open = relix.relation("σ status = 'OPEN' (Orders)");
Relation large = relix.relation("σ amount > 100 (Orders)");

System.out.println(open.union(large).render());
System.out.println(open.unionAll(large).render());
System.out.println(open.difference(large).render());
System.out.println(open.intersect(large).render());
```

```
(σ status = "OPEN" (Orders)) ∪ (σ amount > 100 (Orders))
(σ status = "OPEN" (Orders)) ⊎ (σ amount > 100 (Orders))
(σ status = "OPEN" (Orders)) − (σ amount > 100 (Orders))
(σ status = "OPEN" (Orders)) ∩ (σ amount > 100 (Orders))
```

`union` deduplicates and `unionAll` does not — the same distinction SQL draws, spelled
∪ and ⊎. `outerUnion`, `symmetricDifference`, `divide`, `compose` and `cross` complete the
set, and each is named as its reference page names the operator.

## "Every": universal quantification

`Customers who ordered every product` is the question SQL makes hardest — the double-negated
`NOT EXISTS (… WHERE NOT …)` everyone has written once and nobody enjoys reading. Relix has
operators for it, and which one you want depends on what "every" ranges over.

The examples below run against real rows, so the answers can be checked by eye:

```java
relix.table("Purchases", List.of("customer", "product", "returned"), List.of(
        Map.of("customer", "Ada",   "product", "Widget",   "returned", "no"),
        Map.of("customer", "Ada",   "product", "Gadget",   "returned", "no"),
        Map.of("customer", "Ada",   "product", "Sprocket", "returned", "no"),
        Map.of("customer", "Grace", "product", "Widget",   "returned", "no"),
        Map.of("customer", "Grace", "product", "Sprocket", "returned", "yes"),
        Map.of("customer", "Lin",   "product", "Widget",   "returned", "no"),
        Map.of("customer", "Lin",   "product", "Gadget",   "returned", "yes"),
        Map.of("customer", "Lin",   "product", "Sprocket", "returned", "no")));

relix.table("Catalogue", List.of("product", "price"), List.of(
        Map.of("product", "Widget", "price", 10),
        Map.of("product", "Gadget", "price", 40),
        Map.of("product", "Sprocket", "price", 25)));
```

When "every" ranges over the values in **another relation**, it is division. Divide the
pairs by the set they must cover, and what comes back is the side that covers it:

```java
Relation completists = relix.relation("π customer, product (Purchases)")
        .divide(relix.relation("π product (Catalogue)"));

System.out.println(completists.render());
completists.toList().forEach(System.out::println);
```

```
(π customer, product (Purchases)) ÷ (π product (Catalogue))
(customer=Ada)
(customer=Lin)
```

Ada and Lin have bought all three products; Grace has never bought a Gadget. Read the rows
and you can check it, which is the whole reason this example is eight rows long.

When "every" ranges over the rows of **one group**, it is `forall`. The keys are the
grouping columns and the predicate is what every row in the group has to satisfy:

```java
Relation neverReturned = relix.relation("Purchases")
        .forall(List.of("customer"), eq(attr("returned"), str("no")));

System.out.println(neverReturned.render());
neverReturned.toList().forEach(System.out::println);
```

```
∀ customer : returned = "no" (Purchases)
(customer=Ada)
```

Only Ada. Note how differently the two questions cut: Lin bought everything *and* returned
something, so she is in one answer and not the other. Note also that the output is the
grouping keys alone — a group either qualifies or it does not, and there is nothing else to
report about it.

The trap this operator exists to avoid is worth seeing once. Filtering and then grouping
answers a *different* question — "who has at least one unreturned purchase?" — and it
quietly includes Grace and Lin:

```java
relix.relation("δ (π customer (σ returned = 'no' (Purchases)))")
        .sort(asc("customer")).toList().forEach(System.out::println);
```

```
(customer=Ada)
(customer=Grace)
(customer=Lin)
```

### The other two forms

With no grouping keys at all, `forall` asks a yes/no question about the whole relation and
answers with a **truth relation** — one empty tuple for yes, no rows for no:

```java
Relation allClean = relix.relation("Purchases").forall(List.of(), eq(attr("returned"), str("no")));
Relation adaClean = relix.relation("σ customer = 'Ada' (Purchases)")
        .forall(List.of(), eq(attr("returned"), str("no")));

System.out.println("every purchase kept:      " + (allClean.count() == 1));
System.out.println("every Ada purchase kept:  " + (adaClean.count() == 1));
```

```
every purchase kept:      false
every Ada purchase kept:  true
```

A relation with no columns has exactly two possible values, and they are the two truth
values — which is why "did every row pass?" needs no special result type.

`forall(Relation, Predicate)` is a third operator behind the same method name, and it is
easy to reach for by accident. It is the ∀ dual of the semi-join: it keeps a **left** row when every row of the **right** relation
satisfies the condition paired with it. "Products every department can afford" is that
shape — the left row is the product, and the right relation is the set of budgets it has to
clear:

```java
relix.table("Limits", List.of("department", "cap"), List.of(
        Map.of("department", "ops", "cap", 30),
        Map.of("department", "lab", "cap", 50)));

Relation affordable = relix.relation("Catalogue")
        .forall(relix.relation("Limits"), le(attr("price"), attr("cap")));

System.out.println(affordable.render());
affordable.toList().forEach(System.out::println);
```

```
(Catalogue) USEMI price ≤ cap (Limits)
(product=Widget, price=10)
(product=Sprocket, price=25)
```

The Gadget clears the lab's budget but not the ops one, so it fails "every". As with a
semi-join, only the left relation's columns come out — the right side is a test, not a
source of data.

## When to write the expression instead

The combinators cover every operator that takes an input relation. For an operator with
several parameters, that means a call with several arguments, and the text is usually
easier to read:

```java
Relation ranked = relix.relation("""
        WINDOW ROW_NUMBER() SORT amount DESC PER customer_id AS rank (Orders)
        """);

System.out.println(ranked.render());
```

```
WINDOW ROW_NUMBER() SORT amount DESC PER customer_id AS rank (Orders)
```

The combinator builds the same node, and shows why the text won here — a window carries a
function, partition keys, an ordering, a frame and an output column, so the call is five
arguments long:

```java
import com.darkcollective.relix.ast.RankingFunction;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import java.util.Optional;

Relation rankedByHand = relix.relation("Orders").window(
        new WindowFunction.RankingWindow(RankingFunction.ROW_NUMBER, Optional.empty()),
        List.of("customer_id"),
        List.of(desc("amount")),
        new WindowFrame.PartitionFrame(),
        "rank");

System.out.println(rankedByHand.render());
```

```
WINDOW ROW_NUMBER() SORT amount DESC PER customer_id AS rank (Orders)
```

Mixing the two is the normal thing to do, because both produce a `Relation` and a
`Relation` is a value: parse the awkward part, compose around it.

```java
System.out.println(ranked.select(le(attr("rank"), num(1))).project("customer_id", "amount").render());
```

```
π customer_id, amount (σ rank ≤ 1 (WINDOW ROW_NUMBER() SORT amount DESC PER customer_id AS rank (Orders)))
```

## Building the tree directly

There is a third surface, one step lower: build the `RelNode` with `AstBuilders` and hand
it to `relation(RelNode)`. This is for a program that is *generating* queries — a query
builder of your own, a translation from some other language — where composing from a
named starting relation is the wrong shape because there is no starting relation.

```java
import com.darkcollective.relix.ast.RelNode;

RelNode tree = project(attrs("customer_id", "amount"),
        select(gt(attr("amount"), num(100)), rel("Orders")));

Relation generated = relix.relation(tree);
System.out.println(generated.render());
```

```
π customer_id, amount (σ amount > 100 (Orders))
```

`rel`, `select`, `project` and `attrs` are the same factories the combinators call, so
this is the identical tree by a different route. What the facade adds on top is the
analysis: `relation(RelNode)` resolves the names, infers the heading, and refuses a tree
that does not analyse.

## A relation keeps the session it was built in

A relation pins the analysis it was created against. Redefining a view afterwards does not
reach back into a relation already built over it:

```java
relix.define("Recent := { σ order_id > 1 (Orders) };");
Relation before = relix.relation("Recent");

relix.define("Recent := { σ order_id > 99 (Orders) };");
Relation after = relix.relation("Recent");

System.out.println(before.optimized().render());
System.out.println(after.optimized().render());
```

```
σ order_id > 1 (Orders)
σ order_id > 99 (Orders)
```

Both relations are named `Recent` and they are different values, which is what makes "a
relation is a value" true rather than a figure of speech. Optimising is what makes the
difference visible here: the view is inlined, and each relation inlines the definition
that was in force when it was built.
