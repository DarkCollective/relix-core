# Name: connection … from gedcom (GEDCOM genealogy source)

# Syntax:
connection <Name> from gedcom { path: "<file.ged>" };

source <Name> from <connection> { table: "individuals" | "families",
                                  schema: { <col>: <TYPE>, … } };

connection ancestry from gedcom { path: "family.ged" };

# Description:
GEDCOM is the interchange format every genealogy program reads and writes. A
`.ged` file holds a whole family tree — people, families, events — and relix
reads it as two relations: `individuals` and `families`.

It is a **connection** rather than a file source for one reason: a CSV file is one
relation and a `.ged` file is several, and a connection is exactly the thing several
tables share. You declare the file once and bind each table you want.

# Technical Description:
`path` names the `.ged` file (`url` with an optional `file:` scheme is accepted as
well, since that is how every other connection type is configured). The path is
resolved against the process's working directory, not the script's.

Two tables are available, and a `source … from <connection>` binds one:

| Table | Fields |
|---|---|
| `individuals` | `id`, `name`, `surname`, `sex`, `birth`, `death` |
| `families` | `id`, `husband`, `wife`, `children` |

`birth` and `death` are events, each with `date`, `text` and `place`. `children` is
an array of individual ids. `id`, `husband`, `wife` and each element of `children`
are GEDCOM cross-reference ids with the delimiting `@` removed, so `@I1@` is `I1`.

**A schema is optional.** GEDCOM's shape is fixed by the format rather than by the
file, so the connector describes both tables itself and a dotted reference
(`ancestry.individuals`) resolves its columns with nothing declared. The heading it
gives is the full one in the table above, typed and nested.

**A declared schema decides the heading instead.** Every column is filled by name,
at every level, and a column the file has nothing for is NULL rather than an error.
A schema naming only `id` and `name` gets two columns; a nested
`birth: { place: STRING }` gets a one-field struct. Declare one to narrow a
relation, to rename its columns, or to state what a query depends on — and note
that the same rule is what makes a format as extensible as GEDCOM safe to declare a
heading over at all: a file cannot widen a relation behind a query's back.

**A date is exposed twice, and the pair is the point.** GEDCOM permits
approximations and ranges (`ABT 1900`, `BET 1900 AND 1910`) as well as exact days,
so `date` is a real `DATE` when the value names an exact day and NULL otherwise,
while `text` always holds what the file said. Both NULL means no date was recorded;
a NULL `date` beside a present `text` means the date is not exact. One column
cannot tell those apart, and a genealogist cares which it is.

**A family is a hyperedge** — two parents and any number of children — so the
binary `(parent, child)` edge the graph operators take is a projection over
`μ children (Families)` rather than something the connector invents. Keeping the
family row is what lets a query distinguish full siblings from half siblings, which
a binary edge alone cannot.

# Examples:
Declare the file and bind both tables:

```relix
connection ancestry from gedcom { path: "family.ged" };

source Individuals from ancestry { table: "individuals",
    schema: { id: STRING, name: STRING, surname: STRING, sex: STRING,
              birth: { date: DATE, text: STRING, place: STRING } } };

source Families from ancestry { table: "families",
    schema: { id: STRING, husband: STRING, wife: STRING, children: [STRING] } };
```

The binary `(parent, child)` edge every graph operator takes is `μ` over the
`children` array and a projection — a family is a hyperedge, so both parents
contribute an edge to each child:

```relix
connection ancestry from gedcom { path: "family.ged" };

source Families from ancestry { table: "families",
    schema: { id: STRING, husband: STRING, wife: STRING, children: [STRING] } };

Parents := {
    π husband → parent, children → child (μ children (Families))
  ∪ π wife → parent, children → child (μ children (Families))
};

query { Parents };
```

Nothing declared at all — the connector supplies the heading:

```relix
connection ancestry from gedcom { path: "family.ged" };
query { π name, sex (ancestry.individuals) };
```

Only the columns a query needs:

```relix
connection ancestry from gedcom { path: "family.ged" };
source Names from ancestry { table: "individuals",
    schema: { id: STRING, name: STRING } };
query { Names };
```

# Worked Example:
The edge below is what the projection in the last example produces — one row per
parent-child pair, a two-parent family contributing two. It is declared inline here
so every query on this page runs as written:

```relix
Parents := [
| parent | child |
|--------|-------|
| I1     | I3    |
| I2     | I3    |
| I3     | I5    |
| I4     | I5    |
];

Individuals := [
| id | name         |
|----|--------------|
| I1 | John Smith   |
| I2 | Mary Jones   |
| I3 | Alice Smith  |
| I4 | Peter Brown  |
| I5 | Robert Smith |
];
```

Every ancestor of every person, however many generations back, is one operator over
that edge — the query SQL needs a recursive CTE for:

```relix
query { τ ancestor ASC, descendant ASC
        (ρ Ancestry(ancestor, descendant) (CLOSURE parent, child (Parents))) };
```

```
 ancestor  descendant
 ────────  ──────────
 I1        I3
 I1        I5
 I2        I3
 I2        I5
 I3        I5
 I4        I5
(6 rows)
```

`I1` and `I2` reach `I5` although neither is a parent of theirs: that is the
grandparent relation, and nothing in the query mentions generations.

Naming the people rather than the ids is an ordinary join:

```relix
query {
    τ ancestor ASC, descendant ASC
    (π a.name → ancestor, d.name → descendant
      (ρ a (Individuals) ⨝ a.id = anc (
        ρ Named(anc, desc) (CLOSURE parent, child (Parents))
      ) ⨝ desc = d.id (ρ d (Individuals))))
};
```

```
 ancestor     descendant
 ───────────  ────────────
 Alice Smith  Robert Smith
 John Smith   Alice Smith
 John Smith   Robert Smith
 Mary Jones   Alice Smith
 Mary Jones   Robert Smith
 Peter Brown  Robert Smith
(6 rows)
```

How far apart two people are is `PATH` over the same edge read **undirected**, so a
line of descent can be walked in either direction — which is what makes a sibling,
a cousin or a spouse reachable at all, none of whom is anybody's ancestor:

```relix
query { τ parent ASC, child ASC
        (σ parent = "I3" (PATH parent ↔ child HOPS 1 TO 4 AS degrees (Parents))) };
```

```
 parent  child  degrees
 ──────  ─────  ───────
 I3      I1           1
 I3      I2           1
 I3      I3           2
 I3      I4           2
 I3      I5           1
(5 rows)
```

Both parents are one step away, the other parent of `I3`'s child is two (up through
the child and back down), and `I3` reaches **itself** at two for the same reason —
an undirected walk may return the way it came. A genealogical distance is a question
about that graph, so the answer is the graph's rather than the question's.

# Limitations:
The path is resolved against the working directory rather than the script's, so a
script moved to another directory needs an absolute path or a matching working
directory.

Tags outside the two tables' fields are skipped rather than carried: notes,
sources, repositories and vendor extensions are not exposed. A relation's heading
is what decides the answer, and carrying everything a GEDCOM file may contain would
mean carrying an open one.

A connection's name must not be a word the expression grammar already uses, or a
dotted reference to it will not parse — `tree` is the `TREE` operator, so
`connection tree …` declares fine and `tree.individuals` does not parse. Any name
that is not an operator keyword is safe.

# See Also:
- [connection](connection.md) — the declaration this one is a kind of
- [CLOSURE](../advanced/closure.md) — ancestors and descendants over the edge
- [PATH](../advanced/path.md) — degrees of separation, with the distance
- [UNNEST](../operators/unnest.md) — the `μ` that turns `children` into rows

# Notes:
GEDCOM 5.5.1 is the version read. The reader is tolerant by design: a line it
cannot place is skipped rather than refused, because a reader that rejects a file
every genealogy program accepts is worse than one that ignores what it does not
understand.
