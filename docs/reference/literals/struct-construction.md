# Name: Struct Construction ({ ... })

# Syntax:
{ field: expr, ... }
{ name }            -- shorthand for { name: name }

π id, { name, age } → person (Users)
π id, { full: name } → person (Users)

# Description:
Struct construction builds a nested object value inside a projection — bundling
several columns into one structured column. Instead of flat `name` and `age`
columns you can produce a single `person` column holding `{ name, age }`. It is
how you reshape flat rows into nested, document-style output.

# Technical Description:
`{ field: expr, … }` builds a StructValue (StructConstruction operand); the
shorthand `{ name }` expands to `{ name: name }`. It is parsed wherever an operand
may appear: a projection, a grouping key, a predicate, a function argument. After
`IN`, braces are a set literal instead. It evaluates to a nested struct value and is
never pushed down to SQL. Its type is a `StructType` of its fields' own inferred
types, computed to any depth: `{base: salary, banded: salary * 1.1}` infers as
`{base: N, banded: N}` because both expressions infer as NUMBER.

# Examples:
Bundle columns into a struct (shorthand):
  π id, { name, age } → person (Users)

Explicit field names:
  π id, { full: name, years: age } → person (Users)

Combine with COLLECT to build an array of structs:
```relix
Built := { π customer, { order_id, amount } → o (Orders) };
query { γ customer, COLLECT(o) → orders (Built) };
```


# Worked Example:
Flat columns folded into a struct, and read back out again.

```relix
People := [
| id | first | last  | city   |
|----|-------|-------|--------|
| 1  | Ada   | Byron | London |
| 2  | Grace | Hop   | Boston |
];

Nested := { π id, {first: first, last: last, home: {city: city}} → person (People) };

query { Nested };
```

```
 id  person
 ──  ──────────────────────────────
  1  {first: Ada, last: Byron, hom…
  2  {first: Grace, last: Hop, hom…
(2 rows)
```

One column instead of three, and the engine knows its shape. Constructing a value
does not put it beyond inference: each field takes the type of the expression that
built it, to any depth. The catalog is where that claim can be read back rather
than taken on trust:

```relix
query { π column, type (σ relation = "Nested" (relix.columns)) };
```

```
 column  type
 ──────  ──────────────────────────────
 id      N
 person  {first:S,last:S,home:{city:S}}
(2 rows)
```

`person` is a struct of three known field types, not `ANY`. That matters
downstream, because a known shape is a shape the engine can check a field name
against.

Reading a field is a dotted projection, and it nests as far as the data does:

```relix
query { π id, person.first → first, person.home.city → city (Nested) };
```

```
 id  first  city
 ──  ─────  ──────
  1  Ada    London
  2  Grace  Boston
(2 rows)
```

Without an alias the output column takes the *field's* name rather than the whole
path, so `π person.first` produces a column called `first`.

A field reference works anywhere an ordinary column reference does — inside a
predicate, a function call, an aggregate:

```relix
query { γ MAX(Len(person.first)) → longest (σ person.home.city = 'London' (Nested)) };
```

```
 longest
 ───────
       3
(1 row)
```

A field name the struct does not declare is an **analysis** error, not a surprise
at run time — the same check a misspelled column gets.

# Limitations:
Produces a nested value that does not push down to SQL. A field whose expression
is itself untyped (a path through an `ANY` column, say) contributes `ANY` to the
struct's type — the shape is only as precise as what it is built from.

# Alternatives:
Array construction ([ … ]) for ordered lists rather than named fields. Keep
columns flat if the consumer cannot handle nested output.

# See Also:
[array-construction](array-construction.md), [project](../operators/project.md), [collect](../aggregates/collect.md), [unnest](../operators/unnest.md)

# Notes:
Because a struct is an operand, braces around a γ grouping key are not an error.
`γ {region}, SUM(amount) → total (Sales)` groups by a one-field struct and
returns one struct column called `group` in place of `region`. See
[group](../operators/group.md) for the example.

Access a struct field downstream with dotted projection (e.g. `π person.name`) —
worked through above. The same spelling is used for a relation qualifier above a
join (`π rooms.name`), and that reading is tried first, so a struct column can
never shadow one. A path whose last segment happens to name a column of the same
relation still reads as a path: over a heading of `(name, skills)` where a skill
has its own `name`, `skills.name` is the skill's, because no column named `name`
originates from a relation called `skills`.
