# Name: Coalesce (first non-null)

# Syntax:
Coalesce(<value>, <default>)

π id, Coalesce(mobile, landline) → phone (Contacts)

# Description:
Coalesce returns the first value that isn't missing, scanning its arguments left
to right. Use it to pick the best available value from several candidates — a
mobile number, falling back to a landline, falling back to nothing.

# Technical Description:
Coalesce(v1, v2, …) → ANY returns the first non-NULL argument, or NULL if all are
NULL. Requires at least one argument and accepts any number. PURE, DETERMINISTIC.

Coalesce **short-circuits**: arguments are evaluated left to right and evaluation
stops at the first non-NULL one. A later argument may therefore be an expression
that would fail on this row — it is never reached.

# Examples:
First available phone number:
  π id, Coalesce(mobile, landline) → phone (Contacts)

Preferred then fallback display name:
  π id, Coalesce(display_name, username) → name (Users)

# Pushdown:
SQL: folds to `COALESCE(<a>, <b>, …)` on all dialects, at any argument count. The
two are the same function: first non-NULL, or NULL when there is none.

# Limitations:
Returns NULL only if every argument is NULL. Result type is ANY. At least one
argument is required.

# Alternatives:
Nz substitutes a single fallback for a NULL; IIf for a general true/false choice.

# See Also:
[nz](nz.md), [iif](iif.md), [isnull](../typecheck/isnull.md), [is-null](../../predicates/is-null.md)
