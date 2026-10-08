# Name: Bound parameter (`$name`)

# Syntax:
$<name>                 -- a value supplied when the query runs

σ order_id = $id (Orders)
σ placed_at ≥ $since ∧ status = $status (Orders)

# Description:
A parameter is a value a query is given from outside its text. The query names it —
`$id` — and whatever runs the query supplies it: a program embedding the engine binds it
before running the query, and a session may hold values every query shares.

The value never becomes part of the query. The query still reads `σ order_id = $id`
after the value is bound, and when the comparison is sent to a database it is sent with a
placeholder and the value beside it. So a value that came from somewhere you do not
control — a form field, another program's output — cannot change what the query means,
however it is spelled: a string holding a quote is a string holding a quote.

A parameter has no type of its own. It takes the type of what it is compared with:
`σ order_id = $id` makes `$id` a NUMBER, because `order_id` is one. A parameter compared
with nothing whose type is known can hold a value of any type.

# Technical Description:
`$` must be followed directly by a name — a letter or `_`, then letters, digits and `_`.
Parameters are matched ignoring case, as columns are, and a parameter may share a
column's name: `σ id = $id` compares the column with the parameter.

A parameter's type is read from every comparison it appears in directly, on either side,
including a `LIKE` pattern, in any query, view or `def` of the script. Two comparisons
that disagree are an error, since no one value could satisfy both:

```
σ id = $key ∨ customer = $key (Orders)   -- ERROR: $key is compared with a NUMBER and a STRING
```

A value bound as text — the way a value arrives from a command line, a query string or
an environment variable — is read as the parameter's type, the way that type's literal
reads its text. Text that is not of the type is an error naming the parameter, the type
and the text; it is never read as part of the query:

| Parameter's type | Text it takes |
|---|---|
| NUMBER | a decimal number: `42`, `-3.5`, `1e3` |
| BOOLEAN | `true` or `false`, in any case |
| DATE, TIME, TIMESTAMP | ISO-8601, as `DATE '…'`, `TIME '…'` and `TIMESTAMP '…'` take it: `2026-03-01`, `13:40:00`, `2026-03-01T09:00:00Z` (UTC when it names no offset) |
| DURATION | ISO-8601, as `DURATION '…'` takes it: `PT15M` |
| STRING, or a type it does not have | the text, unchanged: `02139` stays `02139` |

Empty text is not a missing value: bound to a parameter that is not a STRING it is an
error, not NULL. A value bound as anything other than text must already be of the
parameter's type.

A parameter is evaluated like a literal: once per query, the same value in every row. A
query cannot run until every parameter it reaches — its own, and those of the views and
functions it uses — has a value; running it without one is an error naming the
parameter, raised before any row is read.

Pushed to a database, a comparison between a parameter and a column or a value becomes a
`?` in the SQL, with the value sent as a bind parameter; the plan shows which parameter
each `?` stands for. A parameter anywhere else — inside arithmetic, or compared with
another parameter — is evaluated by the engine rather than sent, because a database
cannot always tell what type a placeholder there should be. So is a `LIKE` whose pattern
is a parameter, on a database whose `LIKE` reads a pattern differently from the engine's
unless it can see the pattern. MongoDB is sent no parameter, and a selection over one is
evaluated by the engine. Either way the answer is the same; only where the work happens
differs.

```relix-invalid
query { σ id = $ id (Orders) };
```

# Examples:
Look one order up, the id supplied by the caller:
  σ order_id = $id (Orders)

A view whose filter a program fills in each time it runs it:
  Recent := { σ placed_at ≥ $since (Orders) };

A parameter in a computed column, with no type to take, holds whatever it is given:
  π order_id, $batch → batch (Orders)

# Limitations:
A parameter is a scalar value. It cannot stand for a relation, a column name or a list
of values.

# See Also:
[select](../operators/select.md), [inline-table](inline-table.md), [def](def.md)

# Notes:
Writing a value into the query text instead — building `σ id = 4711` by concatenating
strings — makes the value part of the query, and so lets a value that is not what was
expected change the query. A parameter, or a literal built from a value by the embedding
API, does not.
