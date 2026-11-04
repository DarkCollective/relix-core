# Name: BOOL_AND (whether every value in a group is true)

# Syntax:
BOOL_AND(<condition>)
EVERY(<condition>)       -- the SQL standard's name, the same aggregate

γ customer_id, BOOL_AND(status = "shipped") → all_shipped (Orders)

# Description:
BOOL_AND folds a group's true/false values with AND: it is true when every value in
the group is true, and false as soon as one is false. Asked of each customer's
orders, "have they all shipped?" is one column beside the customer, next to any
other aggregate the GROUP computes. [BOOL_OR](bool_or.md) asks whether any value is
true.

# Technical Description:
BOOL_AND(b: BOOLEAN) → BOOLEAN, as PostgreSQL's `bool_and`. NULLs are skipped, and a
group with no value, empty or all NULL, gives NULL. EVERY is the same aggregate under
the SQL standard's name, and prints back as BOOL_AND. An argument that is not a
BOOLEAN is refused when the query is analysed; a 0/1 flag is folded by comparing it,
`BOOL_AND(flag = 1)`. It takes `FILTER (WHERE …)`, rolls in a ROLLING window, and
computes per grouping set under ROLLUP, CUBE and GROUPING SETS. Order and repeats
make no difference to it. Both names are matched by their text, so neither is
reserved.

# Examples:
Customers whose every order has shipped:
  σ all_shipped = true (γ customer_id, BOOL_AND(status = "shipped") → all_shipped (Orders))

The standard spelling:
  γ region, EVERY(amount > 0) → all_positive (Orders)

Only the orders of this year:
  γ customer_id, BOOL_AND(status = "shipped") FILTER (WHERE order_date >= DATE '2026-01-01') → shipped (Orders)

Whether every order so far has shipped, as a running column:
  ROLLING BOOL_AND(status = "shipped") OVER ALL ROWS SORT order_date PER customer_id AS shipped_so_far (Orders)

# Pushdown:
SQL: folds to `BOOL_AND(<b>)` on **PostgreSQL**. Every other dialect runs it in the
engine: MySQL's `MIN` over a `TINYINT(1)` answers `0` or `1`, which is not the same
value without a cast.

# Alternatives:
[∀](../operators/forall.md) keeps the groups whose every row holds a condition, and
returns only their keys. It is the better tool for a relation-level "for all" across
two relations, such as the suppliers that supply every needed part, and for a
yes-or-no over the whole relation. BOOL_AND is the per-group fold: it keeps every
group and says, as a column, whether the condition held throughout. A row whose
condition is UNKNOWN disqualifies its group under ∀; BOOL_AND skips it.

# See Also:
[bool_or](bool_or.md), [forall](../operators/forall.md), [filter](filter.md), [group](../operators/group.md)
