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

import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.plan.BoundednessException;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RelationAssert} buys one thing over calling a terminal and asserting on what
 * comes back — <strong>the failure names the query</strong> — so that is what is tested
 * here, and it is the only reason the assert was written.
 *
 * <p>{@code TESTING.md} asks for exactly this test: a wrapper that passes the claim
 * through and drops the subject satisfies every positive test and is worthless. Each
 * case below therefore states the true claim once and then reads the message of the
 * false one.
 */
@DisplayName("RelationAssert — assertions that print the query that disagreed")
final class RelationAssertTest {

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

    private static final String OPEN = "σ status = 'OPEN' (Orders)";

    /**
     * {@link #OPEN} as {@code PrettyPrinter} writes it back — a literal's spelling is
     * normalised, so this rather than the source text is what a failure prints.
     */
    private static final String OPEN_RENDERED = "σ status = \"OPEN\" (Orders)";

    @Nested
    @DisplayName("the description")
    final class Description {

        @Test
        @DisplayName("is the expression and its heading, on every failure")
        void carriesTheExpressionAndHeading() {
            try (Relix relix = orders()) {
                assertThatThrownBy(() -> assertThat(relix.relation(OPEN)).hasRowCount(3))
                        .isInstanceOf(AssertionError.class)
                        .hasMessageContaining(OPEN_RENDERED)
                        .hasMessageContaining("order_id:N")
                        .hasMessageContaining("status:S");
            }
        }

        @Test
        @DisplayName("names the query the script named, since that is what a reader looks for")
        void carriesTheLabel() {
            try (Relix relix = orders()) {
                Relation named = relix.script(
                        "Open := { " + OPEN + " }; query Open;").getFirst();

                assertThatThrownBy(() -> assertThat(named).hasRowCount(3))
                        .hasMessageContaining("Open");
            }
        }

        @Test
        @DisplayName("degrades rather than throwing when the relation has no inferred heading")
        void degradesWithoutAHeading() {
            try (Relix relix = Relix.builder().allowUnresolved().build()) {
                relix.define("connection warehouse from database\n"
                        + "        { url: \"jdbc:h2:mem:no_such_db;IFEXISTS=TRUE\" };");
                Relation offline = relix.relation("warehouse.orders");

                // The heading is absent, not a red assert about the heading being absent.
                assertThat(offline).renders().contains("warehouse.orders");
            }
        }
    }

    @Nested
    @DisplayName("claims that execute")
    final class Executing {

        @Test
        @DisplayName("hasRowCount states the claim and prints the rows it found")
        void rowCount() {
            try (Relix relix = orders()) {
                assertThat(relix.relation(OPEN)).hasRowCount(2);

                assertThatThrownBy(() -> assertThat(relix.relation(OPEN)).hasRowCount(3))
                        .hasMessageContaining("expected 3 row(s) but there are 2")
                        .hasMessageContaining("1  OPEN  100")
                        .hasMessageContaining(OPEN_RENDERED);
            }
        }

        @Test
        @DisplayName("isEmpty prints the rows that made it not empty")
        void empty() {
            try (Relix relix = orders()) {
                assertThat(relix.relation("σ status = 'NOPE' (Orders)")).isEmpty();

                assertThatThrownBy(() -> assertThat(relix.relation(OPEN)).isEmpty())
                        .hasMessageContaining("expected no rows but there are 2")
                        .hasMessageContaining("3  OPEN  75");
            }
        }

        @Test
        @DisplayName("isNotEmpty names the query that returned nothing")
        void notEmpty() {
            try (Relix relix = orders()) {
                assertThat(relix.relation(OPEN)).isNotEmpty();

                assertThatThrownBy(() ->
                        assertThat(relix.relation("σ status = 'NOPE' (Orders)")).isNotEmpty())
                        .hasMessageContaining("expected at least one row")
                        .hasMessageContaining("NOPE");
            }
        }

        @Test
        @DisplayName("hasSameRowsAs prints both expressions and both tables")
        void sameRows() {
            try (Relix relix = orders()) {
                Relation written = relix.relation(OPEN);
                assertThat(written).hasSameRowsAs(written.optimized());

                assertThatThrownBy(() -> assertThat(written)
                        .hasSameRowsAs(relix.relation("σ status = 'SHIPPED' (Orders)")))
                        .hasMessageContaining("expected the same rows as")
                        .hasMessageContaining("SHIPPED")
                        .hasMessageContaining("1  OPEN  100")
                        .hasMessageContaining("2  SHIPPED  250");
            }
        }

        @Test
        @DisplayName("the rows are drained once, so a chain of claims costs one run")
        void drainsOnce() {
            java.util.concurrent.atomic.AtomicInteger scans =
                    new java.util.concurrent.atomic.AtomicInteger();
            com.darkcollective.relix.symbol.Schema schema =
                    new com.darkcollective.relix.symbol.Schema(List.of(
                            new com.darkcollective.relix.symbol.ColumnDefinition(
                                    "id", ScalarType.NUMBER)));
            try (Relix relix = Relix.open()) {
                relix.source("Feed", schema, () -> {
                    scans.incrementAndGet();
                    return java.util.stream.Stream.of(
                            com.darkcollective.relix.processor.ArrayRow.of(schema,
                                    com.darkcollective.relix.value.NumberValue.of("1")));
                });

                assertThat(relix.relation("Feed")).hasRowCount(1).isNotEmpty()
                        .rows().hasRowAt(0, "1");

                assertThat(scans).hasValue(1);
            }
        }
    }

    @Nested
    @DisplayName("an execution that cannot happen")
    final class Refused {

        @Test
        @DisplayName("is reported as this relation failing, with the cause kept")
        void unboundedIsNamed() {
            try (Relix relix = Relix.open()) {
                relix.define("source Naturals from generator { name: \"Naturals\" };");

                assertThatThrownBy(() -> assertThat(relix.relation("Naturals")).hasRowCount(3))
                        .isInstanceOf(AssertionError.class)
                        .hasMessageContaining("expected to execute this relation")
                        .hasMessageContaining("BoundednessException")
                        .hasMessageContaining("Naturals")
                        .hasCauseInstanceOf(BoundednessException.class);
            }
        }

        @Test
        @DisplayName("a closed session reads as the same failure rather than an unrelated trace")
        void closedSessionIsNamed() {
            Relix relix = orders();
            Relation open = relix.relation(OPEN);
            relix.close();

            assertThatThrownBy(() -> assertThat(open).isNotEmpty())
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("expected to execute this relation")
                    .hasMessageContaining(OPEN_RENDERED)
                    .hasCauseInstanceOf(RelixException.class);
        }

        @Test
        @DisplayName("a relation with no heading fails naming the relation, not a missing annotation")
        void noHeadingIsNamed() {
            try (Relix relix = Relix.builder().allowUnresolved().build()) {
                relix.define("connection warehouse from database\n"
                        + "        { url: \"jdbc:h2:mem:no_such_db;IFEXISTS=TRUE\" };");

                assertThatThrownBy(() ->
                        assertThat(relix.relation("warehouse.orders")).schema().hasWidth(2))
                        .isInstanceOf(AssertionError.class)
                        .hasMessageContaining("expected this relation to have a heading")
                        .hasMessageContaining("warehouse.orders")
                        .hasCauseInstanceOf(RelixException.class);
            }
        }
    }

    @Nested
    @DisplayName("the rewriter's record")
    final class Rewrites {

        /** Two stacked σs: SEL-002 merges them, the rewrite with the least ambiguity. */
        private static final String STACKED = "σ status = 'OPEN' (σ amount > 50 (Orders))";

        @Test
        @DisplayName("rewrote / didNotRewrite state the two claims a rewrite test makes")
        void firing() {
            try (Relix relix = orders()) {
                assertThat(relix.relation(STACKED).optimized())
                        .rewrote(OptimizationCode.SEL_002)
                        .didNotRewrite(OptimizationCode.PROJ_003);
            }
        }

        @Test
        @DisplayName("a rule that did not fire is reported beside the rules that did")
        void namesTheRulesThatFired() {
            try (Relix relix = orders()) {
                assertThatThrownBy(() -> assertThat(relix.relation(STACKED).optimized())
                        .rewrote(OptimizationCode.PROJ_003))
                        .hasMessageContaining("expected PROJ-003 to fire")
                        .hasMessageContaining("SEL-002");
            }
        }

        @Test
        @DisplayName("a wrong count reports the count and the trail")
        void wrongCount() {
            try (Relix relix = orders()) {
                assertThatThrownBy(() -> assertThat(relix.relation(STACKED).optimized())
                        .rewrote(OptimizationCode.SEL_002, 9))
                        .hasMessageContaining("expected SEL-002 to fire 9 time(s) but it fired 1");
            }
        }

        @Test
        @DisplayName("an unexpected firing names the rule that fired")
        void unexpectedFiring() {
            try (Relix relix = orders()) {
                assertThatThrownBy(() -> assertThat(relix.relation(STACKED).optimized())
                        .didNotRewrite(OptimizationCode.SEL_002))
                        .hasMessageContaining("expected SEL-002 not to fire, but it fired 1");
            }
        }

        @Test
        @DisplayName("an unstaged relation says so, rather than reporting a rule that could not fire")
        void unstagedSaysSo() {
            try (Relix relix = orders()) {
                assertThat(relix.relation(STACKED)).rewroteNothing();

                assertThatThrownBy(() -> assertThat(relix.relation(STACKED))
                        .rewrote(OptimizationCode.SEL_002))
                        .hasMessageContaining("only a relation from optimized()");
            }
        }
    }

    @Nested
    @DisplayName("navigation hands off, and the relation travels with it")
    final class Navigation {

        @Test
        @DisplayName("node reaches the AST asserts")
        void node() {
            try (Relix relix = orders()) {
                assertThat(relix.relation(OPEN)).node().isNode(SelectionNode.class)
                        .input().isRelation("Orders");

                assertThatThrownBy(() ->
                        assertThat(relix.relation(OPEN)).node().isRelation("Orders"))
                        .hasMessageContaining(OPEN_RENDERED);
            }
        }

        @Test
        @DisplayName("schema reaches the heading asserts")
        void schema() {
            try (Relix relix = orders()) {
                assertThat(relix.relation("π order_id (Orders)")).schema()
                        .hasColumnNames("order_id")
                        .hasColumn("order_id", ScalarType.NUMBER);

                assertThatThrownBy(() -> assertThat(relix.relation("π order_id (Orders)"))
                        .schema().hasColumnNames("nope"))
                        .hasMessageContaining("π order_id (Orders)");
            }
        }

        @Test
        @DisplayName("plan reaches the plan asserts, and runs nothing")
        void plan() {
            try (Relix relix = orders()) {
                assertThat(relix.relation(OPEN)).plan()
                        .isNode(com.darkcollective.relix.plan.PhysicalNode.Select.class);

                assertThatThrownBy(() -> assertThat(relix.relation(OPEN)).plan()
                        .isNode(com.darkcollective.relix.plan.PhysicalNode.Sort.class))
                        .hasMessageContaining(OPEN_RENDERED);
            }
        }

        @Test
        @DisplayName("rows reaches the table asserts, under the relation's own heading")
        void rows() {
            try (Relix relix = orders()) {
                assertThat(relix.relation(OPEN)).rows()
                        .hasColumns("order_id", "status", "amount")
                        .hasRow("1", "OPEN", "100");

                assertThatThrownBy(() -> assertThat(relix.relation(OPEN)).rows().hasRowCount(3))
                        .hasMessageContaining(OPEN_RENDERED);
            }
        }

        @Test
        @DisplayName("an empty result still knows its columns, because the heading is declared")
        void emptyResultKeepsItsColumns() {
            try (Relix relix = orders()) {
                assertThat(relix.relation("σ status = 'NOPE' (Orders)")).rows()
                        .hasColumns("order_id", "status", "amount")
                        .isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("hand-offs to AssertJ")
    final class HandOffs {

        @Test
        @DisplayName("tuples is the typed route, for a claim about an accessor")
        void tuples() {
            try (Relix relix = orders()) {
                assertThat(relix.relation(OPEN)).tuples()
                        .extracting(t -> t.longValue("order_id"))
                        .containsExactly(1L, 3L);

                assertThatThrownBy(() -> assertThat(relix.relation(OPEN)).tuples().hasSize(3))
                        .hasMessageContaining(OPEN_RENDERED);
            }
        }

        @Test
        @DisplayName("renders carries the label the text alone does not")
        void renders() {
            try (Relix relix = orders()) {
                Relation query = relix.script("query { " + OPEN + " };").getFirst();

                assertThat(query).renders().isEqualTo(OPEN_RENDERED);
                // The text is largely its own subject; what the hand-off adds is that
                // the script's own name for this query travels with it.
                assertThatThrownBy(() -> assertThat(query).renders().contains("Nope"))
                        .hasMessageContaining("<expression 1>")
                        .hasMessageContaining(OPEN_RENDERED);
            }
        }

        @Test
        @DisplayName("explains prints the plan, and runs nothing")
        void explains() {
            try (Relix relix = orders()) {
                assertThat(relix.relation(OPEN)).explains().contains("rows");

                assertThatThrownBy(() -> assertThat(relix.relation(OPEN)).explains().isBlank())
                        .hasMessageContaining(OPEN_RENDERED);
            }
        }

        @Test
        @DisplayName("count is γ COUNT(*), not the drained rows")
        void count() {
            try (Relix relix = orders()) {
                assertThat(relix.relation(OPEN)).count().isEqualTo(2L);

                assertThatThrownBy(() -> assertThat(relix.relation(OPEN)).count().isEqualTo(3L))
                        .hasMessageContaining(OPEN_RENDERED);
            }
        }
    }

    @Test
    @DisplayName("a null relation is reported rather than throwing a NullPointerException")
    void nullIsReported() {
        assertThatThrownBy(() -> assertThat((Relation) null).isNotEmpty())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expecting actual not to be null");
    }
}
