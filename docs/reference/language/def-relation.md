# Name: def … : RELATION (table-valued function)

# Syntax:
def <name>(<param>: <TYPE> [, ...]) : RELATION := { <relational expression> };

def ordersFor(cid: NUMBER) : RELATION := {
    σ customer_id = cid ∧ status = "completed" (Orders)
};

# Description:
A table-valued function is a parameterised view: a named query that takes
arguments and returns a whole relation. Define "the completed orders for customer
X" once, then call it with different customers wherever a relation is expected —
in a join, a projection, or as a query on its own. It keeps repeated query shapes
DRY.

# Technical Description:
A `def name(...) : RELATION := { body }` binds a RelationFunctionSymbol whose body
is a relational-algebra expression; its result schema is the body's inferred
schema (like a view). Binding is by substitution — the planner inlines the body
with each parameter reference replaced by the supplied argument, then re-infers
its schema. Arguments must be constant expressions (no column references); a correlated
argument needs a `LATERAL` join instead. Nested TVF calls inline
transitively; recursion is rejected at plan time. The RELATION keyword is
case-insensitive.

# Examples:
A filtered, parameterised view:
```relix
def ordersFor(cid: NUMBER) : RELATION := {
    σ customer_id = cid ∧ status = "completed" (Orders)
};
query { ordersFor(2) };
```

Use it inside a join:
  query { π name, amount (Customers ⋈ ordersFor(2)) };

Zero-argument reusable query:
  def activeUsers() : RELATION := { σ active = true (Users) };

# Limitations:
Arguments must be constant — you cannot pass a column from the surrounding query
(no lateral/correlated arguments yet). A TVF body cannot reference itself
(recursion is rejected). The body is not pushed down to SQL as a unit.

# Alternatives:
A plain view (`Name := { … };`) when no parameters are needed. FIX for genuine
recursion.

# See Also:
[def](def.md), [assignment](assignment.md), [import](import.md), [natural-join](../joins/natural-join.md)

# Notes:
Because binding is by inlining, a TVF behaves exactly as if you had written its
body inline with the arguments substituted.
