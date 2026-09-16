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

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.AggregationNode;
import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.AntiJoinNode;
import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.DifferenceNode;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.GroupingKey;
import com.darkcollective.relix.ast.IntersectionNode;
import com.darkcollective.relix.ast.LeftOuterJoinNode;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OuterUnionNode;
import com.darkcollective.relix.ast.PairwiseUniversalNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.RightOuterJoinNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SemiJoinNode;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.SymmetricDifferenceNode;
import com.darkcollective.relix.ast.UnionAllNode;
import com.darkcollective.relix.ast.UnionNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("SelectionPushdownPass — SEL-003..009")
final class SelectionPushdownPassTest {

    private static Schema schemaOf(String... cols) {
        return new Schema(List.of(cols).stream()
                .map(c -> new ColumnDefinition(c, ScalarType.NUMBER))
                .toList());
    }

    private final RelationNode left  = rel("L");
    private final RelationNode right = rel("R");
    /** A relation with no schema annotation — every schema-dependent rule must bail on it. */
    private final RelationNode unknown = rel("Unknown");
    private final Schema leftSchema  = schemaOf("l_id", "l_val");
    private final Schema rightSchema = schemaOf("r_id", "r_val");
    /** Two relations that share a column name, so a predicate on it belongs to neither side. */
    private final RelationNode sharedL = rel("SL");
    private final RelationNode sharedR = rel("SR");
    private final Schema sharedSchema = schemaOf("id");

    private OptimizationContext ctx;
    private SchemaAnnotations schemas;

    @BeforeEach
    void setUp() {
        ctx = new OptimizationContext();
        schemas = new SchemaAnnotations(Map.of(
                left, leftSchema, right, rightSchema,
                sharedL, sharedSchema, sharedR, sharedSchema));
    }

    private RelNode apply(RelNode node) {
        return SelectionPushdownPass.apply(node, "Q", schemas, ctx);
    }

    private static Predicate on(String col) {
        return cmp(attr(col), ComparisonOperator.GREATER, num("0"));
    }

    private static SelectionNode sel(Predicate p, RelNode input) {
        return AstBuilders.select(p, input);
    }

    // =========================================================================
    // SEL-003 — below projection
    // =========================================================================

    @Nested
    @DisplayName("SEL-003 — push below projection")
    class BelowProjection {

        @Test @DisplayName("pushes when every predicate column exists in the projection input")
        void pushes() {
            var proj = project(
                    List.of(ProjectedAttribute.simple(attr("l_id"))), left);
            var node = sel(on("l_id"), proj);

            RelNode result = apply(node);

            assertThat(result).isNode(ProjectionNode.class);
            assertThat(((ProjectionNode) result).input()).isNode(SelectionNode.class);
            assertThat(ctx).fired(OptimizationCode.SEL_003, 1);
        }

        @Test @DisplayName("blocked when the projection input has no schema annotation")
        void blockedWithoutSchema() {
            var unknown = rel("Unknown");
            var proj = project(
                    List.of(ProjectedAttribute.simple(attr("l_id"))), unknown);
            var node = sel(on("l_id"), proj);
            assertThat(apply(node)).isSameAs(node);
        }

        @Test @DisplayName("blocked when a computed alias shadows a predicate column")
        void blockedByComputedAlias() {
            // π l_val * 2 → l_id (L): the predicate's l_id refers to the alias,
            // which is NOT a passthrough of the stored column.
            var computed = ProjectedAttribute.aliased(
                    arith(
                            attr("l_val"), ArithmeticOperator.MULTIPLY, num("2")),
                    "l_id");
            var proj = project(List.of(computed), left);
            var node = sel(on("l_id"), proj);
            assertThat(apply(node)).isSameAs(node);
        }

        @Test @DisplayName("an alias the predicate never mentions does not block the push")
        void unrelatedAliasIgnored() {
            // π l_val → other (L) with a predicate on l_id: the alias cannot shadow
            // a column the predicate does not name, so the shadowing check passes.
            var unrelated = ProjectedAttribute.aliased(attr("l_val"), "other");
            var proj = project(List.of(unrelated), left);
            var node = sel(on("l_id"), proj);
            assertThat(apply(node)).isNode(ProjectionNode.class);
            assertThat(ctx).fired(OptimizationCode.SEL_003, 1);
        }

        @Test @DisplayName("blocked when an alias renames a different stored column")
        void blockedByRenamingAlias() {
            // π l_val → l_id (L): the expression IS a bare attribute, but not the
            // one the alias names, so the predicate's l_id is not a passthrough.
            var renaming = ProjectedAttribute.aliased(attr("l_val"), "l_id");
            var proj = project(List.of(renaming), left);
            var node = sel(on("l_id"), proj);
            assertThat(apply(node)).isSameAs(node);
        }

        @Test @DisplayName("passthrough alias of the same name does not block the push")
        void passthroughAliasAllowed() {
            var passthrough = ProjectedAttribute.aliased(attr("l_id"), "l_id");
            var proj = project(List.of(passthrough), left);
            var node = sel(on("l_id"), proj);
            assertThat(apply(node)).isNode(ProjectionNode.class);
            assertThat(ctx).fired(OptimizationCode.SEL_003, 1);
        }

        @Test @DisplayName("blocked when the predicate references a column absent from the input")
        void blockedByUnknownColumn() {
            var proj = project(
                    List.of(ProjectedAttribute.simple(attr("l_id"))), left);
            var node = sel(on("phantom"), proj);
            assertThat(apply(node)).isSameAs(node);
        }
    }

    // =========================================================================
    // SEL-005 — a schema-on-read input, settled by its own qualifier (#972)
    // =========================================================================

    @Nested
    @DisplayName("SEL-005 — a schema-on-read input's qualifier (#972)")
    class OpenInputQualifier {

        @Test @DisplayName("σ Docs.x over declared ⨝ Docs is pushed into Docs")
        void pushedIntoTheOpenInput() {
            var docs = rel("Docs");
            var annotated = new SchemaAnnotations(Map.of(
                    left, leftSchema, docs, Schema.open()));
            var node = sel(on("Docs.x"), join(left, docs, on("l_id")));

            RelNode result = SelectionPushdownPass.apply(node, "Q", annotated, ctx);

            assertThat(result).isNode(ThetaJoinNode.class)
                    .right().isNode(SelectionNode.class).input().isRelation("Docs");
            assertThat(ctx).fired(OptimizationCode.SEL_005, 1);
        }

        @Test @DisplayName("σ D.x over declared ⨝ ρ D (Docs) reaches Docs with its qualifier dropped")
        void pushedThroughARenamedOpenInput() {
            var docs = rel("Docs");
            var renamed = rename("D", List.of(), docs);
            var annotated = new SchemaAnnotations(Map.of(
                    left, leftSchema, docs, Schema.open(), renamed, Schema.open()));
            var node = sel(on("D.x"), join(left, renamed, on("l_id")));

            RelNode result = SelectionPushdownPass.apply(node, "Q", annotated, ctx);

            assertThat(result).right().isNode(RenameNode.class)
                    .input().isEquivalentTo(sel(on("x"), docs));
        }
    }

    // =========================================================================
    // SEL-004 — below rename (attribute references rewritten)
    // =========================================================================

    @Nested
    @DisplayName("SEL-004 — push below rename")
    class BelowRename {

        @Test @DisplayName("relation-only rename: predicate pushed through unchanged")
        void relationOnlyRename() {
            var rename = rename("Renamed", List.of(), left);
            var node = sel(on("l_id"), rename);

            RelNode result = apply(node);

            assertThat(result).isNode(RenameNode.class);
            var inner = (SelectionNode) ((RenameNode) result).input();
            assertThat(inner.predicate()).isSameAs(node.predicate());
            assertThat(ctx).fired(OptimizationCode.SEL_004, 1);
        }

        @Test @DisplayName("relation-only rename: the rename's own qualifier is dropped on the way down (#971)")
        void relationOnlyRenameDropsItsQualifier() {
            // Beneath ρ Renamed nothing answers to `Renamed`. A declared row forgave the
            // stale qualifier; a schema-on-read row reads it as a path and finds nothing.
            var node = sel(and(on("Renamed.l_id"), on("RENAMED.l_val")),
                    rename("Renamed", List.of(), left));

            RelNode result = apply(node);

            assertThat(result).isNode(RenameNode.class)
                    .input().isNode(SelectionNode.class)
                    .isEquivalentTo(sel(and(on("l_id"), on("l_val")), left));
            assertThat(ctx).fired(OptimizationCode.SEL_004, 1);
        }

        @Test @DisplayName("relation-only rename: any other qualifier is left as written")
        void relationOnlyRenameKeepsOtherQualifiers() {
            // `Renamedx.` is longer; `Xenamed.` is the same length and differs only in
            // its letters.
            var node = sel(and(on("L.l_id"), and(on("Renamedx.l_val"), on("Xenamed.l_id"))),
                    rename("Renamed", List.of(), left));

            RelNode result = apply(node);

            assertThat(result).input().isEquivalentTo(
                    sel(and(on("L.l_id"), and(on("Renamedx.l_val"), on("Xenamed.l_id"))), left));
        }

        @Test @DisplayName("full column rename: every attribute reference maps back to the old name")
        void fullColumnRename() {
            // ρ Renamed(new_id, new_val) (L) — new_id→l_id, new_val→l_val.
            var rename = rename("Renamed", List.of("new_id", "new_val"), left);
            var node = sel(on("new_id"), rename);

            RelNode result = apply(node);

            var inner = (SelectionNode) ((RenameNode) result).input();
            var cmp = (ComparisonPredicate) inner.predicate();
            assertThat(((AttributeOperand) cmp.left()).name()).isEqualTo("l_id");
            assertThat(ctx).fired(OptimizationCode.SEL_004, 1);
        }

        @Test @DisplayName("rewrite reaches every predicate and operand form")
        void rewritesAllPredicateAndOperandForms() {
            var rename = rename("Renamed", List.of("new_id", "new_val"), left);
            // (NOT new_id > 0 OR new_val IS NULL) AND
            //   (Abs(-new_id) ∈ {new_val + 1}) AND new_id LIKE '%1%'
            Operand arith = arith(
                    attr("new_val"), ArithmeticOperator.PLUS, num("1"));
            Predicate pred = and(
                    and(
                            or(
                                    not(on("new_id")),
                                    nullPred(attr("new_val"), true)),
                            elementOf(
                                    func("Abs",unary(attr("new_id"))),
                                    set(arith))),
                    like(attr("new_id"), str("%1%")));
            var node = sel(pred, rename);

            RelNode result = apply(node);

            assertThat(ctx).fired(OptimizationCode.SEL_004, 1);
            var inner = (SelectionNode) ((RenameNode) result).input();
            // No new_* name may survive the rewrite anywhere in the tree.
            assertThat(inner.predicate().toString()).doesNotContain("new_id")
                                                    .doesNotContain("new_val");
        }

        @Test @DisplayName("blocked when the rename arity mismatches the input schema")
        void blockedByArityMismatch() {
            var rename = rename("Renamed", List.of("only_one"), left);
            var node = sel(on("only_one"), rename);
            assertThat(apply(node)).isSameAs(node);
        }

        @Test @DisplayName("blocked when a predicate column is not in the mapping")
        void blockedByUnmappedColumn() {
            var rename = rename("Renamed", List.of("new_id", "new_val"), left);
            var node = sel(on("phantom"), rename);
            assertThat(apply(node)).isSameAs(node);
        }

        @Test @DisplayName("blocked when the rename input has no schema annotation")
        void blockedWithoutSchema() {
            var rename = rename("Renamed", List.of("a", "b"),
                    rel("Unknown"));
            var node = sel(on("a"), rename);
            assertThat(apply(node)).isSameAs(node);
        }

        @Test @DisplayName("relation-only rename blocked when a predicate column is not in the input")
        void relationOnlyRenameBlockedByPhantomColumn() {
            // No column mapping to consult, so the columns must exist as written.
            var rename = rename("Renamed", List.of(), left);
            var node = sel(on("phantom"), rename);
            assertThat(apply(node)).isSameAs(node);
        }

        @Test @DisplayName("pair form: a listed column maps back to its old name")
        void pairFormRename() {
            // ρ Renamed(l_id → new_id) (L)
            var rename = pairRename(new RenameNode.RenamePair("l_id", "new_id"));
            var node = sel(on("new_id"), rename);

            RelNode result = apply(node);

            var inner = (SelectionNode) ((RenameNode) result).input();
            var cmp = (ComparisonPredicate) inner.predicate();
            assertThat(((AttributeOperand) cmp.left()).name()).isEqualTo("l_id");
            assertThat(ctx).fired(OptimizationCode.SEL_004, 1);
        }

        @Test @DisplayName("pair form: an unlisted column keeps its own name")
        void pairFormLeavesUnlistedColumnsAlone() {
            // Only l_id is renamed, so l_val passes through as itself — the pair
            // form renames the columns it lists and maps the rest to themselves.
            var rename = pairRename(new RenameNode.RenamePair("l_id", "new_id"));
            var node = sel(on("l_val"), rename);

            RelNode result = apply(node);

            var inner = (SelectionNode) ((RenameNode) result).input();
            var cmp = (ComparisonPredicate) inner.predicate();
            assertThat(((AttributeOperand) cmp.left()).name()).isEqualTo("l_val");
            assertThat(ctx).fired(OptimizationCode.SEL_004, 1);
        }

        private RenameNode pairRename(RenameNode.RenamePair... pairs) {
            return rename(Optional.of("Renamed"), List.of(), List.of(pairs),
                    left);
        }
    }

    // =========================================================================
    // SEL-004 — the rewriter's "nothing changed" half
    // =========================================================================

    @Nested
    @DisplayName("SEL-004 — sub-expressions with no attribute are reused, not rebuilt")
    class RenameRewriteReuse {

        // The rewriter returns the *same instance* when a sub-expression holds no
        // attribute to rename. That is not just an allocation nicety: the enclosing
        // node's "did anything change?" test is an identity comparison, so a rebuild
        // that returned an equal-but-new instance would propagate a spurious change
        // all the way up. Every form needs a constant-only case to prove it.

        private final RenameNode rename =
                rename("Renamed", List.of("new_id", "new_val"), left);

        private Predicate pushedPredicate(Predicate pred) {
            RelNode result = apply(sel(pred, rename));
            assertThat(ctx).fired(OptimizationCode.SEL_004, 1);
            return ((SelectionNode) ((RenameNode) result).input()).predicate();
        }

        private static ComparisonPredicate constCmp() {
            return cmp(num("1"), ComparisonOperator.EQUAL, num("2"));
        }

        @Test @DisplayName("a constant-only conjunct survives beside a rewritten one")
        void constantConjunctsAreReused() {
            // Each branch pairs an untouched left with a rewritten right, so the
            // enclosing node sees "left same, right changed" — the case a
            // predicate built only from attributes never produces.
            Predicate changed = cmp(
                    num("1"), ComparisonOperator.LESS, attr("new_id"));
            Predicate pred = and(
                    and(constCmp(), constCmp()),          // nothing changes
                    and(
                            or(constCmp(), constCmp()),   // nothing changes
                            or(constCmp(), changed)));    // right changes

            assertThat(pushedPredicate(pred).toString())
                    .contains("l_id").doesNotContain("new_id");
        }

        @Test @DisplayName("constant ¬ / IS NULL / ∈ / LIKE are reused")
        void constantUnaryFormsAreReused() {
            Predicate pred = and(
                    and(
                            not(constCmp()),
                            nullPred(num("1"), true)),
                    and(
                            // element constant, set rewritten
                            and(elementOf(num("1"), set(num("2"))),
                                    elementOf(num("1"), set(attr("new_val")))),
                            // operand constant, pattern rewritten
                            and(like(str("a"), str("b")),
                                    like(str("a"), attr("new_id")))));

            assertThat(pushedPredicate(pred).toString())
                    .contains("l_id").contains("l_val")
                    .doesNotContain("new_id").doesNotContain("new_val");
        }

        @Test @DisplayName("constant arithmetic, calls and unary operands are reused")
        void constantOperandsAreReused() {
            Operand constArith = arith(
                    num("1"), ArithmeticOperator.PLUS, num("2"));
            Operand rightArith = arith(
                    num("1"), ArithmeticOperator.PLUS, attr("new_val"));
            Predicate pred = and(
                    and(
                            cmp(constArith, ComparisonOperator.EQUAL, num("3")),
                            cmp(constArith, ComparisonOperator.EQUAL, rightArith)),
                    and(
                            cmp(
                                    func("Abs",num("1")),
                                    ComparisonOperator.EQUAL, num("1")),
                            cmp(
                                    unary(num("1")),
                                    ComparisonOperator.EQUAL,
                                    unary(attr("new_id")))));

            assertThat(pushedPredicate(pred).toString())
                    .contains("l_id").contains("l_val")
                    .doesNotContain("new_id").doesNotContain("new_val");
        }

        @Test @DisplayName("the rewriter reaches inside struct and array constructions")
        void nestedConstructionsAreRewritten() {
            // NF² operands are not leaves: an attribute inside a struct field or an
            // array element must be renamed like any other.
            Predicate structCmp = cmp(
                    structOf(
                            new StructConstruction.Field("f", num("1"))),
                    ComparisonOperator.EQUAL,
                    structOf(
                            new StructConstruction.Field("f", attr("new_val"))));
            Predicate arrayCmp = cmp(
                    arrayOf(num("1")),
                    ComparisonOperator.EQUAL,
                    arrayOf(attr("new_id")));

            assertThat(pushedPredicate(and(structCmp, arrayCmp)).toString())
                    .contains("l_id").contains("l_val")
                    .doesNotContain("new_id").doesNotContain("new_val");
        }
    }

    // =========================================================================
    // SEL-005 — into joins
    // =========================================================================

    @Nested
    @DisplayName("SEL-005 — push into join inputs")
    class IntoJoins {

        @Test @DisplayName("natural join: left-only predicate goes left")
        void naturalLeft() {
            var node = sel(on("l_id"), naturalJoin(left, right));
            var result = (NaturalJoinNode) apply(node);
            assertThat(result.left()).isNode(SelectionNode.class);
            assertThat(result.right()).isSameAs(right);
            assertThat(ctx).fired(OptimizationCode.SEL_005, 1);
        }

        @Test @DisplayName("natural join: right-only predicate goes right")
        void naturalRight() {
            var node = sel(on("r_val"), naturalJoin(left, right));
            var result = (NaturalJoinNode) apply(node);
            assertThat(result.right()).isNode(SelectionNode.class);
            assertThat(ctx).fired(OptimizationCode.SEL_005, 1);
        }

        @Test @DisplayName("natural join: predicate spanning both sides stays put")
        void naturalSpanningStays() {
            var pred = cmp(attr("l_id"),
                    ComparisonOperator.EQUAL, attr("r_id"));
            var node = sel(pred, naturalJoin(left, right));
            assertThat(apply(node)).isSameAs(node);
        }

        @Test @DisplayName("natural join: no schema annotations — stays put")
        void naturalNoSchemas() {
            var a = rel("A");
            var b = rel("B");
            var node = sel(on("x"), naturalJoin(a, b));
            assertThat(apply(node)).isSameAs(node);
        }

        private Predicate joinCond() {
            return cmp(attr("l_id"),
                    ComparisonOperator.EQUAL, attr("r_id"));
        }

        @Test @DisplayName("theta join: left-only predicate goes left; condition preserved")
        void thetaLeft() {
            var node = sel(on("l_val"), join(left, right, joinCond()));
            var result = (ThetaJoinNode) apply(node);
            assertThat(result.left()).isNode(SelectionNode.class);
            assertThat(result.condition()).isNotNull();
            assertThat(ctx).fired(OptimizationCode.SEL_005, 1);
        }

        @Test @DisplayName("theta join: right-only predicate goes right")
        void thetaRight() {
            var node = sel(on("r_id"), join(left, right, joinCond()));
            var result = (ThetaJoinNode) apply(node);
            assertThat(result.right()).isNode(SelectionNode.class);
        }

        @Test @DisplayName("theta join: spanning predicate stays put")
        void thetaSpanningStays() {
            var pred = cmp(attr("l_id"),
                    ComparisonOperator.GREATER, attr("r_id"));
            var node = sel(pred, join(left, right, joinCond()));
            assertThat(apply(node)).isSameAs(node);
        }

        @Test @DisplayName("left outer join: left-only predicate pushes; right-side predicate must NOT")
        void leftOuter() {
            var pushable = sel(on("l_id"), leftJoin(left, right, joinCond()));
            var pushed = (LeftOuterJoinNode) apply(pushable);
            assertThat(pushed.left()).isNode(SelectionNode.class);

            // A right-side predicate over ⟕ would change null-extension semantics.
            var blocked = sel(on("r_id"), leftJoin(left, right, joinCond()));
            assertThat(apply(blocked)).isSameAs(blocked);
        }

        @Test @DisplayName("right outer join: right-only predicate pushes; left-side predicate must NOT")
        void rightOuter() {
            var pushable = sel(on("r_id"), rightJoin(left, right, joinCond()));
            var pushed = (RightOuterJoinNode) apply(pushable);
            assertThat(pushed.right()).isNode(SelectionNode.class);

            var blocked = sel(on("l_id"), rightJoin(left, right, joinCond()));
            assertThat(apply(blocked)).isSameAs(blocked);
        }

        @Test @DisplayName("semi-join: left-only predicate pushes left")
        void semiJoin() {
            var node = sel(on("l_id"), AstBuilders.semiJoin(left, right, joinCond()));
            var result = (SemiJoinNode) apply(node);
            assertThat(result.left()).isNode(SelectionNode.class);
            assertThat(ctx).fired(OptimizationCode.SEL_005, 1);
        }

        @Test @DisplayName("anti-join: left-only predicate pushes left")
        void antiJoin() {
            var node = sel(on("l_val"), AstBuilders.antiJoin(left, right, joinCond()));
            var result = (AntiJoinNode) apply(node);
            assertThat(result.left()).isNode(SelectionNode.class);
        }

        @Test @DisplayName("pairwise-∀: left-only predicate pushes left")
        void pairwiseUniversal() {
            var node = sel(on("l_id"), AstBuilders.pairwiseUniversal(left, right, joinCond()));
            var result = (PairwiseUniversalNode) apply(node);
            assertThat(result.left()).isNode(SelectionNode.class);
        }

        @Test @DisplayName("semi/anti/∀: predicate not fully on the left stays put")
        void leftOnlyFamilyBlocked() {
            for (RelNode join : List.of(
                    AstBuilders.semiJoin(left, right, joinCond()),
                    AstBuilders.antiJoin(left, right, joinCond()),
                    AstBuilders.pairwiseUniversal(left, right, joinCond()))) {
                var node = sel(on("r_id"), join);
                assertThat(apply(node)).isSameAs(node);
            }
        }

        @Test @DisplayName("natural join: an unannotated input on either side blocks the push")
        void naturalJoinBlockedWithoutSchema() {
            // Which side is missing does not matter — the rule needs both schemas to
            // decide the side, so either gap stops it.
            assertUnchanged(naturalJoin(unknown, right));
            assertUnchanged(naturalJoin(left, unknown));
        }

        @Test @DisplayName("theta join: an unannotated input on either side blocks the push")
        void thetaJoinBlockedWithoutSchema() {
            assertUnchanged(join(unknown, right, joinCond()));
            assertUnchanged(join(left, unknown, joinCond()));
        }

        @Test @DisplayName("a column both sides carry belongs to neither — no push")
        void ambiguousColumnPushesNowhere() {
            // SL and SR both have `id`, so the predicate is satisfiable from either
            // side. "All left" is true, but so is "any right", and pushing into one
            // side alone would change which rows survive the join.
            var node = sel(on("id"), naturalJoin(sharedL, sharedR));
            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SEL_005);
        }

        @Test @DisplayName("the left-side family blocks when its left input is unannotated")
        void leftSideFamilyBlockedWithoutSchema() {
            assertUnchanged(leftJoin(unknown, right, joinCond()));
            assertUnchanged(AstBuilders.semiJoin(unknown, right, joinCond()));
            assertUnchanged(AstBuilders.antiJoin(unknown, right, joinCond()));
            assertUnchanged(AstBuilders.pairwiseUniversal(unknown, right, joinCond()));
        }

        @Test @DisplayName("a right outer join blocks when its right input is unannotated")
        void rightOuterJoinBlockedWithoutSchema() {
            // ⟖ pushes into its right side only, so it is the right schema it needs.
            assertUnchanged(rightJoin(left, unknown, joinCond()));
        }

        private void assertUnchanged(RelNode join) {
            var node = sel(on("l_id"), join);
            assertThat(apply(node)).isSameAs(node);
        }
    }

    // =========================================================================
    // SEL-006 — into union-all
    // =========================================================================

    @Nested
    @DisplayName("SEL-006 — replicate into union-all branches")
    class IntoUnionAll {

        @Test @DisplayName("selection is copied into both branches")
        void replicated() {
            var node = sel(on("l_id"), unionAll(left, right));
            var result = (UnionAllNode) apply(node);
            assertThat(result.left()).isNode(SelectionNode.class);
            assertThat(result.right()).isNode(SelectionNode.class);
            assertThat(ctx).fired(OptimizationCode.SEL_006, 1);
        }
    }

    // =========================================================================
    // SEL-007 — below aggregation (HAVING → WHERE)
    // =========================================================================

    @Nested
    @DisplayName("SEL-007 — push below aggregation (HAVING → WHERE)")
    class BelowAggregation {

        private AggregationNode agg(List<GroupingKey> keys, List<AggregateFunction> aggs) {
            return groupByKeys(keys, aggs, left);
        }

        private final AggregateFunction total =
                AggregateFunction.aliased(AggregateOperator.SUM, "l_val", "total");

        @Test @DisplayName("σ on a bare grouping key is pushed below γ")
        void pushesOnGroupingKey() {
            var node = sel(on("l_id"), agg(GroupingKey.columns(List.of("l_id")), List.of(total)));

            RelNode result = apply(node);

            assertThat(result).isNode(AggregationNode.class);
            var newAgg = (AggregationNode) result;
            assertThat(newAgg.groupingKeys()).containsExactly(GroupingKey.column("l_id"));
            assertThat(newAgg.input()).isNode(SelectionNode.class);
            assertThat(((SelectionNode) newAgg.input()).input()).isSameAs(left);
            assertThat(ctx).fired(OptimizationCode.SEL_007, 1);
        }

        @Test @DisplayName("σ on an aggregate output stays above γ (it is a real HAVING)")
        void aggregateOutputStaysAbove() {
            var node = sel(on("total"), agg(GroupingKey.columns(List.of("l_id")), List.of(total)));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SEL_007);
        }

        @Test @DisplayName("mixed conjunction: the key part pushes, the aggregate part stays as a residual σ")
        void mixedConjunctionSplits() {
            var pred = and(on("l_id"), on("total"));
            var node = sel(pred, agg(GroupingKey.columns(List.of("l_id")), List.of(total)));

            RelNode result = apply(node);

            assertThat(result).isNode(SelectionNode.class);
            var residual = (SelectionNode) result;
            assertThat(residual.predicate()).isEqualTo(on("total"));
            assertThat(residual.input()).isNode(AggregationNode.class);
            var newAgg = (AggregationNode) residual.input();
            assertThat(newAgg.input()).isNode(SelectionNode.class);
            assertThat(((SelectionNode) newAgg.input()).predicate()).isEqualTo(on("l_id"));
            assertThat(ctx).fired(OptimizationCode.SEL_007, 1);
        }

        @Test @DisplayName("a σ-chain settles: the key conjunct reaches γ even when split above an aggregate conjunct")
        void chainedSelectionsSettle() {
            // The shape SelectionSplitPass produces for `σ l_id > 0 ∧ total > 0 (γ …)`.
            var inner = sel(on("total"), agg(GroupingKey.columns(List.of("l_id")), List.of(total)));
            var node  = sel(on("l_id"), inner);

            RelNode result = apply(node);

            assertThat(result).isNode(SelectionNode.class);
            assertThat(((SelectionNode) result).predicate()).isEqualTo(on("total"));
            var newAgg = (AggregationNode) ((SelectionNode) result).input();
            assertThat(((SelectionNode) newAgg.input()).predicate()).isEqualTo(on("l_id"));
            assertThat(ctx).fired(OptimizationCode.SEL_007, 1);
        }

        @Test @DisplayName("an aliased key is not matched — the output name has no column below γ")
        void aliasedKeyNotPushed() {
            var keys = List.of(GroupingKey.aliased(attr("l_id"), "grp"));
            var node = sel(on("grp"), agg(keys, List.of(total)));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SEL_007);
        }

        @Test @DisplayName("a derived key (YEAR(x) → yr) is not matched")
        void derivedKeyNotPushed() {
            var keys = List.of(GroupingKey.aliased(
                    func("YEAR",attr("l_val")), "yr"));
            var node = sel(on("yr"), agg(keys, List.of(total)));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SEL_007);
        }

        @Test @DisplayName("ungrouped (scalar) γ is left alone — it emits a row even over an empty input")
        void ungroupedAggregationNotPushed() {
            var scalar = groupByKeys(List.of(), List.of(total), left);
            var node   = sel(on("l_id"), scalar);

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SEL_007);
        }

        @Test @DisplayName("a null grouping list (ungrouped form) is left alone")
        void nullGroupingListNotPushed() {
            var scalar = groupBy((List<String>) null, List.of(total), left);
            var node   = sel(on("l_id"), scalar);

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SEL_007);
        }

        @Test @DisplayName("a constant conjunct is not pushed (no attributes to match against a key)")
        void constantPredicateNotPushed() {
            var constant = cmp(num("1"), ComparisonOperator.EQUAL, num("1"));
            var node = sel(constant, agg(GroupingKey.columns(List.of("l_id")), List.of(total)));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SEL_007);
        }

        @Test @DisplayName("a qualified reference to a grouping key is matched on its column part")
        void qualifiedKeyReferenceMatches() {
            var node = sel(on("L.l_id"), agg(GroupingKey.columns(List.of("l_id")), List.of(total)));

            RelNode result = apply(node);

            assertThat(result).isNode(AggregationNode.class);
            assertThat(ctx).fired(OptimizationCode.SEL_007, 1);
        }
    }

    // =========================================================================
    // SEL-008 — below δ / τ
    // =========================================================================

    @Nested
    @DisplayName("SEL-008 — push below distinct / sort")
    class BelowDistinctAndSort {

        @Test @DisplayName("σ p (δ R) → δ (σ p R)")
        void belowDistinct() {
            var node = sel(on("l_id"), distinct(left));

            RelNode result = apply(node);

            assertThat(result).isNode(DistinctNode.class);
            var inner = (SelectionNode) ((DistinctNode) result).input();
            assertThat(inner.predicate()).isEqualTo(on("l_id"));
            assertThat(inner.input()).isSameAs(left);
            assertThat(ctx).fired(OptimizationCode.SEL_008, 1);
        }

        @Test @DisplayName("σ p (τ k R) → τ k (σ p R), sort keys preserved")
        void belowSort() {
            var specs = List.of(desc("l_val"));
            var node  = sel(on("l_id"), sort(specs, left));

            RelNode result = apply(node);

            assertThat(result).isNode(SortNode.class);
            var sort = (SortNode) result;
            assertThat(sort.sortSpecs()).isEqualTo(specs);
            assertThat(((SelectionNode) sort.input()).input()).isSameAs(left);
            assertThat(ctx).fired(OptimizationCode.SEL_008, 1);
        }

        @Test @DisplayName("the push continues below the operator it lands on")
        void pushesFurtherAfterwards() {
            // σ (δ (π (L))) → δ (π (σ (L))): SEL-008 then SEL-003.
            var proj = project(List.of(ProjectedAttribute.simple(attr("l_id"))), left);
            var node = sel(on("l_id"), distinct(proj));

            RelNode result = apply(node);

            var distinct = (DistinctNode) result;
            assertThat(distinct.input()).isNode(ProjectionNode.class);
            assertThat(((ProjectionNode) distinct.input()).input()).isNode(SelectionNode.class);
            assertThat(ctx).fired(OptimizationCode.SEL_008, 1);
            assertThat(ctx).fired(OptimizationCode.SEL_003, 1);
        }
    }

    // =========================================================================
    // SEL-009 — over the set operations
    // =========================================================================

    @Nested
    @DisplayName("SEL-009 — distribute over set operations")
    class OverSetOperations {

        @Test @DisplayName("σ p (A ∪ B) → (σ p A) ∪ (σ p B)")
        void overUnion() {
            var result = apply(sel(on("l_id"), union(left, right)));

            assertThat(result).isNode(UnionNode.class);
            var u = (UnionNode) result;
            assertThat(u.left()).isNode(SelectionNode.class);
            assertThat(u.right()).isNode(SelectionNode.class);
            assertThat(ctx).fired(OptimizationCode.SEL_009, 1);
        }

        @Test @DisplayName("σ p (A ∩ B) → (σ p A) ∩ (σ p B)")
        void overIntersection() {
            var result = apply(sel(on("l_id"), intersection(left, right)));

            assertThat(result).isNode(IntersectionNode.class);
            var i = (IntersectionNode) result;
            assertThat(i.left()).isNode(SelectionNode.class);
            assertThat(i.right()).isNode(SelectionNode.class);
            assertThat(ctx).fired(OptimizationCode.SEL_009, 1);
        }

        @Test @DisplayName("σ p (A ∆ B) → (σ p A) ∆ (σ p B)")
        void overSymmetricDifference() {
            var result = apply(sel(on("l_id"), symmetricDifference(left, right)));

            assertThat(result).isNode(SymmetricDifferenceNode.class);
            var d = (SymmetricDifferenceNode) result;
            assertThat(d.left()).isNode(SelectionNode.class);
            assertThat(d.right()).isNode(SelectionNode.class);
            assertThat(ctx).fired(OptimizationCode.SEL_009, 1);
        }

        @Test @DisplayName("σ p (A − B) → (σ p A) − B: the subtrahend is left alone")
        void overDifferenceLeftOnly() {
            var result = apply(sel(on("l_id"), difference(left, right)));

            assertThat(result).isNode(DifferenceNode.class);
            var d = (DifferenceNode) result;
            assertThat(d.left()).isNode(SelectionNode.class);
            assertThat(d.right()).isSameAs(right);
            assertThat(ctx).fired(OptimizationCode.SEL_009, 1);
        }

        @Test @DisplayName("⊔ (outer union) is excluded — its branches have different schemas")
        void outerUnionExcluded() {
            var node = sel(on("l_id"), outerUnion(left, right));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SEL_009);
        }

        @Test @DisplayName("÷ (division) is excluded — it is not a row filter with respect to σ")
        void divisionExcluded() {
            var node = sel(on("l_id"), division(left, right));

            assertThat(apply(node)).isSameAs(node);
            assertThat(ctx).didNotFire(OptimizationCode.SEL_009);
        }
    }

    // =========================================================================
    // No applicable rule
    // =========================================================================

    @Nested
    @DisplayName("No applicable rule — tree unchanged")
    class NoOp {

        @Test @DisplayName("selection over a bare relation stays put")
        void overRelation() {
            var node = sel(on("l_id"), left);
            assertThat(apply(node)).isSameAs(node);
        }

        @Test @DisplayName("non-selection root recurses without change")
        void nonSelectionRoot() {
            var proj = project(
                    List.of(ProjectedAttribute.simple(attr("l_id"))), left);
            assertThat(apply(proj)).isSameAs(proj);
        }

        @Test @DisplayName("a σ over a σ stays put when the inner one has nowhere to go")
        void stackedSelectionsOverABareRelation() {
            // SelectionSplitPass produces σ a (σ b (X)); the chain rule tries the
            // outer conjunct against X directly, and a bare relation takes neither.
            var node = sel(on("l_id"), sel(on("l_val"), left));
            assertThat(apply(node)).isSameAs(node);
        }

        @Test @DisplayName("a stuck σ is still rebuilt when something below it moved")
        void stuckSelectionRebuiltOverAChangedInput() {
            // ⊔ has no pushdown rule, so the outer σ cannot move. But the σ inside
            // the left branch does push below its ρ, so the outer σ has to be
            // rebuilt over the new input — returning it as-is would drop the rewrite.
            var innerSel = sel(on("new_id"),
                    rename("Renamed", List.of("new_id", "new_val"), left));
            var outer = sel(on("l_id"), outerUnion(innerSel, right));

            RelNode result = apply(outer);

            assertThat(result).isNotSameAs(outer).isNode(SelectionNode.class);
            var union = (OuterUnionNode) ((SelectionNode) result).input();
            assertThat(union.left()).isNode(RenameNode.class);
            assertThat(ctx).fired(OptimizationCode.SEL_004, 1);
        }
    }
}
