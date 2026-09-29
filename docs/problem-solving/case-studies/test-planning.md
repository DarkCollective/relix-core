# Test planning

*Generation*

## The problem

*"We test payments across three payment methods, three currencies and two amount tiers.
The full matrix is too many combinations to run, and bank transfers only work in USD. Give
me the smallest suite that still exercises every pair of settings, and prove it covers
them."*

## Classifying it

This is a single-class case study on purpose — it shows a class at full depth rather than
crossing several. It is **[generation](../recipes/generation.md)**: *produce* a set of test
cases rather than filter existing ones. The tells are *"the full matrix"* (a combinatorial
space), *"too many to run"* (a budget), *"every pair"* (a coverage strength), and *"bank
transfers only in USD"* (a constraint on the space). Two moves: a cross product generates
the space and a σ constrains it, then `COVER` thins it to a pairwise-covering suite.

## The data

```relix
Method := [
| method |
|--------|
| card   |
| paypal |
| bank   |
];

Currency := [
| currency |
|----------|
| USD      |
| EUR      |
| GBP      |
];

Tier := [
| tier  |
|-------|
| small |
| large |
];
```

The full matrix is 3 × 3 × 2 = 18.

## Stage 1: generate, then constrain

The cross product proposes every combination; a σ removes the ones that cannot happen
(bank is USD-only):

```relix
Valid := { σ method ≠ "bank" ∨ currency = "USD" (Method × Currency × Tier) };
query { γ COUNT(*) → full  (Method × Currency × Tier) };
query { γ COUNT(*) → valid (Valid) };
```

```
 full
 ────
   18
(1 row)
 valid
 ─────
    14
(1 row)
```

## Stage 2: thin to a covering suite

`COVER 2` keeps a subset in which every **pair** of settings still appears together at
least once. It derives the pairs it must cover from the *constrained* set, so the
impossible combinations are never demanded:

```relix
query { γ COUNT(*) → chosen (COVER 2 (Valid)) };
```

```
 chosen
 ──────
      8
(1 row)
```

## Stage 3: prove it covers (comparison)

Coverage is not a matter of trust: every pair in the valid space minus every pair in the
suite is empty when the design is complete. The proof is an ordinary set difference:

```relix
query { (π method, currency (Valid)) − (π method, currency (COVER 2 (Valid))) };
```

```
 method  currency
 ──────  ────────
(0 rows)
```

## What this shows

Generation is *"describe the space, then thin it"*, and the thinning is honest because the
proof is in the language itself — a difference of projected pairs, not an external oracle.
The constraint is respected **by construction**: because the σ sits *below* `COVER`, the
coverage universe is the valid space, so no thinning step can smuggle a bank-in-EUR case
back in.

The one number to watch is Stage 2's `chosen`: a covering suite should be markedly smaller
than the full matrix (8 vs 18 here). If it is not, either the space is already small or
`COVER` is not earning its place — reach for it when the combinations explode, not when
there are a dozen.

## Recipes drawn on

- [Enough cases to cover](../recipes/generation.md) — `COVER`, the cross product, the
  coverage proof.
- [What changed, what differs](../recipes/comparison.md) — the set difference that proves
  completeness.
