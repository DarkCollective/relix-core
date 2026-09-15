# Name: Selection (σ / SELECT)

# Syntax:
σ <condition> (Relation)
SELECT <condition> (Relation)

σ age > 18 (Users)
SELECT age > 18 (Users)

# Description:
Selection keeps only the rows that match a condition and throws the rest away.
Think of it as the filter on a spreadsheet: "show me only the rows where…".
The relation that comes out has exactly the same columns as the one that went
in — just fewer rows.

The condition can test a column against a value, combine several tests with
AND / OR / NOT, check for missing values, or test membership in a set.

# Technical Description:
σ_p(R) returns the subset of tuples of R for which predicate p evaluates to
TRUE. A row whose predicate evaluates to NULL/UNKNOWN is dropped (three-valued
logic). Selection is a streaming operator — it does not buffer the input — and
the optimizer pushes it as close to the data source as possible, including down
into SQL/Mongo backends as a WHERE / $match clause.

# Examples:
Find adult users:
  σ age >= 18 (Users)

Customers in a specific city, by exact match on text:
  σ city = "London" (Customers)

Combine conditions — active accounts created this year:
  σ active = true ∧ year = 2026 (Accounts)

Orders that are NOT cancelled:
  σ ¬(status = "cancelled") (Orders)

Filter on a computed value — high-value line items:
  σ (price * qty) > 1000 (LineItems)

Rows with a missing email address:
  σ email = NULL (Users)

Members of a set of departments:
  σ dept IN {"hr", "eng", "finance"} (Employees)

# Worked Example:
Four rows of sales, two of them with no `bonus` recorded — the NULLs are there deliberately, because they are what makes the rules below visible.

```relix
Sales := [
| region | rep  | amount | bonus |
|--------|------|--------|-------|
| east   | Ada  | 120    | 10    |
| east   | Bo   | 80     | NULL  |
| west   | Cy   | 200    | 25    |
| west   | Dee  | 50     | NULL  |
];

query { σ amount > 100 (Sales) };
```

```
 region  rep  amount  bonus
 ──────  ───  ──────  ─────
 east    Ada     120     10
 west    Cy      200     25
(2 rows)
```

Same four columns, fewer rows — selection never changes the schema. Note what
happens to the two NULL `bonus` values: nothing. The condition tests `amount`, so
the NULLs are irrelevant here. Had the condition been `bonus > 5`, Bo and Dee
would have been **dropped**, because a comparison against NULL is UNKNOWN, not
true — see [is-null](../predicates/is-null.md) for how to keep them.

# Limitations:
A row whose condition is unknown because of a NULL is excluded, not kept. To
keep NULLs you must test for them explicitly (e.g. `status = "x" ∨ status = NULL`).

# Alternatives:
For "keep rows that have a match in another relation" use a SEMI join (⋉)
rather than a selection with a subquery — relix has no scalar subqueries.

# See Also:
[project](project.md), [theta-join](../joins/theta-join.md), [semi-join](../joins/semi-join.md), [and](../predicates/and.md), [or](../predicates/or.md), [not](../predicates/not.md), [is-null](../predicates/is-null.md), [in](../predicates/in.md)

# Notes:
Selection is one of the most heavily optimized operators: adjacent selections
are merged, conjunctions are split so each part can be pushed independently, and
selections slide below projections, renames, and into join inputs.
