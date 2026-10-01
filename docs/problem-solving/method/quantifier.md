# Find the quantifier

Somewhere in almost every question is a single word that decides the logic. Find that
word and the family of operators follows — the grain told you the *shape* of the
answer, and the quantifier tells you the *rule* that fills it. Learning to hear it is
most of what this manual teaches.

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
draws its class from: this table, and nowhere else, is the list of classes a reader is
taught and a recipe may claim.

## Questions that hide their quantifier

The table works when the word is on the surface. Often it is not — the quantifier is
buried in a domain noun, and the skill is translating the noun back into the word:

- *"loyal customers"* is usually **every** — customers who ordered in *every* period,
  not merely *some*.
- *"orphaned records"* is **none** — records with *no* matching parent.
- *"duplicates"* is **graph**, not selection — two records are the same when something
  links them, and *linked* runs in chains, so it is `CLUSTER`, not a join.
- *"the current price"* is **temporal alignment** — the price *as of* now.
- *"churn"* is **absence** — customers present last month and *not* this one.

When a question resists the table, restate it as plainly as you can and the word tends
to surface. *"Which suppliers are fully certified?"* becomes *"suppliers that hold
**every** required certificate"* — and *every* is in the table.

## Two quantifiers, two views

A real question often carries more than one quantifier, and the mistake is to look for
the single operator that does both. There isn't one. *"Suppliers that hold every
certificate **and** have never delivered late"* is a **universal** (∀/÷) and an
**absence** (▷), and the answer is the two computed separately and combined —
one view each, intersected. Hearing two words is the signal to write two views, which
is what [decompose into views](decompose.md) is for.

## Difficulty is a modifier, not a class

Three questions fix a problem's coordinate: the grain, the quantifier, and what makes
the data difficult. The third — nested, dirty, NULL-heavy, unbounded, federated, out
of step in time — is deliberately *not* in the table above. It is a **modifier** on a
recipe, not a class of its own: a *summary* over *federated* sources is still a γ, run
across two connections; a *ranking* over *dirty* data is still `TOP`, with the junk
filtered first. Find the quantifier to choose the recipe, then let the difficulty tell
you which of the recipe's pitfalls apply.
