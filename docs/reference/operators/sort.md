# Name: Sort (τ / SORT / ORDER [BY])

# Syntax:
τ <key> [ASC|DESC] [NULLS FIRST|NULLS LAST] [, <key> ...] ... (Relation)
SORT ...
ORDER ...          -- SQL-prior synonym
ORDER BY ...       -- SQL-prior synonym; BY is an optional noise word

τ name (Users)                  -- ascending by default
τ salary DESC (Employees)
τ dept ASC, salary DESC (Employees)
τ to_timestamp(logged) DESC (RawLogs)   -- sort by a computed value
ORDER BY salary DESC (Employees)        -- same AST as τ salary DESC (Employees)
τ bonus DESC NULLS FIRST (Sales)        -- missing values first

# Description:
Sort orders the rows of a relation by one or more keys. Each sort key can go
ascending (ASC, the default) or descending (DESC). When you sort by several keys,
the first is the primary order, the next breaks ties, and so on — just like
sorting a spreadsheet by multiple columns.

A sort key can be a bare column or a full expression — e.g.
`to_timestamp(logged)` or `price * qty` — so you can order by a computed value
without projecting it first.

Sorting is most often combined with LIMIT to answer "top N" questions.

# Technical Description:
τ establishes a delivered ordering over R: a sequence of (expression, direction)
sort specifications. It is a blocking operator ([sort]) that buffers its input.
The cost model tracks the delivered order so the optimizer can (a) eliminate a
redundant τ whose input already satisfies the order (SORT-001), (b) feed a merge
join without re-sorting, and (c) enable streaming δ/γ — all reasoned about over
bare-column keys; a derived key (e.g. `to_timestamp(logged)`) sorts in-engine and
does not participate in ordering-based rewrites or ORDER BY pushdown. A τ by bare
columns over a single backend connection pushes down as ORDER BY, and a pushed
ORDER BY can satisfy a downstream merge join (source-sorted merge).

Within a type, values order as that type orders. Strings order by **code point** —
the order UTF-8 bytes sort in, and the order a SQL binary collation gives — rather
than by the UTF-16 code unit a Java `char` is, so a character outside the basic
multilingual plane sorts after every character in it rather than in the middle.
For text inside that plane, which is all of ASCII and every 8-bit character set,
the two rules are the same rule. NULLs sort last in both directions, unless a key
says `NULLS FIRST`; `NULLS LAST` writes the default out. The placement belongs to the
key, so `τ a NULLS FIRST, b (R)` puts NULLs of `a` first and NULLs of `b` last.

τ is not the only source of a delivered order: a merge join emits its output in
join-key order, so a chain of merge joins on the same key sorts once rather than
once per join, and a δ or γ over that output can stream. A semi merge join carries
its left input's order through unchanged; an anti merge join advertises no order,
because it emits left rows with a NULL join key ahead of the sorted run.

A projection or a rename above a τ keeps the order under the output's column names.
A π delivers the longest prefix of its input's order whose keys it projects, so
`π item, price * qty → total (τ price * qty DESC (T))` is sorted by `total DESC`,
which is a bare column again, and a π that drops the second of three keys is sorted
by the first alone. A ρ renames the keys it carries.

# Examples:
Alphabetical list of users:
  τ name (Users)

Highest-paid employees first:
  τ salary DESC (Employees)

Group by department, then highest salary within each:
  τ dept ASC, salary DESC (Employees)

Top 10 orders by amount (sort then limit):
  λ 10 (τ amount DESC (Orders))

Most recent events first (temporal sort):
  τ occurred_at DESC (Events)

Sort by a parsed timestamp — order raw string logs by their real instant:
  τ to_timestamp(logged) DESC (RawLogs)

# Worked Example:
Four rows of sales, two of them with no `bonus` recorded — the NULLs are there deliberately, because they are what makes the rules below visible.

```relix
Sales := [
| region | rep  | amount | bonus |
|--------|------|--------|-------|
| east   | Ada  | 120    | 10    |
| east   | Bo   | 80     | NULL  |
| west   | Cy   | 200    | 25    |
| west   | Dee  | 50     | NULL  |
];

query { τ amount DESC (Sales) };
```

```
 region  rep  amount  bonus
 ──────  ───  ──────  ─────
 west    Cy      200     25
 east    Ada     120     10
 east    Bo       80  NULL
 west    Dee      50  NULL
(4 rows)
```

The same four rows in a new order. Sorting is the one core operator that must
see every row before it can emit the first, which is why it is tagged `[sort]` in
the IR and why an unnecessary `τ` is worth removing — the optimizer does exactly
that (`SORT-001`) when the input already arrives in the required order.

**Where the NULLs go.** Sorting by a column that has some settles the rule, which
is otherwise the kind of thing every database answers differently:

```relix
query { τ bonus DESC (Sales) };
```

```
 region  rep  amount  bonus
 ──────  ───  ──────  ─────
 west    Cy      200     25
 east    Ada     120     10
 east    Bo       80  NULL
 west    Dee      50  NULL
(4 rows)
```

NULLs sort **last**, and last in *both* directions — ascending or descending, a
missing value goes to the end rather than swapping ends with the sort. This holds
whether the `τ` runs in-engine or is pushed into a database: the SQL that comes out
states the placement explicitly rather than inheriting the backend's default, which
would otherwise put a query's NULLs first on one connection and last on another.

PostgreSQL and Oracle put NULLs *first* in a descending sort, so a query ported from
them asks for that with `NULLS FIRST`:

```relix
query { τ bonus DESC NULLS FIRST (Sales) };
```

```
 region  rep  amount  bonus
 ──────  ───  ──────  ─────
 east    Bo       80  NULL
 west    Dee      50  NULL
 west    Cy      200     25
 east    Ada     120     10
(4 rows)
```

The two NULLs tie, so they keep the order they arrived in. A sort that differs from
the one below it only in where its NULLs go is a different order, and is not
removed as redundant.


# Limitations:
Sort must see all rows before emitting any, so it cannot run over an unbounded
generator without a bound below it. Pushed into SQL, `NULLS FIRST` and `NULLS LAST`
are written as such on PostgreSQL, DuckDB and Db2, and as an extra leading key on
the NULL test elsewhere.

# Alternatives:
For "N highest per group" use TOP … PER instead of sorting the whole relation.
For a single extreme value per group use γ with MIN/MAX or ARGMAX/ARGMIN.

# SQL names:
SQL's `ORDER BY a, b DESC` is `SORT a, b DESC (R)`, and `ORDER BY` is accepted as a
synonym of `SORT`. It wraps the expression it orders rather than ending it:
`SELECT a FROM R ORDER BY a` is `SORT a (PROJECT a (R))`. Sort last, after any join:
only a sort promises an order. `ORDER BY … LIMIT n` is [LIMIT](limit.md) over
the sort, and `ORDER BY RANDOM()` is [SHUFFLE](shuffle.md).

# See Also:
[limit](limit.md), [top](../advanced/top.md), [group](group.md), [distinct](distinct.md)

# Notes:
Sorting can be free when the data already arrives ordered (e.g. an indexed
source pushed as ORDER BY); the optimizer removes the in-engine sort in that case.
