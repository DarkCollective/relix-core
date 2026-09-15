# Name: Nz (null-to-value)

# Syntax:
Nz(<value>)                 -- NULL becomes ""
Nz(<value>, <replacement>)  -- NULL becomes the replacement

π name, Nz(nickname, name) → display_name (Users)

# Description:
Nz substitutes a fallback when a value is missing (NULL). With one argument a NULL
becomes an empty string; with two, a NULL becomes whatever replacement you give.
Use it to fill in defaults so missing data doesn't leave blanks downstream.

# Technical Description:
Nz(value) → ANY returns value if non-NULL, else an empty string "". Nz(value,
replacement) returns value if non-NULL, else replacement — as-is, even when the
replacement is itself NULL. PURE, DETERMINISTIC.

Nz **short-circuits**: the replacement is evaluated only when the value is NULL.
So it may be an expression that would fail on the rows where it is not needed.

# Examples:
Show a nickname, falling back to the real name:
  π name, Nz(nickname, name) → display_name (Users)

Default a missing count to zero:
  π id, Nz(views, 0) → views (Pages)

Turn a missing note into an empty string:
  π id, Nz(note) → note (Tickets)

# Pushdown:
SQL: the two-argument form folds to `COALESCE(<value>, <valueIfNull>)` on all
dialects. The one-argument form is not pushed: its substitute is the empty string
for a STRING and NULL otherwise, so the answer depends on the argument's type,
which the database is not told.

# Limitations:
The one-argument form's default is an empty string "", which may not suit numeric
columns — pass an explicit replacement (e.g. 0) for those.

# Alternatives:
Coalesce returns the first non-NULL of several values. IIf with IsNull for more
complex conditions.

# See Also:
[coalesce](coalesce.md), [iif](iif.md), [isnull](../typecheck/isnull.md), [is-null](../../predicates/is-null.md)
