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
package com.darkcollective.relix.optimizer.internal;

import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.FixpointNode;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RecursiveRefNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Selection-into-FIX pushdown — FIX-001 magic sets (ADR-0021 / #334)")
final class SelectionIntoFixpointPassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() { ctx = new OptimizationContext(); }

    // ─── canonical bill-of-materials FIX ──────────────────────────────────────────
    //   FIX BOM (Contains,
    //     π assembly, sub → part (BOM ⋈ ρ Edge(part, sub) (Contains)))
    //   `assembly` is frozen (carried from the ref through the projection/join);
    //   `part` is produced anew each round (not frozen).

    /** Holder so a test can both build the tree and annotate the ref instance. */
    private record Built(FixpointNode fix, RecursiveRefNode ref) {}

    private static Built bom() {
        RecursiveRefNode ref = recRef("BOM");
        RelNode edge = rename("Edge", List.of("part", "sub"), rel("Contains"));
        RelNode join = naturalJoin(ref, edge);
        RelNode step = project(List.of(
                ProjectedAttribute.simple(attr("assembly")),
                ProjectedAttribute.aliased(attr("sub"), "part")), join);
        FixpointNode fix = fixpoint("BOM", rel("Contains"), step);
        return new Built(fix, ref);
    }

    /** Annotates the recursive-ref instance with the (assembly, part) base schema. */
    private static SchemaAnnotations schemasFor(Built b) {
        var map = new IdentityHashMap<RelNode, Schema>();
        map.put(b.ref(), new Schema(List.of(
                new ColumnDefinition("assembly", ScalarType.STRING),
                new ColumnDefinition("part", ScalarType.STRING))));
        return new SchemaAnnotations(map);
    }

    private RelNode apply(RelNode node, SchemaAnnotations schemas) {
        return SelectionIntoFixpointPass.apply(node, "Q", schemas, ctx);
    }

    private boolean fired() {
        return !ctx.recordsFor(OptimizationCode.FIX_001).isEmpty();
    }

    private static Predicate eq(String col, Operand lit) {
        return cmp(attr(col), ComparisonOperator.EQUAL, lit);
    }

    // =========================================================================
    // FIX-001 — fires on a frozen-column selection
    // =========================================================================

    @Nested
    @DisplayName("FIX-001 — frozen-column σ pushed into the recursion")
    class Fires {

        @Test
        @DisplayName("σ on a frozen column seeds the base and restricts the ref; σ removed")
        void frozenColumnPushed() {
            Built b = bom();
            RelNode result = apply(select(eq("assembly", str("Bike")), b.fix()),
                    schemasFor(b));

            FixpointNode fix = assertThat(result).asNode(FixpointNode.class);

            // base is now σ assembly = "Bike" (Contains)
            assertThat(fix.base()).isNode(SelectionNode.class);
            assertThat(((SelectionNode) fix.base()).predicate()).isEqualTo(eq("assembly", str("Bike")));

            // the recursive ref inside the step is wrapped in the same σ
            assertThat(refIsGuarded(fix.step(), "BOM")).isTrue();

            assertThat(fired()).isTrue();
            assertThat(ctx.recordsFor(OptimizationCode.FIX_001).getFirst().detail())
                    .contains("FIX BOM").contains("frozen-column");
        }

        @Test
        @DisplayName("frozen conjunct pushed, non-frozen conjunct kept as residual σ above")
        void mixedConjunctsSplit() {
            Built b = bom();
            Predicate pred = and(eq("assembly", str("Bike")), eq("part", str("Tube")));
            RelNode result = apply(select(pred, b.fix()), schemasFor(b));

            // residual σ part = "Tube" above a bounded FIX
            SelectionNode residual = assertThat(result).asNode(SelectionNode.class);
            assertThat(residual.predicate()).isEqualTo(eq("part", str("Tube")));
            FixpointNode fix = assertThat(residual.input()).asNode(FixpointNode.class);
            assertThat(((SelectionNode) fix.base()).predicate()).isEqualTo(eq("assembly", str("Bike")));
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("a contiguous σ-chain is peeled and the frozen part pushed")
        void selectionChainPeeled() {
            Built b = bom();
            RelNode chain = select(eq("part", str("Tube")),
                    select(eq("assembly", str("Bike")), b.fix()));
            RelNode result = apply(chain, schemasFor(b));

            assertThat(result).isNode(SelectionNode.class);
            assertThat(((SelectionNode) result).predicate()).isEqualTo(eq("part", str("Tube")));
            assertThat(((SelectionNode) result).input()).isNode(FixpointNode.class);
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("frozenness survives a δ (DISTINCT) in the step")
        void frozenThroughDistinct() {
            RecursiveRefNode ref = recRef("BOM");
            RelNode edge = rename("Edge", List.of("part", "sub"), rel("Contains"));
            RelNode join = naturalJoin(ref, edge);
            RelNode proj = project(List.of(
                    ProjectedAttribute.simple(attr("assembly")),
                    ProjectedAttribute.aliased(attr("sub"), "part")), join);
            RelNode step = distinct(proj);
            FixpointNode fix = fixpoint("BOM", rel("Contains"), step);
            var map = new IdentityHashMap<RelNode, Schema>();
            map.put(ref, new Schema(List.of(
                    new ColumnDefinition("assembly", ScalarType.STRING),
                    new ColumnDefinition("part", ScalarType.STRING))));

            RelNode result = apply(select(eq("assembly", str("Bike")), fix),
                    new SchemaAnnotations(map));

            assertThat(result).isNode(FixpointNode.class);
            assertThat(fired()).isTrue();
        }
    }

    @Nested
    @DisplayName("a nested FIX that rebinds the same name")
    class Shadowing {

        /**
         * The step joins the (frozen) recursive ref against a second recursion that
         * binds {@code BOM} again. The inner binder shadows the outer one inside its own
         * step, so the outer restriction belongs in the inner <em>base</em> — where the
         * outer name is still in scope — and nowhere else. Injecting it into the inner
         * step would restrict a different recursion by a predicate that says nothing
         * about it, and the rows would be wrong rather than merely fewer.
         */
        @Test
        @DisplayName("the outer restriction stops at the inner binder")
        void innerBinderIsNotRestricted() {
            RecursiveRefNode outerRef = recRef("BOM");
            RecursiveRefNode otherRef = recRef("OTHER");
            RecursiveRefNode innerStepRef = recRef("BOM");
            FixpointNode inner = fixpoint("BOM", otherRef, innerStepRef);

            RelNode step = project(List.of(
                    ProjectedAttribute.simple(attr("assembly")),
                    ProjectedAttribute.aliased(attr("sub"), "part")),
                    naturalJoin(outerRef, inner));
            FixpointNode fix = fixpoint("BOM", rel("Contains"), step);

            var map = new IdentityHashMap<RelNode, Schema>();
            map.put(outerRef, new Schema(List.of(
                    new ColumnDefinition("assembly", ScalarType.STRING),
                    new ColumnDefinition("part", ScalarType.STRING))));

            RelNode result = apply(select(eq("assembly", str("Bike")), fix),
                    new SchemaAnnotations(map));

            FixpointNode rewritten = assertThat(result).asNode(FixpointNode.class);
            assertThat(fired())
                    .as("the outer ref's path is still frozen, so the rule applies")
                    .isTrue();
            var joined = (com.darkcollective.relix.ast.NaturalJoinNode)
                    ((ProjectionNode) rewritten.step()).input();

            assertThat(joined.left())
                    .as("the outer ref carries the restriction")
                    .isNode(SelectionNode.class);
            assertThat(((SelectionNode) joined.left()).input()).isSameAs(outerRef);

            var innerAfter = (FixpointNode) joined.right();
            assertThat(innerAfter.step())
                    .as("the inner recursion's own ref is untouched — a different relation")
                    .isSameAs(innerStepRef);
            assertThat(innerAfter.base())
                    .as("the inner base names another binder, so there was nothing to restrict")
                    .isSameAs(otherRef);
        }

        @Test
        @DisplayName("a nested FIX under a different name does not hide the ref inside it")
        void differentBinderIsTransparent() {
            // Shadowing is about the name, not about nesting. A FIX binding something
            // else leaves the outer name in scope throughout, so the ref in its step
            // still counts — and counting it puts the outer ref on *both* sides of the
            // join, which is non-linear recursion. The rule declines rather than
            // restricting one occurrence and not the other.
            RecursiveRefNode outerRef = recRef("BOM");
            FixpointNode inner = fixpoint("OTHER", rel("Seed"), recRef("BOM"));

            RelNode step = project(List.of(
                    ProjectedAttribute.simple(attr("assembly")),
                    ProjectedAttribute.aliased(attr("sub"), "part")),
                    naturalJoin(outerRef, inner));
            FixpointNode fix = fixpoint("BOM", rel("Contains"), step);

            var map = new IdentityHashMap<RelNode, Schema>();
            map.put(outerRef, new Schema(List.of(
                    new ColumnDefinition("assembly", ScalarType.STRING),
                    new ColumnDefinition("part", ScalarType.STRING))));

            RelNode result = apply(select(eq("assembly", str("Bike")), fix),
                    new SchemaAnnotations(map));

            assertThat(fired())
                    .as("the ref is on both sides of the join, so the recursion is not linear")
                    .isFalse();
            assertThat(result)
                    .as("nothing is rewritten — the σ stays where the author put it")
                    .isNode(SelectionNode.class);
            assertThat(((SelectionNode) result).input()).isSameAs(fix);
        }
    }

    // =========================================================================
    // FIX-001 — does NOT fire (correctness-by-construction no-ops)
    // =========================================================================

    @Nested
    @DisplayName("FIX-001 — left untouched when nothing is pushable")
    class NoOp {

        @Test
        @DisplayName("σ on a non-frozen (produced) column is not pushed — tree unchanged")
        void nonFrozenColumnLeftAlone() {
            Built b = bom();
            RelNode input = select(eq("part", str("Tube")), b.fix());
            RelNode result = apply(input, schemasFor(b));

            assertThat(result).isSameAs(input);   // reference identity preserved
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a qualified attribute reference is not pushed (could re-bind)")
        void qualifiedReferenceNotPushed() {
            Built b = bom();
            Predicate pred = cmp(
                    attr("Explosion.assembly"), ComparisonOperator.EQUAL, str("Bike"));
            RelNode input = select(pred, b.fix());
            RelNode result = apply(input, schemasFor(b));

            assertThat(result).isSameAs(input);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a conjunct containing a function call is not pushed (determinism guard)")
        void functionCallNotPushed() {
            Built b = bom();
            Predicate pred = cmp(
                    func("Len",attr("assembly")),
                    ComparisonOperator.EQUAL, num("4"));
            RelNode input = select(pred, b.fix());
            RelNode result = apply(input, schemasFor(b));

            assertThat(result).isSameAs(input);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a bare FIX with no selection above it is unchanged")
        void bareFixUnchanged() {
            Built b = bom();
            RelNode result = apply(b.fix(), schemasFor(b));

            assertThat(result).isSameAs(b.fix());
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("frozen analysis bails (no event) when join-side schema is unavailable")
        void missingSchemaBails() {
            Built b = bom();
            // No annotations → frozenThroughJoin cannot confirm the column is on the ref side.
            RelNode result = apply(select(eq("assembly", str("Bike")), b.fix()),
                    SchemaAnnotations.empty());

            assertThat(result).isNode(SelectionNode.class);   // unchanged σ over FIX
            assertThat(fired()).isFalse();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** True if every recursive ref to {@code name} in {@code node} is wrapped in a σ. */
    private static boolean refIsGuarded(RelNode node, String name) {
        if (node instanceof SelectionNode s && s.input() instanceof RecursiveRefNode r
                && r.name().equals(name)) {
            return true;
        }
        if (node instanceof RecursiveRefNode r && r.name().equals(name)) {
            return false;   // a bare, unguarded ref
        }
        return node.children().stream().allMatch(c -> refIsGuarded(c, name));
    }

    // =========================================================================
    // Frozen-ness through ρ (rename) in the step — remapping + bail-outs
    // =========================================================================

    @Nested
    @DisplayName("frozen analysis through a rename in the step")
    class ThroughRename {

        /** FIX T (Base, <step around the ref>) with the ref annotated (a, b). */
        private Built fixWithStep(java.util.function.Function<RecursiveRefNode, RelNode> step) {
            RecursiveRefNode ref = recRef("T");
            return new Built(fixpoint("T", rel("Base"), step.apply(ref)), ref);
        }

        private SchemaAnnotations refSchema(Built b) {
            var map = new IdentityHashMap<RelNode, Schema>();
            map.put(b.ref(), new Schema(List.of(
                    new ColumnDefinition("a", ScalarType.STRING),
                    new ColumnDefinition("b", ScalarType.STRING))));
            return new SchemaAnnotations(map);
        }

        @Test
        @DisplayName("relation-only rename preserves column names — σ still pushes")
        void relationOnlyRename() {
            Built b = fixWithStep(ref -> rename("S", List.of(), ref));
            apply(select(eq("a", str("x")), b.fix()), refSchema(b));
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("column rename remaps the σ column back to the input name")
        void columnRenameRemaps() {
            Built b = fixWithStep(ref -> rename("S", List.of("a2", "b2"), ref));
            apply(select(eq("a2", str("x")), b.fix()), refSchema(b));
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("σ column absent from the rename list — not pushed")
        void columnNotInRename() {
            Built b = fixWithStep(ref -> rename("S", List.of("a2", "b2"), ref));
            apply(select(eq("phantom", str("x")), b.fix()), refSchema(b));
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("rename over an unannotated input — analysis bails, not pushed")
        void renameWithoutInputSchema() {
            Built b = fixWithStep(ref -> rename("S", List.of("a2", "b2"), ref));
            apply(select(eq("a2", str("x")), b.fix()), SchemaAnnotations.empty());
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("rename wider than the input schema — analysis bails, not pushed")
        void renameArityBeyondSchema() {
            Built b = fixWithStep(ref -> rename("S", List.of("a2", "b2", "c2"), ref));
            apply(select(eq("c2", str("x")), b.fix()), refSchema(b));
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("σ in the step preserves frozen-ness")
        void frozenThroughSelection() {
            Built b = fixWithStep(ref -> select(eq("b", str("keep")), ref));
            apply(select(eq("a", str("x")), b.fix()), refSchema(b));
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("an unrecognised step operator is reported not-frozen — not pushed")
        void unknownStepOperator() {
            Built b = fixWithStep(ref -> distinct(
                    new com.darkcollective.relix.ast.LimitNode(java.util.Optional.empty(), 1L, ref)));
            apply(select(eq("a", str("x")), b.fix()), refSchema(b));
            assertThat(fired()).isFalse();
        }
    }

    // =========================================================================
    // Determinism guard — a function call anywhere in the predicate blocks it
    // =========================================================================

    @Nested
    @DisplayName("determinism guard — function calls in every predicate/operand shape")
    class FunctionCallGuard {

        private FunctionCall now() {
            return func("NOW");
        }

        private void assertBlocked(Predicate pred) {
            Built b = bom();
            RelNode node = select(pred, b.fix());
            assertThat(apply(node, schemasFor(b))).isSameAs(node);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("call under AND blocks its own conjunct, not the whole predicate")
        void underAnd() {
            // The conjunctive form is the one the pass meets most, since a σ chain is
            // split into conjuncts before it gets here — and it is the arm no test
            // reached. It is also the only shape where the gate is not all-or-nothing:
            // the volatile conjunct stays above as a residual while the deterministic one
            // is still pushed, so blocking the whole predicate would give up a rewrite
            // that is perfectly sound.
            Built b = bom();
            Predicate volatilePart = cmp(attr("assembly"), ComparisonOperator.EQUAL, now());
            RelNode result = apply(
                    select(and(eq("assembly", str("Bike")), volatilePart), b.fix()),
                    schemasFor(b));

            SelectionNode residual = assertThat(result).asNode(SelectionNode.class);
            assertThat(residual.predicate())
                    .as("the volatile conjunct is what stays above")
                    .isEqualTo(volatilePart);
            FixpointNode fix = assertThat(residual.input()).asNode(FixpointNode.class);
            assertThat(((SelectionNode) fix.base()).predicate())
                    .isEqualTo(eq("assembly", str("Bike")));
            assertThat(fired()).isTrue();
        }

        @Test @DisplayName("call under OR")
        void underOr() {
            assertBlocked(new com.darkcollective.relix.ast.OrPredicate(
                    eq("assembly", str("Bike")),
                    cmp(attr("assembly"),
                            ComparisonOperator.EQUAL, now())));
        }

        @Test @DisplayName("call under NOT")
        void underNot() {
            assertBlocked(new com.darkcollective.relix.ast.NotPredicate(
                    cmp(attr("assembly"),
                            ComparisonOperator.EQUAL, now())));
        }

        @Test @DisplayName("call inside a NULL predicate operand")
        void insideNullPredicate() {
            assertBlocked(new com.darkcollective.relix.ast.NullPredicate(now(), true));
        }

        @Test @DisplayName("call inside an ∈ set expression")
        void insideElementOf() {
            assertBlocked(new com.darkcollective.relix.ast.ElementOfPredicate(
                    attr("assembly"),
                    new com.darkcollective.relix.ast.SetLiteralOperand(List.of(now())),
                    false));
        }

        @Test @DisplayName("call inside a LIKE pattern")
        void insidePattern() {
            assertBlocked(new com.darkcollective.relix.ast.PatternPredicate(
                    attr("assembly"), now(), false));
        }

        @Test @DisplayName("call nested in arithmetic and unary operands")
        void nestedInArithmetic() {
            assertBlocked(cmp(
                    new com.darkcollective.relix.ast.BinaryArithmeticExpression(
                            new com.darkcollective.relix.ast.UnaryOperand(now()),
                            com.darkcollective.relix.ast.ArithmeticOperator.PLUS,
                            num("1")),
                    ComparisonOperator.GREATER, num("0")));
        }

        @Test @DisplayName("the same forms, with the call in their OTHER operand")
        void theOtherSideOfEveryTwoArmedForm() {
            // Each of these walks its two parts with `left || right`, so putting the call
            // in the first part every time leaves the second arm unvisited — and a guard
            // that reads only one side lets a non-deterministic predicate into the loop.
            assertBlocked(new com.darkcollective.relix.ast.OrPredicate(
                    cmp(attr("assembly"),
                            ComparisonOperator.EQUAL, now()),
                    eq("assembly", str("Bike"))));
            assertBlocked(new com.darkcollective.relix.ast.ElementOfPredicate(
                    now(),
                    new com.darkcollective.relix.ast.SetLiteralOperand(List.of(str("Bike"))),
                    false));
            assertBlocked(new com.darkcollective.relix.ast.PatternPredicate(
                    now(), str("B%"), false));
            assertBlocked(cmp(
                    new com.darkcollective.relix.ast.BinaryArithmeticExpression(
                            num("1"),
                            com.darkcollective.relix.ast.ArithmeticOperator.PLUS,
                            now()),
                    ComparisonOperator.GREATER, num("0")));
        }

        @Test @DisplayName("call inside a nested value construction")
        void insideNestedConstructions() {
            assertBlocked(cmp(
                    new com.darkcollective.relix.ast.StructConstruction(List.of(
                            new com.darkcollective.relix.ast.StructConstruction.Field("at", now()))),
                    ComparisonOperator.EQUAL, attr("assembly")));
            assertBlocked(cmp(
                    new com.darkcollective.relix.ast.ArrayConstruction(List.of(now())),
                    ComparisonOperator.EQUAL, attr("assembly")));
        }

        @Test @DisplayName("every two-armed form is call-free when NEITHER side has one")
        void callFreeInEveryTwoArmedForm() {
            // The complement of the two tests above: with the call removed from both
            // sides the || must fall through to false, or the guard would block every
            // predicate of that shape and FIX-001 would never fire on one.
            List<Predicate> callFree = List.of(
                    new com.darkcollective.relix.ast.OrPredicate(
                            eq("assembly", str("Bike")), eq("assembly", str("Trike"))),
                    new com.darkcollective.relix.ast.ElementOfPredicate(
                            attr("assembly"),
                            new com.darkcollective.relix.ast.SetLiteralOperand(List.of(str("Bike"))),
                            false),
                    new com.darkcollective.relix.ast.PatternPredicate(
                            attr("assembly"), str("B%"), false),
                    cmp(
                            new com.darkcollective.relix.ast.BinaryArithmeticExpression(
                                    num("1"),
                                    com.darkcollective.relix.ast.ArithmeticOperator.PLUS,
                                    num("2")),
                            ComparisonOperator.GREATER, num("0")));

            for (Predicate p : callFree) {
                ctx = new OptimizationContext();
                Built b = bom();
                // Conjoined with a pushable equality: the guard is asked about the whole
                // conjunct list, so a call-free companion is what makes the answer visible.
                RelNode node = select(
                        and(eq("assembly", str("Bike")), p), b.fix());
                apply(node, schemasFor(b));
                assertThat(fired())
                        .as("%s is call-free and must not block the push",
                                p.getClass().getSimpleName())
                        .isTrue();
            }
        }

        @Test @DisplayName("call-free AND of comparisons is NOT blocked by the guard")
        void callFreeNotBlocked() {
            Built b = bom();
            RelNode node = select(and(
                    eq("assembly", str("Bike")), eq("assembly", str("Trike"))), b.fix());
            apply(node, schemasFor(b));
            assertThat(fired()).isTrue();
        }
    }
}
