# Name: IIf (inline if / conditional value)

# Syntax:
IIf(<condition>, <value-if-true>, <value-if-false>)

π name, IIf(score >= 50, "pass", "fail") → result (Results)

# Description:
IIf chooses between two values based on a condition — the spreadsheet IF. If the
condition is true it returns the first value, otherwise the second. Use it to
derive labels, flags, or category columns from a test.

# Technical Description:
IIf(condition, trueValue, falseValue) → ANY. The first argument must evaluate to a
BOOLEAN (else an evaluation error); a NULL condition returns NULL. Returns
trueValue when true, else falseValue. PURE, DETERMINISTIC.

IIf **short-circuits**: the condition is evaluated, then *only* the branch it
selects — the other is never evaluated (and a NULL condition evaluates neither).
That is what makes IIf usable as a guard: the untaken branch may be an expression
that would fail on those rows, such as converting a value that is not a number.
[Nz](nz.md) and [Coalesce](coalesce.md) are lazy in the same way, and these three
are the only built-ins that are — every other function evaluates all its
arguments.

# Examples:
Pass/fail label:
  π name, IIf(score >= 50, "pass", "fail") → result (Results)

Bucket a numeric flag:
  π order_id, IIf(amount > 1000, "large", "small") → size (Orders)

Default a display value:
  π order_id, IIf(active = true, status, "inactive") → display_status (Accounts)

# Limitations:
The condition must be BOOLEAN. The result type is ANY (the two branches may differ
in type). A NULL condition yields NULL — neither branch is evaluated in that case.

# Alternatives:
Nz / Coalesce specifically for substituting a value when something is NULL.

# See Also:
[nz](nz.md), [coalesce](coalesce.md), [isnull](../typecheck/isnull.md), [comparison](../../predicates/comparison.md)

# Notes:
For "replace NULL with a default" the dedicated Nz/Coalesce read more clearly than
`IIf(IsNull(x), default, x)`.
