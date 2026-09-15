# Name: Component inventory (`relix.version`)

# Syntax:
relix.version   -- what is installed in this process (component, kind, version)

# Description:
`relix.version` answers *what is running*: one row per component the process has
loaded — the engine itself, the embedding API, and every discovered provider.

Providers are found by service discovery, which means a missing one fails nothing
until a query needs it, and an unexpected one is invisible until it wins a name
clash. Neither is visible to a compiler, a unit test, or a stack trace. This
relation makes both visible, and makes them visible to a *query* — so the answer
can be filtered, joined and printed with the operators you already have.

Columns:

- `component` (`STRING`) — what the thing calls itself: a function library's name,
  a solver's name, a connector's type token, a driver's class.
- `kind` (`STRING`) — one of `"engine"`, `"facade"`, `"function-library"`,
  `"connector"`, `"solver"`, `"driver"`.
- `version` (`STRING`) — the version the runtime can read, or `"unknown"`.

## Why a version can be `unknown`

A version is read from the packaging: the module descriptor when running as
modules, the jar manifest when running from jars. A component built into neither —
loose classes on a class path, most often a test or a development build — carries
no version anywhere the runtime can ask for.

`"unknown"` is therefore the honest answer, and it is not a failure: the row is
still there, and *which* implementations are loaded is the question this relation
exists to answer. The version sharpens the answer; the name is the answer.

## What each host contributes

The engine reports itself and the function libraries it resolves against, because
it holds them. Connectors, solvers and JDBC drivers are reported by whatever
assembled the process, because they live outside the engine — a JDBC driver most
plainly, since the engine carries no database dependency at all.

A host that supplies nothing still gets the engine's own rows. That is the case
for a bare engine embedding, and its inventory is complete for what it is: a
process with no connectors installed reports no connectors, which is true.

# Examples:
The whole inventory:

```relix
query { relix.version };
```

Just the providers, engine and facade aside:

```relix
query { σ kind ∉ {"engine", "facade"} (relix.version) };
```

Whether anything can talk to a database at all — a question with two independent
halves, the connector and the driver:

```relix
query { γ kind, COUNT(*) → installed (σ kind ∈ {"connector", "driver"} (relix.version)) };
```

Components whose version the runtime could not determine, which for a shipped
build is a packaging problem worth knowing about:

```relix
query { π component, kind (σ version = "unknown" (relix.version)) };
```

# Limitations:
A version is only as good as the packaging it is read from; see *Why a version can
be `unknown`* above.

The inventory is what is loaded *now*. A JDBC driver registered later in the run
appears in a later statement's answer, because the rows are gathered per analysis
rather than cached at start-up.

# See also:
- [observability feed](introspection-events.md) — `relix.events`, which answers what
  the last run *did* rather than what is installed.
- [introspection stdlib](introspection-stdlib.md) — the structural catalogs.
- `relix help catalog` — every `relix.*` relation and its columns.
