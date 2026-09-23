# Relix Language Reference

This is the complete reference for the Relix relational-algebra language — one page
per keyword, operator and built-in function. The pages are arranged below in a
**learning order**: start with [getting started](getting-started.md), which runs a
first script end to end, then work down through the statements of a `.relix` file
and the core operators toward joins, set algebra, aggregation, nested data and the
advanced operators. The [glossary](glossary.md) defines the vocabulary the rest of
the manual assumes.

Every operator has both a **Unicode** form (e.g. `σ`, `⋈`, `∪`) and an **ASCII**
keyword form (e.g. `SELECT`, `JOIN`, `UNION`); the two are interchangeable and
produce identical results. Each page gives the syntax, a plain-English
description, real-world examples, and "See Also" links to related pages. The
harder operators include a fully worked example with sample data and the expected
result.

> New to relational algebra? Read the sections in order. Already fluent? Jump
> straight to the operator you need from the tables below.

---

## 1. Getting started

New here? This is the whole language in one page: bring data in, filter and
combine it, name the interesting steps, ask for a result. Everything below is
detail on one of those four moves.

| Page | What it does |
|------|--------------|
| [Getting started](getting-started.md) | Your first script — declare data, name a result, run it |
| [Glossary](glossary.md) | The vocabulary: relation, tuple, bag vs set, materialisation, pushdown |

## 2. Language & scripts

The shape of a `.relix` file — declaring data, naming results, and reusing code.

| Page | What it does |
|------|--------------|
| [source](language/source.md) | Declare an external data source (CSV, database, …) |
| [http source](language/http-source.md) | Read JSON from an HTTP/REST API (headers, auth, body) |
| [connection](language/connection.md) | Share one database connection across many sources |
| [gedcom source](language/gedcom-source.md) | Read a GEDCOM genealogy file as individuals and families |
| [relate](language/relate.md) | Declare named, bounded relationships between relations (schema graph) |
| [inline-table](language/inline-table.md) | Embed small reference data directly in a script |
| [assignment & query](language/assignment.md) | Name a view with `:=` and mark a result `query` |
| [delimited identifier](language/delimited-identifier.md) | Backtick a name that collides with a reserved word (`` `order` ``) |
| [comments](language/comments.md) | `--` to end of line, `/* … */` for a block |
| [operator spellings](language/spellings.md) | Every operator's Unicode glyph and its ASCII equivalent |
| [grammar (EBNF)](language/grammar.md) | The whole language as one EBNF grammar — every statement, operator and token |
| [namespace](language/namespace.md) | Declare the script's namespace |
| [import](language/import.md) | Reuse symbols defined in another file |
| [def](language/def.md) | Define a scalar (per-value) function |
| [def … : RELATION](language/def-relation.md) | Define a table-valued (relation-returning) function |
| [relation catalog](language/introspection-relations.md) | `relix.relations` — every relation the script can name, with its kind, size and whether it ends |
| [introspection stdlib](language/introspection-stdlib.md) | `relix.unused` / `relix.deps` / `relix.impact` / `relix.find` / `relix.schema` / `relix.cycles` / `relix.funcs` — the engine's own introspection, shipped as Relix |
| [observability feed](language/introspection-events.md) | `relix.events` / `relix.rules` — the previous run's engine decisions, as queryable rows |
| [component inventory](language/introspection-version.md) | `relix.version` — the engine, the facade and every discovered provider, as queryable rows |
| [candidate keys](language/introspection-keys.md) | `relix.keys` — the collected candidate keys of every relation, one row per key column |
| [reserved namespace](language/introspection-catalog.md) | `relix.catalog` — the `relix.*` surface describing itself |

## 3. Core operators

The everyday relational-algebra operators — the ones you reach for first.

| Operator | Unicode / ASCII | What it does |
|----------|-----------------|--------------|
| [Selection](operators/select.md) | `σ` / `SELECT` | Keep rows that match a condition |
| [Projection](operators/project.md) | `π` / `PROJECT` | Pick, reorder, rename or compute columns |
| [Rename](operators/rename.md) | `ρ` / `RENAME` | Rename a relation or its columns |
| [Distinct](operators/distinct.md) | `δ` / `DISTINCT` | Remove duplicate rows |
| [Sort](operators/sort.md) | `τ` / `SORT` / `ORDER [BY]` | Order rows by one or more keys |
| [Limit](operators/limit.md) | `λ` / `LIMIT` | Take the first N rows (with optional offset) |

## 4. Predicates

The conditions that go inside a `σ` (selection) or join.

| Page | Unicode / ASCII | What it does |
|------|-----------------|--------------|
| [Comparison](predicates/comparison.md) | `= ≠ < ≤ > ≥` | Compare two values |
| [And](predicates/and.md) | `∧` / `AND` | Both conditions hold |
| [Or](predicates/or.md) | `∨` / `OR` | Either condition holds |
| [Not](predicates/not.md) | `¬` / `NOT` | Negate a condition |
| [Null test](predicates/is-null.md) | `⊥` / `NULL` / `IS [NOT] NULL` | Test for a missing value |
| [Set membership](predicates/in.md) | `∈` / `IN`, `∉` / `NOT IN` | Test membership in a set |
| [Pattern match](predicates/like.md) | `LIKE`, `NOT LIKE` | Wildcard string match (`%` = any run, `_` = any one char) |

## 5. Joins

Combining two relations. The outer joins keep unmatched rows (with NULLs); the
semi/anti joins filter the left relation by the existence of a match.

| Join | Unicode / ASCII | What it does |
|------|-----------------|--------------|
| [Natural join](joins/natural-join.md) | `⋈` / `JOIN` | Match on shared column names |
| [Theta join](joins/theta-join.md) | `⨝` / `><` | Match on an explicit condition |
| [Left outer join](joins/left-outer-join.md) | `⟕` / `\|><` / `LJOIN` | Keep all left rows |
| [Right outer join](joins/right-outer-join.md) | `⟖` / `><\|` / `RJOIN` | Keep all right rows |
| [Full outer join](joins/full-outer-join.md) | `⟗` / `\|><\|` / `FJOIN` | Keep all rows from both sides |
| [Semi join](joins/semi-join.md) | `⋉` / `SEMI` | Left rows that *have* a match |
| [Anti join](joins/anti-join.md) | `▷` / `ANTI` | Left rows that have *no* match |
| [AS-OF join](joins/asof-join.md) | `ASOF` | Match each row to the nearest-in-time row |
| [Interval join](joins/interval-join.md) | `IJOIN` | Match intervals by an Allen relation (OVERLAPS, DURING, …) |
| [Lateral join](joins/lateral-join.md) | `LATERAL` | Invoke a TVF per left row with correlated column arguments |

## 6. Set operations

Treating relations as sets of rows.

| Operation | Unicode / ASCII | What it does |
|-----------|-----------------|--------------|
| [Union](set-operations/union.md) | `∪` / `UNION` | Rows in either (deduplicated) |
| [Union all](set-operations/union-all.md) | `⊎` / `UALL` | Rows in either (keeps duplicates) |
| [Outer-union](set-operations/outer-union.md) | `⊔` / `OUNION` | Merge different-schema relations (align common columns, NULL-pad the rest) |
| [Intersection](set-operations/intersection.md) | `∩` / `INTER` / `INTERSECT` | Rows in both |
| [Difference](set-operations/difference.md) | `−` / `DIFF` / `MINUS` / `EXCEPT` | Rows in the first but not the second |
| [Symmetric difference](set-operations/symmetric-difference.md) | `∆` / `SYMDIFF` | Rows in exactly one |
| [Cartesian product](set-operations/cross.md) | `×` / `CROSS` | Every combination of rows |
| [Division](set-operations/division.md) | `÷` / `DIV` | Which X relate to *all* of the Y |
| [Composition](set-operations/composition.md) | `∘` / `COMPOSE` | Chain on a shared column and drop it |

## 7. Grouping & aggregation

Collapse many rows into summary rows with `γ` (GROUP) and aggregate functions.

| Page | What it does |
|------|--------------|
| [Group / Aggregation](operators/group.md) | `γ` / `GROUP [BY]` — group rows and aggregate |
| [SUM](aggregates/sum.md) | Total of a numeric expression |
| [AVG](aggregates/avg.md) | Mean of a numeric expression |
| [COUNT](aggregates/count.md) | Rows (`COUNT(*)`) or non-NULL values (`COUNT(expr)`) |
| [MIN](aggregates/min.md) | Smallest value |
| [MAX](aggregates/max.md) | Largest value |
| [COLLECT](aggregates/collect.md) | Gather a group's values into an array (NEST) |
| [ARGMAX](aggregates/argmax.md) | A value from the row with the maximum |
| [ARGMIN](aggregates/argmin.md) | A value from the row with the minimum |
| [Window / Rolling](operators/rolling.md) | `ROLLING … OVER … ROWS` — sliding / cumulative aggregate added as a column (no row collapse) |
| [Window / Ranking](operators/window-ranking.md) | `WINDOW ROW_NUMBER/RANK/DENSE_RANK/PERCENT_RANK/NTILE` — per-partition rank column added to every row |
| [Window / Offset](operators/window-offset.md) | `WINDOW LAG/LEAD/FIRST_VALUE/LAST_VALUE` — adjacent / boundary row value added as a column (no row collapse) |

## 8. Nested data (NF²)

Relix relations can hold structs and arrays. These operators build and flatten
nested values.

| Page | What it does |
|------|--------------|
| [Struct construction](literals/struct-construction.md) | Build a nested object `{ … }` in a projection |
| [Array construction](literals/array-construction.md) | Build an array `[ … ]` in a projection |
| [COLLECT](aggregates/collect.md) | Aggregate a group's values into an array (NEST) |
| [Unnest](operators/unnest.md) | `μ` / `UNNEST` — expand an array into rows (the inverse of COLLECT) |
| [WITH ORDINALITY](language/with-ordinality.md) | Add a 1-based position column to an unnest |
| [Unpivot](operators/unpivot.md) | `UNPIVOT` — fold named columns into rows (wide → long) |
| [Pivot](operators/pivot.md) | `PIVOT … BY … PER` — turn distinct key values into columns (long → wide) |
| [Tree](operators/tree.md) | `TREE key BY parentKey [ORDER …] AS children` — fold an adjacency relation into nested documents (recursive COLLECT) |

## 9. Literals & temporal types

Writing constant values, including the first-class temporal types.

| Page | What it does |
|------|--------------|
| [DATE literal](literals/date-literal.md) | `DATE '2026-01-31'` |
| [TIME literal](literals/time-literal.md) | `TIME '09:30:00'` |
| [TIMESTAMP literal](literals/timestamp-literal.md) | `TIMESTAMP '2026-01-31T09:30:00Z'` |
| [DURATION literal](literals/duration-literal.md) | `DURATION 'PT90M'` |
| [Truth relations](literals/truth-relations.md) | `UNIT` / `EMPTY` (aliases `DEE` / `DUM`) — the two zero-column relations |

## 10. Built-in functions

Scalar functions usable in any expression (projection, selection, aggregate
argument).

**String** —
[Len](functions/string/len.md),
[UCase](functions/string/ucase.md),
[LCase](functions/string/lcase.md),
[Trim](functions/string/trim.md),
[LTrim](functions/string/ltrim.md),
[RTrim](functions/string/rtrim.md),
[Left](functions/string/left.md),
[Right](functions/string/right.md),
[Mid](functions/string/mid.md),
[InStr](functions/string/instr.md),
[Chr](functions/string/chr.md),
[Asc](functions/string/asc.md),
[Replace](functions/string/replace.md)

**Math** —
[Abs](functions/math/abs.md),
[Int](functions/math/int.md),
[Fix](functions/math/fix.md),
[Ceil](functions/math/ceil.md),
[Round](functions/math/round.md),
[Sgn](functions/math/sgn.md),
[Sqr](functions/math/sqr.md),
[Log](functions/math/log.md),
[Exp](functions/math/exp.md),
[Power](functions/math/power.md),
[Sin](functions/math/sin.md),
[Cos](functions/math/cos.md),
[Tan](functions/math/tan.md),
[Atn](functions/math/atn.md),
[Rand](functions/math/rand.md)

**Date / time** —
[NOW](functions/datetime/now.md),
[CURRENT_DATE](functions/datetime/current_date.md),
[CURRENT_TIME](functions/datetime/current_time.md),
[YEAR](functions/datetime/year.md),
[MONTH](functions/datetime/month.md),
[DAY](functions/datetime/day.md),
[HOUR](functions/datetime/hour.md),
[MINUTE](functions/datetime/minute.md),
[SECOND](functions/datetime/second.md),
[DATE_TRUNC](functions/datetime/date_trunc.md),
[MINUTES](functions/datetime/minutes.md),
[SECONDS](functions/datetime/seconds.md),
[DAYS](functions/datetime/days.md),
[to_date](functions/datetime/to_date.md),
[to_time](functions/datetime/to_time.md),
[to_timestamp](functions/datetime/to_timestamp.md)

**Conditional** —
[IIf](functions/conditional/iif.md),
[Nz](functions/conditional/nz.md),
[Coalesce](functions/conditional/coalesce.md)

**Type check** —
[IsNull](functions/typecheck/isnull.md),
[IsNumeric](functions/typecheck/isnumeric.md)

**Conversion** —
[CStr](functions/conversion/cstr.md),
[CInt](functions/conversion/cint.md),
[CDbl](functions/conversion/cdbl.md)

**Nested data** —
[Entries](functions/nested/entries.md)

## 11. Advanced operators

Beyond standard SQL — recursion, per-group ranking, sampling, the declarative
solver, combinatorial covering, and universal quantification. Each of these pages
includes a fully worked example.

| Page | What it does |
|------|--------------|
| [Universal quantification](operators/forall.md) | `∀` / `FORALL` — groups where *every* row satisfies a condition |
| [Top-K per group](advanced/top.md) | `TOP … PER` — the N highest rows in each group |
| [Transitive closure](advanced/closure.md) | `CLOSURE` / `RCLOSURE` — reachability over a binary relation |
| [Connected components](advanced/cluster.md) | `CLUSTER … AS` — label each node with its undirected component id |
| [Bounded path reachability](advanced/path.md) | `PATH … HOPS m TO n AS` — pairs reachable within a hop window, with shortest distance |
| [Optimal-path extraction](advanced/trace.md) | `TRACE … VIA … MINIMIZE\|MAXIMIZE AS` — cheapest/longest path with route array |
| [General recursion](advanced/fix.md) | `FIX` — least-fixpoint recursion (WITH RECURSIVE) |
| [Goal-seek](advanced/solve.md) | `SOLVE` — fill the one blank in an equation, per row |
| [Declarative optimisation](advanced/optimize.md) | `OPTIMIZE` — knapsack / allocation under constraints |
| [Combinatorial covering](advanced/cover.md) | `COVER` — minimal all-pairs (pairwise) subset |
| [Bernoulli sampling](advanced/sample-bernoulli.md) | `SAMPLE p` — keep each row with probability p |
| [Reservoir sampling](advanced/sample-reservoir.md) | `SAMPLE n ROWS` — an exact-count uniform sample |
| [DOWNSAMPLE](advanced/downsample.md) | `DOWNSAMPLE ts BY '5m' USING AVG` — time-series bucket aggregation |
| [Sessionization](advanced/sessionize.md) | `SESSIONIZE ts GAP DURATION 'PT30M' PER … AS` — split an ordered stream into sessions by an idle gap |
| [Why (lineage provenance)](operators/why.md) | `ω` / `WHY` — reify a tuple's lineage (which source rows produced it) as a queryable nested column |
| [Query optimizer](advanced/optimizer.md) | The logical rewrite rules (`SEL-…`, `LIM-…`) and how to see which fired |
| [Pushdown](advanced/pushdown.md) | What the backend computes and what the engine does — the boundary `--explain` draws |
