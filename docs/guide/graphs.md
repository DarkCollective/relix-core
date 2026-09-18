# Recursion and graphs

An edge list is a relation, so reachability, components, paths and shortest routes are
operators over it rather than a separate graph library. This page runs each one against a
small network.

```java
Relix relix = Relix.open();

// A ─→ B ─→ C ─→ D,  with a direct A ─→ C shortcut, and an isolated pair E ─→ F.
relix.table("Edges", List.of("src", "dst", "cost"), List.of(
        Map.of("src", "A", "dst", "B", "cost", 4),
        Map.of("src", "B", "dst", "C", "cost", 3),
        Map.of("src", "C", "dst", "D", "cost", 2),
        Map.of("src", "A", "dst", "C", "cost", 9),
        Map.of("src", "E", "dst", "F", "cost", 1)));

relix.relation("Edges").toList().forEach(System.out::println);
```

```
(src=A, dst=B, cost=4)
(src=B, dst=C, cost=3)
(src=C, dst=D, cost=2)
(src=A, dst=C, cost=9)
(src=E, dst=F, cost=1)
```

## Reachability

`closure(from, to)` is the transitive closure: every pair connected by one or more edges.

```java
Relation reachable = relix.relation("π src, dst (Edges)").closure("src", "dst");

System.out.println(reachable.render());
reachable.toList().forEach(System.out::println);
```

```
CLOSURE src, dst (π src, dst (Edges))
(src=A, dst=B)
(src=B, dst=C)
(src=C, dst=D)
(src=A, dst=C)
(src=E, dst=F)
(src=B, dst=D)
(src=A, dst=D)
```

The projection in front of it is doing real work: the closure is over `(src, dst)` pairs,
and carrying `cost` along would ask what the cost of a two-hop pair is — a question the
operator does not answer and `trace` does.

The reflexive form adds every node's pair with itself, which is what you want when "can A
reach A?" should be true:

```java
System.out.println(relix.relation("π src, dst (Edges)")
        .closure("src", "dst", true)
        .count() + " reflexive pairs");
```

```
13 reflexive pairs
```

## Components

`cluster` labels each node with the connected component it belongs to, which is how a
graph is partitioned without knowing in advance how many parts it has.

```java
Relation components = relix.relation("π src, dst (Edges)")
        .cluster("src", "dst", "component");

System.out.println(components.render());
components.toList().forEach(System.out::println);
```

```
CLUSTER src, dst AS component (π src, dst (Edges))
(src=A, component=1)
(src=B, component=1)
(src=C, component=1)
(src=D, component=1)
(src=E, component=2)
(src=F, component=2)
```

## Paths, with their length

`closure` says *whether* two nodes are connected. `path` says how far apart they are, and
takes a hop range so a query can ask only for what it needs:

```java
Relation twoHops = relix.relation("π src, dst (Edges)")
        .path("src", "dst", 2, 3, "hops");

System.out.println(twoHops.render());
twoHops.toList().forEach(System.out::println);
```

```
PATH src, dst HOPS 2 TO 3 AS hops (π src, dst (Edges))
(src=A, dst=D, hops=2)
(src=B, dst=D, hops=2)
```

Bounding the hops is not only a filter on the answer — it bounds the work, so a range is
worth stating whenever the question has one.

## The cheapest route

`trace` walks a weighted edge relation and gives back, for each reachable pair, the best
route and the sequence of nodes it goes through:

```java
import com.darkcollective.relix.ast.ObjectiveSense;

Relation cheapest = relix.relation("Edges")
        .trace("src", "dst", "cost", ObjectiveSense.MINIMIZE, "route");

System.out.println(cheapest.render());
cheapest.toList().forEach(System.out::println);
```

```
TRACE src, dst VIA cost MINIMIZE AS route (Edges)
(src=A, dst=B, cost=4, route=[A, B])
(src=B, dst=C, cost=3, route=[B, C])
(src=C, dst=D, cost=2, route=[C, D])
(src=E, dst=F, cost=1, route=[E, F])
(src=A, dst=C, cost=7, route=[A, B, C])
(src=B, dst=D, cost=5, route=[B, C, D])
(src=A, dst=D, cost=9, route=[A, B, C, D])
```

`A → C` is where that pays: there is a direct edge costing 9 and a two-hop route costing
7, and the cheaper one wins. The route column is an array, read from a row with
`Tuple.array`:

```java
Tuple aToD = cheapest.toList().stream()
        .filter(row -> row.string("src").equals("A") && row.string("dst").equals("D"))
        .findFirst()
        .orElseThrow();

System.out.println(aToD.array("route").stream().map(Value::asDisplayString).toList());
```

```
[A, B, C, D]
```

## General recursion

`CLOSURE`, `CLUSTER` and `PATH` are the specialised, fast forms. `FIX` is the general one:
a base relation and a step that derives more rows from what has been found so far, repeated
until nothing new appears.

The step refers to the accumulated result by the name `FIX` binds, and that name means
nothing outside the operator — so a step is written inside its binder rather than composed
separately:

```java
Relation explosion = relix.relation("""
        FIX Reach (
            π src, dst (Edges),
            π src, dst2 → dst (Reach ⋈ π src → dst, dst → dst2 (Edges))
        )
        """);

explosion.toList().forEach(System.out::println);
```

```
(src=A, dst=B)
(src=B, dst=C)
(src=C, dst=D)
(src=A, dst=C)
(src=E, dst=F)
(src=B, dst=D)
(src=A, dst=D)
```

That is the closure again, written out longhand — which is the useful way to read `FIX`:
whatever a specialised operator does, this can express, and rather more besides.

The same tree can be built without the grammar, for a program generating recursive queries.
`fixpoint` is the binder and `recRef` is the reference to it, and the reference only
analyses because the binder is there to bind it:

```java
import com.darkcollective.relix.ast.RelNode;

RelNode edges = project(attrs("src", "dst"), rel("Edges"));
RelNode shifted = project(
        List.of(projected(attr("src"), "dst"), projected(attr("dst"), "dst2")),
        rel("Edges"));
RelNode step = project(
        List.of(projected(attr("src")), projected(attr("dst2"), "dst")),
        naturalJoin(recRef("Reach"), shifted));

System.out.println(relix.relation(fixpoint("Reach", edges, step)).count() + " pairs");
```

```
7 pairs
```

## When recursion does not terminate

`FIX` is a *least* fixpoint under set semantics, so a duplicate row is never re-added and
cyclic data terminates on its own. What does not terminate is a step that keeps producing
genuinely new rows — counting paths around a cycle, for instance — and for that the session
carries a round limit:

```java
try (Relix bounded = Relix.builder().maxFixpointRounds(2).build()) {
    bounded.table("Chain", List.of("src", "dst"), List.of(
            Map.of("src", "A", "dst", "B"),
            Map.of("src", "B", "dst", "C"),
            Map.of("src", "C", "dst", "D")));

    bounded.relation("""
            FIX Reach (
                Chain,
                π src, dst2 → dst (Reach ⋈ π src → dst, dst → dst2 (Chain))
            )
            """).toList();
} catch (RuntimeException e) {
    System.out.println(e.getClass().getSimpleName());
    System.out.println(e.getMessage());
}
```

```
QueryExecutionException
FIX 'Reach' exceeded 2 iteration round(s); add a bound (e.g. λ n) below it, or increase --max-fixpoint-rounds
```

The limit fails loudly rather than truncating, because a silently truncated fixpoint is a
wrong answer that looks like a right one.

`maxFixpointRounds` bounds how *deep* the recursion goes. Its companion,
`maxMaterializedRows`, bounds how *much* one operator holds — and for a `FIX` that is every
row it has derived so far, which is what a runaway recursion actually fills memory with.
Reach for the round limit when you know roughly how deep a terminating answer should be and
want to catch the case where you were wrong; reach for the row limit when you do not, since
it bounds the memory rather than the depth. Setting both is reasonable, and either failure
names the operator it stopped.

Notice the exception type. A query that does not parse or does not analyse is refused with
`RelixException`, because the facade owns that refusal. A failure that only appears once
rows are moving comes back as the engine's own exception — there is nothing the facade
could add to it, and flattening every cause into one type would take the diagnosis away.
Catch `RuntimeException` around a terminal if you mean to catch everything.
