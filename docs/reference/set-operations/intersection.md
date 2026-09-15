# Name: Intersection (∩ / INTER / INTERSECT)

# Syntax:
Relation1 ∩ Relation2
Relation1 INTER Relation2
Relation1 INTERSECT Relation2     -- SQL-prior synonym

PremiumCustomers ∩ ActiveCustomers

# Description:
Intersection keeps only the rows that appear in BOTH relations. It answers "what
do these two lists have in common" — for example customers who are both premium
and active. Both relations must have the same number of columns, with compatible
types position by position; what the columns are called need not match.

# Technical Description:
R ∩ S requires union-compatible schemas and returns rows present in both (set
semantics). It is a blocking ([set]) operator. Equivalent to R − (R − S).

Compatibility and row matching are both **positional**: two rows holding the same
values in the same order are the same row, whatever each side calls its columns.
The result carries the left side's names.

# Examples:
Customers who are both premium and active:
  PremiumCustomers ∩ ActiveCustomers

Skills required by a role that a candidate also has:
  (π skill (RoleRequirements)) ∩ (π skill (CandidateSkills))

# Worked Example:
Two mailing lists with one row in common — `bo@example.com`, who appears in
both, identically. Every set operation on this page is the same question about
that overlap, asked differently.

```relix
Subscribers := [
| email           | plan |
|-----------------|------|
| ada@example.com | pro  |
| bo@example.com  | free |
| cy@example.com  | pro  |
];

TrialUsers := [
| email           | plan  |
|-----------------|-------|
| bo@example.com  | free  |
| dee@example.com | trial |
];

query { Subscribers ∩ TrialUsers };
```

```
 email           plan
 ──────────────  ────
 bo@example.com  free
(1 row)
```

Only `bo@example.com` — the one row present in both. Note that intersection
compares **whole rows**, not just the key: had Bo's `plan` differed between the
two lists, the row would not match and the result would be empty. To intersect on
a key alone, project to that key first, or use a SEMI join.

# Limitations:
Both sides must be union-compatible. Compares whole rows; project to the relevant
columns first if you want to intersect on a subset.

# Alternatives:
A semi join (⋉) gives "left rows that have a match" while keeping the left's
extra columns. Union (∪) and difference (−) are the other set combinators.

# See Also:
[union](union.md), [difference](difference.md), [semi-join](../joins/semi-join.md)

# Notes:
For "common values of one key" project both sides to that key before
intersecting.
