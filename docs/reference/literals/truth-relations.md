# Name: Truth relation literals (UNIT / EMPTY, a.k.a. DEE / DUM)

# Syntax:
UNIT      -- alias: DEE
EMPTY     -- alias: DUM

query { Orders × UNIT };
query { EMPTY };

# Description:
`UNIT` and `EMPTY` are the two relations that have no columns at all. A relation
with no columns can hold at most one row — the row with nothing in it — so there
are exactly two of them, and they read as truth values: `UNIT` holds that one
empty row ("yes"), `EMPTY` holds nothing ("no").

They are useful for two things. `UNIT` is the neutral element of `×`: crossing
any relation with it gives you that relation back unchanged, which lets a
composed pipeline have a "nothing to add here" case that needs no special
handling. And because a no-key `∀` answers a yes/no question by producing one of
these two relations, crossing a result with that answer gates the result on the
question — rows survive only when the answer was yes.

`DEE` and `DUM` are accepted as aliases; they are the names these relations carry
in Tutorial D and the relational-model literature.

# Technical Description:
`UNIT`/`DEE` and `EMPTY`/`DUM` are keywords in **relation** position that parse to
a `TruthRelationNode` — a leaf of the `RelNode` hierarchy, holding one boolean:
`UNIT` denotes cardinality 1, `EMPTY` cardinality 0. Both infer to `Schema.empty()`,
the *closed* zero-column heading (distinct from the *open* schema, which is also
column-less but resolves every column reference). This is the same heading the
no-key whole-relation universal quantifier `∀ : P (R)` already produces, so every
phase below the parser is width-0 tolerant by construction.

Keyword matching is case-insensitive, and there is no glyph form — like `TRUE` and
`FALSE` these are literals rather than operators. All four words are reserved in
relation position; backtick them (`` `unit` ``) to name a relation `unit`.

Downstream properties: cardinality is exact (1 or 0) rather than estimated, the
cost tier is `INLINE`, the literal is whole-row distinct and `BOUNDED`, and it is
never pushed down to a backend (it has no connection to push to). Under
`--provenance` the literals *are* the semiring identities: `UNIT` is the empty
tuple annotated `one()` (contributing no lineage of its own) and `EMPTY` is the
empty K-relation. The planner emits an ordinary inline scan over the empty schema,
so no new physical operator or executor path exists for them.

The optimizer rule **PROD-001** removes a product against `UNIT`
(`R × UNIT → R`, `UNIT × R → R`). There is deliberately no companion rule for
`EMPTY`: `R × EMPTY` yields no rows but keeps `R`'s heading, so it is not the
zero-column `EMPTY` and the two cannot be substituted for each other.

# Examples:

## `UNIT` is the identity of `×`

`Trades × UNIT` pairs every trade row with the one empty row, which appends
nothing — the same rows, in the same column order:

| trade_id | desk | status  |     `× UNIT` →     | trade_id | desk | status  |
|----------|------|---------|--------------------|----------|------|---------|
| 1        | FX   | settled |                    | 1        | FX   | settled |
| 2        | FX   | pending |                    | 2        | FX   | pending |
| 3        | RATES| settled |                    | 3        | RATES| settled |

`Trades × EMPTY` is the other case: no row of `Trades` finds a partner, so the
result is **empty but still three columns wide** — it is not `EMPTY` itself.

```relix
Trades := [
| trade_id | desk  | status  |
|----------|-------|---------|
| 1        | FX    | settled |
| 2        | FX    | pending |
| 3        | RATES | settled |
];

-- Identical to `query { Trades }`; the optimizer drops the × (PROD-001).
query { Trades × UNIT };
```

## Gating a result on a yes/no question

A no-key `∀` asks a question about a whole relation and answers with one of these
two relations: `UNIT` when every row satisfies the predicate, `EMPTY` when some
row does not. Crossing a report with that answer therefore *gates* the report —
it survives only when the answer is yes.

The desk-sign-off scenario: publish the end-of-day banner only if every trade has
settled.

```relix
Trades := [
| trade_id | desk  | status  |
|----------|-------|---------|
| 1        | FX    | settled |
| 2        | FX    | pending |
| 3        | RATES | settled |
];

Banner := [
| message                    |
|----------------------------|
| Book is flat — signed off. |
];

-- UNIT if every trade settled, EMPTY otherwise (a zero-column relation).
AllSettled := { ∀ : status = "settled" (Trades) };

-- One banner row when AllSettled is UNIT; no rows when it is EMPTY.
query { Banner × AllSettled };
```

Trade 2 is still pending, so `AllSettled` is `EMPTY` and the query returns no
rows. Settle trade 2 and `AllSettled` becomes `UNIT`, so the same query returns
the single banner row — the `×` did not have to change.

```
Trades ──► ∀ : status = "settled" ──► AllSettled   (UNIT or EMPTY, 0 columns)
                                          │
Banner ───────────────────────────────────┴──► ×  ──► 1 row, or none
```


# Worked Example:
The two zero-column relations, printed. This is worth seeing once, because a
relation with no columns and one row does not look like anything else:

```relix
query { UNIT };
```

```
(1 row)
```

```relix
query { EMPTY };
```

```
(0 rows)
```

One row against no rows — with nothing in either row, since neither relation has
a column to hold anything. That is the whole difference between them, and it is
why they read as yes and no.

Crossing with them is where they earn their place. `UNIT` is the identity of `×`:

```relix
Orders := [
| order_id | amount |
|----------|--------|
| 100      | 120    |
| 101      | 80     |
];

query { Orders × UNIT };
```

```
 order_id  amount
 ────────  ──────
      100     120
      101      80
(2 rows)
```

Unchanged — same rows, same heading, because concatenating a zero-column row adds
nothing. `EMPTY` is the annihilator:

```relix
query { Orders × EMPTY };
```

```
 order_id  amount
 ────────  ──────
(0 rows)
```

Every row of the left had to pair with some row of the right, and there were
none. Note the heading survives even though no row does: `Orders × EMPTY` is an
empty relation *of orders*, not a relation with no columns.

# Limitations:
Zero-column relations are not union-compatible with anything else, so
`R ∪ UNIT` is a validation error unless `R` also has the empty heading. There is
no glyph form. Neither literal pushes down to a SQL or MongoDB backend. `R × EMPTY`
is **not** rewritten to `EMPTY` — it keeps `R`'s heading.

Because all four spellings are reserved in relation position, a relation actually
named `unit`, `dee`, `empty`, or `dum` is only reachable through a
[delimited identifier](../language/delimited-identifier.md) — the declaration
itself is accepted, but a bare reference reads as the literal.

# Alternatives:
A no-key `∀ : P (R)` produces these relations without naming them, and is the
usual way one appears in a query. To express "no rows" with a specific heading,
filter that relation with a false predicate rather than reaching for `EMPTY`.

# See Also:
[forall](../operators/forall.md), [cross](../set-operations/cross.md), [delimited-identifier](../language/delimited-identifier.md), [inline-table](../language/inline-table.md)

# Notes:
The lineage names are Tutorial D's `TABLE_DEE` and `TABLE_DUM` (Date & Darwen,
*The Third Manifesto*), named after Tweedledee and Tweedledum — a pair with no
semantic cue as to which is which, which is exactly why Relix leads with
`UNIT`/`EMPTY` and keeps `DEE`/`DUM` as aliases.
