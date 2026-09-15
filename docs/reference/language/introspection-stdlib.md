# Name: Introspection standard library (`relix.unused` / `relix.deps` / `relix.impact` / `relix.find` / `relix.schema` / `relix.cycles` / `relix.funcs`)

# Syntax:
relix.unused                 -- relations nothing else references (one column: name)
relix.deps(<name>)           -- everything <name> transitively depends on
relix.impact(<name>)         -- everything that transitively depends on <name>
relix.find(<column>)         -- relations that have a column with that name (+ its type)
relix.schema(<relation>)     -- the columns of one relation (column, type, ordinal)
relix.cycles                 -- relations caught in a circular definition (one column: name)
relix.funcs(<category>)      -- functions in a category (name, arity, return_type)

# Description:
Relix ships its own introspection logic *written in Relix*, in the reserved
`relix` namespace, right next to the catalog relations it queries
(`relix.relations`, `relix.dependencies`, `relix.plan`, …). These are ordinary
views and table-valued functions — there is no new language construct — so you
call them like any other relation and read their definitions with the REPL's
`:source` command. "See how Relix uses Relix."

- **`relix.unused`** — a view yielding the `name` of every relation that no other
  relation references. Handy for spotting dead definitions (and, by construction,
  top-level outputs, which nothing references).
- **`relix.deps(r)`** — a table-valued function returning the relations `r`
  transitively depends on (its full upstream lineage).
- **`relix.impact(r)`** — the reverse: every relation that transitively depends on
  `r` (its blast radius / downstream impact).
- **`relix.find(col)`** — a table-valued function returning every relation that has a
  column named `col`, paired with the type it carries there. Handy for tracing where
  a column (e.g. `customer_id`) lives across a schema.
- **`relix.schema(rel)`** — the mirror of `relix.find`: a table-valued function
  returning the columns of one relation, each with its `type` and `ordinal`
  position. Where `find` asks "which relations have this column?", `schema` asks
  "what columns does this relation have?" — the data twin of the REPL's `:schema`.
- **`relix.cycles`** — a view yielding the `name` of every relation caught in a
  circular definition. Empty for a well-formed (acyclic) script.
- **`relix.funcs(cat)`** — a table-valued function filtering the function library by
  category (e.g. `"math"`, `"string"`, `"user"`), returning each function's `name`,
  `arity`, and `return_type`. Data-driven off `relix.functions`' properties.

`relix.functions` itself carries one row per function — `name`, `category`,
`arity`, `max_arity`, `return_type`, the `pure` / `deterministic` / `idempotent`
flags, and `is_builtin`. A function that accepts a range of argument counts is
still one row: `arity` is the fewest arguments a call may pass and `max_arity` the
most, with `-1` meaning any number. `Round(x)` and `Round(x, places)` are the same
function, so it reads `arity = 1`, `max_arity = 2`; `Coalesce` reads `1` and `-1`.
`relix.funcs` projects `arity` alone, which is the lower bound.

# Technical Description:
All three are layered over the `relix.dependencies` catalog relation (itself
derived from `relix.plan`). `relix.unused` is the set difference of
all relation names and the depended-on names:

    (π name (relix.relations)) − (π depends_on→name (relix.dependencies))

`relix.deps`/`relix.impact` are the two directions of the transitive closure of
the dependency edge, restricted to the seed `r`:

    relix.deps(r)   = π depends_on (σ dependent  = r (CLOSURE dependent, depends_on (relix.dependencies)))
    relix.impact(r) = π dependent  (σ depends_on = r (CLOSURE dependent, depends_on (relix.dependencies)))

The exploration helpers are filters over the other catalog relations:

    relix.find(col)   = π relation, type (σ column = col (relix.columns))
    relix.schema(rel) = π column, type, ordinal (σ relation = rel (relix.columns))
    relix.funcs(cat)  = π name, arity, return_type (σ category = cat (relix.functions))

`find` and `schema` read the same catalog relation from its two sides, and each
drops the column it filtered on, since that one is constant. `schema` also drops
`distinct_count` and `null_count` — they report what the statistics know rather
than what the heading is; query `relix.columns` directly for those. A relation
with an open (schema-on-read) heading contributes a single `*` row of type `?`.

`relix.cycles` is the *diagonal* of the dependency closure — the edges whose two
endpoints coincide, i.e. relations reachable from themselves:

    relix.cycles = δ π dependent→name (σ dependent = depends_on (CLOSURE dependent, depends_on (relix.dependencies)))

Because the seed is a function parameter, the call argument is a constant string.
The definitions live in the `relix` namespace and the catalog relations exclude
that namespace, so the stdlib never describes itself and the layering stays
acyclic. They run through the normal parser → semantic → optimizer → planner →
executor pipeline — so they are exercised by the very engine they describe — and
are never pushed down to a backend.

# Examples:
Given a script:

```relix
Users  := [| id | name  |
           | 1  | Alice |];
Adults := { σ id > 0 (Users) };
Report := { π name (Adults) };
```

The dependency edges are `Adults → Users` and `Report → Adults`.

Relations nothing references (here, the top-level `Report`):

```relix
query { relix.unused };
-- name
-- ------
-- Report
```

Everything `Report` is built from (upstream lineage):

```relix
query { relix.deps("Report") };
-- depends_on
-- ----------
-- Adults
-- Users
```

Everything that would break if `Users` changed (downstream impact):

```relix
query { relix.impact("Users") };
-- dependent
-- ---------
-- Adults
-- Report
```

Which relations carry a `name` column, and as what type:

```relix
query { relix.find("name") };
-- relation  type
-- --------  ----
-- Users     S
-- Adults    S
```

What columns one relation has, and in what order:

```relix
query { relix.schema("Users") };
-- column  type  ordinal
-- ------  ----  -------
-- id      N     0
-- name    S     1
```

The math functions in the library:

```relix
query { relix.funcs("math") };
-- name  arity  return_type
-- ----  -----  -----------
-- Abs   1      N
-- Sqr   1      N
-- …
```

Relations caught in a circular definition (empty here — the example is acyclic):

```relix
query { relix.cycles };
-- name
-- ----
-- (no rows)
```

Read the shipped definition in the REPL:

```
relix> :source relix.impact
── source for relix.impact ─────────────────────────────────────────────────────
  relix.impact(r: STRING): RELATION := {
    π dependent (σ depends_on = r (CLOSURE dependent, depends_on (relix.dependencies)))
  };
```

`:source <name>` (alias `:def`) works on any session relation or function too,
not just the stdlib — printing a view's `:=` body or a function's definition,
rendered from its AST so it round-trips through the parser.

Five of these entries also have a REPL command as shorthand — `:impact`, `:find`,
`:unused`, `:cycles` and `:funcs` — each running the definition above and printing
its rows under a header naming the exact call. See
the REPL page for the group. Calling the entry yourself is
what you want as soon as the question is not exactly the one the command asks: the
result is an ordinary relation, so it filters, projects and joins against the other
catalog relations like anything else.
