# The Relix programming guide

Using Relix from Java: building queries, inspecting what the engine makes of them, and
running them.

Every Java example in this guide is compiled against the shipped API and run on each
build, and the block underneath it holds what it actually printed. An example that stops
compiling, or stops printing what its page claims, fails the build — so a page here
cannot quietly fall behind the code it describes. Imports are elided where they are noise
and shown where they are the point.

This guide is about the Java API. The language itself — every operator, predicate and
function, with worked examples — is the subject of the other manual, the Relix language
reference. The two share one vocabulary: each combinator on a relation is named exactly
as the reference names the operator, so a question about what an operator *means* is
answered there.

## Introduction

Where to start: how to get the library onto a classpath, then one page that opens a
session, gets data in, writes a query both ways, runs it, and looks at what the engine
did with it.

| Page | What it covers |
|---|---|
| [Getting the library](installing.md) | The artifact and its coordinate; Java 21; adding a JDBC driver; checking what a session found |
| [Getting started](getting-started.md) | The session and the relation; Java data in; the two authoring surfaces; the row terminals; the optimizer and the plan; errors |
| [The session](sessions.md) | Declaring, running a script, reading a session back, diagnostics, what a session can be given, closing |

## Building a query

One method per operator, named as the language reference names it — and the language
itself wherever an expression reads better as text.

| Page | What it covers |
|---|---|
| [Composing a query](composing.md) | The two authoring surfaces; parameters; the unary operators; joins; set operations; universal quantification; building an AST directly |
| [Aggregation and analytics](analytics.md) | Grouping and reducing; NULL rules; ranking, running totals and offsets; top n per group; pivot |
| [Recursion and graphs](graphs.md) | Reachability, components, paths, cheapest routes, general recursion, and the safety valve |
| [Time](temporal.md) | Temporal types; binding a moment; the AS-OF join and its boundaries; interval joins; `SESSIONIZE`; `DOWNSAMPLE` |
| [Goal-seek and optimisation](solving.md) | `SOLVE`; subset selection and allocation with `OPTIMIZE`; solved, infeasible and undetermined; checking for a solver |
| [Nested data](nested.md) | Struct and array construction; `COLLECT` and `UNNEST` as inverses; `TREE`; declaring a nested schema and what it types |

## Getting data in

| Page | What it covers |
|---|---|
| [Bringing your own data and code](data-in.md) | Rows held, rows produced per scan, a connector of your own, a function library of your own |
| [Generating data](generating.md) | Generator relations and their bounds; `COVER` and all-pairs suites; seeded sampling and what a seed buys |
| [Against a database](database.md) | Binding a `DataSource`, introspection, what is pushed down and what is not, joining your data to theirs, connection lifecycle, and what a federated query does not promise |
| [Working without a database](offline.md) | What composes offline; unresolved names; capturing a catalog snapshot and replaying it |

## Getting answers out

| Page | What it covers |
|---|---|
| [Reading results](results.md) | The terminals and who closes what; typed row accessors; nested values; streaming; what a drained read teaches the engine |
| [Looking at what the engine did](inspecting.md) | Rendering, the rewrite and why it fired, the physical plan and its estimates, the session's own report |
| [The engine describes itself](introspection.md) | The `relix.*` catalogs as relations: what is installed, what the session knows, dependencies, functions, and the last run's feed |
| [Provenance: where a row came from](provenance.md) | Lineage as a column; annotating a relation over a semiring; which to reach for |
