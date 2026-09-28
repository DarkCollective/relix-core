# Name: Iteration (ITERATE)

# Syntax:
ITERATE <name> (<base>, <step>) ROUNDS <n>
ITERATE <name> (<base>, <step>) UNTIL STABLE MAX <n> ROUNDS
ITERATE <name> (<base>, <step>) UNTIL <column>, … WITHIN <tolerance> PER <key>, … MAX <n> ROUNDS

ITERATE Board (Start, <next generation of Board>) ROUNDS 4

# Description:
ITERATE repeats a computation, each round starting from what the previous round
produced. You give it a starting relation (the base) and a rule for turning one
round into the next (the step), and say when to stop: after a fixed number of
rounds, when a round changes nothing, or when the numbers it computes stop moving.

It is the operator for anything that *settles* rather than *accumulates*: PageRank
and other scores that are refined until they converge, label propagation, and
state machines such as a cellular automaton, where each generation replaces the
last. FIX, by contrast, only ever adds rows, and so cannot express any of these.

# Technical Description:
`ITERATE name (base, step)` evaluates `base`, then evaluates `step` with `name`
bound to the whole of the previous round's output, which the step's output then
replaces. The result is the last round. Each round is a set — duplicate rows are
collapsed — and the step must be positionally union-compatible with the base,
whose column names the output keeps.

`name` is visible in the step only, where it also qualifies the relation's
columns — `R.rank` — as the name of any relation does. The step may hand the
relation to a table-valued function that takes a relation parameter —
`ITERATE G (Start, generation(G)) ROUNDS 5` — so a rule written once as a
[function](../language/def-relation.md) is what each round applies. Because nothing accumulates, the step is free
of FIX's rules: it may reference `name` any number of times, through any operator,
aggregation and outer joins included. It must reference it at least once, and it
must be deterministic — a step calling `Rand()` or `NOW()`, sampling without a
seed, or calling a user-defined function is rejected, since a round that could
answer differently for the same input makes a convergence test meaningless.

The stop clause is one of three:

- **`ROUNDS n`** applies the step exactly `n` times (`ROUNDS 0` is the base). It
  makes no claim that anything settled, and always finishes.
- **`UNTIL STABLE MAX n ROUNDS`** stops at the first round whose output equals its
  input.
- **`UNTIL c, … WITHIN ε PER k, … MAX n ROUNDS`** pairs each row with the row
  holding the same key in the previous round, and stops when no value in the
  `UNTIL` columns moved by more than `ε`: the largest absolute change, per row,
  across every named column. A key that appears or disappears counts as a change,
  and a NULL is unchanged only against another NULL. The `UNTIL` columns must be
  NUMBER, and the `PER` columns must identify one row in every round — a round in
  which two rows share a key is an error.

The two `UNTIL` forms have a mandatory round cap, and do not return a result they
did not reach. There are two ways to miss it, reported differently:

- **The iteration cycled.** A round reproduced an earlier round exactly. The step
  is deterministic, so every round after it repeats the same cycle, and the test
  can never pass: the error names the period.
- **The cap was reached** without the test passing and without a cycle — the
  iteration may be converging slowly, or not at all.

ITERATE materialises its input, so it is refused over an unbounded relation.

# Examples:
A cellular automaton — the next generation of Conway's Life, where a cell lives if
it has three live neighbours, or two and was already alive:

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

-- four generations later, the glider has moved one cell diagonally
Later := { ITERATE Board (
  Glider,
  π x, y (σ n = 3 (ρ C(x, y, n) (γ nx, ny, COUNT(*) → n (π x + dx → nx, y + dy → ny (Board × Offsets)))))
  ∪ π x, y (σ n = 2 (ρ C(x, y, n) (γ nx, ny, COUNT(*) → n (π x + dx → nx, y + dy → ny (Board × Offsets)))) ⋈ Board)
) ROUNDS 4 };
```

The same step with `UNTIL STABLE MAX 50 ROUNDS` in place of `ROUNDS 4` settles a
still life, reports a blinker as a cycle of period 2, and reports a glider as not
settling within 50 rounds — it never repeats, it only moves.

A round cap is required for an `UNTIL` test:

```relix-invalid
Start := [
| x |
|---|
| 1 |
];

query { ITERATE S (Start, S) UNTIL STABLE };
```

# Worked Example:
A small web of four pages links to one another, and the question is which pages
matter most. PageRank answers it by imagining a reader who follows a random link
from each page 85% of the time and jumps to a random page otherwise: a page's rank
is the share of time the reader spends there. Every page starts equal, and each
round passes each page's rank along its outgoing links until the ranks stop moving.

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

Pages := { δ (π src → page (Links) ∪ π dst → page (Links)) };
OutDegree := { γ src, COUNT(*) → out (Links) };
Weighted := { Links ⋈ OutDegree };

-- 0.0375 is the jump share: (1 − 0.85) / 4 pages
Rank := { ITERATE R (
  π page, 0.25 → rank (Pages),
  π page, 0.0375 + 0.85 * Coalesce(passed, 0) → rank (
    Pages ⟕ Pages.page = In.dst ρ In(dst, passed) (
      γ dst, SUM(rank / out) → passed (ρ From(src, rank) (R) ⋈ Weighted)))
) UNTIL rank WITHIN 0.0001 PER page MAX 100 ROUNDS };
```

How the step works: `R` is the previous round's `(page, rank)`. Renaming it to
`(src, rank)` lets it join the links leaving each page, each carrying its page's
out-degree, so `rank / out` is the share passed along one link, and the `γ` sums
what arrives at each destination. The left outer join keeps a page that nothing
links to — without it, D would drop out of the next round — and `Coalesce` gives
it nothing passed in. The `UNTIL` clause stops when no page's rank moved by more
than 0.0001, pairing the rows of two rounds by `page`.

```relix
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

C is linked from three pages and ranks highest; A is linked only from C, but C's
whole rank flows to it. D has no incoming links, so its rank is the jump share
alone. The ranks sum to 1 — the reader has to be somewhere — to within the
rounding of each division.

# Limitations:
The `UNTIL … WITHIN` test needs the base's columns to be declared; over a relation
whose schema is read from the data it is an error at run time. NUMBER division
rounds to 10 decimal places, so a tolerance below `0.0000000001` cannot be told
apart from zero.

The optimizer does not move a selection or a limit from above an ITERATE into it,
because a filter on one row can change the others' values — restricting PageRank
to one page changes that page's rank. Rewrites inside the step, which preserve
each round, still apply, and a sub-expression of the step that does not read the
iterated name is evaluated once for the whole iteration rather than once per round.

# Alternatives:
FIX for recursion that only adds rows — reachability, bills of materials,
hierarchies. It needs no round cap and terminates on its own when the data is
finite. CLOSURE, PATH and TRACE for the graph questions they name.

# See Also:
[fix](fix.md), [closure](closure.md), [path](path.md), [left-outer-join](../joins/left-outer-join.md), [group](../operators/group.md)

# Notes:
The `--max-fixpoint-rounds` and `--max-materialized-rows` caps described on the
[fix](fix.md) page apply to ITERATE too. The first bounds the rounds of any one
ITERATE; the second the rows it holds, which is the current round, the one being
built, and one earlier round kept to detect a cycle.
