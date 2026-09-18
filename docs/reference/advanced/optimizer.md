# Name: Query optimizer (rewrite rules)

# Syntax:
relix --optimize script.relix     -- print which rules fired, and the before/after trees
relix --trace script.relix        -- a live feed of optimizer, planner and execution decisions

-- in the REPL
:opt <query>                      -- the optimization report for one query
:explain <query>                  -- the physical plan the optimized tree produced

# Description:
Relix rewrites every query's expression tree before planning it. The rewrite is
purely *logical* — the result is a different tree that computes the **same
relation**, chosen because it does less work. Nothing here changes an answer; a
rule that cannot prove itself safe simply does not fire.

Each firing is recorded under a stable **rule code** (`SEL-007`, `LIM-003`, …) so
`--optimize` and `:opt` can show exactly what happened and why a plan looks the
way it does. The code is the contract: it is stable across releases, greppable in
logs, and is what to quote in a bug report.

Views are **inlined first** (`INLINE-001`), so a rule can optimise across a former
view boundary — a filter written outside a view still reaches the table inside it.
Inlining leaves an alias wrapper behind, which `RENAME-001`/`RENAME-002` then clear
away (see *Rename rules* below).

## Why it matters: pushdown compounds

Relix pushes work into the backend where it can. A σ that the
optimizer moves *below* a γ becomes a SQL `WHERE` instead of a `HAVING`, so the
database filters **before** grouping rather than after. The same holds for the
other rules: every operator a filter or limit crosses in the engine is one more
thing the source can do instead.

## Expression and predicate rules (`EXPR-nnn`, `PRED-nnn`)

These run first, so every later rule matches against settled expressions.

| Code | Rewrite |
|------|---------|
| `EXPR-001` | Fold a constant arithmetic expression (`2 * 3` → `6`) |
| `EXPR-002` | Fold a constant string concatenation |
| `EXPR-003` | Drop an additive identity (`x + 0` → `x`) |
| `EXPR-004` | Drop a multiplicative identity (`x * 1`, `x / 1` → `x`) |
| `EXPR-005` | Replace a multiplication by zero with `0` |
| `EXPR-006` | Remove a double negation (`-(-x)` → `x`) |
| `EXPR-007` | Reassociate constants so they can be folded (`(x + 1) + 2` → `x + 3`) |
| `EXPR-008` | Drop a redundant call to an idempotent built-in (`UCase(UCase(x))` → `UCase(x)`) |
| `PRED-001` | Fold a constant branch away (`TRUE ∧ p` → `p`) or short-circuit an absorbing one (`FALSE ∧ p` → `FALSE`) |
| `PRED-002` | Remove a double negation (`¬¬p` → `p`) |
| `PRED-003` | Normalise a comparison to attribute-on-left (`5 > age` → `age < 5`) |
| `PRED-004` | Collapse a conjunction whose bounds on one column cannot all hold (`x > 5 ∧ x < 3` → `false`) |
| `PRED-005` | Drop a bound another conjunct already implies (`x > 5 ∧ x > 3` → `x > 5`) |
| `PRED-006` | Drop a repeated conjunct (`p ∧ p` → `p`) |

Division by zero is never folded — the error belongs at run time, on the rows
that actually hit it, not at plan time on a query that may never reach them.
`NOW()`, `CURRENT_DATE()`, `CURRENT_TIME()` and `Rand()` are never folded or
de-duplicated either: they read the clock or the RNG, so two calls are not the
same value.

### Where they apply: every expression, on every operator

An expression is not only a σ predicate or a π attribute. Wherever an operator
evaluates a scalar expression or a predicate, both families of rule reach it:

| Operator | The expressions it carries |
|----------|----------------------------|
| σ | the predicate |
| π | each projected attribute |
| γ | each grouping key, each aggregate argument, an `ARGMAX`/`ARGMIN` yield |
| τ, `TOP` | each sort key |
| `TREE` | each sibling-ordering key |
| ∀ | the predicate |
| `⨝`, `⟕`, `⟖`, `⟗`, `⋉`, `▷` | the join condition |
| `ASOF` | the condition **and** the tolerance |
| `CLOSURE`, `TRACE` | the source and target endpoint bounds |
| `SOLVE` | both sides of the equation |
| `OPTIMIZE` | the objective and each constraint expression |
| `WINDOW`, `ROLLING` | the function's operands and the `SORT` keys |
| `SESSIONIZE` | the gap threshold |
| a generator relation | the `PRODUCE … WHILE` bound |
| a table-valued function call | each argument |
| `LATERAL` | each argument |

So the redundant `* 1` below is folded once, at plan time, rather than being
re-evaluated for every row the γ reduces:

```relix
Orders := [
| cust | region | amount | qty |
|------|--------|--------|-----|
| A    | west   | 10     | 2   |
| A    | west   | 30     | 1   |
| B    | east   | 5      | 4   |
];
query { γ cust, SUM(amount * qty * 1) → total (Orders) };
```

The γ evaluates `amount * qty` per row instead of `amount * qty * 1`, and the
result is unchanged — `A` → `50`, `B` → `20`.

The list above is not maintained by hand. Both passes drive their rewrite
through the AST's single exhaustive registry of "which expressions does this
node carry", so a new operator cannot be added without declaring its
expressions, and therefore cannot silently opt out of simplification.

**One consequence worth knowing.** An *unaliased* π attribute or aggregate takes
its output column name from the shape of its expression — a compound expression
projects as `_col0` and aggregates as `sum_expr`, while a bare column projects
as its own name and aggregates as `sum_amount`. Folding an expression down to a
bare column therefore also changes that derived name. Write an explicit `→`
alias whenever you refer to the column downstream:

```relix
Orders := [
| cust | amount |
|------|--------|
| A    | 10     |
| B    | 5      |
];
Totals := { γ cust, SUM(amount * 1) → total (Orders) };
query { σ total > 6 (Totals) };
```

### Conditions written in operand position

`IIf(cond, a, b)` takes a **predicate as an argument**. That condition is an
ordinary predicate in an ordinary expression, and both families of rule reach
into it:

    π IIf(qty * 1 > 0, "some", "none") → stock (Inventory)
    π IIf(qty > 0, "some", "none") → stock (Inventory)          -- EXPR-004

    σ IIf(5 > age, "child", "adult") = "child" (People)
    σ IIf(age < 5, "child", "adult") = "child" (People)         -- PRED-003

The rewrite only changes how the condition is *spelled*. `IIf`, `Nz` and
`Coalesce` short-circuit, and simplifying a condition never changes which branch
they evaluate.

## Selection rules (`SEL-nnn`)

The σ rules move a filter as close to the data as it can legally get.

| Code | Rewrite |
|------|---------|
| `SEL-001` | Split a conjunction into stacked selections, so each conjunct moves independently |
| `SEL-002` | Merge adjacent selections back into one conjunction (cleanup) |
| `SEL-003` | Push σ below π |
| `SEL-004` | Push σ below ρ, rewriting column references to their pre-rename names |
| `SEL-005` | Push σ into the join input its predicate belongs to |
| `SEL-006` | Replicate σ into both branches of `⊎` |
| `SEL-007` | Push σ below γ — SQL's `HAVING` → `WHERE` demotion |
| `SEL-008` | Push σ below δ or τ |
| `SEL-009` | Distribute σ over `∪`, `∩`, `∆` (both branches) and `−` (left branch only) |

### `SEL-007` — `HAVING` → `WHERE`

    σ p (γ keys, aggs (R))  ≡  γ keys, aggs (σ p (R))     when attrs(p) ⊆ keys

A condition on a **grouping key** does not need the groups to exist yet: a group
either satisfies it or is not wanted at all, so the filter can run on the rows
going *in*. A condition on an **aggregate output** genuinely needs the group —
that one stays above the γ as a residual σ (a real `HAVING`).

The rule matches a grouping key only when it is a bare, unaliased column, so the
column has the same name below the γ. A derived key (`YEAR(order_date) → yr`) or
an aliased one (`customer_id → cust`) is left alone: its output name names no
column of the input.

### `SEL-008` — below δ and τ

    σ p (δ R)    ≡  δ (σ p R)
    σ p (τ k R)  ≡  τ k (σ p R)

Unconditional. A filter neither adds rows nor changes columns, so deduplicating
or sorting the survivors gives the same answer as filtering the deduplicated or
sorted rows. It is worth doing because **δ and τ both block**: a σ left above one
makes the engine sort or dedupe rows it is about to discard.

### `SEL-009` — over the set operations

    σ p (A ∪ B)  ≡  (σ p A) ∪ (σ p B)        likewise ∩ and ∆
    σ p (A − B)  ≡  (σ p A) − B

For `−` the predicate goes into the **left** branch only. Filtering the
subtrahend would take rows *out* of it, and a row the subtrahend no longer
removes is a row *added* to the result — the one direction of this family that is
not symmetric.

`⊔` (outer union) is excluded: its branches have different schemas, so a
predicate valid against one may name a column the other does not have. `÷` and
`∘` are excluded too — neither is a row filter with respect to σ.

## Projection rules (`PROJ-nnn`)

The π rules control how **wide** the rows are that flow through the query.

| Code | Rewrite |
|------|---------|
| `PROJ-001` | Remove a π that outputs exactly its input's columns (a no-op) |
| `PROJ-002` | Merge two stacked π into one |
| `PROJ-003` | Push π below σ, so the filter runs on narrower rows |
| `PROJ-004` | Prune every base relation to the columns the query actually reads |

### `PROJ-004` — column pruning

    γ region, SUM(amount) → total (Orders)
      ≡  γ region, SUM(amount) → total (π region, amount (Orders))

`PROJ-001..003` are local rewrites of a π that is already written down.
`PROJ-004` is the one that *introduces* one. It walks the tree **top-down**
carrying a "required columns" set: each operator is told which of its output
columns the operator above reads, works out what it therefore needs from each
input, and passes that down. At a base relation the accumulated set is compared
with the table's columns, and anything unread is projected away right at the
leaf.

Each operator contributes what it reads itself:

| Operator | What its input must still produce |
|----------|-----------------------------------|
| σ | what is read above **+** the predicate's columns |
| τ | what is read above **+** the sort keys |
| λ | what is read above — a limit reads no column |
| π | the attribute expressions' own column references |
| γ | the grouping keys **+** the aggregate arguments — nothing from above |
| ρ | what is read above, mapped back through the renames |
| `×` `⋈` `⨝` `⟕ ⟖ ⟗` | what is read above **+** the join condition, split by side |
| `⋉` `▷` | as above on the left; on the right, **only** the condition — a semi- or anti-join emits no right-hand column |

A `⋈` additionally keeps every column the two sides have in common, read or not:
those columns *are* its join keys, so pruning one would silently change what the
join matches on.

### Why this one is worth the most

Width multiplies with everything downstream. A pruned leaf means a narrower
`SELECT` list over JDBC, Mongo or HTTP — bytes off the wire, not just cycles — a
smaller hash-join build side, and less row width through every operator that has
to buffer (`τ`, `γ`, `WINDOW`, `⟗`, `∪`, `÷`).

### What it will not prune

- **δ, and the deduplicating set operations `∪ ∩ − ∆`.** They compare *whole
  rows*, so `π a (δ R)` is not `δ (π a R)`: dropping a column before the dedup
  merges rows that were distinct. `÷`, `∘` and `⊔` are excluded for the same
  family of reasons — their semantics are stated in terms of the operand
  headings. (`⊎` is safe in principle but its branches are matched
  positionally, so it is left alone rather than risked.)
- **The positional form of ρ** (`ρ E (a, b, c)`) — it is arity-bound, so
  narrowing its input would break the rename. The `old → new` pair form prunes
  normally.
- **A schema-on-read source** — an open schema has no column list to prune
  against, so nothing is dropped.

An operator that blocks pruning blocks it only at that edge. A π further down
starts a fresh requirement of its own, so `δ (π a (σ … (R)))` still prunes `R`.

The rule runs **last**, after every other phase has settled: it inserts a π
directly above a base relation, and several rules (`GEN-001` most directly) match
on a shape that a π between the operator and its leaf would hide.

## Limit rules (`LIM-nnn`)

| Code | Rewrite |
|------|---------|
| `LIM-001` | Push λ below π |
| `LIM-002` | Push λ below ρ |
| `LIM-003` | Fuse λ over τ into a keyless `TOP` — the top-N rewrite |
| `LIM-004` | Replicate λ into both branches of `⊎` |

A λ crosses only operators that are **row-count and order neutral**: π narrows
columns and ρ touches names, so neither can filter or reorder. It does *not*
cross a σ — fewer than `n` rows would survive the filter — nor a γ, δ, or set
operation.

### `LIM-003` — top-N

    λ(offset, n) (τ k (R))  ≡  TOP n OFFSET offset ORDER BY k (R)

`λ` over `τ` is the only way to write a global "top N overall", and taken
literally it sorts the **entire** input to then discard all but `n` rows.
[`TOP`](top.md) is the same thing done with a bounded heap, so the pair is fused
into one. The produced `TOP` carries no `PER` key — one global group — which is a
shape with no surface syntax of its own precisely because `λ`/`τ` already is one.

Over a pushdown-capable source the fused form still goes to the backend as
`ORDER BY … LIMIT`, exactly as the pair did.

### `LIM-004` — bounding the branches of a union-all

    λ n (A ⊎ B)  ≡  λ n ((λ n A) ⊎ (λ n B))

Neither branch can contribute more than `n` rows to a limit of `n`, so producing
more is wasted work — and over a database source the branch limit becomes a real
`LIMIT`. The outer λ has to stay: a branch may supply fewer than `n` rows, so the
union still needs capping. With an offset the branch bound is `offset + count`,
because every skipped row might have come from the same branch.

## Join rules (`JOIN-nnn`)

| Code | Rewrite |
|------|---------|
| `JOIN-001` | Turn `σ` over `×` into a theta join |
| `JOIN-002` | Push σ into the join input its predicate belongs to |
| `JOIN-004` | Demote an outer join whose padded rows cannot survive the filter above it |

There is no `JOIN-003`: it swapped a commutative join's inputs to put the cheaper
source on the left, and was removed because that permutes the join's
*ordered* output columns — which a positional `ρ` and the whole-row set
operations read by position, so it returned wrong rows. The planner already
picks the cheaper build side without touching the logical tree. **Join shape is
fixed by the query text**; the optimizer does no reordering.

### `JOIN-004` — outer to inner

```
σ p (A ⟕ B)   ≡   σ p (A ⨝θ B)     when p rejects NULLs on a column of B
```

An outer join emits, besides the matched rows, rows padded with NULLs on the
side that had no match. If the filter above can never be *true* of a row whose
`B` column is NULL, those padded rows all get thrown away — so producing them
was work for nothing.

The demotion is a gate in front of several things an outer join cannot have:
σ pushdown into **both** sides (`SEL-005` pushes into `⟕`'s left only), a
sort-merge plan (only `INNER`/`SEMI`/`ANTI` are merge-eligible), a `JOIN … ON`
pushed to SQL, and `EQ-001`. The planner also picks the cheaper build side of an
inner join by cost, which it does not do for the asymmetric kinds.

A `⟗` loses one half at a time, because each half is killed by a predicate on
the *other* side's columns — the unmatched-left rows are the ones carrying NULL
right columns:

| Join | filter rejects on left | filter rejects on right |
|------|------------------------|-------------------------|
| `⟕`  | —                      | `⨝`                     |
| `⟖`  | `⨝`                    | —                       |
| `⟗`  | `⟕`                    | `⟖`                     |

A `⟗` whose filter rejects on both sides goes straight to `⨝`.

**What rejects a NULL.** A comparison, a `LIKE`, or an `∈` whose operand *is* the
column; `c IS NOT NULL`; a `∧` where either side rejects; a `∨` where **both**
sides do.

**What does not**, and the first is the one that matters:

- **`c IS NULL`.** `σ B.x IS NULL (A ⟕ B)` *is* the anti-join idiom — the query
  is asking for exactly the padded rows. Demoting it would return the opposite
  answer.
- Anything **computed** from the column. `Nz`, `Coalesce` and `IIf` exist
  precisely to turn a NULL into something truthy, so a function call over `c` is
  never assumed to reject.
- `¬(c = 5)`, deliberately. Only `¬(c IS NULL)` is treated as rejecting; the
  general negated case needs a second, dual analysis rather than an inversion of
  this one. Under-firing costs an optimization, getting it wrong costs the
  answer.

A column that either input could own is never attributed to the padded side.

## Lateral decorrelation (`LATERAL-nnn`)

| Code | Rewrite |
|------|---------|
| `LATERAL-001` | Turn an uncorrelated `LATERAL` TVF join into a `×` over a single TVF call |

```
L LATERAL f(c…)   ≡   L × f(c…)     when no argument reads a column of L
```

A `LATERAL` join evaluates its arguments against each left row, instantiates the
function body with those values, and concatenates the left row with every row the
body returns. When the arguments contain no column reference, they evaluate to the
same values for every left row — so every instantiation is the *same* relation, and
pairing that one relation with each left row is a Cartesian product.

**The per-row work it removes.** The lateral executor calls back into **the
planner** once per left row: N argument round trips (runtime value → AST literal)
and N freshly planned bodies for N rows. Decorrelated, that is one plan and one
execution.

**The visibility it buys, which is worth more.** `LATERAL` reports only its left
input as a child, so the function body sits outside structural traversal and no
other rule can see into or through the operator. As a `×` over a plain TVF call it
is ordinary again: `JOIN-001` can fold a selection above it into a theta join,
`JOIN-002`/`SEL-005` push filters into the left input, and the planner costs it
like any other product.

Two properties make the swap safe, and both are about *ordering* rather than row
content:

- the output heading is `left ++ body` either way (the same `concat`), so a
  positional consumer downstream sees no change — the trap the removed `JOIN-003`
  fell into; and
- `×` iterates the left input in the outer loop and the right in the inner one,
  which is exactly the order the lateral emits its per-row groups in.

**When it does not fire.** Every condition asks the same question — *would running
the body once give what running it once per row gives?*

- **Any `AttributeOperand` anywhere in any argument.** That is the definition of a
  correlated lateral, and the test is deliberately blunt, since a false
  "uncorrelated" returns wrong answers.
- **A non-deterministic argument.** `LATERAL f(Rand())` draws once per left row;
  folding it to a single call would draw once in total. A function counts as
  deterministic only when the builtin registry tags it so, which makes a
  user-defined scalar function non-deterministic — the same conservative reading
  `DIST-002` uses.
- **A non-deterministic function body**, which is the same objection one level down.
  `LATERAL sampleOf(0.5)` over a body reading `Rand()` or `NOW()`, drawing an
  unseeded `SAMPLE`, or calling a view or nested TVF that does, produces a different
  relation per left row even with constant arguments. Answered by walking the body —
  through the views and nested TVFs it references — which is why this rule needs the
  symbol table and runs in the preamble rather than as a pipeline rule. A function
  that cannot be resolved is treated as non-deterministic.

What is deliberately *not* treated as volatile is a **base relation**. Re-reading a
source table could in principle return different rows, but the engine already
assumes a source is stable for the duration of a query: `×` materialises its right
input once and replays it for every left row, and every hash join, `÷` and set
operation does the same. Reusing one evaluation of a scan is a liberty the executor
takes already.

## Equality propagation (`EQ-nnn`)

| Code | Rewrite |
|------|---------|
| `EQ-001` | Carry a literal binding across an equi-join, so both sides are filtered |

```
A.x = B.x  ∧  A.x = 5    ⊨    B.x = 5
```

Without this, `σ A.x = 5 (A ⨝ A.x = B.x B)` filters `A` and scans `B` whole —
every `B` row crosses the wire for the join to throw away. Deriving `B.x = 5`
and putting it on `B`'s side means **both backends filter independently**.

```relix
Customers := [
| cid | name |
|-----|------|
| 1   | ada  |
| 2   | bea  |
];
Sales := [
| cid | amount |
|-----|--------|
| 1   | 10     |
| 2   | 40     |
];
query { σ Customers.cid = 1
        (Customers ⨝ Customers.cid = Sales.cid Sales) };
```

The optimizer derives `σ Sales.cid = 1` and places it directly on `Sales`. Over
two database sources that is two `WHERE cid = 1` clauses instead of one.

**Inner joins only.** An outer join's condition does not hold of the rows it
pads with NULLs, so an equality read out of `⟕`/`⟖`/`⟗` and pushed into the
null-supplying side would delete rows the join is required to keep. `JOIN-004`
demotes an outer join to inner where it can, and runs first — so the demoted
join becomes eligible here in the same phase.

Only *conjuncts* count as facts: an equality inside a `∨` holds in one branch
only and is never read. NULLs need no special case, because equality is
NULL-rejecting — a row satisfying `A.x = B.x ∧ A.x = 5` has neither column NULL,
so the derived predicate discards only rows the join would have discarded.

**The column-to-column half is not implemented.** `A.x = B.x ∧ B.x = C.x ⊨
A.x = C.x` is sound, but nothing consumes it today: the optimizer does no join
reordering at all, so an implied cross-join equality widens no search anything
performs and would only add a predicate to evaluate.

## Partition pruning

```
σ k = c (OP … PER k (R))   ≡   OP … PER k (σ k = c (R))
```

Five operators compute their result **independently per partition**, so an
equality fixing the partition key selects exactly one partition — and pushing it
below the operator computes only that one instead of all of them.

| Code | Operator | What is skipped |
|------|----------|-----------------|
| `WINDOW-001` | `WINDOW … PARTITION BY k` | other partitions' frames |
| `TOPK-001` | `TOP n … PER k` | other groups' rankings |
| `OPTIMIZE-001` | `OPTIMIZE … PER k` | other groups' MIP/LP solves |
| `SESSION-001` | `SESSIONIZE … PER k` | other partitions' sort + gap walk |
| `DOWNSAMPLE-001` | `DOWNSAMPLE … PER k` | other groups' bucketing |

All five block — they buffer every partition to produce the one you asked for —
so the saving is real work, not rows discarded slightly later.

```relix
Readings := [
| user_id | seq | v  |
|---------|-----|----|
| u1      | 1   | 10 |
| u1      | 40  | 30 |
| u2      | 2   | 40 |
];
query { σ user_id = "u1"
        (SESSIONIZE seq GAP 5 PER user_id AS run (Readings)) };
```

becomes `SESSIONIZE seq GAP 5 PER user_id AS run (σ user_id = "u1" (Readings))`
— `u2`'s rows are never buffered or sorted. The pushed equality is *removed*
from above: after the operator has run over only that partition, every output
row satisfies it already.

**What is pushable**: a top-level conjoined `=` against a constant, on a bare
partition column. Inequalities, predicates on a computed output column (a window
rank, a session id), equalities on non-partition columns, and disjunctions stay
as a residual σ above the operator. An operator with no `PER` keys has no
partition dimension and is left alone.

**`DOWNSAMPLE … FOR n ROWS` does not prune.** `FOR n ROWS` keeps the `n` most
recent buckets **across all groups**, so filtering after the operator can leave
fewer rows than filtering before it. This is a counterexample, not a
conservative choice.

A predicate on `DOWNSAMPLE`'s *timestamp* column is a **range** restriction on
the buckets rather than a partition selection — a different rewrite, and not one
of these.

## Endpoint bounds on a graph traversal

```
σ from = c (OP from, to … (E))   ≡   OP from, to … ⟨from=c⟩ (E)
```

Three graph operators compute an **all-pairs** answer over an edge relation, and a
selection fixing an endpoint to a constant throws almost all of it away. Because
the endpoint is a partitioning dimension — the all-pairs answer filtered to
`from = X` is what seeding the traversal at `X` alone produces — the equality
folds into the operator and the traversal starts where it was going to end up.

| Code | Operator | What is skipped |
|------|----------|-----------------|
| `CLOSURE-001` | `CLOSURE` / `RCLOSURE` | reachability from every other node |
| `TRACE-001` | `TRACE` | every other node's optimal-path table |
| `PATH-001` | `PATH` | every other node's breadth-first traversal |

A bound on the **target** is the same traversal over the reversed adjacency, and
both bounds together is a single-pair search.

```relix
Transfers := [
| src | dst |
|-----|-----|
| a1  | a2  |
| a2  | a3  |
| b1  | b2  |
];
query { σ src = "a1" (PATH src, dst HOPS 1 TO 3 AS depth (Transfers)) };
```

becomes `PATH src, dst HOPS 1 TO 3 AS depth ⟨src="a1"⟩ (Transfers)` — `b1`'s
traversal never runs. That spelling is the one the operator pages recommend, since
a start node is a selection rather than syntax, so the unbounded form is what
following the manual gives you and this rule is what makes it cheap.

**The distance column is unaffected.** `PATH`'s `depth` is the *shortest* path
length, which is why `(from, to)` is a candidate key of its result; the shortest
path from one node does not depend on which other nodes were searched from, so the
bounded result is a slice of the unbounded one rather than a recomputation of it.

**What is pushable**: a top-level conjoined `=` against a literal, on the operator's
own `from` or `to` column, where that endpoint is not already bound. A predicate on
a computed output column (`depth`, `TRACE`'s weight or path), an inequality, a
second equality on an already-bound endpoint, a cross-column correlation like
`from = to`, and any disjunction all stay as a residual σ above the bounded
operator — so an unrecognised conjunct is never dropped.

## Distinctness rules (`DIST-nnn`)

`δ` buffers a whole hash set, so a `δ` that changes nothing is one of the more
expensive no-ops a plan can contain. Two rules remove one — they look in
opposite directions.

| Code | Rewrite |
|------|---------|
| `DIST-001` | Drop a `δ` whose **input** is already duplicate-free |
| `DIST-002` | Drop a `δ` whose **consumer** is a γ that ignores multiplicity |

`DIST-001` asks what is below: the output of a `γ`, of a set operation
(`∪`/`∩`/`−`/`∆`), of `÷`, of a closure, of `∀`, or of another `δ` is already a
set, and that property survives the row-subset operators (`σ`, `τ`, `λ`, the left
of `⋉`/`▷`) and renames. A base relation counts too when its declared statistics
carry a key.

`DIST-002` asks what is above:

    γ keys, aggs (δ R)  ≡  γ keys, aggs (R)     when no aggregate reads multiplicity

An aggregation groups its input, so deduplicating first is work its consumer would
have done anyway — **provided** the aggregates give the same answer over a bag as
over the set beneath it. `MIN` and `MAX` do; a γ with no aggregates at all (pure
grouping) does. `SUM`, `COUNT`, `AVG` and `COLLECT` do not, and `ARGMAX`/`ARGMIN`
are excluded as well. Every aggregate argument and grouping key must additionally
be **deterministic** — the rule changes how many rows the expression is evaluated
over, so `MIN(Rand())` is not invariant under it.

The rule looks *through* a `σ`, `π`, `ρ` or `τ` sitting between the γ and the δ:
each emits at most one row per input row, so re-admitting duplicates below it
cannot change which rows arrive. A `λ`, a sample, or a `TOP` is **not**
transparent that way — how many rows they keep depends on how many they are
given, which is exactly what the `δ` changes.

## Contradiction and the empty relation (`PRED-004..006`, `EMPTY-nnn`)

A filter can be unsatisfiable — `amount > 50 ∧ amount < 20` admits no row of any
table. Without this family the engine scans, joins and aggregates its way to an
empty answer anyway; with it, the query stops being work.

### Reading a conjunction as bounds

The comparisons a conjunction places on one column fold into an interval. `>` and
`>=` move the lower endpoint, `<` and `<=` the upper, and `=` sets both. Three
things fall out of that one interval:

| Code | Rewrite |
|------|---------|
| `PRED-004` | An **empty** interval means the conjunction is unsatisfiable → `false` |
| `PRED-005` | A bound that is not the tightest in its direction is **implied**, and dropped |
| `PRED-006` | A conjunct that repeats an earlier one is dropped |

Bounds are compared **numerically**, so `5` and `5.0` are one value, and within one
literal kind only — a `NUMBER` bound and a `DATE` bound on the same column are left
alone rather than compared. Temporal bounds (`DATE`, `TIME`, `TIMESTAMP`,
`DURATION`) participate on the same terms as numbers and strings.

A column's **qualifier is part of its identity**: `A.x > 5 ∧ B.x < 3` names two
different columns and is perfectly satisfiable. `≠` is treated as unconstraining —
it excises a point from the interior rather than moving an endpoint — so
`x ≠ 5 ∧ x = 5` is a contradiction the analysis does not report.

### ⚠ `PRED-004` does not fire under `¬`

`x > 5 ∧ x < 3` is FALSE for every non-NULL `x` and **UNKNOWN** when `x` is NULL.
A filter drops the row either way, so treating it as `false` is sound there. Under
a negation the two part company: `¬FALSE` *keeps* a row where `¬UNKNOWN` drops it,
so folding the inside of `¬(x > 5 ∧ x < 3)` would silently add back exactly the
rows where `x` is NULL. `PRED-004` is therefore suppressed in a negated position.
`PRED-005` and `PRED-006` carry no such restriction — dropping an implied or
repeated conjunct preserves the three-valued answer exactly.

### Propagating the empty relation

| Code | Rewrite |
|------|---------|
| `EMPTY-001` | `σ false (R)` → `∅` — the sub-tree is replaced, not just filtered |
| `EMPTY-002` | An operator whose output must be empty because an input is → `∅` |
| `EMPTY-003` | `X ⊎ ∅` → `X` |

`∅` is an internal node with **no rows and the heading of whatever it replaced**,
so a query above it still type-checks and still names the right columns. It is not
the `EMPTY`/`DUM` literal, which is the *zero-column* relation
(see [truth relations](../literals/truth-relations.md)); `∅` has to keep a heading,
`EMPTY` has none to keep.

An operator collapses only when an empty input makes its output empty *for
certain*:

- **σ, π, ρ, τ, δ, λ, μ** — each emits at most one row per input row.
- **γ and ∀ only when they group.** A *scalar* aggregate over nothing is one row,
  not none: `γ COUNT(*)` of an empty relation is `0`, and a no-key `∀` is vacuously
  true. Collapsing either would delete a row the query must return.
- **Joins** — `×`, `⋈`, `⨝θ`, `∘` and the interval join: either side. `⋉`: either
  side, since an empty right matches nothing. `▷` and `⟕`: left only. `⟖`: right
  only. `⟗`: both. AS-OF and lateral: left only, each being driven by its left input.
- **Set operations** — `∩`: either side. `−` and `÷`: left only. `∪`, `⊎`, `⊔`,
  `∆`: both.

Three identities that look safe are **not**, and are left out:

- `∅ ⊎ X → X` would rename the result's columns. `⊎` takes its output schema from
  its **left** branch, so dropping an empty left branch hands the naming to the
  right one. The mirror `X ⊎ ∅ → X` keeps the left schema *and*, `⊎` being a bag
  operation, the left multiplicities — so only that direction ships, as `EMPTY-003`.
- `X − ∅ → X` and `∅ ∪ X → X` would break a *declared* property. `−` and `∪`
  produce sets, and `DIST-001` removes a `δ` above them on exactly that basis;
  replacing one with a possibly-duplicated `X` would make the claim false and the
  `δ` would not come back.
- `X ÷ ∅` is **not empty**. Division by an empty divisor is vacuously satisfied by
  every row of the quotient's projection, so only the left-empty direction collapses.

### What it looks like

    -- as written
    σ amount > 50 ∧ amount < 20 (Orders) ⋈ Customers

    -- PRED-004: the conjunction admits no value
    σ false (Orders) ⋈ Customers

    -- EMPTY-001: the filter's whole sub-tree goes
    ∅ ⋈ Customers

    -- EMPTY-002: a join with an empty side is empty
    ∅

Neither table is read, and `:explain` shows a single `Empty` node.

## Rename rules (`RENAME-nnn`)

| Code | Rewrite |
|------|---------|
| `RENAME-001` | Collapse a relation-only ρ into the ρ directly beneath it |
| `RENAME-002` | Drop a relation-only ρ whose alias nothing in the query names |

Inlining a view `V` produces `ρ V (body)`. The wrapper is needed at that moment —
it keeps `V` a resolvable alias, so a qualified `V.col` still finds the right side
of a join — but once nothing names `V` it is dead weight that sits between
operators later rules need to see adjacent.

```relix
Orders := [
| cust | region | amount |
|------|--------|--------|
| A    | west   | 10     |
| B    | east   | 5      |
];
Big := { σ amount > 5 (Orders) };
query { σ cust = "A" (Big) };
```

Inlining gives `σ cust = "A" (ρ Big (σ amount > 5 (Orders)))`. Dropping the ρ
leaves the two selections adjacent, so `SEL-002` merges them into one filter that
`SEL-005`/pushdown can then carry to the source as a single `WHERE`.

**When it does not fire.** A relation-only ρ renames no column, so removing it can
only change how a *relation-qualified* reference resolves — and a qualified
reference resolves by column provenance, falling back to a bare-name match when
the qualifier names nothing. Two conditions therefore have to hold, and the second
is the one that is easy to miss:

- nothing references the alias itself (`Big.cust` keeps `ρ Big`); **and**
- nothing references a relation name the input would *re-expose*. In
  `(ρ V (Orders)) ⨝ Orders`, a reference to `Orders.id` names exactly one column
  today because the left side is stamped `V`. Drop the ρ and both sides are
  `Orders`: the reference turns ambiguous and silently falls back to a first
  match.

`RENAME-001` needs neither condition. The outer ρ re-anchors every column to its
own name, so the inner alias is already unresolvable one level up — collapsing
loses a name that was invisible anyway.

## How the rules are ordered: phases

The rules are not one flat list. They are grouped into six **phases**, which run
once each, in this order:

| Phase | Rules | What it is for |
|-------|-------|----------------|
| `simplify` | `EXPR-001..008`, `PRED-001..006` | Fold constants and normalise comparisons, so every later rule matches against settled expressions |
| `pushdown` | `SEL-001`, `JOIN-004`, `EQ-001`, `SEL-003..009`, `NEST-001..003`, `WINDOW-001`, `TOPK-001`, `OPTIMIZE-001`, `SESSION-001`, `DOWNSAMPLE-001`, `JOIN-001..002` | Move filters toward the data and shape the joins |
| `cleanup` | `SEL-002`, `PROJ-001..003`, `AGG-001`, `DIST-001..002`, `PROD-001`, `EMPTY-001..003`, `SORT-001` | Put conjunctions back together and drop what a rewrite made redundant |
| `sip` | `CLOSURE-001`, `TRACE-001`, `PATH-001`, `FIX-001`, `GEN-001` | Fold a constraint into an expensive operator, once the σ above it has settled |
| `limit` | `LIM-001..004` | Move limits down, and fuse `λ ∘ τ` into `TOP` |
| `prune` | `PROJ-004` | Narrow every leaf to the columns the query reads |

Within a phase the rules are **swept repeatedly** until a sweep changes nothing,
so one rule's rewrite can expose the pattern another matches on. Across phases
there is no loop — and that separation is load-bearing, not tidiness:

- `SEL-003` pushes σ below π and `PROJ-003` pushes π below σ;
- `SEL-001` splits a conjunction and `SEL-002` merges it back.

Each pair is deliberately split across two phases. Iterating either pair together
would never terminate — it would just trade the two operators' positions forever.
Sweeps are additionally capped, so even a rule pair that misbehaves costs bounded
work rather than hanging the optimizer.

Phase order is also why `:opt` lists rules in the order it does: a `SEL-001` split
always precedes the `SEL-002` merge that undoes it, and `PROJ-004` is always last.

Four rules run *before* the phases, in a per-query preamble, and all four for the
same reason — each needs the symbol table, which the phases deliberately do without:
`INLINE-001` expands the views, `RENAME-001`/`RENAME-002` clear up after it, and
`LATERAL-001` resolves a TVF and classifies its body. They sit outside the loop
because they run before schemas are re-inferred for the expanded tree.

The `WHY` barrier covers them too. A view referenced under a `WHY` is left as a
reference rather than inlined, so `:opt` reports no `INLINE-001` for it: `WHY`
reifies lineage against the base tables and expands the view itself, which is how
the result names the source rows instead of the view's own output.

## Shared sub-expressions

Everything above rewrites the *logical* tree. One decision that changes how much
work a query does is made later, when the physical plan is built: whether a
sub-expression that is read in more than one place is **evaluated** more than once.

A plan is a tree, so by default it is. Two things put a sub-expression in more than
one place — the query itself, and the engine's own desugaring — and both are handled
the same way: plan it once, mark it as shared, and let every reader read that one plan.

Symmetric difference is the clearest case of the second kind, because the
duplication is not something you wrote: `A ∆ B` is computed as `(A − B) ∪ (B − A)`,
which reads both `A` and `B` twice. `:explain` shows the mark:

```relix
Events := [| order_id | customer | event    |
           | 1        | acme     | shipped  |
           | 2        | globex   | shipped  |
           | 2        | globex   | invoiced |
           | 3        | initech  | invoiced |];

Shipped  := { π order_id, customer (σ event = "shipped" (Events)) };
Invoiced := { π order_id, customer (σ event = "invoiced" (Events)) };

query { Shipped ∆ Invoiced };
```

```
SetOp UNION  ~4 rows
├─ SetOp DIFFERENCE  ~? rows
│  ├─ Spool #1  ~2 rows
│  │  └─ Project  ~2 rows
│  │     └─ Select  ~2 rows
│  │        └─ Scan Events  ~4 rows
│  └─ Spool #2  ~2 rows
│     └─ Project  ~2 rows
│        └─ Select  ~2 rows
│           └─ Scan Events  ~4 rows
└─ SetOp DIFFERENCE  ~? rows
   ├─ Spool #2 (shared)  ~2 rows
   └─ Spool #1 (shared)  ~2 rows
```

Each input appears under a `Spool` with an id, and the second branch reads the
**same** two spools rather than sub-plans of its own — `(shared)` marks the reader
that does not own the sub-tree, which is why the sub-tree is drawn only once.
Evaluating each input twice becomes evaluating it once.

The inputs are **views** here for a reason: evaluating one means evaluating its
body, which is work worth doing once. Two inline tables in their place would show
no spool at all — their rows are in memory already, so reading them a second time
repeats nothing. That rule is stated in full below.

### Repeats you wrote

The planner also looks for sub-expressions the *query* reads more than once, and
recognises two of them by their **structure** rather than by name or position — so
the same expression written out twice in two different places is one sub-expression,
and a view referenced twice is planned once rather than expanded twice:

```relix
Orders := [| order_id | customer | amount | status  |
           | 1        | acme     | 100    | shipped |
           | 2        | globex   | 250    | pending |
           | 3        | acme     |  75    | shipped |];

Large := { σ amount > 80 (Orders) };

query { (γ customer, SUM(amount) → total (Large)) ⋈ (π customer, status (Large)) };
```

```
Join NATURAL/HASH build=RIGHT  ~1 rows
├─ Aggregate  ~1 rows
│  └─ Spool #1  ~1 rows
│     └─ Select  ~1 rows
│        └─ Scan Orders  ~3 rows
└─ Project  ~1 rows
   └─ Spool #1 (shared)  ~1 rows
```

`Large` is written twice and planned once: the selection appears under a single
spool, and the projection on the other side of the join reads it.

What counts is **how many places evaluate it**, not how many times it appears. In
`(γ … (σ … R)) ∪ (γ … (σ … R))` the σ is written twice, but sharing the γ above it
already reduces the σ to a single evaluation — holding its rows as well would keep a
second copy for one reader. So only the γ is shared. Write that same σ a third time
*outside* both γs and it has a reader of its own: then both are shared, and the γ's
spool reads the σ's.

A sub-expression whose second evaluation would repeat no work is left alone: the
`UNIT` and `EMPTY` literals, and a reference to an inline or `relix.*` relation,
whose rows are in memory already. A **view** reference is not one of these —
evaluating it means evaluating its body — and neither is a file or database
relation, where evaluating it again is another read of the source.

### Repeats the iteration makes

A `FIX` step runs once per iteration — that is what the operator does — so a
sub-expression inside a step is evaluated as many times as the recursion runs,
however many times it is *written*. Where it does not name the recursion, every one
of those evaluations produces the same rows, so it is shared exactly like a repeat
you wrote out twice:

```relix
Edges := [| src | dst |
          | 1   | 2   |
          | 2   | 3   |
          | 3   | 4   |];
Seed := [| src | dst |
         | 1   | 2   |];

query { FIX Reach (Seed, π src, dst2 → dst (Reach ⋈ (π src → dst, dst → dst2 (Edges)))) };
```

```
FIX Reach  ~? rows
├─ Scan Seed  ~1 rows
└─ Project  ~? rows
   └─ Join NATURAL/HASH build=RIGHT  ~? rows
      ├─ REF Reach  ~? rows
      └─ Spool #1  ~3 rows
         └─ Project  ~3 rows
            └─ Scan Edges  ~3 rows
```

The edge side is written once and spooled anyway: the join reads it on every round,
and reads the same rows every time, so one evaluation serves the whole recursion —
a table scanned once instead of once per round. The seed is not shared: it runs
before the first round and never again. Neither is the join, nor the projection
above it; both read `Reach`, whose rows are the ones that change.

That the join is re-run every round is why sharing the rows is not the whole of it.
A join over an unchanging side still has to **build its lookup table** from those
rows, and it was doing so once per iteration — the rows were free by then, the work
over them was not. A build side the planner has spooled is prepared once and reused,
so what a round costs is the part of it that actually differs: the newly-derived
rows, probed against a table built before the first round.

### What it costs

Sharing means holding rows while another reader still needs them, so the held rows
grow only as far as the readers actually read. Two readers that stop after five rows
between them cost five rows, not the whole relation; a reader that drains the
sub-expression — a grouping, a set operation, anything that must see every row —
fills it completely, as it always did.

That matters beyond memory: it is why sharing a sub-expression never costs more work
than repeating it. The rows one reader pulls are the rows the next one reads, so the
total is what the hungriest reader wanted, never the sum.

Holding is bounded, by a fixed number of rows across one query. A sub-expression
whose readers want more than that stops being held: the reader that outruns it
carries on by itself, and any other re-computes the part it has not already seen —
which costs exactly what not sharing would have cost, and returns the same rows,
because only a reproducible sub-expression is shared in the first place. The budget
is first-come-first-served, so one very large shared sub-expression can use it up and
leave a later one in the same query unshared.

Some sub-expressions are not shared at all, because their two evaluations are
entitled to disagree — and where one of these sits above a sub-expression that
*can* be shared, the one underneath still is:

- **It reads system state.** `Rand()` and an unseeded `SAMPLE` draw afresh each
  time, and `X ∆ X` over one of them is a query *about* that difference. Replaying
  a single draw would answer something else.
- **It reads a recursive relation from the `FIX` around it.** A recursive
  reference means the current iteration's rows, so a buffer filled on the first
  iteration would still be answering for the first iteration on the tenth. The rest
  of the step is unaffected: a sub-expression that does not name the recursion is
  the same in every iteration, so it is computed once for all of them.
- **It is a table-valued function body under a `LATERAL` join.** That body is
  planned per left row, and its rows belong to that row.

Sharing never changes an answer, and never changes the order of one. A spooled
sub-expression emits the same rows, in the same order, under the same schema as the
sub-expression it wraps, so nothing above it — including the operators that read
their inputs by position — can tell the difference.

# Examples:

## Filtering one region out of a per-region rollup

```relix
Sales := [
| rep    | region | amount |
|--------|--------|--------|
| ana    | west   | 300    |
| ben    | west   | 120    |
| cleo   | east   | 90     |
| dara   | east   | 400    |
| eli    | north  | 50     |
];

query { σ region = "west" (γ region, SUM(amount) → total (Sales)) };
```

Written that way the engine would group **all five** rows into three groups, then
throw two groups away. `SEL-007` rewrites it to

    γ region, SUM(amount) → total (σ region = "west" (Sales))

so only the two `west` rows are ever grouped. The answer is identical:

| region | total |
|--------|-------|
| west   | 420   |

Over a JDBC source the difference is visible in the SQL that reaches the
database — `WHERE region = 'west' GROUP BY region` instead of `GROUP BY region`
followed by an in-engine filter.

## A DISTINCT the aggregation above it makes pointless

```relix
Readings := [
| sensor | site  | celsius |
|--------|-------|---------|
| s1     | north | 12      |
| s1     | north | 12      |
| s2     | north | 9       |
| s3     | south | 21      |
| s3     | south | 21      |
];

query { γ site, MIN(celsius) → coldest (δ (Readings)) };
```

The `δ` was written to "clean up" the duplicate readings, and it costs a full
hash set over the input. `DIST-002` removes it: the γ below groups the rows
anyway, and `MIN` over `{12, 12, 9}` is the same as `MIN` over `{12, 9}`.

    γ site, MIN(celsius) → coldest (Readings)

| site  | coldest |
|-------|---------|
| north | 9       |
| south | 21      |

Change `MIN` to `COUNT` and the rule stops firing, because now the duplicates are
the answer: `COUNT` over the deduplicated rows says `north` has 2 readings, over
the raw rows 3. The rewrite is not "the δ looks unnecessary" — it is "this
consumer cannot tell the difference".

## A mixed condition: half demoted, half kept

```relix
Sales := [
| rep    | region | amount |
|--------|--------|--------|
| ana    | west   | 300    |
| ben    | west   | 120    |
| cleo   | east   | 90     |
| dara   | east   | 400    |
];

query { σ region ≠ "north" ∧ total > 200 (γ region, SUM(amount) → total (Sales)) };
```

`region ≠ "north"` is a key condition and moves below the γ; `total > 200` reads
an aggregate and cannot. The optimized shape is

    σ total > 200 (γ region, SUM(amount) → total (σ region ≠ "north" (Sales)))

which is exactly the `WHERE … GROUP BY … HAVING` split SQL would write by hand.

| region | total |
|--------|-------|
| west   | 420   |
| east   | 490   |

## Filtering one branch of a difference

```relix
Active := [
| id | status |
|----|--------|
| 1  | live   |
| 2  | live   |
| 3  | draft  |
];

Retired := [
| id | status |
|----|--------|
| 2  | live   |
];

query { σ status = "live" (Active − Retired) };
```

`SEL-009` pushes the filter into the **left** branch only:

    (σ status = "live" (Active)) − Retired

Rows 1 and 2 survive the filter, and `Retired` removes row 2 — leaving row 1.
Pushing the same filter into `Retired` as well would have been wrong in a way
that is easy to miss: `Retired`'s only row *does* satisfy `status = "live"` here,
but if it did not, the filter would have emptied the subtrahend and row 2 would
have come back.

| id | status |
|----|--------|
| 1  | live   |

## Top three by amount, without sorting everything

```relix
Sales := [
| rep    | region | amount |
|--------|--------|--------|
| ana    | west   | 300    |
| ben    | west   | 120    |
| cleo   | east   | 90     |
| dara   | east   | 400    |
| eli    | north  | 50     |
];

query { λ 3 (τ amount DESC (Sales)) };
```

`LIM-003` fuses the pair into a keyless `TOP`, so the executor keeps a
three-element heap instead of sorting all five rows (and all five million, on a
real table):

    TOP 3 amount DESC (Sales)

| rep  | region | amount |
|------|--------|--------|
| dara | east   | 400    |
| ana  | west   | 300    |
| ben  | west   | 120    |

`:opt` shows the firing:

    LIM-003  limit over sort fused into TOP 3 (bounded heap instead of a full sort)

## Reading two columns of a six-column table

```relix
Orders := [
| order_id | cust | region | amount | placed_by | note        |
|----------|------|--------|--------|-----------|-------------|
| 1        | ana  | west   | 300    | kim       | rush        |
| 2        | ben  | west   | 120    | kim       | gift wrap   |
| 3        | cleo | east   | 90     | raj       | fragile     |
| 4        | dara | east   | 400    | raj       | signature   |
| 5        | eli  | north  | 50     | kim       | none        |
];

query { γ region, SUM(amount) → total (σ amount > 60 (Orders)) };
```

The query names exactly two columns — `region` groups, `amount` is filtered and
summed. `order_id`, `cust`, `placed_by` and `note` are carried through the
selection and into the blocking γ purely to be discarded. `PROJ-004` rewrites it
to

    γ region, SUM(amount) → total (σ amount > 60 (π region, amount (Orders)))

| region | total |
|--------|-------|
| west   | 420   |
| east   | 490   |

`:opt` names the relation and the width it shed:

    PROJ-004  columns pruned at Orders: 2 of 6 read

Over a JDBC source that is the difference between `SELECT order_id, cust,
region, amount, placed_by, note FROM orders` and `SELECT region, amount FROM
orders` — four columns × every row that never leaves the database.

## Both sides of a join, narrowed

```relix
Orders := [
| order_id | cust | amount | note      |
|----------|------|--------|-----------|
| 1        | ana  | 300    | rush      |
| 2        | ben  | 120    | gift wrap |
| 3        | cleo | 90     | fragile   |
];

Customers := [
| cust | city   | tier | signup     |
|------|--------|------|------------|
| ana  | leeds  | 1    | 2024-01-04 |
| ben  | york   | 2    | 2024-03-11 |
| cleo | hull   | 1    | 2025-02-20 |
];

query { π amount, city (Orders ⋈ Customers) };
```

The result reads two columns, one from each side, so the requirement splits by
side and each leaf is pruned independently:

    π amount, city ((π cust, amount (Orders)) ⋈ (π cust, city (Customers)))

Note what survived on **both** sides: `cust`. Nothing above the join reads it —
but it is the natural join's key, and dropping it would have turned a join on
`cust` into a Cartesian product. That is why `⋈` keeps every common column
whether or not anything reads it.

| amount | city  |
|--------|-------|
| 300    | leeds |
| 120    | york  |
| 90     | hull  |

The hash join now builds its index over two-column rows instead of four-column
ones, and over two JDBC tables on one connection the whole thing is still a
single `SELECT … JOIN … ON` — just a narrower one.

# See Also:
- [Aggregation (γ)](../operators/group.md)
- [Selection (σ)](../operators/select.md)
- [Limit (λ)](../operators/limit.md) · [Top-K per group](top.md)
- Interactive REPL — `:opt` shows the report for a single query
