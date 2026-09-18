/*
 * Copyright 2026 Darkcollective, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.darkcollective.relix.optimizer;

/**
 * Enumeration of every transformation rule the query optimizer can apply.
 *
 * <p>Each constant carries a stable {@link #code()} string (e.g.
 * {@code "EXPR-001"}) used in reports and audit logs, together with a short
 * {@link #description()} of what the transformation does.
 *
 * <p>Codes are grouped by category prefix — one per rule family, in the order the
 * constants are declared below:
 * <ul>
 *   <li>{@code EXPR-nnn}     — arithmetic / expression level</li>
 *   <li>{@code PRED-nnn}     — predicate simplification</li>
 *   <li>{@code SEL-nnn}      — selection (σ) rules</li>
 *   <li>{@code PROJ-nnn}     — projection (π) rules</li>
 *   <li>{@code JOIN-nnn}     — join / cross-product rules</li>
 *   <li>{@code LATERAL-nnn}  — lateral (correlated TVF) join decorrelation</li>
 *   <li>{@code EQ-nnn}       — equality propagation across join conditions</li>
 *   <li>{@code LIM-nnn}      — limit (λ) rules</li>
 *   <li>{@code AGG-nnn}      — aggregation (γ) rules</li>
 *   <li>{@code DIST-nnn}     — redundant-{@code δ} elimination</li>
 *   <li>{@code SORT-nnn}     — redundant-{@code τ} elimination</li>
 *   <li>{@code PROD-nnn}     — Cartesian-product identity elimination</li>
 *   <li>{@code EMPTY-nnn}    — empty-relation introduction and propagation</li>
 *   <li>{@code NEST-nnn}     — nest/unnest (NF²) round-trip rules</li>
 *   <li>{@code CLOSURE-nnn}  — endpoint bounds folded into {@code CLOSURE}</li>
 *   <li>{@code TRACE-nnn}    — endpoint bounds folded into {@code TRACE}</li>
 *   <li>{@code PATH-nnn}     — endpoint bounds folded into {@code PATH}</li>
 *   <li>{@code FIX-nnn}      — magic sets into a {@code FIX} recursion</li>
 *   <li>{@code GEN-nnn}      — production bounds folded into a generator</li>
 *   <li>{@code WINDOW-nnn}   — partition pruning into {@code WINDOW}</li>
 *   <li>{@code TOPK-nnn}     — partition pruning into {@code TOP}</li>
 *   <li>{@code OPTIMIZE-nnn} — group pruning into the {@code OPTIMIZE} solver</li>
 *   <li>{@code SESSION-nnn}  — partition pruning into {@code SESSIONIZE}</li>
 *   <li>{@code DOWNSAMPLE-nnn} — group pruning into {@code DOWNSAMPLE}</li>
 *   <li>{@code INLINE-nnn}   — view-body inlining</li>
 *   <li>{@code RENAME-nnn}   — rename collapsing / elimination</li>
 * </ul>
 * <p>{@code OptimizationCodeTest} asserts that this list and the declared constants
 * name the same set of categories, so adding a category without documenting it fails
 * the build.
 *
 * <p>The {@link #category()} convenience method returns the prefix string
 * (e.g. {@code "EXPR"}) derived from {@link #code()}.
 */
public enum OptimizationCode {

    // ── Expression / arithmetic ───────────────────────────────────────────────

    /** Evaluate a binary arithmetic expression whose both operands are numeric
     *  literals at planning time (e.g. {@code 2 * 3} → {@code 6}). */
    EXPR_001("EXPR-001", "Constant arithmetic fold"),

    /** Evaluate a string concatenation whose both operands are string literals
     *  at planning time (e.g. {@code "a" + "b"} → {@code "ab"}). */
    EXPR_002("EXPR-002", "Constant string concatenation fold"),

    /** Remove addition or subtraction of zero
     *  ({@code x + 0} → {@code x}, {@code x − 0} → {@code x},
     *   {@code 0 + x} → {@code x}). */
    EXPR_003("EXPR-003", "Additive identity elimination (x ± 0 → x)"),

    /** Remove multiplication by one or division by one
     *  ({@code x * 1} → {@code x}, {@code 1 * x} → {@code x},
     *   {@code x / 1} → {@code x}). */
    EXPR_004("EXPR-004", "Multiplicative identity elimination (x × 1, x ÷ 1 → x)"),

    /** Replace multiplication by zero with zero
     *  ({@code x * 0} → {@code 0}, {@code 0 * x} → {@code 0}). */
    EXPR_005("EXPR-005", "Zero-multiplication elimination (x × 0 → 0)"),

    /** Remove double unary negation
     *  ({@code −(−x)} → {@code x}). */
    EXPR_006("EXPR-006", "Double negation elimination (−(−x) → x)"),

    /** Reassociate constants in a chain of the same commutative+associative
     *  operator so they can be folded together
     *  ({@code (x + k1) + k2} → {@code x + (k1+k2)}). */
    EXPR_007("EXPR-007", "Constant term accumulation ((x ± k1) ± k2 → x ± k3)"),

    /** Remove a redundant outer call to an idempotent single-argument
     *  built-in function ({@code f(f(x))} → {@code f(x)}). */
    EXPR_008("EXPR-008", "Idempotent function call eliminated (f(f(x)) → f(x))"),

    // ── Predicate simplification ──────────────────────────────────────────────

    /** Simplify AND or OR expressions that have a constant-true or
     *  constant-false branch. */
    PRED_001("PRED-001", "Constant predicate fold (AND/OR with literal branch)"),

    /** Remove double logical negation
     *  ({@code ¬¬p} → {@code p}). */
    PRED_002("PRED-002", "Double NOT elimination (¬¬p → p)"),

    /** Swap the operands of a comparison predicate so the literal is always
     *  on the right, mirroring the operator as needed
     *  ({@code 5 &gt; age} → {@code age &lt; 5}). */
    PRED_003("PRED-003", "Comparison normalised to attribute-on-left canonical form"),

    /** Collapse a conjunction whose bounds on one column cannot all hold to the
     *  constant-false predicate ({@code x > 5 ∧ x < 3}, {@code x = 5 ∧ x = 6}). */
    PRED_004("PRED-004", "Contradictory bounds collapsed to false"),

    /** Drop a bound another conjunct on the same column already implies
     *  ({@code x > 5 ∧ x > 3} → {@code x > 5}). */
    PRED_005("PRED-005", "Redundant bound removed (subsumed by a tighter one)"),

    /** Remove a conjunct that repeats one already present
     *  ({@code p ∧ p} → {@code p}), compared structurally. */
    PRED_006("PRED-006", "Duplicate conjunct removed (p ∧ p → p)"),

    // ── Selection ─────────────────────────────────────────────────────────────

    /** Split a conjunctive selection into two stacked selections
     *  ({@code σ A∧B R} → {@code σ A (σ B R)}), enabling independent
     *  pushdown of each part. */
    SEL_001("SEL-001", "Conjunctive predicate split (σ A∧B → σ A (σ B))"),

    /** Merge two adjacent selections back into one conjunctive predicate
     *  ({@code σ A (σ B R)} → {@code σ A∧B R}), reducing operator count. */
    SEL_002("SEL-002", "Adjacent selection merge (σ A (σ B) → σ A∧B)"),

    /** Push a selection below an intervening projection when all referenced
     *  attributes are present in the projection's input schema. */
    SEL_003("SEL-003", "Selection pushed below projection"),

    /** Push a selection below a rename, rewriting attribute references to
     *  match the pre-rename column names. */
    SEL_004("SEL-004", "Selection pushed below rename"),

    /** Push a selection into the appropriate input of a join when the
     *  predicate references only one side's columns. */
    SEL_005("SEL-005", "Selection pushed into join input"),

    /** Replicate a selection into both branches of a
     *  {@link com.darkcollective.relix.ast.UnionAllNode} so each branch
     *  filters rows early. */
    SEL_006("SEL-006", "Selection pushed into union-all branch"),

    /** Demote a {@code HAVING}-position selection to a {@code WHERE}-position one:
     *  a conjunct that references only <em>bare-column</em> grouping keys of the
     *  {@link com.darkcollective.relix.ast.AggregationNode} below it is evaluated
     *  before the blocking γ rather than after
     *  ({@code σ p (γ keys, aggs (R))} → {@code γ keys, aggs (σ p (R))} when
     *  {@code attrs(p) ⊆ keys}). A conjunct touching an aggregate output stays
     *  above as a residual {@code σ}. */
    SEL_007("SEL-007", "Selection pushed below aggregation (HAVING → WHERE)"),

    /** Push a selection below a {@code δ} (DISTINCT) or a {@code τ} (SORT). Both are
     *  row-preserving and column-preserving with respect to a filter, so filtering
     *  first is free and means fewer rows reach a <em>blocking</em> operator
     *  ({@code σ p (δ R)} → {@code δ (σ p R)},
     *   {@code σ p (τ k R)} → {@code τ k (σ p R)}). */
    SEL_008("SEL-008", "Selection pushed below DISTINCT / SORT"),

    /** Distribute a selection over a set operation so each branch filters early:
     *  replicated into both branches of {@code ∪}, {@code ∩} and {@code ∆}, and into
     *  the <em>left</em> branch only of {@code −} — filtering the subtrahend would
     *  remove rows from it and so <em>add</em> rows to the result.  {@code ⊎} is
     *  {@link #SEL_006}; {@code ⊔} (outer union) is excluded because its branches
     *  have different schemas, so a predicate valid against one may reference a
     *  column absent from the other. */
    SEL_009("SEL-009", "Selection distributed over set operation"),

    // ── Projection ────────────────────────────────────────────────────────────

    /** Remove a projection that outputs exactly the same schema as its input
     *  (a no-op pass-through). */
    PROJ_001("PROJ-001", "Redundant projection eliminated (projects identical schema)"),

    /** Merge two stacked projections into one
     *  ({@code π a (π a,b R)} → {@code π a R}). */
    PROJ_002("PROJ-002", "Consecutive projections merged"),

    /** Push a projection below an intervening selection so fewer columns
     *  are carried through the selection evaluation. */
    PROJ_003("PROJ-003", "Projection pushed below selection"),

    /** Prune the columns a query never reads, by a top-down "required columns" walk:
     *  each node is told which of its output columns its parent needs and derives what
     *  it therefore needs from each child, so a base relation is wrapped in a narrowing
     *  {@code π} whenever the query reads strictly fewer columns than the table has
     *  ({@code γ region, SUM(amount) (Sales)} →
     *   {@code γ region, SUM(amount) (π region, amount (Sales))}), and an intermediate
     *  {@code π} is narrowed to the columns actually read above it. Width multiplies
     *  through everything downstream — the {@code SELECT} list a pushdown renderer
     *  emits, a hash join's build side, and every materializing operator
     *  ({@link ColumnPruningPass}). */
    PROJ_004("PROJ-004", "Columns pruned (required-columns walk narrows the leaves)"),

    // ── Join / cross-product ──────────────────────────────────────────────────

    /** Convert a {@link com.darkcollective.relix.ast.SelectionNode} applied
     *  to a {@link com.darkcollective.relix.ast.ProductNode} into a
     *  {@link com.darkcollective.relix.ast.ThetaJoinNode}, allowing the
     *  executor to filter during the join rather than afterwards. */
    JOIN_001("JOIN-001", "Cartesian product + selection converted to theta join"),

    /** Push a selection whose predicate references only one side of a join
     *  down into that join input. */
    JOIN_002("JOIN-002", "Selection pushed into join input"),

    /** Demote an outer join to a less outer one when a predicate above it rejects NULLs
     *  on a column from the padded side, making the rows that join produced by padding
     *  unreachable ({@code σ p (A ⟕ B)} → {@code σ p (A ⨝θ B)} when {@code p} rejects
     *  NULLs on a column of {@code B}). A {@code ⟗} demotes one half at a time, to
     *  {@code ⟕}/{@code ⟖} and then to {@code ⨝θ}. The demotion is a gate in front of
     *  several optimizations an outer join cannot have: σ pushdown into <em>both</em>
     *  sides, sort-merge planning, {@code JOIN … ON} pushdown, and {@code EQ-001}.
     *  {@code c IS NULL} is deliberately <em>not</em> null-rejecting — that is the
     *  anti-join idiom ({@link OuterJoinDemotionPass}). */
    JOIN_004("JOIN-004", "Outer join demoted (predicate above rejects NULLs on the padded side)"),

    // ── Lateral decorrelation ─────────────────────────────────────────────────

    /** Turn an <em>uncorrelated</em> {@code LATERAL} table-valued-function join into a
     *  plain Cartesian product against a single TVF call
     *  ({@code L LATERAL f(args)} → {@code L × f(args)}) when no argument references a
     *  column of the left input. The lateral operator re-plans and re-executes the
     *  function body once per left row; with constant arguments every one of those
     *  invocations produces the same relation, so a single call is equivalent — the
     *  schema is {@code left ++ body} either way, and {@code ×} pairs the two sides in
     *  the same order the lateral concatenates them.
     *
     *  <p>Beyond the per-row work it removes, the rewrite is what makes the operator
     *  <em>visible</em>: {@link com.darkcollective.relix.ast.LateralJoinNode#children()}
     *  reports only the left input, so the TVF body is outside structural traversal and
     *  no other rule can see through it. As a {@code ×} it can become a theta join
     *  ({@code JOIN-001}), take a pushed selection, and be reordered
     *  ({@link LateralDecorrelationPass}). */
    LATERAL_001("LATERAL-001", "Uncorrelated LATERAL decorrelated to a product with a plain TVF call"),

    // ── Equality propagation ──────────────────────────────────────────────────

    /** Propagate a literal binding across an equi-join's equality classes, so a filter
     *  written against one side also constrains the other
     *  ({@code A.x = B.x ∧ A.x = 5 ⊨ B.x = 5}). The derived predicate is placed as a
     *  {@code σ} directly on the side whose schema owns the column, so with
     *  capability-based pushdown <em>both</em> backends filter independently
     *  instead of one shipping its whole table for the engine to discard. Inner
     *  ({@link com.darkcollective.relix.ast.ThetaJoinNode}) joins only — an outer join's
     *  condition does not hold of its padded rows ({@link TransitiveEqualityPass}). */
    EQ_001("EQ-001", "Equality propagated across an equi-join (literal bound on both sides)"),

    // ── Limit ─────────────────────────────────────────────────────────────────

    /** Push a {@link com.darkcollective.relix.ast.LimitNode} below an
     *  intervening {@link com.darkcollective.relix.ast.ProjectionNode}, since
     *  projection is row-count neutral. */
    LIM_001("LIM-001", "Limit pushed below projection"),

    /** Push a {@link com.darkcollective.relix.ast.LimitNode} below a
     *  {@link com.darkcollective.relix.ast.RenameNode}: a rename touches names, never
     *  rows, so it is row-count and order neutral
     *  ({@code λ(off, n)(ρ … (R))} → {@code ρ … (λ(off, n)(R))}). */
    LIM_002("LIM-002", "Limit pushed below rename"),

    /** Fuse a limit over a sort into a {@link com.darkcollective.relix.ast.TopKNode} —
     *  the classic top-N rewrite ({@code λ(off, n)(τ k (R))} → {@code TOP n OFFSET off
     *  ORDER BY k (R)}, with no grouping keys, i.e. one global group). The pair
     *  materializes and sorts the <em>whole</em> input to then discard all but
     *  {@code n} rows; {@code TOP} keeps a bounded heap instead. The λ's offset carries
     *  across unchanged, since {@code TopKNode} skips before it takes. */
    LIM_003("LIM-003", "Limit over sort fused into TOP (top-N)"),

    /** Replicate a limit into both branches of a
     *  {@link com.darkcollective.relix.ast.UnionAllNode}, so neither branch produces
     *  more rows than the whole union can possibly use
     *  ({@code λ n (A ⊎ B)} → {@code λ n ((λ n A) ⊎ (λ n B))}). The outer λ must stay:
     *  each branch may supply fewer than {@code n} rows. With an offset the branch
     *  bound is {@code offset + count} — the outer λ still has to skip. */
    LIM_004("LIM-004", "Limit replicated into union-all branches"),

    // ── Aggregation ─────────────────────────────────────────────────────────

    /** Collapse a redundant outer aggregation directly above another
     *  aggregation: when the outer {@link com.darkcollective.relix.ast.AggregationNode}
     *  has no aggregate functions and its grouping keys (as a set) are exactly
     *  the inner aggregation's output columns, the inner already emits one row
     *  per grouping-key combination, so re-grouping by all of its columns is a
     *  no-op and the outer aggregation is removed
     *  ({@code γ cols [] (γ keys, aggs (R))} → {@code γ keys, aggs (R)}). */
    AGG_001("AGG-001", "Redundant re-grouping eliminated (γ over γ collapsed)"),

    // ── Distinctness ──────────────────────────────────────────────────────────

    /** Remove a {@code δ} (DISTINCT) whose input is already provably
     *  duplicate-free, so the deduplication is redundant
     *  ({@code δ(R)} → {@code R} when {@code R} is whole-row distinct or has a
     *  candidate key — e.g. {@code δ} over {@code γ}, a set operation, transitive
     *  closure, or another {@code δ}). Distinctness is derived bottom-up by
     *  {@link com.darkcollective.relix.cost.PropertyDeriver}. */
    DIST_001("DIST-001", "Redundant DISTINCT removed (input already duplicate-free)"),

    /** Remove a {@code δ} (DISTINCT) below an aggregation that ignores multiplicity, so
     *  the deduplication is work its consumer does not need
     *  ({@code γ keys, aggs (δ R)} → {@code γ keys, aggs (R)} when the γ has no
     *  aggregates, or only {@code MIN}/{@code MAX} over deterministic arguments).
     *  Where {@link #DIST_001} looks <em>down</em> from the {@code δ} at an input that is
     *  already a set, this looks <em>up</em> at a consumer that would collapse the
     *  duplicates anyway. {@code SUM}/{@code COUNT}/{@code AVG}/{@code COLLECT} read
     *  multiplicity and block it, as do {@code ARGMAX}/{@code ARGMIN}. The rule looks
     *  through an intervening {@code σ}/{@code π}/{@code ρ}/{@code τ} — each emits at
     *  most one row per input row — but not a {@code λ}/sample/{@code TOP}, whose output
     *  depends on how many rows arrive. */
    DIST_002("DIST-002", "DISTINCT removed below a duplicate-insensitive aggregation"),

    // ── Ordering ──────────────────────────────────────────────────────────────

    /** Remove a {@code τ} (SORT) whose input already delivers an order that
     *  satisfies it, so the sort is redundant ({@code τ keys (R)} → {@code R} when
     *  {@code R}'s delivered order has {@code keys} as a prefix — e.g. {@code τ} over
     *  a compatible {@code τ}, or over {@code σ}/{@code λ} above one). The delivered
     *  order is derived by {@link com.darkcollective.relix.cost.OrderDeriver}. */
    SORT_001("SORT-001", "Redundant SORT removed (input already delivers the order)"),

    // ── Product identity ──────────────────────────────────────────────────────

    /** Drop a Cartesian product against the one-tuple truth relation
     *  {@code UNIT} ({@code DEE}), which is the identity of {@code ×}
     *  ({@code R × UNIT} → {@code R}, {@code UNIT × R} → {@code R}). {@code UNIT}
     *  has the empty heading and exactly one row, so the product reproduces
     *  {@code R}'s rows <em>and</em> its column order exactly — unlike swapping a
     *  product's operands, which permutes the ordered output schema. The
     *  zero-tuple {@code EMPTY} ({@code DUM}) has no matching rule: {@code R × EMPTY}
     *  is empty but keeps {@code R}'s heading, so it cannot be rewritten to the
     *  zero-column {@code EMPTY}. */
    PROD_001("PROD-001", "Product against UNIT removed (× identity)"),

    // ── Empty-relation propagation ────────────────────────────────────────────

    /** Replace a selection whose predicate is constant-false with the empty
     *  relation carrying that selection's heading ({@code σ false (R)} → {@code ∅}). */
    EMPTY_001("EMPTY-001", "Unsatisfiable selection replaced by the empty relation"),

    /** Replace an operator with the empty relation because an input that forces
     *  emptiness is empty ({@code ∅ ⋈ X} → {@code ∅}). */
    EMPTY_002("EMPTY-002", "Emptiness propagated through an operator"),

    /** Drop an empty right branch of a bag union ({@code X ⊎ ∅} → {@code X}). */
    EMPTY_003("EMPTY-003", "Empty branch of a bag union dropped"),

    // ── Nest / unnest (NF²) ─────────────────────────────────────────────────────

    /** Collapse a nest immediately undone by an unnest — the
     *  {@code μ ∘ COLLECT = id} round-trip law (after Hölsch, Grossniklaus
     *  &amp; Scholl, SIGMOD 2016). When a {@code μ} unnests exactly the
     *  array column produced by a single {@code COLLECT} aggregate of the
     *  {@link com.darkcollective.relix.ast.AggregationNode} directly beneath it,
     *  the group-then-explode reproduces the pre-nest rows, so the pair is
     *  rewritten to a plain projection
     *  ({@code μ g (γ keys, COLLECT(x)→g (R))} → {@code π keys, (x → g) (R)}),
     *  removing a blocking {@code γ} and the {@code μ}. */
    NEST_001("NEST-001", "Nest/unnest round-trip collapsed (μ over COLLECT → projection)"),

    /** Push a selection below an {@code μ} (UNNEST) when its predicate references
     *  neither the unnested column nor the {@code WITH ORDINALITY} column, so the
     *  filter runs before the row-multiplying explode
     *  ({@code σ p (μ c (R))} → {@code μ c (σ p (R))}). */
    NEST_002("NEST-002", "Selection pushed below unnest"),

    /** Push a column-pruning projection below an {@code μ} (UNNEST) so fewer
     *  columns flow through the row-multiplying explode
     *  ({@code π cols (μ c (R))} → {@code μ c (π cols (R))}); fires only when every
     *  projected attribute is a bare column, the unnested column is projected
     *  unaliased, and there is no {@code WITH ORDINALITY} column. */
    NEST_003("NEST-003", "Projection pushed below unnest"),

    // ── Selection pushdown into expensive operators (SIP / magic sets) ──────────

    /** Fold a constant endpoint equality above a {@code CLOSURE}/{@code RCLOSURE}
     *  into the operator as a source/target bound, turning all-pairs reachability
     *  into single-source / single-target / single-pair traversal — the canonical
     *  magic-sets / sideways-information-passing rewrite. Fires on
     *  a top-level conjoined equality on the {@code from}/{@code to} column against a
     *  literal in the selection chain directly above the closure; any non-pushable
     *  conjunct remains as a {@code σ} above, so the rewrite is a no-op when nothing
     *  is pushable. {@code σ from = c (CLOSURE from, to (E))} →
     *  {@code CLOSURE from, to ⟨from=c⟩ (E)}. */
    CLOSURE_001("CLOSURE-001", "Selection folded into CLOSURE endpoint bound (single-source reachability)"),

    /** Fold a constant endpoint equality above a {@code TRACE} optimal-path operator
     *  into the operator as a source/target bound, turning all-pairs path search into
     *  single-source / single-target / single-pair search.
     *  Fires on a top-level conjoined equality on the {@code from}/{@code to} column
     *  against a literal in the selection chain directly above the trace; any
     *  non-pushable conjunct remains as a {@code σ} above (no-op when nothing is
     *  pushable). {@code σ from = c (TRACE from, to VIA w … (E))} →
     *  {@code TRACE from, to VIA w … ⟨from=c⟩ (E)}. */
    TRACE_001("TRACE-001", "Selection folded into TRACE endpoint bound (single-source path search)"),

    /** Fold a constant endpoint equality above a {@code PATH} bounded-traversal operator
     *  into the operator as a source/target bound, turning an all-pairs breadth-first
     *  search into a single-source / single-target / single-pair one. The {@code depth}
     *  column is unaffected: the shortest path from a seed does not depend on which other
     *  nodes were searched from, so the bounded result is a slice of the unbounded one.
     *  {@code σ from = c (PATH from, to HOPS m TO n AS d (E))} →
     *  {@code PATH from, to HOPS m TO n AS d ⟨from=c⟩ (E)}. */
    PATH_001("PATH-001", "Selection folded into PATH endpoint bound (single-source traversal)"),

    /** Push a selection over <em>frozen</em> columns into a {@code FIX} least-fixpoint —
     *  the general magic-sets / sideways-information-passing rewrite over arbitrary
     *  monotone, linear recursion — the umbrella case of which
     *  {@code CLOSURE-001}/{@code TRACE-001} are fixed-shape instances. When every
     *  attribute a top-level conjunct references is <em>frozen</em> — carried unchanged
     *  by the step from the recursive reference to its output — the selection
     *  distributes over the fixpoint: {@code σ p (FIX name (base, step))} ≡
     *  {@code FIX name (σ p (base), step[name ↦ σ p (name)])}, so the recursion is
     *  seeded and restricted by {@code p} and the full fixpoint is avoided. Sound by the
     *  step's validator-enforced linearity + monotonicity. Non-frozen
     *  conjuncts remain as a residual {@code σ} above (no-op when nothing is pushable).
     *  {@code σ assembly = "Bike" (FIX BOM (Contains, π assembly, … (BOM ⋈ …)))} →
     *  {@code FIX BOM (σ assembly = "Bike" (Contains), π … (σ assembly = "Bike" (BOM) ⋈ …))}. */
    FIX_001("FIX-001", "Selection over frozen columns pushed into FIX recursion (magic sets)"),

    /** Fold a top-level upper-bound equality/inequality above a monotone unbounded
     *  generator ({@code Naturals}/{@code Primes}) into the producer as a generation
     *  stop, so the otherwise non-terminating scan is finite. Fires on
     *  {@code σ n < k}/{@code n <= k}/{@code n = k} directly above the generator's
     *  ascending value column; the σ is kept above as a residual filter (correctness by
     *  construction), and the leaf becomes boundedness-BOUNDED so a downstream blocking
     *  operator over it is legal. {@code σ n < 100 (Naturals)} →
     *  {@code σ n < 100 (Naturals ⟨produce while n < 100⟩)}. */
    GEN_001("GEN-001", "Bound pushed into generator (unbounded scan made finite)"),

    // ── Partition pruning (selection into expensive operators) ────────────────

    /** Prune a partitioned {@link com.darkcollective.relix.ast.WindowNode} to the
     *  partition(s) a selection fixes: an equality {@code partitionKey = constant}
     *  on a {@code PARTITION BY} column above the window is pushed below it, so the
     *  per-partition window computation runs over only the matching partition and
     *  the other partitions are never computed
     *  ({@code σ k = c (WINDOW … PARTITION BY k …)} →
     *   {@code WINDOW … PARTITION BY k … (σ k = c (R))}). Safe because the window
     *  frame never crosses a partition boundary, so the per-partition result is
     *  identical. Predicates on non-partition or computed columns stay as a
     *  residual {@code σ} above the window. */
    WINDOW_001("WINDOW-001", "Partition pruned (σ on PARTITION BY key pushed below WINDOW)"),

    /** Prune a {@link com.darkcollective.relix.ast.TopKNode} to the partition(s) a
     *  selection fixes: an equality {@code partitionKey = constant} on a {@code PER}
     *  grouping column above the top-k is pushed below it, so top-k is computed for
     *  only the matching group instead of every group
     *  ({@code σ k = c (TOP n … PER k (R))} → {@code TOP n … PER k (σ k = c (R))}).
     *  Safe because top-k is computed independently per group; predicates on
     *  non-partition columns stay as a residual {@code σ} above. */
    TOPK_001("TOPK-001", "Partition pruned (σ on PER key pushed below TOP)"),

    /** Prune an {@link com.darkcollective.relix.ast.OptimizeNode} to the group(s) a
     *  selection fixes: an equality {@code groupingKey = constant} on a {@code PER}
     *  key above the operator is pushed below it, so the solver runs its MIP/LP for
     *  only the matching group instead of every group
     *  ({@code σ k = c (OPTIMIZE … PER k (R))} → {@code OPTIMIZE … PER k (σ k = c (R))}).
     *  Safe because {@code OPTIMIZE} solves each group's problem independently; a
     *  predicate on a non-grouping column changes the optimum under
     *  emit-the-optimum semantics and stays as a residual {@code σ} above
     *. */
    OPTIMIZE_001("OPTIMIZE-001", "Group pruned (σ on PER key pushed below OPTIMIZE)"),

    /** Prune a {@link com.darkcollective.relix.ast.SessionizeNode} to the partition(s) a
     *  selection fixes: an equality {@code partitionKey = constant} on a {@code PER}
     *  column above the operator is pushed below it, so only the matching partition is
     *  buffered, sorted and walked for session boundaries
     *  ({@code σ k = c (SESSIONIZE … PER k (R))} → {@code SESSIONIZE … PER k (σ k = c (R))}).
     *  Safe because a session boundary never spans a {@code PER} key
     *  ({@link SelectionIntoTimeSeriesPass}). */
    SESSION_001("SESSION-001", "Partition pruned (σ on PER key pushed below SESSIONIZE)"),

    /** Prune a {@link com.darkcollective.relix.ast.DownsampleNode} to the group(s) a
     *  selection fixes: an equality {@code groupingKey = constant} on a {@code PER}
     *  column above the operator is pushed below it, so only the matching group's rows
     *  are bucketed and consolidated
     *  ({@code σ k = c (DOWNSAMPLE … PER k (R))} → {@code DOWNSAMPLE … PER k (σ k = c (R))}).
     *  Does <em>not</em> fire when the operator carries {@code FOR n ROWS}: that keeps the
     *  {@code n} most recent buckets across <em>all</em> groups, so pushing a group filter
     *  below it changes which buckets survive
     *  ({@link SelectionIntoTimeSeriesPass}). */
    DOWNSAMPLE_001("DOWNSAMPLE-001", "Group pruned (σ on PER key pushed below DOWNSAMPLE)"),

    // ── View inlining ─────────────────────────────────────────────────────────

    /** Replace a reference to a named view ({@link
     *  com.darkcollective.relix.symbol.relation.QueryRelationSymbol}) with the
     *  view's own body, so that later rules (e.g. selection/projection pushdown)
     *  can optimise across the former view boundary. */
    INLINE_001("INLINE-001", "View body inlined into the referencing query"),

    // ── Rename elimination (cleanup after inlining) ───────────────────────────

    /** Collapse a relation-only {@code ρ} into the {@code ρ} directly beneath it
     *  ({@code ρ V (ρ W [spec] (R))} → {@code ρ V [spec] (R)}). Unconditional: the
     *  outer rename re-anchors every column's provenance to {@code V}, so {@code W}
     *  is not a resolvable qualifier at the outer node's output either way
     *  ({@link RenameEliminationPass}). */
    RENAME_001("RENAME-001", "Rename chain collapsed (ρ over ρ)"),

    /** Remove a relation-only {@code ρ} whose alias nothing references
     *  ({@code ρ V (X)} → {@code X}) — the wrapper {@link ViewInliner} puts around
     *  every inlined view body, which otherwise survives into the final plan and
     *  sits between operators that later passes need to see adjacent. Fires only
     *  when a whole-tree sweep finds neither {@code V} nor any relation name
     *  {@code X} would re-expose used as a qualifier, since a relation-qualified
     *  reference resolves by column provenance ({@link RenameEliminationPass}). */
    RENAME_002("RENAME-002", "Unreferenced rename removed (ρ alias names nothing)");

    // ── Enum infrastructure ───────────────────────────────────────────────────

    private final String code;
    private final String description;

    OptimizationCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Returns the stable string code used in reports and audit logs,
     * e.g. {@code "EXPR-001"}.
     *
     * @return the code string; never null or blank
     */
    public String code() {
        return code;
    }

    /**
     * Returns a short human-readable description of what this transformation
     * does.
     *
     * @return the description; never null or blank
     */
    public String description() {
        return description;
    }

    /**
     * Returns the category prefix of this code (the part before the first
     * {@code '-'}), e.g. {@code "EXPR"} for {@link #EXPR_001}.
     *
     * @return the category string; never null or blank
     */
    public String category() {
        return code.substring(0, code.indexOf('-'));
    }

    /**
     * Returns a combined {@code "code — description"} string suitable for
     * display in reports.
     *
     * @return formatted string; never null
     */
    @Override
    public String toString() {
        return code + " — " + description;
    }
}
