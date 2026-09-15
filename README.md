# Relix

> A relational algebra engine you can embed in a Java program.

[![CI](https://github.com/DarkCollective/relix-core/actions/workflows/ci.yml/badge.svg)](https://github.com/DarkCollective/relix-core/actions/workflows/ci.yml)

Relix takes a query written in **relational algebra** — filter, project, join,
group — infers its schema, rewrites it, works out how much of it your database can
run, and runs the rest itself.

```java
try (Relix relix = Relix.open()) {
    relix.define("""
        source Orders from csv("orders.csv") {
            header: true,
            schema: { order_id: NUMBER, product_id: NUMBER, amount: NUMBER, status: STRING }
        };
        """);

    Relation best = relix.relation("""
        λ 10 (τ revenue DESC (
            γ product_id, SUM(amount) → revenue (σ status = "completed" (Orders))
        ))
        """);

    for (Tuple row : best.toList()) {
        System.out.println(row.longValue("product_id") + ": " + row.decimal("revenue"));
    }
}
```

Every operator has an ASCII spelling too, so that query can equally be written
`LIMIT 10 (SORT revenue DESC (GROUP product_id, SUM(amount) -> revenue (...)))`.

---

## Why relational algebra

SQL is the lingua franca, and parts of it are genuinely awkward. "The row with the
maximum value per group" needs a window function or a self-join. "Rows matching
*every* row over there" becomes a double-negated `NOT EXISTS`. Reachability needs a
recursive CTE. None of these is hard in the algebra underneath — they are hard in
the surface syntax that grew on top of it.

Relix exposes the algebra. A query is an expression, every expression is a
relation, and relations compose without special cases. That buys operators SQL
makes painful or cannot express at all: transitive closure, symmetric difference,
universal quantification, top-*k* per group, argmax, temporal joins, sessionization,
goal-seek, and constrained optimization — each a first-class operator rather than a
pattern you reassemble each time.

It also buys provenance. Annotate a result and each row can tell you *why* it is
there: which base tuples contributed, how many derivations produced it, or the
cheapest path that reached it.

## What it does with your database

A source can be a CSV file, a JSON document, an HTTP endpoint, or a database
table. Where a sub-tree of your query is something the backend can evaluate, Relix
folds it into a single native query — `SELECT … WHERE … GROUP BY … ORDER BY …
LIMIT` for SQL, an aggregation pipeline for MongoDB — and evaluates the remainder
itself. What could not be pushed down still runs; it just runs here.

You can see exactly what happened. A plan reports which rules fired, what it
pushed down, and what it estimated.

## Getting started

Relix is not yet published to a repository. Build it from source:

```bash
./gradlew publishToMavenLocal
```

That publishes one coordinate, `com.darkcollective.relix:relix`, carrying the
engine, the default function library, the CSV/JSON/HTTP/JDBC connectors and the
bundled solver. A consuming build names it and a JDBC driver, and nothing else.

```gradle
repositories {
    mavenLocal()
    mavenCentral()      // for ojalgo, the one dependency not merged into the jar
}

dependencies {
    implementation 'com.darkcollective.relix:relix:1.0-SNAPSHOT'
}
```

Then read **[the programming guide](docs/guide/README.md)**, which is written as
one worked example and whose every snippet is compiled and run as part of the
build.

## Documentation

| | |
|---|---|
| [Programming guide](docs/guide/README.md) | Building, inspecting and running queries from Java |
| [Language reference](docs/reference/README.md) | Every operator, predicate, function and literal |
| [Architecture](ARCHITECTURE.md) | How the engine is put together, and the boundaries inside it |

Both manuals are **executed rather than proofread**. Every algebra example is
parsed and analysed against a real schema; every worked example is run and its
output compared against the result the page prints; every Java snippet is compiled
against the real classpath and run, with its printed output compared against the
fence below it. A stale example fails the build by name.

## Requirements

Java 24. `./gradlew build` needs nothing else — no Docker, no network, no
database. The heavier suites are tagged and excluded from that gate, because a
gate that cannot run on a clean checkout is one people learn to skip;
`./gradlew verifyAll` runs them where the machine can.

## Status

Relix is under active development and has not had a stable release. The embedding
API carries `@since` tags and a backward-compatible-per-major promise, checked by a
compatibility gate; the rest of the module graph is internal to this build and not
a surface to depend on.

## License

Apache License 2.0 — see [`LICENSE.txt`](LICENSE.txt) and [`NOTICE`](NOTICE).
© 2026 Darkcollective, LLC.
