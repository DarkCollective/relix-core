# Aggregation and analytics

Grouping, ranking, running totals, top-n-per-group. This page runs every example against
real rows, so each one shows both the expression and the result it produces.

```java
import com.darkcollective.relix.ast.AggregateOperator;

Relix relix = Relix.open();

relix.table("Sales", List.of("day", "region", "rep", "amount"), List.of(
        Map.of("day", 1, "region", "North", "rep", "Ada", "amount", 100),
        Map.of("day", 1, "region", "North", "rep", "Grace", "amount", 250),
        Map.of("day", 2, "region", "North", "rep", "Ada", "amount", 75),
        Map.of("day", 2, "region", "South", "rep", "Lin", "amount", 300),
        Map.of("day", 3, "region", "South", "rep", "Lin", "amount", 120),
        Map.of("day", 3, "region", "South", "rep", "Omar", "amount", 40)));

relix.relation("Sales").toList().forEach(System.out::println);
```

```
(day=1, region=North, rep=Ada, amount=100)
(day=1, region=North, rep=Grace, amount=250)
(day=2, region=North, rep=Ada, amount=75)
(day=2, region=South, rep=Lin, amount=300)
(day=3, region=South, rep=Lin, amount=120)
(day=3, region=South, rep=Omar, amount=40)
```

`Tuple`'s own `toString` is what prints those rows, which keeps the examples below about
the query rather than about formatting.

## Grouping

`aggregate(keys, aggregates)` is γ. It takes the grouping columns and the reductions in
**one call**, because γ is one operator — there is no separate "group by" step to attach
an aggregate to later.

```java
Relation byRegion = relix.relation("Sales").aggregate(
        List.of("region"),
        List.of(agg(AggregateOperator.SUM, "amount", "total"),
                agg(AggregateOperator.COUNT, "amount", "orders")));

System.out.println(byRegion.render());
byRegion.toList().forEach(System.out::println);
```

```
γ region, SUM(amount) → total, COUNT(amount) → orders (Sales)
(region=North, total=425, orders=3)
(region=South, total=460, orders=3)
```

The alias is the third argument to `agg`. Leave it off and the output column is named
after the operator and its argument:

```java
Relation unaliased = relix.relation("Sales")
        .aggregate(List.of("region"), List.of(agg(AggregateOperator.AVG, "amount")));

unaliased.toList().forEach(System.out::println);
```

```
(region=North, avg_amount=141.6666666667)
(region=South, avg_amount=153.3333333333)
```

Grouping by nothing reduces the whole relation to one row. That is a different thing from
an empty result, and the distinction matters: a count of nothing is a row saying zero.

```java
relix.relation("σ amount > 1000 (Sales)")
        .aggregate(List.of(), List.of(agg(AggregateOperator.COUNT, "amount", "n")))
        .toList()
        .forEach(System.out::println);
```

```
(n=0)
```

### Grouping and aggregating by an expression

A grouping key is an expression, not only a column name, and so is an aggregate's
argument. `aggregateBy` takes `GroupingKey`s for the first and `aggOf` builds the second:

```java
Relation banded = relix.relation("Sales").aggregateBy(
        List.of(key(func("IIf", condition(gt(attr("amount"), num(100))), str("large"), str("small")), "band")),
        List.of(aggOf(AggregateOperator.SUM, times(attr("amount"), num(2)))));

System.out.println(banded.render());
banded.toList().forEach(System.out::println);
```

```
γ IIf((amount > 100), "large", "small") → band, SUM(amount * 2) (Sales)
(band=small, sum_expr=430)
(band=large, sum_expr=1340)
```

Three things are worth noting there. The key is a function call over the row, so the
grouping is computed rather than looked up; `SUM(amount * 2)` reduces an expression, which
is why an aggregate takes an operand rather than a column name; and `condition(...)` is
what puts a predicate where an operand is expected — `IIf` takes a boolean argument, and a
comparison is a `Predicate` until it is wrapped.

The unaliased output column is `sum_expr` rather than `sum_amount`, because the argument is
an expression and there is no column name to derive one from. Alias anything you intend to
read back by name.

### NULLs

Aggregates follow the SQL rule: a row whose argument is NULL is skipped, so `COUNT(expr)`
counts non-NULL values and `SUM` of nothing at all is NULL rather than zero. The two rows
below that omit `value` are how a NULL is written when the heading is stated.

```java
relix.table("Readings", List.of("sensor", "value"), List.of(
        Map.of("sensor", "a", "value", 10),
        Map.of("sensor", "a"),
        Map.of("sensor", "b")));

relix.relation("Readings").aggregate(
                List.of("sensor"),
                List.of(agg(AggregateOperator.COUNT, "value", "n"),
                        agg(AggregateOperator.SUM, "value", "total")))
        .toList()
        .forEach(System.out::println);
```

```
(sensor=a, n=1, total=10)
(sensor=b, n=0, total=NULL)
```

`COLLECT` is the deliberate exception — it keeps NULLs, because gathering a group's values
into an array is a different question from reducing them.

## Ranking and running totals

A window function adds a column to every row rather than reducing the rows away. All three
families are the `window` combinator, differing in the function they carry.

**Ranking** — a position within each partition:

```java
import com.darkcollective.relix.ast.RankingFunction;
import com.darkcollective.relix.ast.WindowFrame;
import com.darkcollective.relix.ast.WindowFunction;
import java.util.Optional;

Relation ranked = relix.relation("Sales").window(
        new WindowFunction.RankingWindow(RankingFunction.ROW_NUMBER, Optional.empty()),
        List.of("region"),
        List.of(desc("amount")),
        new WindowFrame.PartitionFrame(),
        "rank");

System.out.println(ranked.render());
ranked.toList().forEach(System.out::println);
```

```
WINDOW ROW_NUMBER() SORT amount DESC PER region AS rank (Sales)
(day=1, region=North, rep=Grace, amount=250, rank=1)
(day=1, region=North, rep=Ada, amount=100, rank=2)
(day=2, region=North, rep=Ada, amount=75, rank=3)
(day=2, region=South, rep=Lin, amount=300, rank=1)
(day=3, region=South, rep=Lin, amount=120, rank=2)
(day=3, region=South, rep=Omar, amount=40, rank=3)
```

**A running total** — the same operator with an aggregate function and a cumulative frame:

```java
Relation running = relix.relation("Sales").window(
        new WindowFunction.AggregateWindow(AggregateOperator.SUM, attr("amount")),
        List.of("region"),
        List.of(asc("day")),
        new WindowFrame.CumulativeFrame(),
        "running_total");

running.toList().forEach(System.out::println);
```

```
(day=1, region=North, rep=Ada, amount=100, running_total=100)
(day=1, region=North, rep=Grace, amount=250, running_total=350)
(day=2, region=North, rep=Ada, amount=75, running_total=425)
(day=2, region=South, rep=Lin, amount=300, running_total=300)
(day=3, region=South, rep=Lin, amount=120, running_total=420)
(day=3, region=South, rep=Omar, amount=40, running_total=460)
```

The frame is what separates a running total from a moving one. `CumulativeFrame` is
everything up to this row; `BoundedFrame(n)` is the last *n* rows, which is a moving
average; `PartitionFrame` is the whole partition, which is what a ranking function needs.

**An offset function** reads another row of the same partition:

```java
import com.darkcollective.relix.ast.OffsetFunction;

Relation withPrevious = relix.relation("Sales").window(
        new WindowFunction.OffsetWindow(OffsetFunction.LAG, attr("amount"),
                Optional.of(num(1)), Optional.empty()),
        List.of("region"),
        List.of(asc("day")),
        new WindowFrame.PartitionFrame(),
        "previous");

withPrevious.toList().forEach(System.out::println);
```

```
(day=1, region=North, rep=Ada, amount=100, previous=NULL)
(day=1, region=North, rep=Grace, amount=250, previous=100)
(day=2, region=North, rep=Ada, amount=75, previous=250)
(day=2, region=South, rep=Lin, amount=300, previous=NULL)
(day=3, region=South, rep=Lin, amount=120, previous=300)
(day=3, region=South, rep=Omar, amount=40, previous=120)
```

Where a window carries this many parameters, the language spelling is often the clearer
one, and both produce the same tree:

```java
System.out.println(relix.relation(
        "WINDOW LAG(amount, 1) SORT day PER region AS previous (Sales)").render());
```

```
WINDOW LAG(amount, 1) SORT day PER region AS previous (Sales)
```

## Top n per group

Ranking and then filtering on the rank is the usual way to express "the best three per
region", and it works. `top` says it directly, in one operator the planner can act on:

```java
Relation best = relix.relation("Sales").top(List.of("region"), List.of(desc("amount")), 1);

System.out.println(best.render());
best.toList().forEach(System.out::println);
```

```
TOP 1 amount DESC PER region (Sales)
(day=1, region=North, rep=Grace, amount=250)
(day=2, region=South, rep=Lin, amount=300)
```

The grouping list is what makes it per-group. Pass an empty list and it is the top rows of
the whole relation, which is `sort(...).limit(n)` said as one operator.

## Reshaping

`pivot` spreads one column's values across the heading, and `unpivot` folds a run of
columns back into name/value pairs.

```java
Relation pivoted = relix.relation("Sales")
        .aggregate(List.of("day", "region"), List.of(agg(AggregateOperator.SUM, "amount", "total")))
        .pivot("total", "region", List.of("day"));

System.out.println(pivoted.render());
pivoted.toList().forEach(System.out::println);
```

```
PIVOT total BY region PER day (γ day, region, SUM(amount) → total (Sales))
(day=1, North=350, South=NULL)
(day=2, North=75, South=300)
(day=3, North=NULL, South=160)
```

A pivot's heading depends on the *data* — one column per distinct value of the key column
— so it is one of the few operators whose schema cannot be known before it runs. The
analyser types it as an open heading and the executor fills it in.

## What each of these means

The semantics — which frames are legal, how ties rank, what a pivot does with a value that
appears twice — belong to the language reference, and each combinator here carries the
name of the page that answers for it.
