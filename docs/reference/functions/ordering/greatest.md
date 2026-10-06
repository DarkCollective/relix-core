# Name: GREATEST (largest of several values)

# Syntax:
GREATEST(<a>, <b>, …)

π id, GREATEST(a, b, c) → highest (Scores)

# Description:
GREATEST returns the largest of its arguments, compared across one row. It is the
across-a-row companion of the aggregate `MAX`, which runs down a column inside a `γ`:
`MAX` asks "the largest value in this group", GREATEST asks "the largest of these
values on this row". Give it two values or more.

Use it to take the higher of two candidates — an advantage roll (`GREATEST(a, b)`),
the winner of a comparison — or, with LEAST, to clamp a value to a range:
`LEAST(hi, GREATEST(lo, x))`.

# Technical Description:
GREATEST(v1, v2, …) → the largest argument, ranked by the engine's own order — the same
order `τ` sorts by and `MAX` reduces with. It accepts two arguments or more, of any
ordered type: NUMBER, STRING, or a temporal type (DATE, TIME, TIMESTAMP, DURATION).
PURE, DETERMINISTIC.

The result type follows the arguments: all of one type returns that type, and a
mixture the row decides returns ANY. Two arguments of kinds that cannot be ranked — a
NUMBER against a STRING — are an error on that row, reported by the order itself.

**NULL is propagated.** Any NULL argument makes the result NULL, because a comparison
involving NULL is never true, so there is no basis for calling a NULL larger than a
value.

# Examples:
The higher of two rolls (advantage):
  π GREATEST(d1, d2) → result (Rolls)

Floor a value at zero:
  π GREATEST(balance, 0) → non_negative (Accounts)

Clamp a value to a range `[lo, hi]`:
  π LEAST(hi, GREATEST(lo, x)) → clamped (Values)

# Pushdown:
SQL: folds to `GREATEST(<a>, <b>, …)` on **MySQL** and **Db2**, whose native `GREATEST`
propagates a NULL argument exactly as relix does. PostgreSQL, SQL Server and DuckDB
ignore a NULL argument (returning NULL only when every argument is NULL) and SQLite has
no such function, so on those it is evaluated in the engine — where the answer is the
same. A spelling may compute the same value or none; it does not get to compute a
different one, which is why the skip-NULL dialects are left out.

# Limitations:
Two arguments or more. NULL in any argument → NULL out. Arguments must share an
orderable type; a number and a date on the same call is an error.

# Alternatives:
LEAST for the smallest instead of the largest. The aggregate `MAX` for the largest
value down a column within a group.

# See Also:
[least](least.md), [max](../../aggregates/max.md), [min](../../aggregates/min.md)
