# Name: Logical OR (∨ / OR)

# Syntax:
<condition1> ∨ <condition2>
<condition1> OR <condition2>

σ status = "new" ∨ status = "pending" (Orders)

# Description:
OR combines two conditions and keeps a row when EITHER is true. Use it to widen a
filter — "orders that are new or pending", "users in sales or marketing".

# Technical Description:
∨ forms an OrPredicate. Under three-valued logic, FALSE ∨ FALSE = FALSE; any TRUE
makes it TRUE; otherwise UNKNOWN. OR binds looser than AND, so `a ∧ b ∨ c` means
`(a ∧ b) ∨ c`; use parentheses to be explicit.

# Examples:
Either of two statuses:
  σ status = "new" ∨ status = "pending" (Orders)

Mix with AND (parenthesise for clarity):
  σ (region = "EMEA" ∨ region = "APAC") ∧ amount > 1000 (Orders)

Keep rows where a value is present OR explicitly null:
  σ score >= 50 ∨ score = NULL (Results)

# Limitations:
A disjunction over the same column equality is more cleanly written with IN (∈).
Precedence with AND can surprise — parenthesise when mixing.

# Alternatives:
IN (∈) for many equality alternatives on one column. UNION (∪) to combine the
results of two separately-filtered queries.

# See Also:
[and](and.md), [not](not.md), [in](in.md), [comparison](comparison.md), [select](../operators/select.md)

# Notes:
`x = a ∨ x = b ∨ x = c` is equivalent to, and clearer as, `x ∈ {a, b, c}`.
