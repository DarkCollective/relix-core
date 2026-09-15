# Name: Relation catalog (`relix.relations`)

# Syntax:
relix.relations   -- every relation the script can name (name, kind, namespace, materialization, row_count, boundedness)

# Description:
`relix.relations` has one row per relation the script can name — the sources it
declares, the inline tables it embeds, the connection-backed tables it reaches, and
every view it defines with `:=`.

It is the catalog the others hang off: `relix.columns` names the same relations
one row per column, `relix.keys` one row per key column, and a query joining any of
them to this one is how a question about *all* the relations gets asked.

Columns:

- `name` (`STRING`) — the relation's name, as it was written.
- `kind` (`STRING`) — what it is: `"SRC"` a declared source, `"INL"` an inline
  table, `"DB"` a connection-backed table, `"QR"` a view, `"SYS"` an
  engine-computed catalog. The same codes the IR prints.
- `namespace` (`STRING`) — the namespace the relation was declared in.
- `materialization` (`STRING`) — how much of the relation has to be held at once to
  produce a row: `"stream"` (none), `"bag"`, `"set"`, `"sort"`.
- `row_count` (`NUMBER`) — how many rows it has, where that was measured. NULL
  where it was not.
- `boundedness` (`STRING`) — whether it is finite: `"bounded"`, `"unbounded"`, or
  `"unknown"`.

## Two questions about size, and only one of them is measured

`row_count` and `boundedness` sound like the same question and are not. A count is
*observed* — from the rows of an inline table, from a generator that knows its own
length, from the catalog of an introspected database table. Boundedness is
*proved*, from what a relation is and what it reads.

That is why the pairs which look inconsistent are the informative ones:

| `boundedness` | `row_count` | What it means |
|---|---|---|
| `bounded` | a number | finite, and counted |
| `bounded` | NULL | finite, but nobody has counted it — a CSV file nothing has read |
| `unbounded` | NULL | it has no count because it has no end |
| `unknown` | NULL | nothing here shows it finite either way |

Reading a NULL `row_count` as "empty" is the mistake the two columns together
prevent: a table with no count is one nothing has counted.

## Where `boundedness` comes from

A relation that holds its own rows — an inline table, a file, an HTTP resource, a
database table, one of these catalogs — is `bounded`. Each is a finite extent that
somebody is holding.

A **generator** is the exception, and the only leaf that can be otherwise: a
generator declares whether it ends, so `Range` is `bounded` while `Naturals` and
`Primes` are `unbounded`.

A **view** is neither declared nor measured but *derived*, from the body it was
defined as — so it is exactly as bounded as what it reads, through however many
other views:

```relix
source Naturals from generator { name: "Naturals" };
Big     := { σ n > 1000000 (Naturals) };   -- still unbounded: filtering removes no end
FirstTen := { λ 10 (Naturals) };           -- bounded: λ is what bounds it
```

That derivation is why the column is worth having on views at all. Selecting from
an infinite relation leaves it infinite, and `λ` is the operator that ends it — the
same rule the engine uses when it refuses to sort or group an endless input.

## `unknown` is an answer, not a gap

`boundedness` is never NULL. `"unknown"` means *nothing here shows this finite
either way*, which is a claim, where a NULL `row_count` is the absence of one.

Three things say it. A generator this session cannot place — because no generator
of that name is installed. A relation named in a view that resolves to nothing. And
a definitional cycle: `A` defined from `B` and `B` from `A` puts neither on any
footing from which finiteness can be shown.

`"unknown"` does not mean a query over the relation is refused. The engine refuses
what is *provably* endless, and treats not-knowing as permission to proceed — so
this column tells you where a query is running on trust.

# Technical Description:
The extent is fixed when analysis finishes: it describes the script, and querying
it runs nothing against a source. A view's boundedness is derived from the same
walk the planner uses, over the same lattice — the two cannot disagree about
whether a query's input ends.

Like the other catalogs, this one describes only relations *outside* the reserved
`relix` namespace, which is what keeps it about your script. Use `relix.catalog` to
see the namespace itself.

# Examples:
Every relation the script can name:

```relix
query { relix.relations };
```

Just the views, in order:

```relix
query { τ name (σ kind = "QR" (relix.relations)) };
```

What is finite, and therefore safe to sort, group or collect:

```relix
query { π name (σ boundedness = "bounded" (relix.relations)) };
```

The ones to be careful with — endless, or not shown to be otherwise:

```relix
query { π name, kind, boundedness (σ boundedness ≠ "bounded" (relix.relations)) };
```

Finite but never counted, which is the pair a NULL count on its own reads wrongly:

```relix
query { π name (σ boundedness = "bounded" ∧ row_count IS NULL (relix.relations)) };
```

How much of the script is of each kind, and how much of it ends:

```relix
query { γ kind, boundedness, COUNT(*) → relations (relix.relations) };
```

Which relations buffer their whole input before emitting a row, joined to their
size so the expensive ones stand out:

```relix
query {
  τ row_count DESC (σ materialization ≠ "stream" (relix.relations))
};
```

# Limitations:
`row_count` is a count where one was taken, not an estimate; a view never carries
one. See *Two questions about size* above.

`boundedness` is a lower bound on what is provable, not on what is true. A `FIX`
whose step computes new values can have an endless extent and still read
`bounded` — whether such a recursion terminates is not decidable, so no analysis of
this kind answers it. What stops that query is the engine's runtime limit, not this
column.

A table-valued function's body is not walked, so a view calling one is derived from
the rest of its body alone.

# See also:
- [candidate keys](introspection-keys.md) — `relix.keys`, the other thing the
  statistics know about a relation.
- [introspection stdlib](introspection-stdlib.md) — `relix.schema(rel)` and
  `relix.find(col)`, the column catalog read from either side.
- [reserved namespace](introspection-catalog.md) — `relix.catalog`, this relation's
  counterpart for the `relix.*` names themselves.
- `relix help catalog` — every `relix.*` relation and its columns.
