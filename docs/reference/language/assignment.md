# Name: := and query (views and result queries)

# Syntax:
<Name> := { <relational expression> };       -- define a named view
query { <relational expression> };            -- an anonymous result query
query <Name>;                                 -- emit a named view as a result

CustomerTotals := { γ customer_id, SUM(amount) → total (Orders) };
query { π customer_id, total (CustomerTotals) };

# Description:
The `:=` assignment names an intermediate result (a "view") so you can build a
query up in readable steps and reuse a sub-result by name. A `query` statement
marks what the script should actually produce — either an inline expression or a
previously-named view. Together they let you write a pipeline as a series of
named stages ending in one or more results.

# Technical Description:
`Name := { body }` binds a QueryRelationSymbol whose `body()` is a RelNode; it is
a logical view, inlined by the optimizer/planner at use sites (INLINE-001) so
pushdown crosses former view boundaries. `query { expr }` registers a root query
(an ExpressionQueryTarget, auto-named query[n]); `query Name;` registers a
NamedQueryTarget. The SemanticModel's rootQueries drive execution, optimisation,
and IR generation.

# Examples:
Build in stages and query the last:
```relix
OrderAmounts := { π order_id, amount (Orders) };
CustomerTotals := { γ customer_id, SUM(amount) → total (Orders) };
query CustomerTotals;
```

Anonymous inline result:
  query { λ 10 (τ amount DESC (Orders)) };

Reuse a view in two places:
```relix
Active := { σ active = true (Customers) };
query { Active ⋈ Orders };
```

# Limitations:
A view is a logical definition, not a materialised table — it is recomputed
(inlined) wherever used. A script with no `query` statement produces no output.

Outside a view, its columns answer to the view's name, not to the relations inside
it. When the view reads a schema-on-read source (JSON, HTTP, MongoDB), a reference
that still uses an inner relation's name is not refused. It yields NULL instead; see
[theta-join](../joins/theta-join.md) for a worked case.

# Alternatives:
A table-valued function (`def … : RELATION`) is a view that takes parameters.

# See Also:
[def-relation](def-relation.md), [project](../operators/project.md), [group](../operators/group.md)

# Notes:
Views are inlined before optimisation, so splitting a query into named views costs
nothing at runtime and aids readability.
