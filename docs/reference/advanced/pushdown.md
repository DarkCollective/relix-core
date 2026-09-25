# Name: Pushdown (what the backend computes)

# Syntax:
relix --explain script.relix       -- the physical plan, with any pushed-down query
relix --trace script.relix         -- a live feed including the planner's decisions

-- in the REPL
:explain <query>                   -- the plan for one query

# Description:
Relix does not fetch a table and then filter it. Where the source is capable, the
engine folds as much of the query as it can into the source's own language — a
SQL `SELECT` for a JDBC connection, an aggregation pipeline for MongoDB — and
computes only the remainder itself. That fold is called **pushdown**, and it is
the difference between a filter that runs in the database and a filter that runs
after a million rows have crossed the network.

Nothing is lost when a fold fails. The answer is identical either way; what
changes is where the work happens and how much data moves. So pushdown is never
a correctness question, only a performance one — and the plan is where you read
the answer:

```
Select                                                    ← computed by the engine
└─ PushedScan [jdbc/warehouse] SELECT … WHERE …           ← computed by the database
```

Read a plan as a boundary. Everything below a `PushedScan` happened in the
backend; everything above it happened in the engine, in this process's memory.

Two properties of that boundary are worth internalising, because together they
explain almost every plan that surprises someone. Pushdown is **contiguous** — it
folds a *sub-tree* that sits directly over one connection's tables, so a single
operator the backend cannot express stops the fold at that point, and everything
above it stays in the engine even if the backend could have done it. And it is
**per connection** — two connections are two backends that know nothing of each
other, so a join across them is always computed here, over whatever each side
returns.

# Technical Description:
## Which sources push down at all

| Source | Folds |
|---|---|
| `connection … from jdbc` (and `database`) | a single SQL `SELECT` |
| `connection … from mongodb` | one aggregation pipeline |
| `csv`, `json`, `http`, `generator`, inline tables, rows supplied by a host program | nothing — the whole query runs in the engine |

The second row is not a shortcoming to work around. A CSV file has no query
engine, so the engine reading the file *is* the only place the work can happen.

## The clause-order rule (SQL)

A pushed SQL query is one `SELECT`, so the operators fold in the order that
statement's clauses run:

```
SELECT [DISTINCT] <select list> FROM … [JOIN …] WHERE … GROUP BY … HAVING … ORDER BY … LIMIT …
```

An operator folds only if its clause comes *after* everything already folded.
This is the single mechanical rule behind most of the table below: a σ folds onto
a scan, but not on top of a γ, because `WHERE` cannot follow `GROUP BY` — that
one needs a sub-query, and the engine computes it instead. Likewise a projection
or a grouping **fixes the select list**, so nothing that needs the table's raw
columns folds above it, and only one `ORDER BY` and one `LIMIT` can fold.

Reordering is the optimizer's job, not the renderer's, and it happens first: a σ
the optimizer pushes below a γ arrives at the renderer already in a position
where it can fold. That is why the two features compound — see
[optimizer](optimizer.md).

## SQL: which operators fold

| Operator | Folds as | Does not fold when |
|---|---|---|
| relation over a table | `FROM table` | the source is not a connection-backed table |
| σ selection | `WHERE`, or `HAVING` when a γ is already folded | a projection, sort, limit or window is already folded, or the predicate has an untranslatable part |
| π projection | the select list | a projection or a grouping is already folded; a *column-dropping* π keeps the scan foldable, a computing or renaming one fixes the list |
| δ distinct | `SELECT DISTINCT` | a `GROUP BY`, sort or limit is already folded |
| γ aggregation | `GROUP BY` | any grouping key is not a bare column — a computed key such as `YEAR(ts)`, or a renamed one — or an aggregate has no SQL spelling |
| ∀ universal | `GROUP BY … HAVING` | there are no grouping keys |
| τ sort | `ORDER BY` | a projection, sort or limit is already folded, or a sort key is not a bare column. Above a γ the key may be one of its output columns |
| λ limit | `LIMIT`/`OFFSET`, or `OFFSET … FETCH` on SQL Server and Db2 | a limit is already folded, or — on SQL Server, whose `OFFSET … FETCH` is legal only after an `ORDER BY` — nothing below it is sorted |
| `TOP` | `ORDER BY … LIMIT` | it is per-group (`PER …`) — only the whole-relation form folds |
| ⨝ theta join | `JOIN … ON` | either side is not a bare table scan, the two sides are on different connections or are the same relation (a self-join), or the condition has an untranslatable part |
| ⋈ natural join | `JOIN … ON` equating the shared columns | either side is not a bare table scan, the two sides are on different connections or are the same relation, or a shared column has a different type on each side |
| `ASOF` join | `LEFT JOIN LATERAL (… ORDER BY … LIMIT 1) ON TRUE`, or `JOIN LATERAL` for the `INNER` form | the dialect is not PostgreSQL, DuckDB, Db2 or SQL Server (which spells it `OUTER APPLY (SELECT TOP 1 …)`), or the join carries a `WITHIN` tolerance |
| `IJOIN` interval | `JOIN … ON` over the endpoints | either side is not a bare scan, or they are on different connections |
| `WINDOW` / `ROLLING` | `OVER (PARTITION BY … ORDER BY …)` | the dialect is MySQL, or a projection, grouping or limit is already folded |

A join folds the same way whether its tables are declared sources or are named
directly as `connection.table`. The generated statement names each side by an
alias of its own: the relation's name when that is a plain identifier, otherwise
the table's name, and `t0`/`t1` when neither is. A condition still refers to a side
by its relation name, so over two undeclared tables

```relix
connection shop from database { url: "jdbc:h2:mem:shop" };
query { shop.customers ⨝ shop.customers.customer_id = shop.orders.customer_id shop.orders };
```

folds to one statement:

```sql
SELECT customers.customer_id, customers.name, orders.order_id, orders.customer_id, orders.amount
FROM customers customers JOIN orders orders ON (customers.customer_id = orders.customer_id)
```

### Above a γ

A grouping does not end the statement. A σ above one becomes its `HAVING`, a τ
becomes an `ORDER BY` over the aggregate, and the λ above that becomes the
`LIMIT` — so the ordinary shape of a report reaches the database whole:

```relix
query {
    λ 10 (τ revenue DESC, region ASC
        (σ revenue > 1000
            (γ region, SUM(amount) → revenue (Orders))))
};
```

folds to one statement, and ten rows come back rather than one per region:

```sql
SELECT region, SUM(amount) FROM orders
GROUP BY region
HAVING (SUM(amount) > 1000)
ORDER BY (SUM(amount) IS NULL) ASC, SUM(amount) DESC, (region IS NULL) ASC, region ASC
LIMIT 10
```

Note what the aggregate looks like in the `HAVING` and `ORDER BY`: the expression
is repeated rather than referred to by its relix name. Standard SQL does not let a
`HAVING` clause name a select-list alias, and `ORDER BY` accepts one only as a bare
column reference — not inside the expression that places NULLs where the engine
places them. Repeating it is legal everywhere, so it is what every dialect gets.

A π above a γ is the one that does not fold: it would have to rewrite the select
list the grouping fixed, and the projection happens in the engine instead.

Every other operator is computed by the engine. That includes the outer joins
⟕ ⟖ ⟗, the semi and anti joins ⋉ ▷, every set operation (∪ ⊎ ⊔ ∩ − ∆ × ÷ ∘),
μ unnest, the recursion and graph operators (`CLOSURE`, `CLUSTER`, `PATH`, `FIX`,
`TRACE`), `PIVOT`/`UNPIVOT`, `TREE`,
`SESSIONIZE`, `DOWNSAMPLE`, `SAMPLE`, `COVER`, `SOLVE`, `OPTIMIZE` and ω `WHY`.
An outer join over two tables of one database is therefore an engine join over
two pushed scans — each side's filters still fold, and the join itself does not.

## SQL: which expressions fold

Inside a folded operator, an expression folds if every part of it does.

| Folds | Does not fold |
|---|---|
| column references | struct `{…}` and array `[…]` construction |
| number, string, boolean literals | a predicate used as a value |
| `DATE`, `TIME`, `TIMESTAMP` literals, on every dialect but SQLite | temporal literals on SQLite, which has no date type |
| `DURATION` literals, on PostgreSQL and DuckDB | `DURATION` literals on other dialects |
| `= ≠ < ≤ > ≥`, `∧ ∨ ¬`, `IS [NOT] NULL`, `IN`, `LIKE` | |
| `+ - *` and unary minus | division `/` |

A part that does not fold does not merely omit itself — it stops the whole
operator folding, and everything above that operator with it.

**Division is computed here, on every backend.** A relix `/` is exact decimal
arithmetic to ten places, rounded half-up, and no backend's `/` answers that same
question. H2 and PostgreSQL divide two integers as integers, so `81 / 2` is `40`
there and `40.5` here — a filter over it keeps different rows, not a
differently-rounded number. MySQL does produce a decimal and still differs: its
scale is the operand's plus four by default, so `amount / 3` comes back `33.3333`
against `33.3333333333`. Division by zero disagrees a third way, the engine and
PostgreSQL raising where MySQL answers NULL.

A cast would settle the first of those and not the others, because a backend's
decimal scale is a configuration rather than a spelling. So a filter or projection
containing a division reads its rows and finishes here.

Where that matters on a large table, the lever is to filter on something that folds
and divide above it. Multiplication is exact on every backend, so a threshold on a
ratio can often be written as one on the numerator instead — though the two are the
same question only where the divisor's sign is known and it is never zero, since
multiplying across a comparison turns it around for a negative divisor, and the
division would have raised where the multiplication has an answer.

## SQL: which functions fold

This is the sharp edge, and it is worth knowing before you write a filter over a
large table. Of the built-in scalar functions, **twenty-one** have a SQL spelling:

| Function | SQL |
|---|---|
| `YEAR`, `MONTH`, `DAY`, `HOUR`, `MINUTE`, `SECOND` | `EXTRACT(unit FROM e)`; `DATEPART(unit, SWITCHOFFSET(e, '+00:00'))` on SQL Server; SQLite declines them |
| `DATE_TRUNC` | `date_trunc(…)` on PostgreSQL, DuckDB and Db2, `CAST(DATE_FORMAT(…) AS DATETIME)` on MySQL; the generic dialect, SQLite and SQL Server decline it |
| `Abs`, `Int`, `Ceil`, `Sgn`, `Round` | `ABS`, `FLOOR`, `CEILING`, `SIGN`, `ROUND` (`ROUND(e, 0)` on SQL Server); SQLite, which rounds in floating point, declines `Int`, `Ceil` and `Round` |
| `Fix` | `TRUNCATE(e, 0)` on MySQL, `TRUNC(e)` on PostgreSQL, DuckDB and Db2, `ROUND(e, 0, 1)` on SQL Server; the generic dialect and SQLite decline it |
| `Coalesce`, `Nz` (two-argument form) | `COALESCE(…)` |
| `IsNull` | `(e IS NULL)`; `(CASE WHEN e IS NULL THEN 1 ELSE 0 END)` on SQL Server |
| `Replace` | `REPLACE(s, find, replacement)`, the string under a binary collation on SQL Server |
| `Len`, `Left`, `Right`, `Mid` | `CHAR_LENGTH`, `LEFT`, `RIGHT`, `SUBSTRING` on **MySQL, PostgreSQL and DuckDB**; `LENGTH` and `SUBSTR` on **SQLite**; `CHARACTER_LENGTH` and `SUBSTRING` in `CODEUNITS32` on **Db2**; nowhere else |

Everything else is evaluated in the engine, and two families are worth knowing by
name because SQL has a same-named function for every one of them and answers
differently:

- **Most of the string library** — `UCase`, `LCase`, `Trim`, `LTrim`, `RTrim`,
  `InStr`, `Chr`, `Asc`. Case mapping belongs to the column's collation rather
  than to the function: MySQL upper-cases `Straße` to `STRAßE` where relix
  produces `STRASSE`. Whitespace is not one set: SQL `TRIM` removes spaces where
  relix removes every Unicode whitespace character, so a tab-padded value comes
  back trimmed from the engine and untrimmed from the database. And searching is
  collation-dependent, so SQL's `LOCATE` finds a case-insensitive match where
  relix finds none.
- **The transcendental numerics** — `Sqr`, `Log`, `Exp`, `Power`, `Sin`, `Cos`,
  `Tan`, `Atn`. A database computes these to within about one unit in the last
  place, and so does the engine, and they are entitled to land on either side of
  it: MySQL answers `TAN(4)` with `1.1578212823495775` where relix answers
  `1.1578212823495777`. Four of them additionally have no answer for some
  arguments — relix rejects a negative square root and a non-positive logarithm,
  and cannot represent the infinity an overflowing `Exp` produces, where SQL
  returns NULL. A query that succeeds pushed and fails in the engine is worse than
  one that disagrees in the last digit, and both are reasons to compute here.

In every case the same query would answer differently depending on where the
planner happened to put the work, and that is the one thing a spelling must never
cause. A slower plan is the deliberate trade.

`Len`, `Left`, `Right` and `Mid` are the four that are offered to **named**
dialects rather than to SQL at large, and the reason is worth knowing because it
is not about the functions. relix counts and slices a string in **code points**,
and so do MySQL, PostgreSQL, DuckDB, SQLite and Db2 — but H2, which resolves to the generic dialect,
counts UTF-16 code units, so its `CHAR_LENGTH` and `LEFT` are different functions
wearing familiar names and disagree on any text holding a character outside the
basic multilingual plane. "SQL counts characters" is exactly the kind of claim
that survives being read and not being run, so each dialect on that list was put
there by running it against a fixture holding an astral character.

A call is offered to a backend only when it is **deterministic**. A function whose
value depends on when it runs — `NOW`, `CURRENT_DATE`, `CURRENT_TIME`, `Rand` —
is never evaluated by the database, because it would answer from its own clock and
its own random source rather than from the ones this session was given.

For a clock call the engine does something better than declining: it evaluates the
call **once for the run** and pushes the resulting literal. `σ at < NOW()` reaches
the database as a comparison against a timestamp, so it filters at the source, and
the instant in it is the session's. This is sound because such a function declares
itself *stable* — one value for a whole run — and because a run reads its clock
once, so the value sent to the database is the same value the unfolded half of the
same query computes. `Rand` declares nothing, which means its value may differ per
row: there is no single value to send, so it stays in the engine.

Like everything else here it is a property of the call rather than a list of
names: a function library of your own gets the same treatment from the properties
its signatures declare.

The cost is worth pricing. A `σ` over `NOW()` cannot fold, so it reads its input
and filters here. Where that matters, compute the instant in the host program and
compare against a `TIMESTAMP` literal, which folds and means exactly what it says.

Every other built-in — the whole string family (`UCase`, `Trim`, `Left`,
`Replace`, `Len`, …), the whole math family (`Abs`, `Round`, `Power`, …), the
conversions (`CStr`, `CInt`, `CDbl`), the conditionals (`IIf`, `Nz`,
`Coalesce`), and the type checks — is evaluated by the engine. So
`σ UCase(name) = 'ADA' (Customers)` reads the whole table and filters it here,
while `σ name = 'Ada' (Customers)` filters in the database. When a filter over a
large table has to be case-insensitive, consider a source-side column or a
comparison the backend can spell.

A function library of your own may supply a spelling for its functions, and the
engine folds those exactly as it folds these.

Of the aggregates, `SUM`, `COUNT`, `MIN` and `MAX` fold. `COLLECT`, `ARGMAX` and
`ARGMIN` are computed by the engine, so a γ using one of them keeps its whole
grouping here.

**`AVG` is computed here too**, for the reason division is. It is the only
aggregate that divides, and a relix average is exact decimal to ten places rounded
half-up, which no backend's `AVG` carries: asked for the mean of three values
summing to 457, the engine answers `152.3333333333` and H2 answers
`152.33333333333334`. A γ or a `ROLLING` over `AVG` therefore reads its rows and
finishes here. Where that matters on a large table, `SUM` and `COUNT` both fold and
the division above them is one row's worth of work — and the rounding then becomes
yours to choose rather than the backend's.

## SQL dialects

The dialect comes from the connection's `dialect:` property, or from its JDBC URL
when that property is absent.

### `generic` is an assumption, not a description

Every other dialect names a database whose behaviour relix has been checked
against. `generic` is what an *unrecognised* backend gets, and there is nothing to
check it against — so what it renders is the portable spelling plus an assumption
that the backend behaves in the common way. Mostly it does. Where it does not, a
pushed query can answer differently from the same query run in the engine, and
these are the two known places:

- **String ordering.** relix orders strings by code point. A backend that orders by
  UTF-8 bytes or by a binary collation agrees. One that orders by a locale
  collation does not — PostgreSQL under `en_US.UTF-8` sorts `'a'` before `'B'`,
  where relix sorts every uppercase letter first. H2 orders by UTF-16 code unit,
  which agrees with relix except when a character outside the basic multilingual
  plane is compared with one in `U+E000..U+FFFF`.
- **String equality.** relix compares strings exactly. A backend whose columns carry
  a case- or accent-insensitive collation does not, and an unrecognised URL is
  exactly the case where relix cannot tell.

Both are narrow in practice and neither touches 8-bit text: for ASCII, Latin-1, or
any data inside the basic multilingual plane, code point and code unit are the same
number and the orders agree exactly. The difference needs both an unusual backend
and unusual data.

Two things make it avoidable when it matters. Naming the dialect (`dialect: mysql`,
`dialect: postgres`, `dialect: duckdb`, `dialect: sqlite`, `dialect: sqlserver`, `dialect: db2`) moves the connection off `generic` and onto a checked answer.
Declaring `collation: database` tells relix to make no assumption about string
comparison at all, at the cost of computing those in the engine — see
[connection](../language/connection.md).

| Dialect | Identifiers | Notable |
|---|---|---|
| `generic` (default) | unquoted, unless not a plain identifier | no `DATE_TRUNC`; relies on the backend folding unquoted identifiers, and on its string comparison being the common one — see below |
| `postgres` | `"quoted"` | with DuckDB, the only dialects that fold an `ASOF` join, `DURATION` literals and `NULLS LAST`; string *ordering* is collated, string equality is not |
| `mysql` | `` `quoted` `` | no window functions, so those are computed in the engine; `DATE_TRUNC` folds through `DATE_FORMAT`; string comparison and ordering are collated |
| `db2` | unquoted, unless not a plain identifier | Db2 11.5 or later; PostgreSQL's SQL for `NULLS LAST`, `date_trunc` and an `ASOF` join, `FETCH FIRST` for a limit; strings compare and order exactly in a UTF-8 database — see below |
| `sqlserver` | `[bracketed]` | `OFFSET … FETCH`, so a limit folds only over a sort; NULLs placed with `CASE`; `ASOF` through `APPLY`; no `DURATION`, `DATE_TRUNC` or string counting; string comparison and ordering are collated — see below |
| `sqlite` | `"quoted"` | no date or time types, so no temporal literal, `EXTRACT` or `DATE_TRUNC` folds; `LIKE` is sent as `GLOB`; rounding runs in the engine; strings compare and order exactly |
| `duckdb` | `"quoted"` | PostgreSQL's SQL — `NULLS LAST`, `date_trunc`, an `ASOF` join through `LATERAL`, window functions — with a binary default collation, so neither string comparison nor string ordering is collated |

Each dialect writes a table name one part at a time, so a schema-qualified
`table: "sales.orders"` becomes `"sales"."orders"` on PostgreSQL. On `generic`, a
name that is a plain identifier (letters, digits and `_`, not starting with a digit)
is written bare, and any other name is double-quoted exactly as written. A table
created as `"order-lines"` is therefore read as `"order-lines"`, and its name must
match the stored name's case.

### DuckDB, and the file it names

DuckDB runs inside the process that opens it, so a DuckDB connection names a
database *file* rather than a server:

```relix
connection lake from database { url: "jdbc:duckdb:/data/lake.duckdb" };
```

A bare `jdbc:duckdb:`, DuckDB's in-memory form, is private to the one JDBC
connection that opened it. relix opens connections of its own, so each would see a
fresh, empty database — name a file. The dialect is recognised from the `jdbc:duckdb:`
prefix, or can be named with `dialect: duckdb`. Its SQL is PostgreSQL's in every place
relix renders differently, and its default collation is binary, so a DuckDB
connection folds every string comparison and ordering as written.

### SQLite, and the dates it does not have

SQLite has no date or time types. A column declared `DATETIME` holds whatever the
application wrote into it — text, a Julian day number, seconds since the epoch — and a
comparison against it compares that text or number. A `TIMESTAMP '…'` sent as a string
would agree with relix only while every stored value was written in exactly the form
the literal is, which nothing relix can see reports. So on a SQLite connection no
temporal literal, `EXTRACT` or `DATE_TRUNC` is folded: those predicates read their rows
and are decided in the engine, over the values the driver read, where they mean what
they say whatever the storage convention.

Two things still fold, and both rest on one assumption worth knowing: that a column
holds its dates in *one* form. Sorting on a temporal column, and comparing two of them,
compare the stored text or numbers directly — which is right for a column written
consistently, as SQLite's own date functions also assume, and wrong for one that mixes
forms.

SQLite's `LIKE` ignores the case of ASCII letters, and no collation changes that, so a
pattern is sent as the case-sensitive `GLOB` instead — see [like](../predicates/like.md).
Its `ROUND`, `FLOOR` and `CEILING` answer in binary floating point, so `Round`, `Int`
and `Ceil` are computed here. It has no `LATERAL`, so an `ASOF` join runs in the
engine. A SQLite connection, like a DuckDB one, names a file: `jdbc:sqlite::memory:` is
private to the JDBC connection that opened it.

### SQL Server, and the clauses it spells otherwise

SQL Server is recognised from a `jdbc:sqlserver:` URL, or named with
`dialect: sqlserver`. Most of what relix renders it spells otherwise, and three of
the differences change *what* folds rather than how it is written:

- **A limit needs a sort.** T-SQL has no `LIMIT`; its `OFFSET … FETCH` is legal only
  after an `ORDER BY`. So `λ 10 (τ oid (Orders))` folds, into
  `ORDER BY … OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY`, and `λ 10 (Orders)` does not —
  the rows are read and the first ten kept here. The `ORDER BY (SELECT NULL)` idiom
  would make it legal, and would also let the database choose the rows.
- **No session time zone.** `DATETIME2` is zone-less and `DATETIMEOFFSET` carries its
  own offset, and neither reads a session setting, so there is none to pin. An
  extraction is sent as `DATEPART(unit, SWITCHOFFSET(e, '+00:00'))`, which reads any of
  them at UTC as relix does. `DATE_TRUNC` is not sent: `DATETRUNC` arrived in SQL
  Server 2022, which a declared dialect cannot confirm.
- **No boolean value.** `TRUE` is written `1`, `IsNull` is a `CASE`, and NULLs are
  placed last with `CASE WHEN e IS NULL THEN 1 ELSE 0 END` rather than a boolean key.

An `ASOF` join folds as `OUTER APPLY (SELECT TOP 1 …)`, or `CROSS APPLY` for the
`INNER` form — the same correlated lookup `LATERAL` is elsewhere. A string literal is
written `N'…'`, so a character outside the database's code page survives the trip.
`Len`, `Left`, `Right` and `Mid` are computed here: T-SQL's `LEN` counts UTF-16 units
and ignores trailing spaces.

SQL Server's default collation, `SQL_Latin1_General_CP1_CI_AS`, is case-insensitive,
so strings are compared under `Latin1_General_100_BIN2`, in the places MySQL's are
converted. Two differences remain that no collation removes, and both are narrow:

- **Trailing spaces.** SQL Server compares strings padded, as the SQL standard's
  `PAD SPACE` rule says, so `'a' = 'a '` is true there under every collation it has;
  relix compares them as different values. A column whose values differ only in
  trailing spaces — a `CHAR(n)` column, say — compared or grouped on SQL Server can
  answer differently than in the engine.
- **Ordering beyond the basic multilingual plane.** A binary collation orders by
  UTF-16 code unit, as H2 does, and differs from relix's code-point order only for a
  supplementary character compared with one in `U+E000..U+FFFF`.

### Db2

Db2 for Linux, UNIX and Windows is recognised from a `jdbc:db2:` URL, or named with
`dialect: db2`, and is supported from 11.5. Most of what relix renders it takes as
written — `NULLS LAST`, typed temporal literals, `date_trunc`, window functions, and an
`ASOF` join as `LEFT JOIN LATERAL (… LIMIT 1) ON TRUE`. A limit is the standard's
`FETCH FIRST n ROWS ONLY`, which Db2 accepts with or without an `ORDER BY`.

Three things differ:

- **Names are left bare.** Db2 folds an unquoted name to upper case, so a table
  created as `orders` is stored as `ORDERS`. relix writes a plain identifier unquoted,
  as the generic dialect does, and delimits only a name like `order-lines` that could
  not be written bare. A table whose name was created quoted and in lower case must be
  declared exactly as it was created.
- **Characters are counted in `CODEUNITS32`.** Db2's `LENGTH`, `LEFT` and `RIGHT`
  count bytes by default, and its `LEFT` pads with spaces when asked for more than a
  string holds, so `Len`, `Left`, `Right` and `Mid` go through `CHARACTER_LENGTH` and
  `SUBSTRING` told to count in code points.
- **No zone to pin.** Db2 for LUW has no time-zone-bearing timestamp, so there is no
  session setting a temporal fold could be shifted by; a `TIMESTAMP` column holds the
  wall clock relix reads as UTC.

Strings are compared and ordered as written. A Db2 database created in UTF-8 — the
default — uses the `IDENTITY` collation, which compares bytes and so orders by code
point exactly as relix does. A database created with a locale collation does not, and
should declare `collation: database`. Like SQL Server, Db2 compares strings padded, so
`'a' = 'a '` is true there and false in relix.

### Strings, and the collation they are compared under

relix compares strings exactly — character for character. A database compares them
under the column's **collation**, and a collation may say that two values differing
in case or in accent are the same value. MySQL's default (`utf8mb4_0900_ai_ci`)
says exactly that, so `'Gold'`, `'GOLD'` and `'gold'` are one value there and three
here.

That would make a query answer differently depending on whether the planner folded
it, which is the one thing pushdown must never do. So on a dialect whose collation
is not the engine's comparison, a string is rendered with an explicit exact one:

```sql
WHERE CONVERT(`region` USING utf8mb4) COLLATE utf8mb4_0900_bin = 'west'
```

The `COLLATE` replaces the column's collation; the `CONVERT` is what makes that
legal for a column of any character set, since a collation belongs to one. This
applies wherever strings are compared for **equality** — `=`, `≠`, `IN`, `LIKE`, a
`GROUP BY`, a `SELECT DISTINCT`, a join condition — and it costs the column's index
for that comparison, which is the right trade: the alternative is not an indexed
lookup but no fold at all, so the choice is between a scan inside the database and
every row of the table crossing the wire to be scanned here.

Ordering is collated the same way and pushed the same way. relix orders strings by
**code point** — the order a binary collation gives, and the order UTF-8 bytes
already sort in — so `τ` on a string key, a `<`/`>` between strings, and `MIN`/`MAX`
over one all reach the database.

**Comparing and ordering are two questions, and PostgreSQL answers them
differently.** Its default collation is *deterministic*, which means it decides
whether two strings are equal by comparing their bytes — so `=`, `IN`, `LIKE`, a
`GROUP BY` and a `DISTINCT` mean there exactly what they mean here, and are pushed
untouched, keeping the column's index. It decides their *order* by the locale, and
a locale sorts the way a dictionary does: under `en_US.UTF-8`, `'Alan'` sorts
before `'a😀b'` sorts before `'grace'`, where by code point the emoji comes after
both. So only the ordering is wrapped, in the collation that orders by code point:

```sql
ORDER BY ("name") COLLATE "C" ASC NULLS LAST
```

That split is why a MySQL connection and a PostgreSQL one are collated in
different places, and it is measured rather than reasoned: the agreement suite runs
one corpus against both, and on PostgreSQL every equality case agreed and the three
ordering ones did not.

One shape does not: ordering by a *grouping key* that had to be collated. MySQL
matches an `ORDER BY` term against the `GROUP BY` expression as text, and placing
NULLs wraps the key, so the term is no longer the expression MySQL is looking for
and it rejects the query. That query is computed in the engine, and folds normally
on a connection that needs no collating.

A connection whose string columns really are declared with a binary collation can
say so, and gets all of it back — the comparison rendered as written, and string
ordering pushed:

```relix
connection shop from database { url: "${SHOP_DB}", collation: exact };
```

The opposite, `collation: database`, asks for the conservative reading on a dialect
whose default is `exact`. Neither changes the answer relix gives; they change which
work the database does. See [connection](../language/connection.md).

### Time zones, and the session relix opens

A relix `TIMESTAMP` is an instant, and the engine reads and computes it at UTC. A
truncation or an extraction that folds is computed by the **server**, over whatever
wall clock the session presents — so on a session an hour off UTC,
`DATE_TRUNC('day', ts)` buckets by a different day than the same expression buckets
by in the engine, and the query returns different rows depending on whether it
folded.

So relix **pins the session to UTC** on every connection it opens to an identified
backend — `SET TIME ZONE 'UTC'` on PostgreSQL, `SET time_zone = '+00:00'` on MySQL,
`SET TimeZone = 'UTC'` on DuckDB (SQL Server has no session zone, and is handled by
reading each value at UTC instead) — and a temporal fold means the same thing wherever it runs. On PostgreSQL there is
no other way to say it: the driver takes the session's zone from the client
machine's default and ignores one named in the connection string, so without this a
query's answers moved with the time zone of the host the engine ran on.

Two consequences worth knowing. A `TIMESTAMP WITH TIME ZONE` column is read as the
instant it holds, which is what it always meant. And a connection whose backend
relix cannot identify — the generic dialect — is left alone rather than sent a
statement it might reject, so its session is whatever the driver made it.

## MongoDB

A MongoDB connection folds a shorter list, into one aggregation pipeline:

| Operator | Pipeline stage |
|---|---|
| relation over a collection | the collection |
| σ selection | `$match` |
| μ unnest | `$unwind` |
| π projection | `$project` |
| λ limit | `$skip` / `$limit` |

Sorting, grouping and joins are computed by the engine, as is a negated
predicate (`¬`). The six `EXTRACT`-style date parts and `DATE_TRUNC` have
pipeline spellings and fold inside a `$match` or `$project`.

# Examples:
A query that folds completely. The filter, the grouping and the aggregate all
reach the database, so the rows that cross the network are the answer rather than
the input:

```relix
connection warehouse from database { url: "${DB_URL}" };

source WarehouseOrders from warehouse {
    table: "orders",
    schema: { order_id: NUMBER, customer_id: NUMBER, amount: NUMBER, status: STRING }
};

query { γ customer_id, SUM(amount) → total (σ status = 'OPEN' (WarehouseOrders)) };
```

The same query with one changed grouping key does not fold its γ, because a
computed key is not a bare column. The σ still folds, so the database still
filters — the grouping happens here, over the filtered rows:

```relix
connection warehouse from database { url: "${DB_URL}" };

source WarehouseOrders from warehouse {
    table: "orders",
    schema: { order_id: NUMBER, amount: NUMBER, status: STRING, placed_at: TIMESTAMP }
};

query { γ YEAR(placed_at) → yr, SUM(amount) → total (σ status = 'OPEN' (WarehouseOrders)) };
```

A join across two connections is always computed by the engine. Each side folds
separately, which is the useful part: both databases filter before anything is
sent:

```relix
connection warehouse from database { url: "${WAREHOUSE_URL}" };
connection archive from database { url: "${ARCHIVE_URL}" };

source LiveOrders from warehouse {
    table: "orders", schema: { order_id: NUMBER, status: STRING }
};
source ClosedOrders from archive {
    table: "closed", schema: { order_id: NUMBER, closed_on: DATE }
};

query {
    (σ status = 'OPEN' (LiveOrders)) ⨝ LiveOrders.order_id = ClosedOrders.order_id (ClosedOrders)
};
```

Reading a CSV file, nothing folds and the plan says so — there is no backend to
fold into, and every operator is the engine's:

```relix
source LocalOrders from csv("./orders.csv") {
    header: true,
    schema: { order_id: NUMBER, amount: NUMBER, status: STRING }
};

query { γ status, SUM(amount) → total (σ amount > 100 (LocalOrders)) };
```

# Limitations:
Pushdown reaches one connection's tables at a time, and stops at the first
operator or expression the backend cannot spell. An operator above that point is
computed in the engine even where the backend could have expressed it in
isolation.

The engine's own buffering is the practical consequence. A γ, τ, δ or set
operation that does not fold holds its input in this process's memory while it
runs, so the operators that stop folding are the ones worth checking on a large
table. `--explain` is how you check.

# Alternatives:
When a query has to do work the fold cannot reach, define a **view in the
database** and point a source at it with `table:`. A view is read exactly as a
table is, so the backend computes whatever the view expresses and the engine
folds its own filters and limits on top of the result — see
[source](../language/source.md).

# See Also:
[optimizer](optimizer.md), [source](../language/source.md),
[connection](../language/connection.md), [group](../operators/group.md),
repl

# Notes:
`--explain` is the only authority on what a particular query folded. The tables
here describe the shapes the renderers accept, but whether *your* query matches
one of them depends on the tree the optimizer produced, and reading the plan is
faster than reasoning about it.
