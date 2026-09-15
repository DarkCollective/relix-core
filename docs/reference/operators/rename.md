# Name: Rename (ρ / RENAME)

# Syntax:
ρ <NewName> (Relation)                       -- rename the relation only
ρ <NewName>(col1, col2, ...) (Relation)      -- rename relation and all columns
ρ <NewName> (old → new, ...) (Relation)      -- rename only the listed columns
ρ (old → new, ...) (Relation)                -- rename columns, keep the relation name
RENAME ...

ρ E (Employees)
ρ E(eid, ename, dept_id) (Employees)
ρ E (name → full_name) (Employees)
ρ (name → full_name) (Employees)

# Description:
Rename gives a relation — and optionally some or all of its columns — new names.
It is most useful when you need to join a relation to itself, or when two
relations have clashing column names and you want to tell them apart.

There are three column forms:

- **relation only** — name the relation, leave every column untouched;
- **positional** — supply a full list of new column names (the list must match
  the column count exactly);
- **pairs** — `old → new` renames only the columns you list; every unlisted
  column passes through unchanged. This is the textbook ρ_{a→b}(R) form, and it
  is the terse way to disambiguate a single colliding column in a wide relation.

The pair form and the positional form cannot be mixed in one list. In the pair
form the new relation name is optional: `ρ (name → full_name) (R)` renames the
column in place, keeping the relation's existing name and each surviving column's
origin qualifier.

# Technical Description:
ρ_S(R) renames the relation to S; ρ_S(a1,…,an)(R) additionally renames the
attributes positionally (arity equality with R's schema is required, or it is a
validation error). ρ_S(a→x, b→y)(R) renames exactly the named attributes and
leaves the rest, matching source names case-insensitively. A rename in the pair
form is a validation error when a source column does not exist, when the same
source is renamed twice, or when a target name collides with another surviving
or renamed column.

Rename is a streaming, order-preserving operator; a relation-only rename is
transparent to the optimizer and to ordering derivation. Any column-renaming form
(positional or pairs) invalidates the input's delivered ordering column names, so
it is not treated as order-preserving.

Rename also **re-anchors column provenance**: after `ρ S (…)` every output
column's relation qualifier becomes `S`, so a later qualified reference
`S.column` resolves to it. This is what makes a self-join work — `ρ M(…)
(Employees)` gives the second copy the qualifier `M`, so `M.mid` and
`Employees.manager_id` refer to different sides even though both originate from
`Employees`. When the pair form omits the new relation name, each renamed column
keeps its existing origin qualifier under its new name.

# Examples:
Self-join needs two names for the same table — find employees who manage someone:
```relix
Mgr := { ρ M(mid, mname, mdept, mmanager, msalary) (Employees) };
query { π mname (Employees ⨝ Employees.manager_id = Mgr.mid Mgr) };
```

Give a result set tidy column names before exporting:
  ρ Report(country, total_sales) (γ region, SUM(amount) → s (Sales))

Rename just the relation (columns kept) to disambiguate a union:
  ρ Archived (OldOrders) ∪ ρ Live (NewOrders)

**Worked example — disambiguate one colliding column with a pair rename.**
`devices` and `rooms` both carry a `name` column, so joining them makes `name`
ambiguous. The pair form renames only `rooms.name`, leaving `room_id` alone, so
the join reads cleanly — no need to restate every column of `rooms`.

```
devices                          rooms
┌───────────┬─────────┬───────┐  ┌─────────┬────────────┐
│ device_id │ room_id │ name  │  │ room_id │ name       │
├───────────┼─────────┼───────┤  ├─────────┼────────────┤
│ 1         │ 10      │ Therm │  │ 10      │ LivingRoom │
│ 2         │ 20      │ Lamp  │  │ 20      │ Bedroom    │
└───────────┴─────────┴───────┘  └─────────┴────────────┘
```

```relix
Joined := {
  π name → device, room_name → room
    (devices ⨝ devices.room_id = RoomsR.room_id
      (ρ RoomsR (name → room_name) (rooms)))
};
```

`ρ RoomsR (name → room_name) (rooms)` yields `room_id, room_name` (a two-column
relation whose `room_id` is untouched), so the join key stays `room_id` and the
two name columns are now distinct. The result:

```
┌────────┬────────────┐
│ device │ room       │
├────────┼────────────┤
│ Therm  │ LivingRoom │
│ Lamp   │ Bedroom    │
└────────┴────────────┘
```

# Limitations:
The positional form names columns **by position**, which a schema-on-read source
(JSON, HTTP, MongoDB) does not have — each document carries the fields it carries,
in its own order. Use the pair form there, `ρ (old → new, …)`, which names the
columns explicitly and works over any relation.

The positional form must list exactly as many names as the relation has columns.
To rename a subset, use the pair form (`ρ (old → new, …) (R)`) or projection with
aliases (`π old → new, …`). A pair rename must not rename a column onto a name
that another column already keeps.

# Alternatives:
π with → aliases renames selected columns and can also drop/reorder them. Use ρ
when you want to keep all columns and only change names — the pair form makes a
subset rename as terse as a projection alias while preserving column order and
every unlisted column.

# See Also:
[project](project.md), [natural-join](../joins/natural-join.md), [theta-join](../joins/theta-join.md), [union](../set-operations/union.md)

# Notes:
Because a relation-only rename changes no data, the optimizer treats it as
order- and distinctness-preserving and can push selections through it. A
column-renaming rename (positional or pairs) still keeps row identity, so
distinctness survives, but the optimizer rewrites a selection's column references
through the rename when pushing it below.
