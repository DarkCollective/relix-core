# Name: Reserved namespace (`relix.catalog`)

# Syntax:
relix.catalog   -- the relix.* surface itself (name, kind, arity, schema_code)

# Description:
`relix.catalog` is the reserved namespace describing itself: one row per `relix.*`
relation and table-valued function.

Every other catalog describes *your* relations and deliberately excludes the
reserved namespace — which is what keeps `relix.relations` about your script. The
side effect was that the introspection surface was the one thing you could not
introspect: to find out what `relix.*` holds, you had to read the documentation.
This relation answers that from inside the engine.

Columns:

- `name` (`STRING`) — the qualified name, exactly as you would type it
  (`relix.plan`), because the use of a listing is that you can copy a row out of it.
- `kind` (`STRING`) — `"SYS"` for an engine-computed relation, `"QR"` for one of
  the [stdlib](introspection-stdlib.md) views, `"TVF"` for a table-valued function.
  The same codes the IR prints.
- `arity` (`NUMBER`) — how many arguments a call passes. NULL for a relation, which
  is not called.
- `schema_code` (`STRING`) — the heading it yields, in the compact form
  `relix.plan.schema_code` also uses: `{name:S,kind:S,…}`.

## It changes nothing else

Asking is naming it. `relix.relations`, `relix.columns`, `relix.functions` and
`relix.keys` keep exactly the extents they had — user relations only. There is no
flag and no mode; the reserved namespace is described by one relation, and you get
it when you write it.

## It lists itself

`relix.catalog` has a row of its own. A listing with a hole where the entry point
is would be a poor listing, and there is no danger in it here: the extent is
computed directly from what is registered, never derived from another catalog, so
it cannot recurse.

## There is no description column

What each relation is *for* is what this manual is. A one-line summary carried in
the engine as well would be a second copy of that text with nothing keeping the two
in step, so the relation carries the shape and leaves the meaning to the pages.

# Technical Description:
The extent is computed at analysis time from the symbols registered in the reserved
namespace, so it reflects what this build actually ships rather than a list
maintained beside it — a relation added to the catalog appears here without anyone
remembering to add it.

`kind` distinguishes the two ways a `relix.*` relation is produced, which is worth
reading: a `SYS` row is a relation the engine computes in Java, while a `QR` or
`TVF` row is written in Relix and layered over those. `:source` in the REPL prints
the definition of the second kind.

# Examples:
The whole reserved namespace:

```relix
query { relix.catalog };
```

Just the callable ones, with how many arguments each takes:

```relix
query { σ kind = "TVF" (relix.catalog) };
```

Which parts of the introspection surface are themselves written in Relix, rather
than computed by the engine:

```relix
query { π name (σ kind ≠ "SYS" (relix.catalog)) };
```

How much of the surface there is, by kind:

```relix
query { γ kind, COUNT(name) → entries (relix.catalog) };
```

Everything that yields a relation name you could then look up — the entries whose
heading mentions a `relation` column:

```relix
query { π name, schema_code (σ schema_code LIKE "%relation:%" (relix.catalog)) };
```

# Limitations:
It describes shape, not meaning; see *There is no description column* above.

`schema_code` is a rendered string rather than rows, so it is read rather than
joined. The relational form of a heading is `relix.columns`, which covers user
relations only.

Scalar functions are not here. This relation lists what lives in the reserved
namespace, and the function library is not reserved — `relix.functions` lists every
callable function, and `relix.funcs(cat)` filters it by category.

# See also:
- [introspection stdlib](introspection-stdlib.md) — the `relix.*` views and TVFs
  this relation lists, and their definitions.
- [candidate keys](introspection-keys.md) — `relix.keys`.
- [component inventory](introspection-version.md) — `relix.version`, which answers
  what is *installed* rather than what is reserved.
- `relix help catalog` — the same surface as prose, with examples.
