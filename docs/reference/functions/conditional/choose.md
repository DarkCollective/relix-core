# Name: Choose (pick a value by position)

# Syntax:
Choose(<index>, <value1>, <value2>, …)

π size_label (π Choose(size_code, "small", "medium", "large") → size_label (Orders))

# Description:
Choose returns one of its values by a 1-based index: `Choose(2, "a", "b", "c")` is
`"b"`. It is the positional companion to [Switch](switch.md) — where Switch picks by
testing conditions, Choose picks by a number — and VBA's `Choose`. Use it to turn a
small integer code into a label without a chain of equality tests.

# Technical Description:
Choose(index, value1, value2, …) → ANY. The index is 1-based: `1` selects the first
value, `2` the second, and so on. An index that is NULL, below `1`, or past the last
value matches nothing and the result is NULL. The index must be a number (else an
evaluation error); a whole-valued decimal such as `2.0` selects position 2, while `2.5`
matches nothing — the same as a simple `CASE` with integer labels. The result type is
the type the values share, or ANY when they differ; the index's type does not affect it.
PURE, DETERMINISTIC.

Choose **short-circuits**: the index is evaluated, then only the value it selects — the
other values are never evaluated, so a value that would fail on a row is safe when the
index does not pick it. [IIf](iif.md), [Switch](switch.md), [Nz](nz.md) and
[Coalesce](coalesce.md) are lazy in the same way; these five are the only built-ins that
are.

Choose folds into a backend **simple `CASE`** — `CASE index WHEN 1 THEN v1 WHEN 2 THEN
v2 … END` — on every SQL dialect, so a query over a database source pushes it down. The
two match exactly: an index that equals no position (out of range, or NULL) selects no
branch and the result is NULL. MongoDB, which has no `CASE`, evaluates it in-engine.

# Examples:
Label a 1-based size code:
  π order_id, Choose(qty, "single", "pair", "trio") → bundle (Orders)

Map a status number to text, out-of-range falling to NULL:
  π order_id, Choose(amount, "low", "mid", "high") → band (Orders)

# Limitations:
The index must be a number and is 1-based. An index that is NULL or outside the range of
values yields NULL — there is no default branch; wrap the call in [Nz](nz.md) or
[Coalesce](coalesce.md) to supply one. The result type is ANY when the values differ in
type. Choose folds to a simple `CASE` on SQL backends; on MongoDB it is evaluated
in-engine.

# Alternatives:
[Switch](switch.md) when the choice is driven by conditions rather than a position.
[IIf](iif.md) for a single two-way choice. For a label table too large to inline, a
lookup relation joined on the code keeps the mapping as editable data.

# See Also:
[switch](switch.md), [iif](iif.md), [nz](nz.md), [coalesce](coalesce.md)

# Notes:
Choose is VBA's `Choose`. It reads most clearly when the index is genuinely a small
1-based position; for a wider or sparser mapping, a lookup relation joined on the code is
clearer than a long argument list.
