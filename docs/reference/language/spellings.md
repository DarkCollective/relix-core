# Name: Operator spellings (Unicode and ASCII)

# Syntax:
σ amount > 100 (Orders)        -- Unicode
SELECT amount > 100 (Orders)   -- ASCII, identical meaning

A ⋈ B
A JOIN B

# Description:
Every operator in Relix has two spellings: a Unicode glyph and an ASCII form. They
are accepted in exactly the same places and mean exactly the same thing — the two
forms parse to the same tree, so nothing downstream can tell which one you typed.

Use whichever suits where you are typing. The glyphs read like the algebra they
come from and are what the engine prints back to you; the ASCII forms need no
special keyboard and survive a terminal, an email, or a code review comment
intact. A single expression may mix them freely.

## Unary operators

| Unicode | ASCII keyword | Operation |
|---------|--------------|-----------|
| `π` | `PROJECT` | Projection |
| `σ` | `SELECT` | Selection |
| `ρ` | `RENAME` | Rename |
| `γ` | `GROUP` | Aggregation |
| `τ` | `SORT` | Sort |
| `λ` | `LIMIT` | Limit |
| `δ` | `DISTINCT` | Distinct |
| `μ` | `UNNEST` | Unnest (array→rows) |
| `∀` | `FORALL` | Universal quantification (`∀ keys : P (R)`) |

## Joins

| Unicode | ASCII | Operation |
|---------|-------|-----------|
| `⋈` | `JOIN` | Natural join |
| `⨝` | `><` | Theta join |
| `⟕` | `\|><` | Left outer join |
| `⟖` | `><\|` | Right outer join |
| `⟗` | `\|><\|` | Full outer join |
| `⋉` | `SEMI` | Semi-join |
| `▷` | `ANTI` | Anti-join |

## Set operations

| Unicode | ASCII | Operation |
|---------|-------|-----------|
| `×` | `CROSS` | Cartesian product |
| `∪` | `UNION` | Union |
| `⊎` | `UALL` | Union all |
| `⊔` | `OUNION` | Outer-union (heterogeneous merge) |
| `−` | `DIFF` | Set difference |
| `∆` | `SYMDIFF` | Symmetric difference |
| `∩` | `INTER` | Intersection |
| `÷` | `DIV` | Division |
| `∘` | `COMPOSE` | Composition |

## Logical and comparison operators

| Unicode | ASCII | Operation |
|---------|-------|-----------|
| `∧` | `AND` | Logical and |
| `∨` | `OR` | Logical or |
| `¬` | `NOT` | Logical not |
| `≠` | `!=` | Not equal |
| `≤` | `<=` | Less or equal |
| `≥` | `>=` | Greater or equal |

## Special forms

| Unicode | ASCII | Operation |
|---------|-------|-----------|
| `→` | `->` | Alias arrow (aggregation/projection) |
| `↔` | `<->` | Undirected edge, between a graph operator's two endpoint columns |
| `⊥` | `NULL` | Null check |
| `∈` | `IN` | Element of |
| `∉` | `NOT IN` | Not element of (two tokens) |

## Nullary relation literals

The two relations with the empty (zero-column) heading, written where a relation
goes. They have no glyph: like `TRUE` and `FALSE` they are literals rather than
operators.

| Keyword | Alias | Meaning |
|---------|-------|---------|
| `UNIT` | `DEE` | Empty heading, one (empty) tuple — TRUE; the identity of `×` |
| `EMPTY` | `DUM` | Empty heading, no tuple — FALSE |

## SQL-prior aliases

Extra synonyms for the canonical forms above, for readers arriving from SQL. They
parse to the identical tree; the canonical form is what gets printed back.

| SQL-prior form | Canonical form | AST |
|---|---|---|
| `INTERSECT` | `INTER` / `∩` | IntersectionNode |
| `MINUS`, `EXCEPT` | `DIFF` / `−` | DifferenceNode |
| `x IS NULL` / `x IS NOT NULL` | `x = NULL` / `x ≠ NULL` (`⊥`) | Null predicate |
| `ORDER`, `ORDER BY` | `SORT` / `τ` | SortNode |
| `GROUP BY` | `GROUP` / `γ` | AggregationNode |
| `LJOIN` / `RJOIN` / `FJOIN` | `\|><` / `><\|` / `\|><\|` | outer joins |
| `COUNT(*)` | `COUNT(1)` | AggregateFunction(COUNT, number `1`) |

`BY` is an optional noise word after `ORDER` and `GROUP`. The `MINUS` **keyword** is
set difference while the arithmetic `-` character is unchanged, so `a - b` and
`A MINUS B` never collide.

`COUNT(*)` is SQL's row-count spelling: a literal argument is never NULL, so
counting it counts every row, where `COUNT(expr)` counts non-NULL values. `*` is
special **only** as the sole argument to `COUNT` — `SUM(*)` is a syntax error and
`COUNT(a * b)` is still multiplication.

# Technical Description:
The choice of spelling is made in the lexer and is gone by the time a tree exists:
`σ` and `SELECT` produce the same token, so no phase above the lexer — inference,
validation, optimisation, planning, execution — can distinguish them. Two scripts
differing only in spelling optimise to the same plan and push down the same query.

Printing goes one way only. A tree rendered back to text — by a session's own
report, or by the pretty-printer behind it — comes out in the **canonical** form:
the glyph for an operator that has one, and the canonical keyword for an alias. A
script written in ASCII and printed back therefore comes back in Unicode, and a
script written with `INTERSECT` comes back with `∩`. The round trip preserves the
tree, not the typing.

The ASCII forms are ordinary keywords, which is why a relation or column whose name
collides with one needs backticks — see the delimited identifier page. The glyphs
are not keywords and never collide, so `σ` is always the operator and a relation may
be called `select` as long as it is written `` `select` ``.

# Examples:
The same filter, both ways:

```relix
Adults := { σ age > 18 (Users) };
```

```relix
AdultsAscii := { SELECT age > 18 (Users) };
```

A natural join, both ways:

```relix
Joined := { A ⋈ B };
```

```relix
JoinedAscii := { A JOIN B };
```

Mixing the two in one expression is allowed, and is what most scripts end up doing
— the glyph for the operator, a word for the connective:

```relix
ActiveAdults := { SELECT age > 18 ∧ active = "yes" (Users) };
```

An outer join written in ASCII, with an explicit condition:

```relix
Everyone := { Users |><| Users.id = Orders.user_id Orders };
```

The SQL-prior aliases, which read naturally to someone arriving from SQL:

```relix
ByRegion := { GROUP BY region, SUM(amount) → revenue (Orders) };
```

```relix
Placed := { σ shipped_at IS NULL (Orders) };
```

# Limitations:
`∉` is two tokens in its ASCII form (`NOT IN`) where the glyph is one. This makes
no difference to what parses, but it is the one row of the table where the ASCII
column is not a single word.

There is no ASCII spelling that is *only* ASCII: every form in the left column of
these tables is accepted everywhere, so a script cannot be made to reject glyphs.
A team that wants ASCII-only sources enforces that in review, not in the grammar.

Aggregate names (`SUM`, `COUNT`, `MIN`, …) have no glyph. They are function names
rather than operators, so they are written the same way whichever spelling the
surrounding expression uses.

# See Also:
- [grammar (EBNF)](grammar.md) — every spelling on this page in its place in the
  whole language, one rule at a time
- [comments](comments.md) — the other lexical rule worth knowing before writing a
  script, and the one place `//` will surprise you
- [delimited identifier](delimited-identifier.md) — backticking a name that
  collides with one of the ASCII keywords above
- [truth relations](../literals/truth-relations.md) — the full page for `UNIT` and
  `EMPTY`
