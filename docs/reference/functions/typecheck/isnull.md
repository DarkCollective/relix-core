# Name: IsNull (test for missing value)

# Syntax:
IsNull(<value>)

σ IsNull(email) = true (Users)

# Description:
IsNull reports whether a value is missing (NULL), returning true or false. Use it
in expressions where you need a boolean for missingness — for example inside IIf,
or as a computed flag column.

# Technical Description:
IsNull(value) → BOOLEAN. Returns true iff value is NULL. PURE, DETERMINISTIC.
Unlike the `= ⊥`/`= NULL` null test (a predicate), IsNull yields a BOOLEAN value
usable anywhere an operand is expected.

# Examples:
Flag rows with a missing email:
  π id, IsNull(email) → email_missing (Users)

Combine with IIf to derive a label:
  π id, IIf(IsNull(phone), "no phone", phone) → contact (Users)

# Pushdown:
SQL: folds to `(<e> IS NULL)` on all dialects — an operator rather than a call.
SQL asks the same question of the same three-valued world, so there is no NULL
rule to reconcile.

# Limitations:
For filtering, the dedicated null-test predicate (`email = NULL`) is the idiomatic
form; IsNull is for use as a boolean value in expressions.

# Alternatives:
The `= ⊥` / `= NULL` predicate for selection conditions; Nz/Coalesce to substitute
rather than test.

# See Also:
[is-null](../../predicates/is-null.md), [nz](../conditional/nz.md), [coalesce](../conditional/coalesce.md), [iif](../conditional/iif.md), [isnumeric](isnumeric.md)
