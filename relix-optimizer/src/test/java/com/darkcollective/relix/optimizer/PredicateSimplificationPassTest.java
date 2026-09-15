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

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.AsOfJoinNode;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.TieBreak;
import com.darkcollective.relix.ast.UniversalNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.Expr.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("PredicateSimplificationPass")
final class PredicateSimplificationPassTest {

    private OptimizationContext ctx;
    private static final SchemaAnnotations SCHEMAS = SchemaAnnotations.empty();
    private static final RelNode BASE = rel("T");

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private RelNode apply(RelNode node) {
        return PredicateSimplificationPass.apply(node, "Q", SCHEMAS, ctx);
    }

    private SelectionNode sel(Predicate pred) {
        return select(pred, BASE);
    }

    // =========================================================================
    // PRED-001: constant predicate fold
    // =========================================================================

    @Nested
    @DisplayName("PRED-001 — constant predicate fold")
    class Pred001 {

        @Test @DisplayName("TRUE AND p → p  (left is constant-true numeric comparison)")
        void trueAndP() {
            // 1 = 1 is always true
            var tautology = cmp(num("1"), ComparisonOperator.EQUAL, num("1"));
            var p         = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var node = sel(and(tautology, p));

            RelNode result = apply(node);

            var newSel = (SelectionNode) result;
            assertThat(newSel.predicate()).isSameAs(p);
            assertThat(ctx).fired(OptimizationCode.PRED_001, 1);
        }

        @Test @DisplayName("p AND TRUE → p  (right is constant-true)")
        void pAndTrue() {
            var p         = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var tautology = cmp(num("5"), ComparisonOperator.LESS, num("10"));
            var node = sel(and(p, tautology));

            RelNode result = apply(node);

            var newSel = (SelectionNode) result;
            assertThat(newSel.predicate()).isSameAs(p);
            assertThat(ctx).fired(OptimizationCode.PRED_001, 1);
        }

        @Test @DisplayName("FALSE OR p → p  (left is constant-false)")
        void falseOrP() {
            var contradiction = cmp(num("1"), ComparisonOperator.EQUAL, num("2"));
            var p             = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var node = sel(or(contradiction, p));

            RelNode result = apply(node);

            var newSel = (SelectionNode) result;
            assertThat(newSel.predicate()).isSameAs(p);
            assertThat(ctx).fired(OptimizationCode.PRED_001, 1);
        }

        @Test @DisplayName("p OR FALSE → p  (right is constant-false)")
        void pOrFalse() {
            var p             = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var contradiction = cmp(num("10"), ComparisonOperator.LESS, num("3"));
            var node = sel(or(p, contradiction));

            RelNode result = apply(node);

            var newSel = (SelectionNode) result;
            assertThat(newSel.predicate()).isSameAs(p);
            assertThat(ctx).fired(OptimizationCode.PRED_001, 1);
        }

        @Test @DisplayName("string comparison: \"a\" = \"a\" AND p → p")
        void stringTautology() {
            var tautology = cmp(str("US"), ComparisonOperator.EQUAL, str("US"));
            var p         = cmp(attr("status"), ComparisonOperator.EQUAL, str("active"));
            var node = sel(and(tautology, p));

            RelNode result = apply(node);
            assertThat(((SelectionNode) result).predicate()).isSameAs(p);
        }

        @Test @DisplayName("non-constant comparison is NOT folded")
        void nonConstantNotFolded() {
            // age > 18 — not a constant-evaluable predicate
            var p1 = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var p2 = cmp(attr("score"), ComparisonOperator.LESS, num("100"));
            var node = sel(and(p1, p2));

            RelNode result = apply(node);
            assertThat(result).isSameAs(node);
            assertThat(ctx.isEmpty()).isTrue();
        }
    }

    // =========================================================================
    // PRED-002: double NOT elimination
    // =========================================================================

    @Nested
    @DisplayName("PRED-002 — double NOT elimination")
    class Pred002 {

        @Test @DisplayName("¬(¬p) → p")
        void doubleNot() {
            var p    = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var node = sel(not(not(p)));

            RelNode result = apply(node);

            var newSel = (SelectionNode) result;
            assertThat(newSel.predicate()).isSameAs(p);
            assertThat(ctx).fired(OptimizationCode.PRED_002, 1);
        }

        @Test @DisplayName("¬(constant) folds to the other constant — PRED-001, through the ¬")
        void negationOfAConstant() {
            // The one place a ¬ produces a constant rather than eliminating another ¬, and
            // it is safe only because the inner predicate is a literal comparison, which
            // has no NULL reading — ¬UNKNOWN is not FALSE. Reached by no test: the
            // constant fold and the double-¬ rule each had their own cases and this sits
            // between them.
            // Asserted as "a constant, and the opposite one", not as a spelling: the
            // pass writes its constants as a comparison of two boolean literals, and
            // pinning that text would make this a test of the encoding.
            Predicate fromTrue = predicateOf(apply(sel(not(
                    cmp(num("1"), ComparisonOperator.EQUAL, num("1"))))));
            Predicate fromFalse = predicateOf(apply(sel(not(
                    cmp(num("1"), ComparisonOperator.EQUAL, num("0"))))));

            assertThat(constantValue(fromTrue)).as("¬TRUE is FALSE").isFalse();
            assertThat(constantValue(fromFalse)).as("¬FALSE is TRUE").isTrue();
            assertThat(ctx).fired(OptimizationCode.PRED_001, 2);
        }

        private static Predicate predicateOf(RelNode node) {
            return ((SelectionNode) node).predicate();
        }

        /** The truth of a folded constant, read off the two boolean literals it compares. */
        private static boolean constantValue(Predicate p) {
            var c = (ComparisonPredicate) p;
            var left = (com.darkcollective.relix.ast.BooleanOperand) c.left();
            var right = (com.darkcollective.relix.ast.BooleanOperand) c.right();
            return left.value() == right.value();
        }

        @Test @DisplayName("¬p is not simplified (single NOT)")
        void singleNotUnchanged() {
            var p    = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var node = sel(not(p));
            assertThat(apply(node)).isSameAs(node);
        }

        @Test @DisplayName("¬(¬(¬p)) → ¬p (one elimination)")
        void tripleNot() {
            var p      = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var notP   = not(p);
            var notNot = not(notP);
            var triple = not(notNot);
            var node   = sel(triple);

            RelNode result = apply(node);

            // Inner ¬(¬p) is eliminated → outer ¬ remains
            var newSel = (SelectionNode) result;
            assertThat(newSel.predicate()).isInstanceOf(NotPredicate.class);
            var outerNot = (NotPredicate) newSel.predicate();
            assertThat(outerNot.predicate()).isSameAs(p);
            assertThat(ctx).fired(OptimizationCode.PRED_002, 1);
        }
    }

    // =========================================================================
    // PRED-003: comparison normalisation
    // =========================================================================

    @Nested
    @DisplayName("PRED-003 — comparison normalisation (literal to right)")
    class Pred003 {

        @Test @DisplayName("5 > age → age < 5")
        void numericLiteralOnLeft() {
            var ageAttr = attr("age");
            var pred    = cmp(num("5"), ComparisonOperator.GREATER, ageAttr);
            var node    = sel(pred);

            RelNode result = apply(node);

            var newSel  = (SelectionNode) result;
            var newPred = (ComparisonPredicate) newSel.predicate();
            assertThat(newPred.left()).isSameAs(ageAttr);
            assertThat(newPred.operator()).isEqualTo(ComparisonOperator.LESS);
            assertThat(newPred.right()).isEqualTo(num("5"));
            assertThat(ctx).fired(OptimizationCode.PRED_003, 1);
        }

        @Test @DisplayName("\"Alice\" = name → name = \"Alice\"")
        void stringLiteralOnLeft() {
            var nameAttr = attr("name");
            var pred     = cmp(str("Alice"), ComparisonOperator.EQUAL, nameAttr);
            var node     = sel(pred);

            RelNode result = apply(node);

            var newPred = (ComparisonPredicate) ((SelectionNode) result).predicate();
            assertThat(newPred.left()).isSameAs(nameAttr);
            assertThat(newPred.operator()).isEqualTo(ComparisonOperator.EQUAL);
        }

        @Test @DisplayName("operator table: all operators are correctly mirrored")
        void operatorMirroring() {
            var x = attr("x");
            assertMirror(ComparisonOperator.EQUAL,         ComparisonOperator.EQUAL,         x);
            assertMirror(ComparisonOperator.NOT_EQUAL,     ComparisonOperator.NOT_EQUAL,      x);
            assertMirror(ComparisonOperator.LESS,          ComparisonOperator.GREATER,        x);
            assertMirror(ComparisonOperator.LESS_EQUAL,    ComparisonOperator.GREATER_EQUAL,  x);
            assertMirror(ComparisonOperator.GREATER,       ComparisonOperator.LESS,           x);
            assertMirror(ComparisonOperator.GREATER_EQUAL, ComparisonOperator.LESS_EQUAL,     x);
        }

        private void assertMirror(ComparisonOperator inputOp, ComparisonOperator expectedOp,
                                   AttributeOperand x) {
            var innerCtx   = new OptimizationContext();
            var pred       = cmp(num("5"), inputOp, x);
            var simplified = PredicateSimplificationPass.apply(sel(pred), "Q", SCHEMAS, innerCtx);
            var newPred    = (ComparisonPredicate) ((SelectionNode) simplified).predicate();
            assertThat(newPred.operator())
                    .as("mirror of %s", inputOp)
                    .isEqualTo(expectedOp);
        }

        @Test @DisplayName("age > 18 is NOT changed — attribute already on left")
        void attributeOnLeftUnchanged() {
            var pred = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));
            var node = sel(pred);
            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx.isEmpty()).isTrue();
        }

        /**
         * Regression: the literal test used to list only NUMBER/STRING/BOOLEAN,
         * predating the ADR-0013 temporal types, so a temporal literal on the
         * left was never normalised to the right. That mattered downstream —
         * {@code MongoExpressions.comparison} only renders attribute-on-left, so
         * a literal-first temporal σ silently failed to push down and fell back
         * to a full in-engine scan.
         */
        @Test @DisplayName("TIMESTAMP '…' < ts → ts > TIMESTAMP '…' (temporal literals normalise too)")
        void temporalLiteralOnLeft() {
            var tsAttr  = attr("ts");
            var literal = timestamp(Instant.parse("2024-01-01T00:00:00Z"));
            var node    = sel(cmp(literal, ComparisonOperator.LESS, tsAttr));

            RelNode result = apply(node);

            var newPred = (ComparisonPredicate) ((SelectionNode) result).predicate();
            assertThat(newPred.left()).isSameAs(tsAttr);
            assertThat(newPred.operator()).isEqualTo(ComparisonOperator.GREATER);
            assertThat(newPred.right()).isEqualTo(literal);
            assertThat(ctx).fired(OptimizationCode.PRED_003, 1);
        }

        @Test @DisplayName("DATE / TIME / DURATION literals on the left normalise as well")
        void otherTemporalLiteralsOnLeft() {
            record Case(Operand literal, String label) { }
            List<Case> cases = List.of(
                    new Case(date(LocalDate.parse("2024-01-01")), "DATE"),
                    new Case(time(LocalTime.parse("09:30:00")), "TIME"),
                    new Case(duration(Duration.ofHours(2)), "DURATION"));

            for (Case c : cases) {
                ctx = new OptimizationContext();
                var col  = attr("v");
                var node = sel(cmp(c.literal(), ComparisonOperator.LESS_EQUAL, col));

                var newPred = (ComparisonPredicate) ((SelectionNode) apply(node)).predicate();

                assertThat(newPred.left()).as(c.label()).isSameAs(col);
                assertThat(newPred.operator()).as(c.label())
                        .isEqualTo(ComparisonOperator.GREATER_EQUAL);
                assertThat(ctx).as(c.label()).fired(OptimizationCode.PRED_003, 1);
            }
        }

        @Test @DisplayName("5 > 3 is NOT normalised — both sides are literals (PRED-001 domain)")
        void bothLiteralsUnchanged() {
            var pred = cmp(num("5"), ComparisonOperator.GREATER, num("3"));
            var node = sel(pred);
            apply(node);
            // PRED-001 fires (constant-true evaluation possible), PRED-003 does NOT
            assertThat(ctx).didNotFire(OptimizationCode.PRED_003);
        }
    }

    // =========================================================================
    // Interaction: passes leave the tree unchanged when no rules fire
    // =========================================================================

    @Nested
    @DisplayName("No-op — tree returned unchanged")
    class NoOp {

        @Test @DisplayName("plain RelationNode returned as-is")
        void leafUnchanged() {
            assertThat(apply(BASE)).isSameAs(BASE);
        }

        @Test @DisplayName("selection with attribute-on-left predicate — no change")
        void cleanSelectionUnchanged() {
            var node = sel(cmp(attr("age"), ComparisonOperator.GREATER, num("18")));
            assertThat(apply(node)).isSameAs(node);
        }
    }

    // =========================================================================
    // Conditional-join arms: every join node carrying a predicate gets the
    // same simplification treatment as σ (the traversal's per-type arms)
    // =========================================================================

    @Nested
    @DisplayName("Join conditions — simplified in every conditional-join node type")
    class JoinConditionArms {

        private static final RelNode L = new com.darkcollective.relix.ast.RelationNode("L");
        private static final RelNode R = new com.darkcollective.relix.ast.RelationNode("R");

        /** {@code TRUE AND (a = b)} — PRED-001 reduces it to the bare comparison. */
        private Predicate simplifiable() {
            var tautology = cmp(num("1"), ComparisonOperator.EQUAL, num("1"));
            return and(tautology,
                    cmp(attr("a"), ComparisonOperator.EQUAL, attr("b")));
        }

        /** Already-minimal condition the simplifier must leave alone. */
        private Predicate clean() {
            return cmp(attr("a"), ComparisonOperator.EQUAL, attr("b"));
        }

        private void assertSimplified(RelNode result, Predicate original) {
            Predicate rewritten = switch (result) {
                case com.darkcollective.relix.ast.ThetaJoinNode j         -> j.condition();
                case com.darkcollective.relix.ast.LeftOuterJoinNode j     -> j.condition();
                case com.darkcollective.relix.ast.RightOuterJoinNode j    -> j.condition();
                case com.darkcollective.relix.ast.FullOuterJoinNode j     -> j.condition();
                case com.darkcollective.relix.ast.SemiJoinNode j          -> j.condition();
                case com.darkcollective.relix.ast.AntiJoinNode j          -> j.condition();
                case com.darkcollective.relix.ast.PairwiseUniversalNode j -> j.condition();
                default -> throw new AssertionError("unexpected node " + result);
            };
            assertThat(rewritten).isNotSameAs(original);
            assertThat(rewritten).isInstanceOf(ComparisonPredicate.class);
            assertThat(ctx).fired(OptimizationCode.PRED_001, 1);
        }

        @Test @DisplayName("θ-join condition is simplified")
        void thetaJoin() {
            var pred = simplifiable();
            assertSimplified(apply(new com.darkcollective.relix.ast.ThetaJoinNode(L, R, pred)), pred);
        }

        @Test @DisplayName("left outer join condition is simplified")
        void leftOuter() {
            var pred = simplifiable();
            assertSimplified(apply(new com.darkcollective.relix.ast.LeftOuterJoinNode(L, R, pred)), pred);
        }

        @Test @DisplayName("right outer join condition is simplified")
        void rightOuter() {
            var pred = simplifiable();
            assertSimplified(apply(new com.darkcollective.relix.ast.RightOuterJoinNode(L, R, pred)), pred);
        }

        @Test @DisplayName("full outer join condition is simplified")
        void fullOuter() {
            var pred = simplifiable();
            assertSimplified(apply(new com.darkcollective.relix.ast.FullOuterJoinNode(L, R, pred)), pred);
        }

        @Test @DisplayName("semi-join condition is simplified")
        void semiJoin() {
            var pred = simplifiable();
            assertSimplified(apply(new com.darkcollective.relix.ast.SemiJoinNode(L, R, pred)), pred);
        }

        @Test @DisplayName("anti-join condition is simplified")
        void antiJoin() {
            var pred = simplifiable();
            assertSimplified(apply(new com.darkcollective.relix.ast.AntiJoinNode(L, R, pred)), pred);
        }

        @Test @DisplayName("pairwise-∀ condition is simplified")
        void pairwiseUniversal() {
            var pred = simplifiable();
            assertSimplified(apply(new com.darkcollective.relix.ast.PairwiseUniversalNode(L, R, pred)), pred);
        }

        @Test @DisplayName("clean join is returned by reference (no reallocation)")
        void cleanJoinUnchanged() {
            var join = new com.darkcollective.relix.ast.ThetaJoinNode(L, R, clean());
            assertThat(apply(join)).isSameAs(join);
        }

        @Test @DisplayName("a rewritten child forces a new join node even when the condition is clean")
        void childRewriteBubblesUp() {
            var dirtyChild = sel(and(
                    cmp(num("1"), ComparisonOperator.EQUAL, num("1")),
                    cmp(attr("x"), ComparisonOperator.GREATER, num("0"))));
            var join = new com.darkcollective.relix.ast.ThetaJoinNode(dirtyChild, R, clean());

            RelNode result = apply(join);

            assertThat(result).isNotSameAs(join);
            var newJoin = (com.darkcollective.relix.ast.ThetaJoinNode) result;
            assertThat(newJoin.condition()).isSameAs(join.condition());
            assertThat(((SelectionNode) newJoin.left()).predicate())
                    .isInstanceOf(ComparisonPredicate.class);
        }
    }

    // =========================================================================
    // PRED-004 / PRED-005 / PRED-006 — range analysis over a conjunction
    // =========================================================================

    @Nested
    @DisplayName("PRED-004 — contradictory bounds collapse to false")
    class Pred004 {

        private boolean isFalse(RelNode node) {
            return PredicateSimplifier.evalConstant(((SelectionNode) node).predicate())
                    .filter(v -> !v).isPresent();
        }

        private boolean fired(OptimizationCode code) {
            return ctx.records().stream().anyMatch(t -> t.code() == code);
        }

        @Test @DisplayName("x > 5 ∧ x < 3 is unsatisfiable")
        void disjointRanges() {
            var node = sel(and(
                    cmp(attr("x"), ComparisonOperator.GREATER, num("5")),
                    cmp(attr("x"), ComparisonOperator.LESS, num("3"))));
            assertThat(isFalse(apply(node))).isTrue();
            assertThat(fired(OptimizationCode.PRED_004)).isTrue();
        }

        @Test @DisplayName("x = 5 ∧ x = 6 is unsatisfiable")
        void conflictingEqualities() {
            var node = sel(and(
                    cmp(attr("x"), ComparisonOperator.EQUAL, num("5")),
                    cmp(attr("x"), ComparisonOperator.EQUAL, num("6"))));
            assertThat(isFalse(apply(node))).isTrue();
        }

        @Test @DisplayName("x > 5 ∧ x <= 5 is unsatisfiable — a shared endpoint one side excludes")
        void touchingEndpointsWithOneExclusive() {
            var node = sel(and(
                    cmp(attr("x"), ComparisonOperator.GREATER, num("5")),
                    cmp(attr("x"), ComparisonOperator.LESS_EQUAL, num("5"))));
            assertThat(isFalse(apply(node))).isTrue();
        }

        @Test @DisplayName("x >= 5 ∧ x <= 5 is satisfiable — it pins x to 5")
        void touchingEndpointsBothInclusive() {
            var node = sel(and(
                    cmp(attr("x"), ComparisonOperator.GREATER_EQUAL, num("5")),
                    cmp(attr("x"), ComparisonOperator.LESS_EQUAL, num("5"))));
            assertThat(isFalse(apply(node))).isFalse();
            assertThat(fired(OptimizationCode.PRED_004)).isFalse();
        }

        @Test @DisplayName("bounds on different columns never contradict each other")
        void differentColumnsDoNotInteract() {
            var node = sel(and(
                    cmp(attr("x"), ComparisonOperator.GREATER, num("5")),
                    cmp(attr("y"), ComparisonOperator.LESS, num("3"))));
            assertThat(isFalse(apply(node))).isFalse();
        }

        @Test @DisplayName("a qualifier keeps two columns apart — A.x > 5 ∧ B.x < 3 is fine")
        void qualifiersKeepColumnsApart() {
            var node = sel(and(
                    cmp(attr("A.x"), ComparisonOperator.GREATER, num("5")),
                    cmp(attr("B.x"), ComparisonOperator.LESS, num("3"))));
            assertThat(isFalse(apply(node))).isFalse();
        }

        @Test @DisplayName("5.0 and 5 are one bound — scale does not hide a contradiction")
        void numericScaleIsNormalised() {
            var node = sel(and(
                    cmp(attr("x"), ComparisonOperator.GREATER, num("5.0")),
                    cmp(attr("x"), ComparisonOperator.LESS, num("5")))); 
            assertThat(isFalse(apply(node))).isTrue();
        }

        @Test @DisplayName("temporal bounds are compared too")
        void temporalBounds() {
            var node = sel(and(
                    cmp(attr("d"), ComparisonOperator.GREATER,
                            date(LocalDate.of(2026, 6, 1))),
                    cmp(attr("d"), ComparisonOperator.LESS,
                            date(LocalDate.of(2026, 1, 1)))));
            assertThat(isFalse(apply(node))).isTrue();
        }

        @Test @DisplayName("every literal kind a bound can be written over is compared")
        void everyComparableLiteralKind() {
            // One arm per type in the bound comparator, each shown to detect a
            // contradiction and — the other half of the claim — to let a satisfiable
            // range through. A type whose arm is missing silently declines to fold,
            // which looks identical to "no contradiction here".
            record Case(String column, Operand low, Operand high, String label) { }
            List<Case> cases = List.of(
                    new Case("s", str("m"), str("a"), "STRING"),
                    new Case("d", date(LocalDate.of(2026, 6, 1)),
                            date(LocalDate.of(2026, 1, 1)), "DATE"),
                    new Case("t", time(LocalTime.of(17, 0)),
                            time(LocalTime.of(9, 0)), "TIME"),
                    new Case("ts", timestamp(Instant.parse("2026-06-01T00:00:00Z")),
                            timestamp(Instant.parse("2026-01-01T00:00:00Z")), "TIMESTAMP"),
                    new Case("dur", duration(Duration.ofHours(5)),
                            duration(Duration.ofHours(1)), "DURATION"));
            for (Case c : cases) {
                var contradictory = sel(and(
                        cmp(attr(c.column()), ComparisonOperator.GREATER, c.low()),
                        cmp(attr(c.column()), ComparisonOperator.LESS, c.high())));
                assertThat(isFalse(apply(contradictory)))
                        .as("%s: > high ∧ < low is a contradiction", c.label()).isTrue();

                var satisfiable = sel(and(
                        cmp(attr(c.column()), ComparisonOperator.GREATER, c.high()),
                        cmp(attr(c.column()), ComparisonOperator.LESS, c.low())));
                assertThat(isFalse(apply(satisfiable)))
                        .as("%s: > low ∧ < high is satisfiable", c.label()).isFalse();

                // Each arm tests its left operand first, so a bound of this type against
                // a bound of another is the arm a same-type pair never enters. It must
                // decline rather than compare across kinds — the validator reports the
                // type error, a rewrite that met one only steps aside.
                var crossKind = sel(and(
                        cmp(attr(c.column()), ComparisonOperator.GREATER, c.low()),
                        cmp(attr(c.column()), ComparisonOperator.LESS, num("0"))));
                assertThat(isFalse(apply(crossKind)))
                        .as("%s: a bound against a NUMBER bound is not comparable", c.label())
                        .isFalse();
            }
        }

        @Test @DisplayName("mixed literal kinds bail rather than compare or throw")
        void mixedKindsBail() {
            var node = sel(and(
                    cmp(attr("x"), ComparisonOperator.GREATER, num("5")),
                    cmp(attr("x"), ComparisonOperator.LESS, str("abc"))));
            assertThat(isFalse(apply(node))).isFalse();
        }

        @Test @DisplayName("≠ is unconstraining — x ≠ 5 ∧ x = 5 is not detected")
        void notEqualIsUnconstraining() {
            // A contradiction in fact, but ≠ excises a point rather than moving an
            // endpoint; modelling it needs a hole set, which is deliberately out of scope.
            var node = sel(and(
                    cmp(attr("x"), ComparisonOperator.NOT_EQUAL, num("5")),
                    cmp(attr("x"), ComparisonOperator.EQUAL, num("5"))));
            assertThat(isFalse(apply(node))).isFalse();
        }

        @Test @DisplayName("⚠ under a ¬ the collapse is suppressed — ¬FALSE and ¬UNKNOWN differ")
        void suppressedUnderNegation() {
            // For a NULL x, `x > 5 ∧ x < 3` is UNKNOWN and `¬UNKNOWN` drops the row;
            // folding the inside to FALSE would make `¬FALSE` keep it.
            var contradiction = and(
                    cmp(attr("x"), ComparisonOperator.GREATER, num("5")),
                    cmp(attr("x"), ComparisonOperator.LESS, num("3")));
            var node = sel(not(contradiction));
            apply(node);
            assertThat(fired(OptimizationCode.PRED_004)).isFalse();
        }
    }

    @Nested
    @DisplayName("PRED-005 — subsumed bounds removed")
    class Pred005 {

        private Predicate predicateOf(RelNode node) {
            return ((SelectionNode) node).predicate();
        }

        @Test @DisplayName("x > 5 ∧ x > 3 keeps only x > 5")
        void tighterLowerBoundWins() {
            var tighter = cmp(attr("x"), ComparisonOperator.GREATER, num("5"));
            var node = sel(and(tighter,
                    cmp(attr("x"), ComparisonOperator.GREATER, num("3"))));
            assertThat(predicateOf(apply(node))).isEqualTo(tighter);
        }

        @Test @DisplayName("x < 3 ∧ x < 9 keeps only x < 3")
        void tighterUpperBoundWins() {
            var tighter = cmp(attr("x"), ComparisonOperator.LESS, num("3"));
            var node = sel(and(
                    cmp(attr("x"), ComparisonOperator.LESS, num("9")), tighter));
            assertThat(predicateOf(apply(node))).isEqualTo(tighter);
        }

        @Test @DisplayName("x > 5 ∧ x >= 5 keeps the exclusive bound")
        void exclusiveBeatsInclusiveAtTheSameEndpoint() {
            var exclusive = cmp(attr("x"), ComparisonOperator.GREATER, num("5"));
            var node = sel(and(
                    cmp(attr("x"), ComparisonOperator.GREATER_EQUAL, num("5")), exclusive));
            assertThat(predicateOf(apply(node))).isEqualTo(exclusive);
        }

        @Test @DisplayName("x = 5 ∧ x > 3 keeps the equality, not a pair of ranges")
        void equalitySubsumesTheRange() {
            var equality = cmp(attr("x"), ComparisonOperator.EQUAL, num("5"));
            var node = sel(and(equality,
                    cmp(attr("x"), ComparisonOperator.GREATER, num("3"))));
            assertThat(predicateOf(apply(node))).isEqualTo(equality);
        }

        @Test @DisplayName("a lower and an upper bound are both kept — neither implies the other")
        void bothDirectionsKept() {
            var node = sel(and(
                    cmp(attr("x"), ComparisonOperator.GREATER, num("1")),
                    cmp(attr("x"), ComparisonOperator.LESS, num("9"))));
            assertThat(predicateOf(apply(node))).isInstanceOf(AndPredicate.class);
        }

        @Test @DisplayName("a non-comparison conjunct is never dropped")
        void opaqueConjunctSurvives() {
            var node = sel(and(
                    new com.darkcollective.relix.ast.NullPredicate(attr("y"), true),
                    cmp(attr("x"), ComparisonOperator.GREATER, num("5"))));
            assertThat(predicateOf(apply(node))).isInstanceOf(AndPredicate.class);
        }
    }

    @Nested
    @DisplayName("PRED-006 — duplicate conjunct removed")
    class Pred006 {

        @Test @DisplayName("p ∧ p → p, matched structurally rather than by identity")
        void duplicateRemoved() {
            // Two separate instances, as two occurrences in real source would be.
            var node = sel(and(
                    cmp(attr("x"), ComparisonOperator.GREATER, num("5")),
                    cmp(attr("x"), ComparisonOperator.GREATER, num("5"))));
            var result = ((SelectionNode) apply(node)).predicate();
            assertThat(result).isInstanceOf(ComparisonPredicate.class);
            assertThat(ctx.records()).anyMatch(t -> t.code() == OptimizationCode.PRED_006);
        }

        @Test @DisplayName("a repeated non-comparison conjunct is removed too")
        void duplicateOpaqueConjunctRemoved() {
            var node = sel(and(
                    new com.darkcollective.relix.ast.NullPredicate(attr("y"), true),
                    new com.darkcollective.relix.ast.NullPredicate(attr("y"), true)));
            assertThat(((SelectionNode) apply(node)).predicate())
                    .isInstanceOf(com.darkcollective.relix.ast.NullPredicate.class);
        }

        @Test @DisplayName("two different conjuncts are both kept")
        void distinctConjunctsKept() {
            var node = sel(and(
                    new com.darkcollective.relix.ast.NullPredicate(attr("y"), true),
                    new com.darkcollective.relix.ast.NullPredicate(attr("z"), true)));
            assertThat(((SelectionNode) apply(node)).predicate()).isInstanceOf(AndPredicate.class);
        }
    }

    // =========================================================================
    // The other predicate carriers (#564)
    // =========================================================================

    /**
     * Before #564 the pass had one arm for σ and one for
     * {@link com.darkcollective.relix.ast.ConditionalJoinNode}, so ∀'s predicate and an
     * AS-OF join's condition — ordinary σ-shaped predicates that simply live on a node
     * outside that interface — were never simplified.  The whole PRED family reaches
     * them now.
     */
    @Nested
    @DisplayName("∀ and AS-OF — the predicate carriers no arm used to match")
    class OtherPredicateCarriers {

        private final RelNode right = rel("U");

        private UniversalNode forall(Predicate p) {
            return universal(List.of("cust"), p, BASE);
        }

        private AsOfJoinNode asOf(Predicate p) {
            return asOfJoin(BASE, right, p, Optional.empty(), true,
                    TieBreak.FIRST);
        }

        @Test @DisplayName("PRED-001 folds a tautological conjunct out of ∀'s predicate")
        void universalConstantFold() {
            var tautology = cmp(num("1"), ComparisonOperator.EQUAL, num("1"));
            var p         = cmp(attr("age"), ComparisonOperator.GREATER, num("18"));

            var result = (UniversalNode) apply(forall(and(tautology, p)));

            assertThat(result.predicate()).isSameAs(p);
            assertThat(result.groupingAttributes()).containsExactly("cust");
            assertThat(ctx).fired(OptimizationCode.PRED_001, 1);
        }

        @Test @DisplayName("PRED-003 normalises a comparison in an AS-OF condition")
        void asOfNormalised() {
            // 5 < t → t > 5
            var result = (AsOfJoinNode) apply(
                    asOf(cmp(num("5"), ComparisonOperator.LESS, attr("t"))));

            var condition = (ComparisonPredicate) result.condition();
            assertThat(condition.left()).isEqualTo(attr("t"));
            assertThat(condition.operator()).isEqualTo(ComparisonOperator.GREATER);
            assertThat(ctx).fired(OptimizationCode.PRED_003, 1);
        }

        @Test @DisplayName("the fields around the rewritten predicate are carried across")
        void asOfSurroundingFieldsSurvive() {
            var node = asOfJoin(BASE, right,
                    cmp(num("5"), ComparisonOperator.LESS, attr("t")),
                    Optional.of(duration(Duration.ofHours(1))), false,
                    TieBreak.LAST);

            var result = (AsOfJoinNode) apply(node);

            assertThat(result.tolerance()).isEqualTo(node.tolerance());
            assertThat(result.inner()).isFalse();
            assertThat(result.tieBreak()).isEqualTo(TieBreak.LAST);
        }

        @Test @DisplayName("PRED-004 collapses contradictory bounds in an AS-OF condition")
        void asOfContradictionCollapses() {
            var result = (AsOfJoinNode) apply(asOf(and(
                    cmp(attr("x"), ComparisonOperator.GREATER, num("5")),
                    cmp(attr("x"), ComparisonOperator.LESS, num("3")))));

            assertThat(PredicateSimplifier.evalConstant(result.condition())).contains(false);
            assertThat(ctx).fired(OptimizationCode.PRED_004, 1);
        }

        @Test @DisplayName("PRED-005 drops the looser of two bounds in ∀'s predicate")
        void universalRedundantBoundDropped() {
            var tighter = cmp(attr("x"), ComparisonOperator.GREATER, num("10"));
            var result  = (UniversalNode) apply(forall(and(
                    cmp(attr("x"), ComparisonOperator.GREATER, num("5")), tighter)));

            assertThat(result.predicate()).isEqualTo(tighter);
            assertThat(ctx).fired(OptimizationCode.PRED_005, 1);
        }

        @Test @DisplayName("a clean ∀ / AS-OF comes back by reference")
        void cleanCarriersUnchanged() {
            var clean = cmp(attr("x"), ComparisonOperator.GREATER, num("5"));
            List<RelNode> nodes = List.of(forall(clean), asOf(clean));

            assertThat(nodes).allSatisfy(node -> assertThat(apply(node)).isSameAs(node));
            assertThat(ctx.isEmpty()).isTrue();
        }
    }

    // =========================================================================
    // evalConstant — the constant-comparison truth table
    // =========================================================================

    /**
     * Every operator against every ordering outcome, for each literal type it accepts.
     *
     * <p>{@code evalConstant} is the decision behind {@code PRED-001..003}: a comparison
     * it answers is replaced by that answer, so a wrong arm removes or keeps rows rather
     * than costing an optimisation. The switch mapping an operator to a sign of
     * {@code compareTo} is six arms wide and each has three inputs that matter — less,
     * equal, greater — which is why this is written as a table rather than as the two or
     * three cases a rewrite test happens to need.
     */
    @Nested
    @DisplayName("evalConstant — comparing two literals")
    class ConstantComparison {

        private static Optional<Boolean> eval(Operand left, ComparisonOperator op, Operand right) {
            return PredicateSimplifier.evalConstant(cmp(left, op, right));
        }

        private static Optional<Boolean> numbers(String left, ComparisonOperator op, String right) {
            return eval(num(left), op, num(right));
        }

        @Test
        @DisplayName("every operator, against a smaller, an equal and a larger number")
        void everyOperatorOverNumbers() {
            record Case(ComparisonOperator op, boolean whenLess, boolean whenEqual,
                        boolean whenGreater) { }
            List<Case> table = List.of(
                    new Case(ComparisonOperator.EQUAL,         false, true,  false),
                    new Case(ComparisonOperator.NOT_EQUAL,     true,  false, true),
                    new Case(ComparisonOperator.LESS,          true,  false, false),
                    new Case(ComparisonOperator.LESS_EQUAL,    true,  true,  false),
                    new Case(ComparisonOperator.GREATER,       false, false, true),
                    new Case(ComparisonOperator.GREATER_EQUAL, false, true,  true));
            for (Case c : table) {
                assertThat(numbers("1", c.op(), "2")).as("1 %s 2", c.op()).contains(c.whenLess());
                assertThat(numbers("2", c.op(), "2")).as("2 %s 2", c.op()).contains(c.whenEqual());
                assertThat(numbers("3", c.op(), "2")).as("3 %s 2", c.op()).contains(c.whenGreater());
            }
        }

        @Test
        @DisplayName("numeric comparison is by value, not by spelling")
        void numbersCompareByValue() {
            assertThat(numbers("5.0", ComparisonOperator.EQUAL, "5")).contains(true);
            assertThat(numbers("05", ComparisonOperator.EQUAL, "5")).contains(true);
        }

        @Test
        @DisplayName("an unparseable numeric literal declines rather than throwing")
        void unparseableNumberDeclines() {
            assertThat(numbers("not-a-number", ComparisonOperator.EQUAL, "1")).isEmpty();
        }

        @Test
        @DisplayName("every operator over strings, ordered lexicographically")
        void everyOperatorOverStrings() {
            for (ComparisonOperator op : ComparisonOperator.values()) {
                Optional<Boolean> less = eval(str("a"), op, str("b"));
                Optional<Boolean> equal = eval(str("b"), op, str("b"));
                Optional<Boolean> greater = eval(str("c"), op, str("b"));
                assertThat(less).as("\"a\" %s \"b\"", op).isPresent();
                assertThat(equal).as("\"b\" %s \"b\"", op).isPresent();
                assertThat(greater).as("\"c\" %s \"b\"", op).isPresent();
            }
            assertThat(eval(str("a"), ComparisonOperator.LESS,
                    str("b"))).contains(true);
            assertThat(eval(str("b"), ComparisonOperator.GREATER_EQUAL,
                    str("b"))).contains(true);
            assertThat(eval(str("A"), ComparisonOperator.EQUAL,
                    str("a"))).as("case-sensitive").contains(false);
        }

        @Test
        @DisplayName("booleans answer = and ≠, and decline every ordering operator")
        void booleans() {
            var t = new com.darkcollective.relix.ast.BooleanOperand(true);
            var f = new com.darkcollective.relix.ast.BooleanOperand(false);
            assertThat(eval(t, ComparisonOperator.EQUAL, t)).contains(true);
            assertThat(eval(t, ComparisonOperator.EQUAL, f)).contains(false);
            assertThat(eval(t, ComparisonOperator.NOT_EQUAL, f)).contains(true);
            assertThat(eval(t, ComparisonOperator.NOT_EQUAL, t)).contains(false);
            for (ComparisonOperator op : List.of(ComparisonOperator.LESS,
                    ComparisonOperator.LESS_EQUAL, ComparisonOperator.GREATER,
                    ComparisonOperator.GREATER_EQUAL)) {
                assertThat(eval(t, op, f)).as("true %s false", op).isEmpty();
            }
        }

        @Test
        @DisplayName("a literal of one kind against another kind is never folded")
        void mixedKindsDecline() {
            // Each type arm tests its left operand first, so a matching left with a
            // mismatched right is the arm a same-type test never enters.
            var number = num("1");
            var string = str("1");
            var bool = new com.darkcollective.relix.ast.BooleanOperand(true);
            assertThat(eval(number, ComparisonOperator.EQUAL, string)).isEmpty();
            assertThat(eval(string, ComparisonOperator.EQUAL, number)).isEmpty();
            assertThat(eval(bool, ComparisonOperator.EQUAL, number)).isEmpty();
            assertThat(eval(number, ComparisonOperator.EQUAL, bool)).isEmpty();
            assertThat(eval(string, ComparisonOperator.EQUAL, bool)).isEmpty();
        }

        @Test
        @DisplayName("a comparison against a column is not a constant")
        void columnDeclines() {
            assertThat(eval(attr("x"), ComparisonOperator.EQUAL,
                    num("1"))).isEmpty();
        }

        @Test
        @DisplayName("a predicate that is not a comparison is not a constant")
        void nonComparisonDeclines() {
            assertThat(PredicateSimplifier.evalConstant(
                    new com.darkcollective.relix.ast.NullPredicate(
                            attr("x"), true))).isEmpty();
        }
    }

    // =========================================================================
    // Absorption — the constant may be on either branch
    // =========================================================================

    /**
     * {@code FALSE ∧ p} and {@code TRUE ∨ p} are absorbing, and so are their mirrors.
     *
     * <p>Each rule reads {@code isConstant(left, …) || isConstant(right, …)}, so a fixture
     * that always writes the constant first short-circuits and never shows the second
     * branch is inspected. The mirror is not a stylistic variant: predicates arrive in
     * whatever order the user wrote them, and {@code PRED-003}'s normalisation moves
     * operands within a comparison, not conjuncts within an AND.
     */
    @Nested
    @DisplayName("absorption reads both branches")
    class AbsorptionEitherSide {

        private Predicate constantFalse() {
            return PredicateSimplifier.constant(false);
        }

        private Predicate constantTrue() {
            return PredicateSimplifier.constant(true);
        }

        private Predicate ordinary() {
            return cmp(attr("x"), ComparisonOperator.GREATER, num("5"));
        }

        private boolean isFalse(RelNode node) {
            return PredicateSimplifier.evalConstant(((SelectionNode) node).predicate())
                    .filter(v -> !v).isPresent();
        }

        @Test
        @DisplayName("FALSE ∧ p and p ∧ FALSE both collapse to FALSE")
        void andAbsorbsFromEitherBranch() {
            for (Predicate p : List.of(
                    and(constantFalse(), ordinary()),
                    and(ordinary(), constantFalse()))) {
                var node = apply(sel(p));
                assertThat(isFalse(node)).as("%s", p).isTrue();
            }
        }

        @Test
        @DisplayName("TRUE ∨ p and p ∨ TRUE both collapse to TRUE")
        void orAbsorbsFromEitherBranch() {
            for (Predicate p : List.of(
                    or(constantTrue(), ordinary()),
                    or(ordinary(), constantTrue()))) {
                var node = apply(sel(p));
                assertThat(PredicateSimplifier.evalConstant(((SelectionNode) node).predicate()))
                        .as("%s", p).contains(true);
            }
        }

        @Test
        @DisplayName("a rebuild fires when either branch changed, not only the first")
        void rebuildsWhenEitherBranchChanged() {
            // The rebuild test is `newLeft != left || newRight != right`. A simplification
            // confined to the *second* conjunct must still produce a new node, or the
            // rewrite is computed and then thrown away.
            Predicate untouched = cmp(attr("y"), ComparisonOperator.LESS, num("9"));
            Predicate normalisable = cmp(num("5"), ComparisonOperator.GREATER, attr("x"));

            var andRight = (SelectionNode) apply(sel(and(untouched, normalisable)));
            assertThat(andRight.predicate()).isNotEqualTo(and(untouched, normalisable));

            var orRight = (SelectionNode) apply(sel(or(untouched, normalisable)));
            assertThat(orRight.predicate()).isNotEqualTo(or(untouched, normalisable));
        }
    }
}
