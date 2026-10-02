# Name: Switch (multi-branch conditional value)

# Syntax:
Switch(<condition1>, <value1>, <condition2>, <value2>, …[, <default>])

π order_id, Switch(amount >= 100, "large", amount >= 50, "medium", "small") → size (Orders)

# Description:
Switch chooses a value from a list of condition/value pairs — the multi-branch form
of [IIf](iif.md), and the relational answer to SQL's searched `CASE WHEN`. It tests
each condition in turn and returns the value paired with the first one that is true. A
final, unpaired argument is the default, returned when no condition holds; with no
default and no match the result is NULL. Use it to derive a label, grade, or bucket
from several thresholds without nesting `IIf` inside `IIf`.

# Technical Description:
Switch(cond1, value1, …[, default]) → ANY. Arguments pair up as (condition, value);
a trailing odd argument is the default. Each condition must evaluate to a BOOLEAN (else
an evaluation error); a NULL condition is treated as not matched and the next pair is
tried, as a `CASE WHEN NULL` arm is in SQL. The result type is the type the value
branches share, or ANY when they differ — the conditions do not affect it. PURE,
DETERMINISTIC.

Switch **short-circuits**: conditions are evaluated left to right and evaluation stops
at the first true one, whose value alone is then evaluated; a later value — and the
default — is never touched once a branch is chosen. That is what makes an earlier
condition a guard for a later value, exactly as it is for `IIf`. [IIf](iif.md),
[Nz](nz.md) and [Coalesce](coalesce.md) are lazy in the same way; these four are the
only built-ins that are.

Switch runs in the engine and is not rendered to a backend `CASE` — a query over a
database source evaluates it after the scan rather than pushing it down.

# Examples:
Size band from two thresholds, with a default:
  π order_id, Switch(amount >= 100, "large", amount >= 50, "medium", "small") → size (Orders)

Grade a score, highest band first:
  π name, Switch(score >= 70, "distinction", score >= 50, "pass", "fail") → grade (Results)

No default — an unmatched row is NULL:
  π order_id, Switch(amount >= 1000, "vip") → tier (Orders)

# Limitations:
Each condition must be BOOLEAN. The result type is ANY when the value branches differ
in type. Order matters: the first true condition wins, so write the most specific or
highest band first. With no default, a row that matches nothing yields NULL. Switch is
evaluated in-engine and is not pushed down to SQL or MongoDB backends.

# Alternatives:
[IIf](iif.md) for a single two-way choice. [Nz](nz.md) / [Coalesce](coalesce.md) for
substituting a value when something is NULL. When the branches are really a set of data
bands — tiers, grades, price brackets — a lookup relation joined by range (a
[theta join](../../joins/theta-join.md)) or nearest-below ([AS-OF join](../../joins/asof-join.md))
keeps the bands as editable data rather than in the text of one expression.

# See Also:
[iif](iif.md), [nz](nz.md), [coalesce](coalesce.md), [comparison](../../predicates/comparison.md)

# Notes:
Switch is VBA's `Switch`, and it reads best with the broadest-matching branch last and
the most specific first, since the first true condition decides. For "replace NULL with
a default" the dedicated Nz/Coalesce read more clearly than a Switch on `IsNull`.
