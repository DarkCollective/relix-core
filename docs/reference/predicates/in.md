# Name: Set Membership (∈ / IN, ∉ / NOT IN)

# Syntax:
attr ∈ { value1, value2, ... }      attr IN { value1, value2, ... }
attr ∉ { value1, value2, ... }      attr NOT IN { value1, value2, ... }

σ dept ∈ {"hr", "eng"} (Employees)
σ dept NOT IN {"mgmt"} (Employees)

# Description:
The membership test keeps rows whose value is one of a listed set (∈ / IN), or is
NOT one of them (∉ / NOT IN). It is the tidy way to write "status is new, pending,
or paid" without a long chain of OR equalities.

# Technical Description:
∈ forms an ElementOfPredicate against a set literal `{…}`; ∉ is its negated form.
Equivalent to a disjunction of equalities but expressed once. ASCII: IN, and NOT
IN (two tokens). The set literal holds constant operands.

# Examples:
One of several departments:
  σ dept ∈ {"hr", "eng", "finance"} (Employees)

Exclude a set of statuses:
  σ status NOT IN {"cancelled", "refunded"} (Orders)

Numeric set:
  σ priority IN {1, 2, 3} (Tickets)


# Worked Example:
One membership test standing in for a chain of equalities:

```relix
Tickets := [
| id | priority | status    |
|----|----------|-----------|
| 1  | 1        | open      |
| 2  | 2        | cancelled |
| 3  | 3        | open      |
| 4  | 5        | refunded  |
];

query { σ priority ∈ {1, 2, 3} (Tickets) };
```

```
 id  priority  status
 ──  ────────  ─────────
  1         1  open
  2         2  cancelled
  3         3  open
(3 rows)
```

The same rows come back from `σ priority = 1 ∨ priority = 2 ∨ priority = 3`; the
set literal is the readable spelling of that disjunction.

`∉` is the exclusion form, and reads the way you would say it out loud — "not
cancelled or refunded":

```relix
query { σ status ∉ {"cancelled", "refunded"} (Tickets) };
```

```
 id  priority  status
 ──  ────────  ──────
  1         1  open
  3         3  open
(2 rows)
```

# Limitations:
The set is a literal list of constant values, not a subquery — to test membership
in another relation's values, use a SEMI join (⋉) instead. A NULL value on either
side makes the test UNKNOWN and the row is dropped: a NULL column is neither in
the set nor out of it, and if the *set* holds a NULL then failing to match the
other elements settles nothing — the unknown one might have been the value — so
`∉` keeps that row no more than `∈` does.

# Alternatives:
A SEMI join (⋉) for "value appears in another relation". A chain of `= … ∨ = …`
is the long-hand form IN replaces.

# See Also:
[or](or.md), [comparison](comparison.md), [semi-join](../joins/semi-join.md), [select](../operators/select.md)

# Notes:
Prefer IN over a long OR chain for readability; for dynamic sets drawn from data,
reach for a semi join.
