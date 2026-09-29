# Iterate until it settles

> **Grain:** one row per thing that settles (a page, a cell) · **Class:** Iteration · **Signals:** converge, iterate until stable, simulate rounds, settle, numeric fixpoint, generations · **Operators:** `ITERATE`

## The problem

*"Rank these pages by a score that is refined until it stops moving. Or run a cellular
automaton forward a few generations, where cells are born and die each round."*

## How to recognise it

The question describes a computation that **settles** rather than **accumulates** —
*converge*, *until stable*, *simulate N rounds*, *until the numbers stop moving*,
*generations*. Each round starts from what the last round produced and **replaces** it;
the answer is the final round.

This is the tell that separates it from [recursion](recursion.md) (`FIX`): `FIX` only
ever *adds* rows and terminates on its own when nothing new appears. `ITERATE` *replaces*
the whole state each round, so rows can come and go — which is exactly what a score being
refined, or a cell dying, needs, and what `FIX` cannot express.

`ITERATE name (base, step)` binds `name` to the previous round's whole output; the step's
output replaces it. Say when to stop:

- **`ROUNDS n`** — apply the step exactly `n` times.
- **`UNTIL STABLE MAX n ROUNDS`** — stop when a round changes nothing.
- **`UNTIL c, … WITHIN ε PER k, … MAX n ROUNDS`** — stop when no value in columns `c`
  moved by more than `ε`, pairing rows across rounds by key `k`.

The two `UNTIL` forms **require** a round cap and do not return a result they did not
reach.

## Recipe 1: converge to a fixpoint (UNTIL … WITHIN)

PageRank refines every page's score by passing it along outgoing links, round after
round, until the scores stop moving. A page's rank is the share of a random surfer's
time spent on it.

```relix
Links := [
| src | dst |
|-----|-----|
| A   | B   |
| A   | C   |
| B   | C   |
| C   | A   |
| D   | C   |
];

Pages     := { δ (π src → page (Links) ∪ π dst → page (Links)) };
OutDegree := { γ src, COUNT(*) → out (Links) };
Weighted  := { Links ⋈ OutDegree };

-- 0.0375 is the jump share: (1 − 0.85) / 4 pages
Rank := { ITERATE R (
  π page, 0.25 → rank (Pages),
  π page, 0.0375 + 0.85 * Coalesce(passed, 0) → rank (
    Pages ⟕ Pages.page = In.dst ρ In(dst, passed) (
      γ dst, SUM(rank / out) → passed (ρ From(src, rank) (R) ⋈ Weighted)))
) UNTIL rank WITHIN 0.0001 PER page MAX 100 ROUNDS };
query { τ rank DESC (Rank) };
```

```
 page  rank
 ────  ──────────────
 C     0.394199878685
 A      0.37249131329
 B      0.19580880811
 D             0.0375
(4 rows)
```

The step reads the previous round's `(page, rank)` as `R`, passes each page's rank along
its links (`rank / out` per link), sums what arrives at each destination, and keeps a
page nothing links to via the outer join. `UNTIL rank WITHIN 0.0001 PER page` stops when
no page's rank moved by more than the tolerance between rounds.

A scalar numeric fixpoint — Newton's method for a square root, `x → (x + n/x) / 2` — is
the same shape with one row and one `UNTIL` column.

## Recipe 2: simulate rounds (a state machine)

Conway's Life is the canonical state machine: each generation *replaces* the last, and a
cell's fate depends on its live neighbours. `ROUNDS 4` runs the glider forward four
generations.

```relix
Offsets := [
| dx | dy |
|----|----|
| -1 | -1 |
| -1 | 0  |
| -1 | 1  |
| 0  | -1 |
| 0  | 1  |
| 1  | -1 |
| 1  | 0  |
| 1  | 1  |
];

Glider := [
| x | y |
|---|---|
| 1 | 0 |
| 2 | 1 |
| 0 | 2 |
| 1 | 2 |
| 2 | 2 |
];

Later := { ITERATE Board (
  Glider,
  π x, y (σ n = 3 (ρ C(x, y, n) (γ nx, ny, COUNT(*) → n (π x + dx → nx, y + dy → ny (Board × Offsets)))))
  ∪ π x, y (σ n = 2 (ρ C(x, y, n) (γ nx, ny, COUNT(*) → n (π x + dx → nx, y + dy → ny (Board × Offsets)))) ⋈ Board)
) ROUNDS 4 };
query { τ x, y (Later) };
```

```
 x  y
 ─  ─
 1  3
 2  1
 2  3
 3  2
 3  3
(5 rows)
```

The step counts live neighbours (`Board × Offsets`, grouped), keeps cells with exactly
three (a birth) and cells with exactly two that were already alive (survival). Rows are
born and die each round — the whole board is replaced — which is why this is `ITERATE`,
not `FIX`. Swapping `ROUNDS 4` for `UNTIL STABLE MAX 50 ROUNDS` would settle a still
life, report a blinker as a period-2 cycle, and report the glider as not settling (it
moves forever).

## Variations

- **The three stop clauses** answer three questions: *run N rounds* (`ROUNDS`), *has it
  stopped changing?* (`UNTIL STABLE`), *have the numbers converged?* (`UNTIL … WITHIN`).
- **A rule written once** — the step may call a relation-valued function, so
  `ITERATE G (Start, generation(G)) ROUNDS 5` applies a `def`-ined rule each round.
- **Label propagation, smoothing, other scores** are Recipe 1's shape with a different
  step.

## Pitfalls

- **`ITERATE` replaces; `FIX` accumulates.** If the answer only ever grows (reachability,
  a parts explosion), that is [recursion](recursion.md) (`FIX`), which needs no round cap.
  Reach for `ITERATE` when state is *replaced* each round.
- **An `UNTIL` test needs a round cap and may not be reached.** The `UNTIL` forms require
  `MAX n ROUNDS` and report two different misses: a **cycle** (a round reproduced an
  earlier one — it can never settle, and the error names the period) or the **cap
  reached** without converging. Neither returns a partial result.
- **The step must be deterministic.** A step calling `Rand()`, `NOW()`, an unseeded
  sample, or a user function is rejected — a round that could answer differently makes a
  convergence test meaningless.
- **`UNTIL … WITHIN` needs declared NUMBER columns and a key that identifies one row per
  round.** Two rows sharing a `PER` key in a round is an error; the base's schema must be
  declared (not schema-on-read).
- **A selection is not pushed into an `ITERATE`.** Filtering PageRank to one page would
  change the other pages' values, so the optimiser leaves a σ above it.

## Check it

- Give an `UNTIL … WITHIN` iteration a **tiny** input whose fixpoint you can compute by
  hand, and confirm it converges to it — and that the ranks (or shares) **sum** to what
  they should.
- Feed a known **cycle** (a Life blinker) to `UNTIL STABLE` and confirm it reports the
  period rather than looping.
- Confirm `ROUNDS 0` returns the base unchanged, and that a state-machine step actually
  *loses* rows some round (proving it replaces rather than accumulates).

## Related

- [When the rule is recursive](recursion.md) — `FIX`, the *accumulating* sibling; the
  Pitfalls above are mostly about telling the two apart.
- [How many, how much, per what](summary.md) — each round's step is ordinary algebra, γ
  and joins included (unlike a `FIX` step).
- Reference pages (`docs/reference`): `iterate`, `fix`.
