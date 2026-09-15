# Name: Union All (⊎ / UALL)

# Syntax:
Relation1 ⊎ Relation2
Relation1 UALL Relation2

JanuarySales ⊎ FebruarySales

# Description:
Union all stacks two relations into one and KEEPS every row, including
duplicates. It is the right choice when you are concatenating data that is
already distinct (e.g. monthly partitions) and you do not want to pay for
de-duplication, or when duplicate counts actually matter.

# Technical Description:
R ⊎ S requires union-compatible schemas and returns the bag (multiset) union —
multiplicities add. It is a blocking ([bag]) operator and is cheaper than ∪
because it skips de-duplication. Selections replicate into both branches
(SEL-006).

# Examples:
Concatenate monthly partitions you know are disjoint:
  JanuarySales ⊎ FebruarySales ⊎ MarchSales

Pool measurements from several sensors, keeping every reading:
  SensorA ⊎ SensorB ⊎ SensorC

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

query { Subscribers ⊎ TrialUsers };
```

```
 email            plan
 ───────────────  ─────
 ada@example.com  pro
 bo@example.com   free
 cy@example.com   pro
 bo@example.com   free
 dee@example.com  trial
(5 rows)
```

Five rows — `bo@example.com` appears **twice**, once from each side. That is the
whole difference from `∪`, which would return four. Union-all never compares rows,
so it is also the cheaper of the two: no buffering, no dedup pass.

# Limitations:
Keeps duplicates by design — if you need a de-duplicated result use UNION (∪) or
follow with δ.

# Alternatives:
Union (∪) when you want duplicates removed.

# See Also:
[union](union.md), [distinct](../operators/distinct.md), [difference](difference.md)

# Notes:
A filter applied to a union-all is pushed into both branches by the optimizer, so
`σ p (A ⊎ B)` becomes `σ p (A) ⊎ σ p (B)`.
