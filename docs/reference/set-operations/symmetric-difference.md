# Name: Symmetric Difference (∆ / SYMDIFF)

# Syntax:
Relation1 ∆ Relation2
Relation1 SYMDIFF Relation2

LedgerA ∆ LedgerB

# Description:
Symmetric difference keeps the rows that are in exactly one of the two relations
— in the first or the second, but not in both. It is the natural "what's
different between these two lists" operator, ideal for reconciling two snapshots
and surfacing only the discrepancies.

# Technical Description:
R ∆ S = (R − S) ∪ (S − R), defined over union-compatible relations. The planner
desugars it into difference and union over set-semantics inputs (materialises
SET). It does not push down.

# Examples:
Reconcile two account ledgers — show only the entries that differ:
  LedgerA ∆ LedgerB

Rows that changed between yesterday's and today's snapshot:
  Yesterday ∆ Today

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

query { Subscribers ∆ TrialUsers };
```

```
 email            plan
 ───────────────  ─────
 ada@example.com  pro
 cy@example.com   pro
 dee@example.com  trial
(3 rows)
```

Everyone who is in exactly one of the two lists — the union minus the
intersection. Unlike `−`, this **is** symmetric: swapping the operands gives the
same three rows. It is the "what disagrees between these two sources?" operator,
which is why it is useful for reconciliation.

# Limitations:
Both sides must be union-compatible. Like difference, it is non-monotone and
cannot appear in a recursive FIX step. It materialises rather than streams.

# Alternatives:
A full outer join (⟗) with NULL tests gives a similar reconciliation when you
also want to see which side each row came from. Difference (−) for one-directional
"in A not B".

# See Also:
[difference](difference.md), [union](union.md), [full-outer-join](../joins/full-outer-join.md)

# Notes:
Cleaner than a full-outer-join-plus-NULL-tests when the two relations already
share the same columns and you only care about the discrepancies.
