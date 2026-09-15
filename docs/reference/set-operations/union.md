# Name: Union (∪ / UNION)

# Syntax:
Relation1 ∪ Relation2
Relation1 UNION Relation2

CurrentCustomers ∪ ArchivedCustomers

# Description:
Union stacks two relations on top of each other into one, and removes duplicate
rows. Both relations must have the same shape (same columns). Use it to combine
records from two sources into a single list — for example active and archived
customers — without listing anyone twice.

# Technical Description:
R ∪ S requires union-compatible schemas (same arity, compatible column types) and
returns the set of rows appearing in either, de-duplicated (set semantics). It is
a blocking ([set]) operator.

Compatibility is **positional**: the two sides must agree on the number of columns
and on the type of each position, and need not agree on what those columns are
called. Rows are matched by position for the same reason, so a row of `L` and a row
of `R` holding the same values in the same order are one row. The result carries the
left side's column names.

# Examples:
Combine two customer lists into one de-duplicated list:
  CurrentCustomers ∪ ArchivedCustomers

All cities mentioned in either shipping or billing addresses:
  (π city (ShippingAddresses)) ∪ (π city (BillingAddresses))

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

query { Subscribers ∪ TrialUsers };
```

```
 email            plan
 ───────────────  ─────
 ada@example.com  pro
 bo@example.com   free
 cy@example.com   pro
 dee@example.com  trial
(4 rows)
```

Four rows from three plus two: `bo@example.com` is in both lists and appears
**once**. Union is set semantics — it deduplicates. When you want to keep the
duplicate (to count contributions, say), use `⊎` / `UALL` instead, which returns
five rows here.

# Limitations:
Both sides must be union-compatible: the same number of columns, and compatible
types position by position. Column *names* need not match, and do not affect which
rows are duplicates. Duplicates are removed; to keep them use UNION ALL (⊎).

# Alternatives:
Union all (⊎) keeps duplicates and is cheaper. Intersection (∩) keeps only rows
in both; difference (−) keeps rows in the first but not the second.

# See Also:
[union-all](union-all.md), [intersection](intersection.md), [difference](difference.md), [symmetric-difference](symmetric-difference.md), [distinct](../operators/distinct.md)

# Notes:
If you know the inputs are already disjoint, ⊎ (UNION ALL) avoids the
de-duplication cost.
