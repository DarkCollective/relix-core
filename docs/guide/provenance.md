# Provenance: where a row came from

"Which source rows produced this answer, and how many ways?" is a question about a result
that the result itself does not carry. Relix answers it two ways: as an operator inside a
query, and as a whole-relation annotation over a semiring.

```java
Relix relix = Relix.open();

relix.table("Orders", List.of("order_id", "customer_id", "amount"), List.of(
        Map.of("order_id", 1, "customer_id", 10, "amount", 100),
        Map.of("order_id", 2, "customer_id", 11, "amount", 250),
        Map.of("order_id", 3, "customer_id", 10, "amount", 75)));

relix.table("Customers", List.of("customer_id", "region"), List.of(
        Map.of("customer_id", 10, "region", "North"),
        Map.of("customer_id", 11, "region", "South")));
```

## Lineage as a column

`why()` is the ω operator. It passes its input through unchanged and adds one nested
column, `provenance`, holding the rows that produced each result:

```java
Relation joined = relix.relation("Orders")
        .join(relix.relation("Customers"),
              eq(attr("Orders.customer_id"), attr("Customers.customer_id")))
        .project("order_id", "region");

Relation explained = joined.why();

System.out.println(explained.render());
explained.schema().columns().forEach(column ->
        System.out.println("  " + column.name() + " : " + column.type().display()));
```

```
ω (π order_id, region ((Orders) ⨝ Orders.customer_id = Customers.customer_id (Customers)))
  order_id : number
  region : string
  provenance : any
```

The added column is `ANY`, because its shape is a document rather than a scalar. What it
holds is one entry per *derivation* — a distinct way the row could have been produced —
each naming the base rows that combined to make it:

```java
Tuple firstRow = explained.toList().getFirst();

System.out.println(firstRow.string("region"));
System.out.println(firstRow.get("provenance").asDisplayString());
```

```
North
[{coefficient: 1, variables: [{relation: Customers, ordinal: 1, columns: {customer_id: 10, region: North}}, {relation: Orders, ordinal: 1, columns: {amount: 100, customer_id: 10, order_id: 1}}], truncated: false}]
```

Because it is a column, the rest of the algebra applies to it. Lineage can be filtered,
projected and unnested like any other nested value — which is the whole point of reifying
it rather than reporting it:

```java
System.out.println(explained.unnest("provenance").count() + " derivations in total");
```

```
3 derivations in total
```

`why()` is a boundary: the lineage bookkeeping runs *below* it and is frozen into the
column *at* it. Above it, `provenance` is ordinary data, so nothing further re-threads the
annotation and a query does not pay for lineage it has already collected.

## Annotating a whole relation

The other form annotates every result row with a value from a **semiring** — an algebra
with a "combine alternatives" operation and a "combine steps" operation. Which semiring you
pick decides what the annotation means.

```java
import com.darkcollective.relix.provenance.CountingSemiring;
import com.darkcollective.relix.processor.provenance.Annotated;

var counted = relix.relation("π region (Orders ⋈ Customers)")
        .provenance(CountingSemiring.INSTANCE);

counted.stream().forEach((Annotated<java.math.BigInteger> annotated) ->
        System.out.println(annotated.row() + " ×" + annotated.annotation()));
```

```
Row{region=North} ×2
Row{region=South} ×1
```

`North` appears with a multiplicity of 2 because two orders produced it. That is the
counting semiring's answer to "how many ways": under set semantics the duplicate row is
gone, and the annotation is what remembers that there were two of it.

The general form of that is worth holding on to, because it is what the number means
everywhere: **a counting annotation counts derivations, not rows returned.** For most of
the algebra the two coincide — a projection, a filter, a join and a `⊎` all keep
duplicates, so the annotation and the number of rows the query yields agree exactly. They
part company at the operators that deduplicate: `A ∪ A` returns each row once and
annotates it with the derivations from both sides. Neither number is wrong; they answer
different questions. The boolean semiring has no such gap, because `∨` is idempotent —
which is the same reason set semantics is the boolean reading of a K-relation in the
first place.

Swap the semiring and the same query answers a different question. The boolean semiring is
plain reachability — "was this row derivable at all":

```java
import com.darkcollective.relix.provenance.BooleanSemiring;

relix.relation("π region (Orders ⋈ Customers)")
        .provenance(BooleanSemiring.INSTANCE)
        .stream()
        .forEach(annotated -> System.out.println(annotated.row() + " " + annotated.annotation()));
```

```
Row{region=North} true
Row{region=South} true
```

Six semirings ship, and they differ only in what `plus` and `times` mean:

| Semiring | An annotation means |
|---|---|
| `BooleanSemiring` | whether the row is derivable at all — set semantics, the default |
| `CountingSemiring` | how many derivations there are — bag multiplicity, path counts |
| `TropicalSemiring` | the cost of the cheapest derivation — shortest path |
| `SecurityLattice` | the clearance a row's derivation requires |
| `PolynomialSemiring` | the full lineage expression — which rows, in which combinations |
| `PathCostSemiring` | the cheapest cost together with the route that achieves it |

A weighted variant reads each base row's cost from a column, which is what the tropical
semiring needs to have anything to minimise. Customer 10 placed orders of 100 and 75, and
the annotation is the cheaper of the two derivations:

```java
import com.darkcollective.relix.provenance.TropicalSemiring;

relix.relation("π customer_id (Orders)")
        .provenance(TropicalSemiring.INSTANCE, "amount")
        .stream()
        .forEach(annotated -> System.out.println(annotated.row() + " " + annotated.annotation()));
```

```
Row{customer_id=10} 75.0
Row{customer_id=11} 250.0
```

## Writing your own

A `Semiring<K>` is four operations and one reading. `zero`, `one`, `plus` and `times` say
how annotations combine; `base` says what a source row is worth in the first place, and is
where a weight column enters. Nothing else is needed, and nothing in the engine recognises
a semiring by name — an implementation of your own is threaded exactly as a bundled one
is, through every positive operator and through weighted transitive closure.

The tropical example above minimised. The same query under a semiring that *adds* its
derivations reports each customer's total instead, and the only difference is `plus`:

```java
import com.darkcollective.relix.provenance.BaseTuple;
import com.darkcollective.relix.provenance.Semiring;

import java.math.BigDecimal;

// Alternative derivations add; joint requirements multiply; a base row is worth
// whatever the weight column says, and one that carries no weight is neutral.
class TotalSemiring implements Semiring<Double> {
    public Double zero() {
        return 0.0d;
    }

    public Double one() {
        return 1.0d;
    }

    public Double plus(Double a, Double b) {
        return a + b;
    }

    public Double times(Double a, Double b) {
        return a * b;
    }

    public Double base(BaseTuple tuple) {
        return tuple.weight().map(BigDecimal::doubleValue).orElse(one());
    }
}

relix.relation("π customer_id (Orders)")
        .provenance(new TotalSemiring(), "amount")
        .stream()
        .forEach(annotated -> System.out.println(annotated.row() + " " + annotated.annotation()));
```

```
Row{customer_id=10} 175.0
Row{customer_id=11} 250.0
```

`BaseTuple` is the source row as a semiring can read it: which leaf it came from, which
occurrence it is within that leaf, its weight if it has one, and its column values. The
last two are what let an annotation depend on the data — a cost, a multiplicity, a
probability — and the first two are what let it name the row, which is how lineage mints a
distinct variable per occurrence.

An absent weight is the semiring's own decision rather than the engine's, and the two
defensible answers differ: `one()` leaves a row neutral, while a fixed constant makes an
unweighted graph a defined special case instead of a degenerate one. A kinship coefficient
takes the second reading — every parent edge is half a generation whether or not anyone
wrote a weight down — and gets the textbook answer over a bare `(parent, child)` relation.

### Installing one by name

Implementing the interface is enough to pass a semiring to `provenance(...)` yourself. To
make one resolvable *by name* — so that `--provenance --semiring kinship` reaches it, and
it appears in the list of installed semirings — publish a `SemiringLibrary`:

```java
import com.darkcollective.relix.provenance.NamedSemiring;
import com.darkcollective.relix.provenance.SemiringLibrary;

class KinshipLibrary implements SemiringLibrary {
    public String name() {
        return "kinship";
    }

    public List<NamedSemiring> semirings() {
        return List.of(new NamedSemiring("kinship", List.of("consanguinity"),
                new TotalSemiring(),
                "Coefficient of relationship between two individuals."));
    }
}
```

Declare it as a service — `provides com.darkcollective.relix.provenance.SemiringLibrary`
in your `module-info`, and a `META-INF/services` file of the same name — and it is
discovered on startup. A module path reads the first and a class path the second, so
declaring both is what makes the library work either way. Where two libraries offer the
same name the higher `priority()` wins, and the loss is reported rather than resolved
silently.

## Which to reach for

`why()` when the lineage is part of the answer — an audit column, a "show your working"
view, something a downstream query filters on. It is an operator, so it composes.

`provenance(semiring)` when the annotation is *about* the whole result rather than in it,
and when the algebra matters: counting derivations, cheapest routes, security levels. It
sits off the normal streaming path and is never pushed to a backend, because no backend
knows the semiring.
