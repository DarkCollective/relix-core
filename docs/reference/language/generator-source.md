# Name: source … from generator (generated series)

# Syntax:
source <Name> from generator { name: "<generator>", <argument>: "<value>", … };

source Small from generator { name: "Range", lo: "1", hi: "10" };
source Whole from generator { name: "Naturals" };
source Prime from generator { name: "Primes" };

# Description:
A generator is a relation whose rows the engine makes up rather than reads from
anywhere: the numbers from 1 to 10, every whole number, every prime. It is declared like
any other source, naming the series. Nothing has to be stored and nothing has to be
fetched, so it is always available.

It is useful as the spine of something else — a calendar to left-join sparse readings
onto, a set of ids to probe with, the rows of a test fixture — and, for the two
generators that never end, as a source that a bound turns into a finite relation.

# Technical Description:
`name` selects the generator, and every other entry is an argument to it. Each argument
is written as a double-quoted string. Three generators are built in:

| Generator | Arguments | Rows | Ends? |
|---|---|---|---|
| `Range` | `lo` and `hi` (required, integers); `step` (optional, a positive integer, default 1) | one column `n`: `lo`, `lo + step`, … up to `hi`, inclusive; none when `lo` is greater than `hi` | yes |
| `Naturals` | none | one column `n`: 0, 1, 2, … | no |
| `Primes` | none | one column `n`: 2, 3, 5, 7, … | no |

Each generator owns its heading, so a generator source declares no `schema`. All three
produce a single `n` column of type NUMBER, in ascending order and without duplicates.

An unknown generator name, a missing required argument, or an argument that is not an
integer is an error, naming the generator.

**An unbounded generator is a bounded relation once a bound sits above it.** `Naturals`
and `Primes` have no last row, so a query that would have to read them whole — a
grouping, a sort, a distinct count — is refused when it is planned, while one that stops
is fine: `λ 5 (Naturals)` pulls five rows, and `σ n < 20 (Primes)` is turned by the
optimizer into a generator that stops at 20 (see [optimizer](../advanced/optimizer.md),
rule `GEN-001`). The `relix.relations` catalog reports each generator's
`boundedness`.

# Examples:
The numbers 1 to 10 in steps of 3:

```relix
source Small from generator { name: "Range", lo: "1", hi: "10", step: "3" };

query { Small };
```

```
 n 
 ──
  1
  4
  7
 10
(4 rows)
```

The first three whole numbers, from a series that never ends:

```relix
source Whole from generator { name: "Naturals" };

query { λ 3 (Whole) };
```

```
 n
 ─
 0
 1
 2
(3 rows)
```

A bound above the generator is enough:

```relix
source Whole from generator { name: "Naturals" };

query { σ n < 3 (Whole) };
```

The primes below 30, found by the engine stopping rather than by filtering a series
that never ends:

```relix
source Prime from generator { name: "Primes" };

query { σ n < 30 (Prime) };
```

# Limitations:
Only the three generators above exist. A generator's arguments are fixed numbers: they
cannot be read from another relation's columns.

A query that needs every row of an unbounded generator is refused instead of running
without end.

# Alternatives:
For a generator of ids or dates to join against, `Range` is usually enough. For a set of
rows you write out, an [inline table](inline-table.md).

# See Also:
[source](source.md), [inline-table](inline-table.md), [limit](../operators/limit.md), [relation catalog](introspection-relations.md)

# Notes:
Because the series is a function of its arguments, a generator always returns the same
rows when asked again.
