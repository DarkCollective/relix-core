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
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.PathNode;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.AstBuilders.attr;
import static com.darkcollective.relix.ast.AstBuilders.cmp;
import static com.darkcollective.relix.ast.AstBuilders.num;
import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.ast.AstBuilders.select;
import static com.darkcollective.relix.ast.AstBuilders.str;
import static com.darkcollective.relix.optimizer.OptimizerAssertions.assertThat;

@DisplayName("Selection-into-PATH pushdown — PATH-001")
final class SelectionIntoPathPassTest {

    private OptimizationContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new OptimizationContext();
    }

    private RelNode apply(RelNode node) {
        return SelectionIntoPathPass.apply(node, "Q", SchemaAnnotations.empty(), ctx);
    }

    private boolean fired() {
        return !ctx.recordsFor(OptimizationCode.PATH_001).isEmpty();
    }

    private static PathNode path() {
        return AstBuilders.path("src", "dst", 1, 3, "depth", rel("Edges"));
    }

    private static Predicate eq(String col, Operand lit) {
        return cmp(attr(col), ComparisonOperator.EQUAL, lit);
    }

    @Nested
    @DisplayName("what is folded")
    final class Folded {

        @Test
        @DisplayName("an equality on the source column becomes the source bound")
        void sourceBound() {
            RelNode out = apply(select(eq("src", str("A")), path()));

            PathNode p = assertThat(out).asNode(PathNode.class);
            assertThat(p.boundSource()).contains(str("A"));
            assertThat(p.boundTarget()).isEmpty();
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("an equality on the target column becomes the target bound")
        void targetBound() {
            PathNode p = assertThat(apply(select(eq("dst", str("D")), path()))).asNode(PathNode.class);

            assertThat(p.boundSource()).isEmpty();
            assertThat(p.boundTarget()).contains(str("D"));
        }

        @Test
        @DisplayName("both endpoints fold from one conjunction")
        void bothFromOneConjunction() {
            RelNode out = apply(select(
                    AstBuilders.and(eq("src", str("A")), eq("dst", str("D"))), path()));

            PathNode p = assertThat(out).asNode(PathNode.class);
            assertThat(p.boundSource()).contains(str("A"));
            assertThat(p.boundTarget()).contains(str("D"));
        }

        @Test
        @DisplayName("both endpoints fold from a split selection chain")
        void bothFromSplitChain() {
            RelNode out = apply(select(eq("src", str("A")), select(eq("dst", str("D")), path())));

            PathNode p = assertThat(out).asNode(PathNode.class);
            assertThat(p.boundSource()).contains(str("A"));
            assertThat(p.boundTarget()).contains(str("D"));
        }

        @Test
        @DisplayName("the hop window, depth column and edge direction are carried through")
        void carriesTheOperatorsOwnFields() {
            // The bound is the only thing that changes; everything that decides what the
            // traversal computes has to survive the rewrite untouched.
            PathNode original = AstBuilders.path("a", "b", true, 2, 5, "hops", rel("Edges"));
            PathNode p = assertThat(apply(select(eq("a", num("1")), original))).asNode(PathNode.class);

            assertThat(p.fromColumn()).isEqualTo("a");
            assertThat(p.toColumn()).isEqualTo("b");
            assertThat(p.undirected()).isTrue();
            assertThat(p.minHops()).isEqualTo(2);
            assertThat(p.maxHops()).isEqualTo(5);
            assertThat(p.depthColumn()).isEqualTo("hops");
        }
    }

    @Nested
    @DisplayName("what stays above")
    final class Residual {

        @Test
        @DisplayName("a predicate on the depth column stays as a selection")
        void depthPredicateIsResidual() {
            RelNode out = apply(select(
                    AstBuilders.and(eq("src", str("A")),
                            cmp(attr("depth"), ComparisonOperator.GREATER, num("1"))),
                    path()));

            SelectionNode s = assertThat(out).asNode(SelectionNode.class);
            assertThat(s.predicate()).isEqualTo(
                    cmp(attr("depth"), ComparisonOperator.GREATER, num("1")));
            assertThat(assertThat(s.input()).asNode(PathNode.class).boundSource())
                    .contains(str("A"));
        }

        @Test
        @DisplayName("a second equality on an already-bound endpoint stays above")
        void secondEqualityIsResidual() {
            // Folding both would silently drop one, turning `src = A ∧ src = B` — which
            // matches nothing — into a search from A.
            RelNode out = apply(select(
                    AstBuilders.and(eq("src", str("A")), eq("src", str("B"))), path()));

            SelectionNode s = assertThat(out).asNode(SelectionNode.class);
            assertThat(s.predicate()).isEqualTo(eq("src", str("B")));
            assertThat(assertThat(s.input()).asNode(PathNode.class).boundSource())
                    .contains(str("A"));
        }
    }

    @Nested
    @DisplayName("what does not fire")
    final class NoOp {

        @Test
        @DisplayName("an inequality on an endpoint is left alone")
        void inequality() {
            RelNode in = select(cmp(attr("src"), ComparisonOperator.GREATER, str("A")), path());

            assertThat(apply(in)).isSameAs(in);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an equality against another column is left alone")
        void columnToColumn() {
            RelNode in = select(cmp(attr("src"), ComparisonOperator.EQUAL, attr("dst")), path());

            assertThat(apply(in)).isSameAs(in);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("an already-bounded path is left alone")
        void alreadyBounded() {
            PathNode bounded = AstBuilders.path("src", "dst", false, 1, 3, "depth",
                    java.util.Optional.of(str("A")), java.util.Optional.empty(), rel("Edges"));
            RelNode in = select(eq("dst", str("D")), bounded);

            assertThat(apply(in)).isSameAs(in);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("a path already bounded on its target only is left alone")
        void alreadyTargetBounded() {
            // The source-bound check short-circuits the conjunction, so this is the arm
            // that says a target-only bound is equally off limits.
            PathNode bounded = AstBuilders.path("src", "dst", false, 1, 3, "depth",
                    java.util.Optional.empty(), java.util.Optional.of(str("D")), rel("Edges"));
            RelNode in = select(eq("src", str("A")), bounded);

            assertThat(apply(in)).isSameAs(in);
            assertThat(fired()).isFalse();
        }

        @Test
        @DisplayName("nothing pushable above, but a rewritten input, rebuilds the chain")
        void rebuildsOverARewrittenInput() {
            // The outer chain has no pushable conjunct — `depth` is a computed column —
            // while the inner path does, so the outer operator has to be rebuilt over the
            // new input with its own selection chain put back on top.
            PathNode inner = AstBuilders.path("src", "dst", 1, 3, "depth", rel("Edges"));
            RelNode tree = select(
                    cmp(attr("depth"), ComparisonOperator.GREATER, num("1")),
                    AstBuilders.path("src", "dst", 1, 2, "depth",
                            select(eq("src", str("A")), inner)));

            RelNode out = apply(tree);

            SelectionNode s = assertThat(out).asNode(SelectionNode.class);
            PathNode outer = assertThat(s.input()).asNode(PathNode.class);
            assertThat(outer.boundSource()).isEmpty();
            assertThat(outer.maxHops()).isEqualTo(2);
            PathNode rewritten = assertThat(outer.input()).asNode(PathNode.class);
            assertThat(rewritten.boundSource()).contains(str("A"));
            assertThat(fired()).isTrue();
        }

        @Test
        @DisplayName("a bare path with no selection above it is left alone")
        void barePath() {
            RelNode in = path();

            assertThat(apply(in)).isSameAs(in);
            assertThat(fired()).isFalse();
        }
    }
}
