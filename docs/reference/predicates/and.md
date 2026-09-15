# Name: Logical AND (∧ / AND)

# Syntax:
<condition1> ∧ <condition2>
<condition1> AND <condition2>

σ age > 18 ∧ active = true (Users)

# Description:
AND combines two conditions and keeps a row only when BOTH are true. Use it to
narrow a filter — "adults who are also active", "orders that are paid and
shipped". Chain as many as you like.

# Technical Description:
∧ forms an AndPredicate. Under three-valued logic, TRUE ∧ TRUE = TRUE; any FALSE
makes it FALSE; otherwise UNKNOWN. AND binds tighter than OR and looser than NOT.
The optimizer splits a conjunctive selection into separate selections so each
conjunct can be pushed independently toward its source (SEL-001).

# Examples:
Two conditions both required:
  σ age > 18 ∧ active = true (Users)

Three-way conjunction:
  σ region = "EMEA" ∧ amount > 1000 ∧ status = "paid" (Orders)

In a join condition:
  A ⟕ A.id = B.id ∧ A.active = true B

# Limitations:
With NULLs, a row where one side is UNKNOWN is not kept unless the other side
makes the whole expression FALSE-or-TRUE per three-valued logic. Use parentheses
when mixing with OR to make precedence explicit.

# Alternatives:
OR (∨) for "either condition". NOT (¬) to negate. IN (∈) is cleaner than a long
chain of `= …  ∨  = …` equalities.

# See Also:
[or](or.md), [not](not.md), [comparison](comparison.md), [select](../operators/select.md)

# Notes:
Because conjuncts are split and pushed independently, writing one σ with several
ANDs is as efficient as nesting separate σ operators.
