# Name: LEAST (smallest of several values)

# Syntax:
LEAST(<a>, <b>, …)

π id, LEAST(a, b, c) → lowest (Scores)

# Description:
LEAST returns the smallest of its arguments, compared across one row. It is the
across-a-row companion of the aggregate `MIN`, which runs down a column inside a `γ`:
`MIN` asks "the smallest value in this group", LEAST asks "the smallest of these
values on this row". Give it two values or more.

Use it to take the lower of two candidates — a disadvantage roll (`LEAST(a, b)`), the
loser of a comparison — or, with GREATEST, to clamp a value to a range:
`LEAST(hi, GREATEST(lo, x))`.

# Technical Description:
LEAST(v1, v2, …) → the smallest argument, ranked by the engine's own order — the same
order `τ` sorts by and `MIN` reduces with. It accepts two arguments or more, of any
ordered type: NUMBER, STRING, or a temporal type (DATE, TIME, TIMESTAMP, DURATION).
PURE, DETERMINISTIC.

The result type follows the arguments: all of one type returns that type, and a
mixture the row decides returns ANY. Two arguments of kinds that cannot be ranked — a
NUMBER against a STRING — are an error on that row, reported by the order itself.

**NULL is propagated.** Any NULL argument makes the result NULL, because a comparison
involving NULL is never true, so there is no basis for calling a NULL smaller than a
value.

# Examples:
The lower of two rolls (disadvantage):
  π LEAST(d1, d2) → result (Rolls)

Drop the lowest of four dice:
  π (d1 + d2 + d3 + d4) - LEAST(d1, d2, d3, d4) → kept (Rolls)

Clamp a value to a range `[lo, hi]`:
  π LEAST(hi, GREATEST(lo, x)) → clamped (Values)

# Pushdown:
SQL: folds to `LEAST(<a>, <b>, …)` on **MySQL** and **Db2**, whose native `LEAST`
propagates a NULL argument exactly as relix does. PostgreSQL, SQL Server and DuckDB
ignore a NULL argument (returning NULL only when every argument is NULL) and SQLite has
no such function, so on those it is evaluated in the engine — where the answer is the
same. A spelling may compute the same value or none; it does not get to compute a
different one, which is why the skip-NULL dialects are left out.

# Limitations:
Two arguments or more. NULL in any argument → NULL out. Arguments must share an
orderable type; a number and a date on the same call is an error.

# Alternatives:
GREATEST for the largest instead of the smallest. The aggregate `MIN` for the
smallest value down a column within a group.

# See Also:
[greatest](greatest.md), [min](../../aggregates/min.md), [max](../../aggregates/max.md)
