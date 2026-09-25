# Relix

> A relational algebra engine you can embed in a Java program.

[![Maven Central](https://img.shields.io/maven-central/v/com.darkcollective.relix/relix?include_prereleases)](https://central.sonatype.com/artifact/com.darkcollective.relix/relix)
[![CI](https://github.com/DarkCollective/relix-core/actions/workflows/ci.yml/badge.svg)](https://github.com/DarkCollective/relix-core/actions/workflows/ci.yml)

Relix takes a query written in **relational algebra** (filter, project, join,
group), infers its schema, rewrites it, works out how much of it your database can
run, and runs the rest itself. A source can be a database table, a CSV or JSON
file, or an HTTP endpoint, and one query can read several.

**[relix.darkcollective.com](https://relix.darkcollective.com)** has the manuals,
the language reference and worked examples.

## Install

Relix needs Java 21. It is one artifact on Maven Central, carrying the engine, the
function library and the CSV, JSON, HTTP and JDBC connectors. Use the version on the
badge above.

```gradle
dependencies {
    implementation 'com.darkcollective.relix:relix:<version>'
}
```

```xml
<dependency>
    <groupId>com.darkcollective.relix</groupId>
    <artifactId>relix</artifactId>
    <version><!-- the version on the badge --></version>
</dependency>
```

## A first query

```java
try (Relix relix = Relix.open()) {
    relix.define("""
        Orders := [
        | order_id | product | amount | status    |
        |----------|---------|--------|-----------|
        | 1        | lamp    | 40     | completed |
        | 2        | desk    | 250    | completed |
        | 3        | lamp    | 40     | completed |
        | 4        | chair   | 90     | pending   |
        ];
        """);

    Relation best = relix.relation("""
        LIMIT 2 (SORT revenue DESC (
            GROUP product, SUM(amount) -> revenue (SELECT status = 'completed' (Orders))
        ))
        """);

    for (Tuple row : best.toList()) {
        System.out.println(row.string("product") + ": " + row.decimal("revenue"));
    }
    System.out.println(best.render());
}
```

```
desk: 250
lamp: 80
λ 2 (τ revenue DESC (γ product, SUM(amount) → revenue (σ status = "completed" (Orders))))
```

`SELECT` here is relational selection, what SQL calls `WHERE`. Every operator also
has its symbol from the algebra, and the two spellings mean the same thing:
`render()` prints the query back in symbols.

## Why relational algebra

SQL is the lingua franca, and parts of it are awkward. "The row with the maximum
value per group" needs a window function or a self-join. "Rows matching *every* row
over there" becomes a double-negated `NOT EXISTS`. Reachability needs a recursive
CTE. None of these is hard in the algebra underneath; they are hard in the syntax
that grew on top of it.

Relix exposes the algebra. A query is an expression, every expression is a
relation, and relations compose without special cases. That gives first-class
operators for transitive closure, universal quantification, top-*k* per group,
argmax, temporal joins, sessionization, goal-seek and constrained optimization, and
lets any result say *why* each row is there.

Where part of a query is something the database can evaluate, Relix folds it into a
single native query and runs the remainder itself. A plan shows which rules fired
and what was pushed down.

## Documentation

| | |
|---|---|
| [Programming guide](docs/guide/README.md) | Building, inspecting and running queries from Java |
| [Language reference](docs/reference/README.md) | Every operator, predicate, function and literal |
| [Coming from SQL](https://relix.darkcollective.com/sql.html) | Everyday SQL beside its Relix, each pair checked against a real database |
| [Beyond SQL](https://relix.darkcollective.com/beyond-sql.html) | Questions SQL makes hard, each with its Relix answer |
| [Grammar](https://relix.darkcollective.com/reference/language/grammar.ebnf.txt) | The whole language as plain-text EBNF |
| [llms.txt](https://relix.darkcollective.com/llms.txt) | A primer for language models writing Relix |
| [Architecture](ARCHITECTURE.md) | How the engine is put together |

Every example in the manuals is executed on each build rather than proofread, and
so is the one above.

## Building from source

```bash
./gradlew build
```

It needs Java 21 and nothing else: no Docker, no network, no database.

## Building against an unreleased engine

A project that needs an engine change before it is released can use a snapshot or a
local checkout.

**Snapshots.** Every change merged to `develop` publishes one to Central's snapshot repository,
named for the release it leads to:

```gradle
repositories {
    mavenCentral()
    maven { url 'https://central.sonatype.com/repository/maven-snapshots/' }
}

dependencies {
    implementation 'com.darkcollective.relix:relix:<next release>-SNAPSHOT'
}
```

A snapshot changes with every push and is for development only. A release of anything
built on Relix should depend on a release or a release candidate.

**A local checkout.** A Gradle composite build compiles against a clone of this
repository directly, with nothing published. In the consuming project's
`settings.gradle`:

```gradle
includeBuild('../relix-core') {
    dependencySubstitution {
        substitute module('com.darkcollective.relix:relix') using project(':relix-dist')
    }
}
```

The dependency keeps whatever version it declares; the local build replaces it.

## Status

Relix is under active development and has not had a stable release. Release
candidates are on Maven Central. The embedding API carries `@since` tags and a
backward-compatible-per-major promise, checked by a compatibility gate.

## Contributing

Contributions are welcome. See [`CONTRIBUTING.md`](CONTRIBUTING.md), report
security problems as [`SECURITY.md`](SECURITY.md) describes rather than in an issue,
and follow the [code of conduct](CODE_OF_CONDUCT.md).

## License

Apache License 2.0. See [`LICENSE.txt`](LICENSE.txt) and [`NOTICE`](NOTICE).
© 2026 Darkcollective, LLC.
