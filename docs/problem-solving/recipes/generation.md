# Enough cases to cover

> **Grain:** one row per generated case · **Class:** Generation · **Signals:** enough cases, all combinations, every pair, test matrix, cover, generate then filter · **Operators:** `COVER`, × (generate)

## The problem

*"We test across three browsers, three operating systems and two plans. The full
matrix is 18 combinations, and Safari only runs on macOS. Give me the smallest test
suite that still exercises every pair of factor values — and prove it is complete."*

## How to recognise it

The question is about **producing** rows rather than filtering existing ones —
*enough cases*, *every combination*, *all pairs*, *a test matrix*, *generate and then
narrow*. The tell is a combinatorial space that explodes and a budget that cannot
afford all of it. Two moves make the class:

- **Generate the candidate space**, then constrain it — a cross product (×) proposes
  every combination, and a σ removes the invalid ones. *Generator proposes, selection
  disposes.*
- **Thin it to just enough** — `COVER t` keeps a small subset in which every
  combination of `t` values still appears at least once. `t = 2` is all-pairs, the
  usual sweet spot.

## The data

Three factors — the full space is 3 × 3 × 2 = 18.

```relix
Browser := [
| browser |
|---------|
| Chrome  |
| Firefox |
| Safari  |
];

OS := [
| os      |
|---------|
| Windows |
| macOS   |
| Linux   |
];

Plan := [
| plan |
|------|
| Free |
| Pro  |
];
```

## Recipe 1: generate, then constrain (× and σ)

The cross product is the generator: it proposes every combination. A σ then removes
the ones that cannot happen — Safari runs only on macOS:

```relix
Full  := { Browser × OS × Plan };
Valid := { σ browser ≠ "Safari" ∨ os = "macOS" (Full) };
query { γ COUNT(*) → valid_combos (Valid) };
```

<!-- output: paste from a run. Expected: 14 — the 18 combinations minus Safari on Windows and Linux (2 browsers-off × 2 plans). -->

## Recipe 2: thin to a covering suite (COVER)

`COVER 2` keeps a subset in which **every pair** of factor values still appears
together at least once. Crucially, it derives the pairs it must cover from the
*constrained* input — so the invalid combinations are never demanded, and the
constraint comes for free:

```relix
Suite := { COVER 2 (Valid) };
query { τ browser, os (Suite) };
```

<!-- output: paste from a run. Expected: 8 rows out of 14, and Safari appears only on macOS. -->

Eight cases instead of the full fourteen, yet every browser–os, browser–plan and
os–plan pair is present somewhere. The `τ` only sorts for reading; `COVER` emits in
selection order.

**Prove it is complete, in-language.** Coverage is verifiable with a difference —
every pair in the valid space minus every pair in the suite is empty when the design
is complete, so no external oracle is needed:

```relix
query { (π browser, os (Valid)) − (π browser, os (Suite)) };
```

<!-- output: paste from a run. Expected: empty — every (browser, os) pair the constraint allows is covered. -->

```relix
query { σ browser = "Safari" (Suite) };
```

<!-- output: paste from a run. Expected: only Safari-on-macOS rows — the constraint held through the thinning. -->

## Variations

- **`COVER 1`** keeps every value once (the cheapest); **`COVER t` at t = the number
  of columns** is every distinct row (equivalent to δ). `t` interpolates between them.
- **Bias which candidates win** — order the input first: `COVER 2 (τ priority DESC
  (Valid))`, since the greedy search breaks ties toward earlier rows.
- **An unbounded generator** (a numeric range, the naturals) must be bounded before a
  blocking operator like `COVER`, `γ` or δ consumes it — a σ or `TOP` below it. A cross
  product of finite tables, as here, is already bounded.

## Pitfalls

- **Strength is required and is not a filter.** `COVER (…)` with no `t` is an error.
  `COVER 2` does not *reduce* the semantics — it selects rows; it never invents a
  combination that was not in the input.
- **Greedy, not minimal.** `COVER` finds a near-minimal suite, not a provably minimal
  one — it may be a row or two above the theoretical optimum. That is the right
  trade-off for the cost of an exact solver.
- **Constrain before you cover, not after.** Filtering the *suite* after `COVER` can
  break coverage. Put the σ below `COVER` so the coverage universe is the valid space —
  then the constraint is respected by construction.
- **A blocking generator over an unbounded input is rejected.** Bound it first.

## Check it

- **Verify coverage in-language** with the difference above — do not trust the row
  count alone. An empty difference for every factor pair is the proof.
- Add a **constraint** and confirm the excluded combinations appear in neither the
  valid space nor the suite (no Safari on Windows).
- Compare the suite size to the full matrix — a covering suite should be markedly
  smaller, or `COVER` is not earning its place.

## Related

- [Which rows, and which columns](selection.md) — the σ that constrains the generated
  space is ordinary selection.
- [What changed, what differs](comparison.md) — the coverage proof is a set difference.
- Reference pages (`docs/reference`): `cover`, `cross`, `distinct`,
  `difference`.
