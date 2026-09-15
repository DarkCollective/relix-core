# Architecture

Relix is a relational algebra engine. It reads a query written in relational
algebra, works out what it means, rewrites it into something cheaper, decides how
to run it — including how much of it a database can run instead — and then runs
what is left.

This document is a map of how that is put together. It is aimed at someone reading
the source for the first time: what the pieces are, which direction they depend on
each other, and which boundaries are enforced rather than merely intended.

For what the *language* means, see [`docs/reference`](docs/reference/README.md).
For how to use the engine from Java, see [`docs/guide`](docs/guide/README.md).

---

## The pipeline

A query passes through five stages. Each produces a value the next one consumes,
and each is a module you can use on its own.

```
  text ──► Script ──► SemanticModel ──► RelNode ──► PhysicalNode ──► rows
       │           │                 │           │                │
   frontend    analysis          optimizer     planner         executor
```

**Frontend.** Turns something a human wrote into a `Script` — a tree of
statements. The `.relix` grammar is one frontend; a Java program calling the
builders is another. The engine never sees text.

**Analysis.** Resolves imports, collects every relation and function into a symbol
table, infers the schema of every node in every expression, and validates what it
can. The result is a `SemanticModel`: immutable, and the thing every later stage
reads.

**Optimization.** Rewrites the logical tree into an equivalent one — pushing
filters toward the data, dropping redundant work, folding constants, propagating
emptiness. Every rule is a pure syntactic rewrite and each is named, so a report
can say which ones fired.

**Planning.** Chooses physical operators — which join algorithm, which side to
build — and decides how much of the query to hand to the source. A sub-tree that a
database can evaluate becomes a single scan holding native SQL or a MongoDB
pipeline.

**Execution.** Pulls rows through the physical plan. The engine is pull-based end
to end, so a query that is never drained does no work, and closing a result
cancels it.

---

## Boundaries

Three lines run through the module graph. They are worth knowing early because
they explain why the pieces are arranged the way they are — and each is enforced
by a test that reads the compiled module descriptors, not by convention.

**The engine does not depend on a frontend.** The concrete syntax is one way to
produce a `Script` and not a privileged one, so no engine module may reach the
grammar. This is what makes the AST the contract rather than the text.

**The engine does not depend on an implementation of what it describes.** It knows
a function's *form* — its arity, its types, whether it is pure, whether a backend
can spell it — while a provider supplies the code that computes it. The same rule
covers connectors and the mathematical-programming solver. The consequence is
checkable and is the point of the arrangement: every engine module's descriptor
names nothing but `java.base` and other engine modules.

**One artifact is published.** The modules below are how this build is organised,
not a set of names a program should depend on. `relix-dist` assembles them into a
single jar, `com.darkcollective.relix:relix`, and that coordinate is the whole
published surface.

---

## Modules

### The engine

| Module | What it holds |
|---|---|
| `relix-ast` | The sealed node hierarchies — `RelNode`, `Predicate`, `Operand` — plus the builders for constructing a tree without the grammar, and the pretty-printer that turns one back into text |
| `relix-symbol` | `Schema`, `SymbolTable`, and the symbol kinds a name can resolve to; the type system, including nested struct and array types |
| `relix-value` | The sealed `Value` hierarchy — one field of one row — and the conversions between a value and JSON |
| `relix-function` | The function SPI: signatures, arities, accumulators for aggregates, and the pushdown spelling a backend can render. Declares the service the providers implement |
| `relix-lang-ast` | The statement-level AST — `Script`, `Statement`, source declarations — which is the engine's front door |
| `relix-semantic` | The five-phase analysis pipeline, and the `relix.*` catalog relations through which the engine describes itself |
| `relix-cost` | Cardinality estimation, and the relation properties the optimizer and planner reason about: distinctness, ordering, and whether a relation ends |
| `relix-optimizer` | The logical rewrite rules, grouped into phases and driven to a fixpoint. Every rule is named and reportable |
| `relix-plan` | Physical planning: join algorithms, shared sub-plans, and the pushdown renderers that fold a sub-tree into native SQL or a MongoDB pipeline |
| `relix-processor` | The executor, and the seams a data source plugs into. Streams rows through the physical plan |
| `relix-events` | The observability seam — one event feed that the optimizer, planner and executor write to and any consumer reads |
| `relix-provenance` | The semiring algebra behind provenance: why a row is in the answer, counted, weighted, or named |
| `relix-solver` | The mathematical-programming SPI — a linear program and the answer to one — depended on by both the planner and the executor, and therefore depending on nothing itself |
| `relix-json` | A small dependency-free JSON reader and writer, shared by everything that emits machine-readable output |

### Frontends

| Module | What it holds |
|---|---|
| `relix-parser` | The expression parser — relational algebra in Unicode or ASCII |
| `relix-lang` | The script parser: a whole `.relix` file, including its imports |
| `relix-embed` | The embedding API. A session, a relation as a value, a combinator per operator, and the terminals that inspect or run one. This is what a Java program uses |

### Providers

Discovered at runtime. Each implements an SPI the engine declares and nothing
more, which is what makes the arrangement a rule rather than a description.

| Module | What it holds |
|---|---|
| `relix-function-builtin` | The default function library — the scalar functions and aggregates, one definition each, and the reference pages documenting them |
| `relix-connectors-std` | The standard connectors: CSV, JSON, HTTP and JDBC, with the driver provisioning JDBC needs |
| `relix-solver-ojalgo` | The shipped solver. Every reference to the underlying library lives in one class |
| `relix-mongo-connector` | A MongoDB connector, built as an out-of-tree plugin — the case that proves a third party can write one |

### Packaging

| Module | What it holds |
|---|---|
| `relix-dist` | Assembles the single published artifact from the modules above, and holds the descriptor naming exactly the packages a caller may reach |

---

## Building

```bash
./gradlew build
```

That is the gate: compilation, the fast hermetic tests, the documentation guards,
and Javadoc. It needs a Java 24 toolchain and nothing else — no Docker, no
network, no database.

The heavier suites are tagged and excluded from it, because a gate that cannot run
on a clean checkout is one people learn to skip. `./gradlew verifyAll` runs the
gate plus every tier; the container suites skip rather than fail where there is no
Docker daemon, so it is safe to run anywhere and simply verifies less.

```bash
./gradlew publishToMavenLocal    # the one artifact, into ~/.m2
./gradlew javadocCore            # the engine's API documentation
./gradlew guides                 # the documentation guards alone
```

---

## Documentation that checks itself

Prose describing an API goes stale silently, because a renamed method leaves the
sentence reading perfectly. Both manuals are therefore executed rather than
proofread.

Every relational-algebra example in `docs/reference` is parsed and analysed
against a shared schema, and every worked example is run with its output compared
against the result the page prints. Every Java example in `docs/guide` is compiled
against the real classpath, run, and its printed output compared against the
fence below it.

All of this happens during `./gradlew build`, and a stale example fails it by
name.
