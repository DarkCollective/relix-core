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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.ast.Expr;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.events.QueryEventListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;

/**
 * Executing the unrewritten tree.
 *
 * <p>Every case here turns on one probe, and it is the reason this expression rather than a
 * prettier one: {@code SUM(amount * 1)} is folded to {@code SUM(amount)} by {@code EXPR-004},
 * and an unaliased aggregate takes its output column name from its argument's <em>shape</em>
 * — so the two trees answer {@code sum_expr} and {@code sum_amount} for the same number.
 * That makes "which tree ran?" a question about the rows themselves rather than about a plan
 * or a feed, which is the only form of the question a test cannot answer by agreeing with the
 * implementation.
 */
@DisplayName("Relation — executing the tree as written")
final class AsWrittenTest {

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private static Relix orders() {
        Relix relix = Relix.open();
        relix.table("Orders", List.of(
                row("order_id", 1, "status", "OPEN", "amount", 100),
                row("order_id", 2, "status", "SHIPPED", "amount", 250),
                row("order_id", 3, "status", "OPEN", "amount", 75)));
        return relix;
    }

    /** The folded aggregate: one number, two column names. */
    private static Relation summed(Relix relix) {
        return relix.relation("γ SUM(amount * 1) (Orders)");
    }

    /** {@code amount > 50 + 50} — a predicate the constant folder rewrites and nothing else does. */
    private static Predicate foldable() {
        return Expr.gt(Expr.attr("amount"), Expr.plus(Expr.num(50), Expr.num(50)));
    }

    /** The rule codes a run reported, which is empty exactly when the rewriter did not run. */
    private static List<String> rulesFiredBy(Relation relation) {
        List<String> codes = new ArrayList<>();
        QueryEventListener listener = event -> {
            if (event.stage() == QueryEvent.Stage.OPTIMIZE) {
                codes.add(event.code());
            }
        };
        try (Stream<Tuple> rows = relation.stream(listener)) {
            rows.forEach(ignored -> { });
        }
        return codes;
    }

    @Nested
    @DisplayName("which tree runs")
    final class WhichTree {

        @Test
        @DisplayName("by default the rewritten one — unchanged")
        void byDefaultTheRewrittenOne() {
            try (Relix relix = orders()) {
                assertThat(summed(relix).run().schema())
                        .hasColumnNames("sum_amount");
            }
        }

        @Test
        @DisplayName("asWritten() runs the tree the caller wrote")
        void asWrittenRunsTheWrittenTree() {
            try (Relix relix = orders()) {
                assertThat(summed(relix).asWritten().run().schema())
                        .hasColumnNames("sum_expr");
            }
        }

        @Test
        @DisplayName("and the two agree on the answer, which is the point of being able to ask")
        void bothTreesAgree() {
            // Aliased, so the fold that renames the column cannot make two equal answers
            // look unequal — the same rule still fires, which `renders()` below pins.
            try (Relix relix = orders()) {
                Relation query = relix.relation("γ SUM(amount * 1) → total (Orders)");
                assertThat(query.asWritten()).renders().isNotEqualTo(query.optimized().render());
                assertThat(query.asWritten()).hasSameRowsAs(query);
            }
        }

        @Test
        @DisplayName("every row terminal honours it, not just stream()")
        void everyRowTerminalHonoursIt() {
            try (Relix relix = orders()) {
                Relation written = summed(relix).asWritten();
                assertThat(written).tuples()
                        .singleElement()
                        .extracting(tuple -> tuple.decimal("sum_expr"))
                        .isNotNull();
                assertThat(written.run().schema()).hasColumnNames("sum_expr");
                try (Stream<Tuple> rows = written.stream()) {
                    assertThat(rows.toList().getFirst().get("sum_expr")).isNotNull();
                }
            }
        }
    }

    @Nested
    @DisplayName("what the feed says")
    final class Feed {

        @Test
        @DisplayName("a default run reports the rules that fired")
        void defaultRunReportsItsRules() {
            try (Relix relix = orders()) {
                assertThat(rulesFiredBy(summed(relix))).contains("EXPR-004");
            }
        }

        @Test
        @DisplayName("an as-written run reports no rule at all, the rewriter having not run")
        void asWrittenRunReportsNoRule() {
            try (Relix relix = orders()) {
                assertThat(rulesFiredBy(summed(relix).asWritten())).isEmpty();
            }
        }

        @Test
        @DisplayName("optimized().stream(listener) reports the rewrite that produced it")
        void optimizedReportsItsOwnRewrite() {
            // It did not, and silently: the terminal ran the rewriter over the rewritten
            // tree, so the events a caller saw were the *second*, empty pass's — a feed
            // saying no rule fired for a relation that exists only because rules did.
            try (Relix relix = orders()) {
                Relation rewritten = summed(relix).optimized();
                assertThat(rulesFiredBy(rewritten))
                        .isEqualTo(rewritten.events().stream().map(QueryEvent::code).toList())
                        .contains("EXPR-004");
            }
        }
    }

    @Nested
    @DisplayName("composition")
    final class Composition {

        @Test
        @DisplayName("carries the caller's instruction — it is about how they want it run")
        void carriesTheInstruction() {
            try (Relix relix = orders()) {
                assertThat(rulesFiredBy(summed(relix).asWritten().limit(2))).isEmpty();
            }
        }

        @Test
        @DisplayName("but not optimized()'s mark, which the added expression has not been through")
        void doesNotCarryTheRewriterMark() {
            // The composed σ is foldable and the relation it is composed onto is not, so
            // the feed distinguishes the two answers: carrying REWRITTEN across would
            // leave `50 + 50` unfolded in a plan the caller never asked to be left alone.
            try (Relix relix = orders()) {
                Relation base = relix.relation("Orders").optimized();
                assertThat(rulesFiredBy(base.select(foldable()))).isNotEmpty();
            }
        }

        @Test
        @DisplayName("the same composition on an as-written relation folds nothing")
        void theSameCompositionAsWrittenFoldsNothing() {
            try (Relix relix = orders()) {
                Relation base = relix.relation("Orders").asWritten();
                assertThat(rulesFiredBy(base.select(foldable()))).isEmpty();
            }
        }

        @Test
        @DisplayName("count() is the algebra, so it is run the way the caller asked")
        void countFollowsTheSameRule() {
            // No feed reaches count(), so this asserts the answer and the rule above
            // asserts the mechanism: count() composes a γ through the same funnel limit()
            // does, and that funnel is what the two cases above pin.
            try (Relix relix = orders()) {
                assertThat(summed(relix).asWritten().count()).isEqualTo(1L);
            }
        }
    }

    @Nested
    @DisplayName("idempotence")
    final class Idempotence {

        @Test
        @DisplayName("asWritten() twice is the same relation")
        void twiceIsTheSame() {
            try (Relix relix = orders()) {
                Relation written = summed(relix).asWritten();
                assertThat(written.asWritten()).isSameAs(written);
            }
        }

        @Test
        @DisplayName("it settles execution only — render/explain already show the written tree")
        void inspectionIsUnchanged() {
            try (Relix relix = orders()) {
                Relation query = summed(relix);
                assertThat(query.asWritten()).renders().isEqualTo(query.render());
                assertThat(query.asWritten()).explains().isEqualTo(query.explain());
            }
        }

        @Test
        @DisplayName("on a rewritten relation it cannot un-rewrite the tree")
        void cannotUnrewrite() {
            try (Relix relix = orders()) {
                Relation rewritten = summed(relix).optimized();
                assertThat(rewritten.asWritten().node()).isEqualTo(rewritten.node());
            }
        }
    }
}
