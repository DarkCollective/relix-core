# Generating data

Most relations are read. A few are **computed** — their rows are produced by the engine
rather than stored anywhere — and they answer the same reader question in three different
ways: *how many rows am I going to get, and can I get them again?*

| | Where the rows come from | What bounds them | Can you get them again? |
|---|---|---|---|
| Generators | A named series the engine knows how to enumerate | Their arguments, or nothing at all | Always — they are functions of their arguments |
| `COVER` | A subset of a candidate set | Its construction: enough rows to cover every combination | Always — the choice is deterministic |
| Sampling | A random subset of its input | A probability, or an exact count | Only with a seed |

```java
Relix relix = Relix.open();
```

## Generator relations

A generator is declared like any other source, naming the series rather than a file:

```java
relix.define("""
        source Small from generator { name: "Range", lo: "1", hi: "6" };
        """);

relix.relation("Small").toList().forEach(System.out::println);
```

```
(n=1)
(n=2)
(n=3)
(n=4)
(n=5)
(n=6)
```

`Range` takes `lo`, `hi` and an optional `step`, and produces a single `n` column. It is a
relation in every ordinary sense — it joins, it filters, it feeds a `×` — which is what
makes it useful as the spine of something else: a calendar to left-join sparse readings
onto, a set of ids to probe with, a row source for a test fixture.

Two of the built-in generators have no last row at all. `Naturals` and `Primes` enumerate
forever, which is safe to have because the engine knows they do:

```java
relix.define("""
        source Primes from generator { name: "Primes" };
        """);

try (Stream<Tuple> primes = relix.relation("Primes").stream()) {
    System.out.println(primes.limit(8).map(row -> row.longValue("n").toString()).toList());
}
```

```
[2, 3, 5, 7, 11, 13, 17, 19]
```

`stream()` is what an unbounded relation is *for*: the engine is pull-based end to end, so
eight rows is eight rows of work. The collecting terminals refuse such a relation instead
of hanging, and [Reading results](results.md#streaming) covers that side of it.

A bound placed *above* an unbounded generator does not have to be a post-filter. The
optimizer pushes it into the generator, so the enumeration itself stops:

```java
System.out.println(relix.relation("σ n ≤ 20 (Primes)").optimized().render());
System.out.println(relix.relation("σ n ≤ 20 (Primes)").toList().size() + " primes ≤ 20");
```

```
σ n ≤ 20 (Primes ⟨produce while n ≤ 20⟩)
8 primes ≤ 20
```

The relation went from one that never ends to one that does, which is why a collecting
terminal is willing to read it.

## COVER: a test suite that is small on purpose

`COVER t` keeps just enough rows that every combination of `t` column values still appears
somewhere. It is the operator behind all-pairs testing, and it is worth a worked example
because the saving is not small.

A checkout flow has to work across browsers, operating systems, locales, payment methods
and network conditions. Each factor is a relation; the exhaustive matrix is their product:

```java
relix.table("Browsers", List.of("browser"), List.of(
        Map.of("browser", "Chrome"), Map.of("browser", "Firefox"),
        Map.of("browser", "Safari"), Map.of("browser", "Edge")));

relix.table("Systems", List.of("os"), List.of(
        Map.of("os", "Windows"), Map.of("os", "macOS"), Map.of("os", "Linux"),
        Map.of("os", "iOS"), Map.of("os", "Android")));

relix.table("Locales", List.of("locale"), List.of(
        Map.of("locale", "en-GB"), Map.of("locale", "de-DE"), Map.of("locale", "fr-FR"),
        Map.of("locale", "ja-JP"), Map.of("locale", "pt-BR")));

relix.table("Payments", List.of("payment"), List.of(
        Map.of("payment", "card"), Map.of("payment", "paypal"),
        Map.of("payment", "applepay"), Map.of("payment", "banktransfer")));

relix.table("Networks", List.of("network"), List.of(
        Map.of("network", "wifi"), Map.of("network", "4g"), Map.of("network", "3g")));

relix.define("""
        Matrix := { Browsers × Systems × Locales × Payments × Networks };
        """);

System.out.println(relix.relation("Matrix").count() + " exhaustive combinations");
```

```
1200 exhaustive combinations
```

Twelve hundred runs of an end-to-end suite is not a thing anyone schedules. Before
shrinking it, though, some of those rows are not runnable at all — Safari does not run on
Windows, and Apple Pay is not offered off Apple platforms. Those are ordinary selections:

```java
relix.define("""
        Feasible := { σ (browser ≠ "Safari" ∨ os ∈ {"macOS", "iOS"})
                      ∧ (payment ≠ "applepay" ∨ os ∈ {"macOS", "iOS"}) (Matrix) };
        """);

System.out.println(relix.relation("Feasible").count() + " runnable combinations");
```

```
885 runnable combinations
```

That the constraints are just a `σ` is the part worth noticing. `COVER` derives what it has
to cover from the candidate set it is given, so a combination you filtered out is never
demanded — there is no separate constraint language, and no way for the constraints and the
coverage goal to disagree.

Now the suite. `cover(2)` asks for every *pair* of values to appear together somewhere:

```java
Relation suite = relix.relation("Feasible").cover(2);

System.out.println(suite.render());
System.out.println(suite.count() + " runs cover every feasible pair");
```

```
COVER 2 (Feasible)
30 runs cover every feasible pair
```

Thirty runs instead of eight hundred and eighty-five, and the claim is checkable rather
than trusted. Every pair of values that occurs in the candidate set has to occur in the
suite, so the pairs the suite is *missing* are a set difference — and there are none, for
any of the ten pairs of columns:

```java
List<String> factors = List.of("browser", "os", "locale", "payment", "network");
Relation feasible = relix.relation("Feasible");

for (int a = 0; a < factors.size(); a++) {
    for (int b = a + 1; b < factors.size(); b++) {
        String x = factors.get(a);
        String y = factors.get(b);
        System.out.println(x + " × " + y + " — uncovered: "
                + feasible.project(x, y).difference(suite.project(x, y)).count()
                + " of " + feasible.project(x, y).distinct().count());
    }
}
```

```
browser × os — uncovered: 0 of 17
browser × locale — uncovered: 0 of 20
browser × payment — uncovered: 0 of 16
browser × network — uncovered: 0 of 12
os × locale — uncovered: 0 of 25
os × payment — uncovered: 0 of 17
os × network — uncovered: 0 of 15
locale × payment — uncovered: 0 of 20
locale × network — uncovered: 0 of 15
payment × network — uncovered: 0 of 12
```

That is the check written in the language itself, which is the point of the operator
returning a relation rather than a report: "is every pair covered?" is a difference, and an
empty difference is the answer.

Strength is the dial between "every value at least once" and "every distinct row". At `t=1`
the suite only has to exercise each individual value; at `t=3` every triple has to appear;
and at a `t` equal to the number of columns it degenerates to `δ`:

```java
for (int t = 1; t <= 3; t++) {
    System.out.println("t=" + t + " → " + relix.relation("Feasible").cover(t).count() + " runs");
}
```

```
t=1 → 5 runs
t=2 → 30 runs
t=3 → 108 runs
```

The selection is greedy with a deterministic tie-break, so the same input gives the same
suite every time. Ordering the input is how you bias which candidates win — put a `τ` under
the `COVER` and the rows you would rather run are the ones picked first. `cover(t, true)`
asks for a **minimal** suite instead of a greedy one; that is a search, and it needs the
installed solver [Goal-seek and optimisation](solving.md#checking-that-a-solver-is-installed)
describes.

## Sampling

Sampling answers the same size question with randomness rather than construction. There are
two operators, and they differ in which side of the sample you get to fix.

`sample(p)` is Bernoulli: each row is kept independently with probability `p`, so the
*rate* is fixed and the count varies. `sampleReservoir(n)` fixes the count instead: exactly
`n` rows, chosen uniformly, or all of them if there are fewer than `n`.

Both take a seed, and everything below passes one — an unseeded sample cannot have a
printed result at all, which is the most direct possible demonstration of the difference:

```java
Relation tenth = relix.relation("Feasible").sample(0.1, 2026);
Relation five = relix.relation("Feasible").sampleReservoir(5, 2026);

System.out.println(tenth.render());
System.out.println(tenth.count() + " rows from a 10% sample of "
        + relix.relation("Feasible").count());
System.out.println(five.render());
System.out.println(five.count() + " rows from the reservoir");
```

```
SAMPLE 0.1 SEED 2026 (Feasible)
86 rows from a 10% sample of 885
SAMPLE 5 ROWS SEED 2026 (Feasible)
5 rows from the reservoir
```

The same seed over the same input picks the same rows — not merely within one run, but for
any relation carrying that seed, however it was built:

```java
Relation composed = relix.relation("Feasible").sampleReservoir(3, 7);
Relation written = relix.relation("SAMPLE 3 ROWS SEED 7 (Feasible)");

System.out.println("same rows: " + composed.toList().equals(written.toList()));
composed.toList().forEach(row -> System.out.println(row.string("browser") + " / "
        + row.string("os") + " / " + row.string("payment")));
```

```
same rows: true
Firefox / macOS / card
Safari / iOS / card
Chrome / Android / banktransfer
```

### Why a seed is more than convenience

A seeded sample is a *function* of its input. An unseeded one reads system state, and the
engine treats it as volatile — which changes what the planner is allowed to do with it.

When one sub-expression is read in two places, the planner evaluates it once and shares the
result. A symmetric difference reads both its inputs twice, so writing the same seeded
sample on both sides gives the planner four readers of one relation:

```java
System.out.println(relix.relation("""
        (SAMPLE 5 ROWS SEED 7 (Feasible)) ∆ (SAMPLE 5 ROWS SEED 7 (Feasible))
        """).explain());
```

```
SetOp UNION  ~10 rows
├─ SetOp DIFFERENCE  ~? rows
│  ├─ Spool #1  ~5 rows
│  │  └─ SAMPLE 5 ROWS SEED 7  ~5 rows
│  │     └─ Select  ~396 rows
│  │        └─ Join PRODUCT/NESTED_LOOP build=RIGHT  ~1200 rows
│  │           ├─ Join PRODUCT/NESTED_LOOP build=RIGHT  ~400 rows
│  │           │  ├─ Join PRODUCT/NESTED_LOOP build=RIGHT  ~100 rows
│  │           │  │  ├─ Join PRODUCT/NESTED_LOOP build=LEFT  ~20 rows
│  │           │  │  │  ├─ Scan Browsers  ~4 rows
│  │           │  │  │  └─ Scan Systems  ~5 rows
│  │           │  │  └─ Scan Locales  ~5 rows
│  │           │  └─ Scan Payments  ~4 rows
│  │           └─ Scan Networks  ~3 rows
│  └─ Spool #1 (shared)  ~5 rows
└─ SetOp DIFFERENCE  ~? rows
   ├─ Spool #1 (shared)  ~5 rows
   └─ Spool #1 (shared)  ~5 rows
```

`Spool #1` sits **on** the sample: the draw happens once and all four readers see the same
five rows. Take the seed away and the two spellings become two different draws — that is
what the query now says, and sharing one evaluation between them would answer a different
question:

```java
System.out.println(relix.relation("""
        (SAMPLE 5 ROWS (Feasible)) ∆ (SAMPLE 5 ROWS (Feasible))
        """).explain());
```

```
SetOp UNION  ~10 rows
├─ SetOp DIFFERENCE  ~? rows
│  ├─ SAMPLE 5 ROWS  ~5 rows
│  │  └─ Spool #1  ~396 rows
│  │     └─ Select  ~396 rows
│  │        └─ Join PRODUCT/NESTED_LOOP build=RIGHT  ~1200 rows
│  │           ├─ Join PRODUCT/NESTED_LOOP build=RIGHT  ~400 rows
│  │           │  ├─ Join PRODUCT/NESTED_LOOP build=RIGHT  ~100 rows
│  │           │  │  ├─ Join PRODUCT/NESTED_LOOP build=LEFT  ~20 rows
│  │           │  │  │  ├─ Scan Browsers  ~4 rows
│  │           │  │  │  └─ Scan Systems  ~5 rows
│  │           │  │  └─ Scan Locales  ~5 rows
│  │           │  └─ Scan Payments  ~4 rows
│  │           └─ Scan Networks  ~3 rows
│  └─ SAMPLE 5 ROWS  ~5 rows
│     └─ Spool #1 (shared)  ~396 rows
└─ SetOp DIFFERENCE  ~? rows
   ├─ SAMPLE 5 ROWS  ~5 rows
   │  └─ Spool #1 (shared)  ~396 rows
   └─ SAMPLE 5 ROWS  ~5 rows
      └─ Spool #1 (shared)  ~396 rows
```

The spool has moved **below** the sample. The candidate set — the product and the selection
over it, by far the expensive part — is still computed once and shared; each `SAMPLE` then
draws from it independently. Sharing stops exactly where volatility begins, and not a level
higher.

Neither plan is a compromise; each is the correct reading of what was written. It is worth
knowing which one you wrote, because a repeated unseeded sample inside a larger query is
paid for every time it appears.

Neither sampling operator is folded into a backend query — the rows are drawn in the engine,
over rows the engine has read.
