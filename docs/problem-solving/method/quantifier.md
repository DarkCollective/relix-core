# Find the quantifier

<!-- skeleton: the table is the substance of the chapter; prose to be written -->

**The claim.** Somewhere in every question is the word that decides the logic.
Find it and the recipe family follows.

| The question says… | Class | Reach for |
|---|---|---|
| "which …" | Selection | σ, π, joins |
| "how many / how much" | Summary | γ, `ROLLING`, `DOWNSAMPLE` |
| "the most / top N / latest" | Ranking | `TOP`, `WINDOW RANK`, `ARGMAX` |
| "any / at least one" | Existence | ⋉ |
| "none / never / missing" | Absence | ▷, − |
| "every / all / only" | Universal | ∀, ÷ |
| "what changed / what differs" | Comparison | −, ∆, ⟗ |
| "connected / reachable / via / ancestors" | Graph | `CLOSURE`, `PATH`, `CLUSTER`, `TRACE`, `FIX` |
| "as of / during / overlapping" | Temporal alignment | AS-OF join, interval join |
| "in a row / per visit / gaps" | Sequence | `SESSIONIZE`, `WINDOW LAG`/`LEAD` |
| "the best combination within limits" | Optimization | `OPTIMIZE` |
| "what value makes this true" | Equation | `SOLVE` |
| "enough cases to cover" | Generation | `COVER`, generators |
| "as a tree / as columns / flattened" | Reshaping | `PIVOT`/`UNPIVOT`, `TREE`, μ, `COLLECT` |
| "why / where did this come from" | Explanation | `WHY` |
| "a random sample / a representative subset" | Sampling | `SAMPLE`, `SAMPLE … ROWS` |
| "converge / iterate until stable / simulate rounds" | Iteration | `ITERATE` |

The **Class** column is the registry every recipe's [coordinate](../coordinate.md)
draws its class from: this table, and nowhere else, is the list of classes a reader
is taught and a recipe may claim.

**To cover:**

- Questions that hide their quantifier: "loyal customers" is usually *every*,
  "orphaned records" is *none*, "duplicates" is *graph*.
- Questions carrying two quantifiers, and how they become two views.
- The difficulty axis (nested, dirty, NULLs, unbounded, federated, time skew) as
  a modifier on a recipe rather than a class of its own.
