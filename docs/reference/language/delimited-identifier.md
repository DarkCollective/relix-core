# Name: Delimited Identifier (`` `name` ``)

# Syntax:
`` `name` ``            -- any name, wrapped in backticks
`` `order` ``           -- the relation/column named "order", not the ORDER operator
`` `my table` ``        -- a name containing spaces
`` `a``b` ``            -- a name containing a literal backtick (doubled to escape)

# Description:
Relix reserves a fixed set of operator and keyword words — `SELECT`, `SORT`,
`ORDER`, `GROUP`, `JOIN`, `UNION`, `IS`, `IN`, and so on (the full set is the
lexer keyword table; `relix help lang operators` prints it). Written bare
in a relation position, such a word is read as the operator, so a relation or
view that happens to be named after one cannot be referenced directly:

```
π name (order)     -- ERROR: 'order' is read as the ORDER/τ operator
```

A **delimited identifier** — the name wrapped in backticks — tells the lexer to
treat the text as a plain name, never an operator or keyword:

```
π name (`order`)   -- OK: 'order' is the relation named "order"
```

Backticks also let a name contain characters an ordinary identifier can't
(spaces, punctuation): `` `line total` ``, `` `2024 q1` ``. A literal backtick
inside the name is written by doubling it: `` `a``b` `` is the name `` a`b ``.

This is the same escape hatch SQL provides with double-quotes (ANSI) or backticks
(MySQL). Relix uses backticks because single and double quotes are already string
literals.

# Technical Description:
The lexer emits a delimited identifier as an ordinary `IDENTIFIER` token whose
value is the decoded text (backticks stripped, doubled backticks collapsed), so
every downstream consumer treats it exactly like a normal name — there is no
distinct AST node and no semantic difference from an unreserved bare name. An
unterminated identifier (end of input or a newline before the closing backtick)
or an empty `` `` `` is a parse error. An empty name:

```relix-invalid
query { π `` (Orders) };
```

A name that reaches the end of the line before its closing backtick:

```relix-invalid
query { π `order
id` (Orders) };
```

The pretty-printer performs the inverse: when it prints a **relation** or
**column** reference whose name collides with a reserved word (or is not
identifier-shaped), it re-adds the backticks, so a printed tree always parses
back to the identical tree. Operator-introduced parameter names (the columns
after `AS`, `VIA`, `BY`, `PER`, `HOPS`, a rename column list, a projection or
aggregate alias) are read leniently by the parser and are printed bare.

# Examples:
A view named after the RA operator word `order` — **defined bare** (a name is a
plain identifier on the left of `:=`; only the operator words are reserved, and
only inside an expression) and **referenced with backticks** downstream:

```relix
order := { σ status = "open" (RawOrders) };
Open  := { π id, total (`order`) };
query { Open };
```

Join two relations, one named after a reserved word (`select`), and filter on a
column whose name also collides (`order`):

<!-- analysis-skip: a relation named with a delimited identifier can only come from a catalog, so this example cannot declare what it references -->
```relix
Recent := { `select` ⋈ Users };
query { σ `order` = 1 (Events) };
```

A qualified column reference delimits only the reserved segment:

```
σ `order`.total > 0 (Sales)     -- 'order' the table, 'total' an ordinary column
```

# Limitations:
- The backtick delimiter is fixed; there is no alternate quoting character.
- A name may not span a line — the closing backtick must be on the same line.
- A single delimited name that itself contains a dot (`` `a.b` ``) is stored as
  the bare string `a.b`, indistinguishable from a qualified reference, so it
  re-renders as two segments (`` `a`.`b` ``). Such names are vanishingly rare.
- Backticks are recognised inside relational-algebra expressions (view bodies,
  `query { … }`). A **statement-level** name — the left side of `:=` or a
  `source` / `def` declaration — is a bare identifier and does not (yet) accept
  backticks, so a name that collides with a *statement* keyword (`source`,
  `query`, `import`, `namespace`, …) cannot be used there. The RA operator words
  (`order`, `select`, `sort`, …) are not statement keywords, so they work bare on
  the left of `:=` and only need backticks where they are *referenced*.

# Alternatives:
Prefer a non-reserved name when you control the schema — delimiting is for names
you don't control (imported schemas, external tables) or that genuinely need
unusual characters.

# See Also:
[assignment & query](assignment.md), [source](source.md),
[reference index](../README.md)

# Notes:
Delimiting a name that does **not** need it is harmless and a no-op on output —
`` `Users` `` parses to exactly the same relation as `Users`.
