# Name: BOOL_OR (whether any value in a group is true)

# Syntax:
BOOL_OR(<condition>)

γ customer_id, BOOL_OR(status = "returned") → any_returned (Orders)

# Description:
BOOL_OR folds a group's true/false values with OR: it is true as soon as one value in
the group is true, and false when none is. "Has this customer ever returned an
order?" is one column beside the customer. [BOOL_AND](bool_and.md) asks whether every
value is true.

# Technical Description:
BOOL_OR(b: BOOLEAN) → BOOLEAN, as PostgreSQL's `bool_or`. NULLs are skipped, and a
group with no value, empty or all NULL, gives NULL. An argument that is not a BOOLEAN
is refused when the query is analysed; a 0/1 flag is folded by comparing it,
`BOOL_OR(flag = 1)`. It takes `FILTER (WHERE …)`, rolls in a ROLLING window, and
computes per grouping set under ROLLUP, CUBE and GROUPING SETS. Order and repeats
make no difference to it. The name is matched by its text, so it is not reserved.

# Examples:
Customers with at least one returned order:
  σ any_returned = true (γ customer_id, BOOL_OR(status = "returned") → any_returned (Orders))

Whether any order in each region was large, beside the order count:
  γ region, COUNT(*) → orders, BOOL_OR(amount > 1000) → has_large (Orders)

# Pushdown:
SQL: folds to `BOOL_OR(<b>)` on **PostgreSQL**. Every other dialect runs it in the
engine: MySQL's `MAX` over a `TINYINT(1)` answers `0` or `1`, which is not the same
value without a cast.

# Alternatives:
To keep only the groups where some row holds, without a column, a semi-join or
`EXISTS` reads the rows directly. [∀](../operators/forall.md) is the "for all"
counterpart across relations.

# See Also:
[bool_and](bool_and.md), [forall](../operators/forall.md), [exists](../predicates/exists.md), [filter](filter.md), [group](../operators/group.md)
