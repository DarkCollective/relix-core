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
package com.darkcollective.relix.processor.reference;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.exec.RelNodeExecutor;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.rel;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window operators, against an <b>independent statement of what they mean</b>.
 *
 * <p>No database computes a window the way relix defines one, so the pushdown agreement
 * suites cannot reach this: they check that a folded {@code OVER} matches the engine, and
 * both sides are wrong together if the engine is. Everything else asserting these
 * operators is a row somebody wrote out, which says the answer has not changed rather
 * than that it was ever right.
 *
 * <p>{@link WindowReference} is written from {@code docs/reference} — the trailing frame,
 * the tie rules, which row an offset reads, where an {@code NTILE}'s leftover rows go — so
 * a disagreement here is between the engine and its own manual, and either being wrong is
 * worth knowing.
 *
 * <h2>The data is chosen to make the rules bite</h2>
 *
 * Every distinguishing case the manual names is present: a <b>tie</b> in the sort key, so
 * {@code RANK} and {@code DENSE_RANK} can differ and {@code ROW_NUMBER} has to fall back
 * to row order; a <b>NULL value</b>, which the aggregates skip and the offsets carry; a
 * <b>single-row partition</b>, which is {@code PERCENT_RANK}'s special case; and
 * partitions of <b>different sizes that do not divide evenly</b>, which is the only way to
 * tell which end of an {@code NTILE} takes the leftovers.
 */
@DisplayName("The window operators against an independent definition")
final class WindowReferenceTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    /**
     * Five rows in {@code a} and one in {@code b}.
     *
     * <p>In {@code a}: {@code k} ties at 20 (rows 2 and 3), so the three ranking families
     * separate; five rows over three buckets leaves two over, which is what an
     * {@code NTILE} has to place; and row 4 holds a NULL value. {@code b} is the one-row
     * partition.
     */
    private static final List<WindowReference.Row> ROWS = List.of(
            new WindowReference.Row(1, "a", 10, 5),
            new WindowReference.Row(2, "a", 20, 7),
            new WindowReference.Row(3, "a", 20, 2),
            new WindowReference.Row(4, "a", 30, null),
            new WindowReference.Row(5, "a", 40, 9),
            new WindowReference.Row(6, "b", 15, 3));

    private static final String TABLE = table(ROWS);

    private static String table(List<WindowReference.Row> rows) {
        StringBuilder sb = new StringBuilder("Events := [\n| id | grp | k | v |\n|----|-----|---|---|\n");
        for (WindowReference.Row row : rows) {
            sb.append("| ").append(row.id())
              .append(" | ").append(row.partition())
              .append(" | ").append(row.sortKey())
              .append(" | ").append(row.value() == null ? "" : row.value())
              .append(" |\n");
        }
        return sb.append("];\n").toString();
    }

    // =========================================================================

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget named -> rel(named.name());
            case ExpressionQueryTarget e -> e.expression();
        };
    }

    /** Runs {@code expression} and returns each row's id mapped to its {@code w} column. */
    private static Map<Integer, String> engine(String expression) {
        SemanticModel m = model(TABLE + "query { " + expression + " };\n");
        ExecutionContext ctx = ExecutionContext.inlineOnly(m);
        Map<Integer, String> out = new LinkedHashMap<>();
        try (Stream<com.darkcollective.relix.processor.Row> rows =
                     EXECUTOR.execute(queryNode(m.rootQueries().getFirst()), ctx)) {
            rows.forEach(row -> out.put(
                    Integer.parseInt(row.get("id").asDisplayString()),
                    row.get("w").asDisplayString()));
        }
        return out;
    }

    /**
     * Compares the engine's column against the reference's, per row.
     *
     * <p>Numbers are compared numerically rather than as text: how many places an average
     * carries is a rendering question this is not about, and it is answered by its own
     * tests.
     */
    private static void assertAgrees(String what, String expression,
                                     Map<Integer, String> expected) {
        Map<Integer, String> actual = engine(expression);

        assertThat(actual.keySet())
                .as("%s: every input row must survive a window — it adds a column", what)
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((id, want) -> {
            String got = actual.get(id);
            if (isNumber(want) && isNumber(got)) {
                assertThat(new BigDecimal(got))
                        .as("%s: row %d — %s", what, id, expression)
                        .isEqualByComparingTo(new BigDecimal(want));
            } else {
                assertThat(got).as("%s: row %d — %s", what, id, expression).isEqualTo(want);
            }
        });
    }

    private static boolean isNumber(String s) {
        try {
            new BigDecimal(s);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @ParameterizedTest(name = "{0}, sorted {1}")
        @CsvSource({"ROW_NUMBER,ASC", "ROW_NUMBER,DESC", "RANK,ASC", "RANK,DESC",
                    "DENSE_RANK,ASC", "DENSE_RANK,DESC", "PERCENT_RANK,ASC", "PERCENT_RANK,DESC"})
        void matchesTheDefinition(String function, String direction) {
            boolean ascending = direction.equals("ASC");
            Map<Integer, String> expected = switch (function) {
                case "ROW_NUMBER" -> WindowReference.rowNumber(ROWS, ascending);
                case "RANK" -> WindowReference.rank(ROWS, ascending);
                case "DENSE_RANK" -> WindowReference.denseRank(ROWS, ascending);
                default -> WindowReference.percentRank(ROWS, ascending);
            };
            assertAgrees(function, "WINDOW " + function + "() SORT k " + direction
                    + " PER grp AS w (Events)", expected);
        }

        @ParameterizedTest(name = "NTILE({0})")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6})
        void ntileMatchesTheDefinition(int buckets) {
            assertAgrees("NTILE(" + buckets + ")",
                    "WINDOW NTILE(" + buckets + ") SORT k ASC PER grp AS w (Events)",
                    WindowReference.ntile(ROWS, true, buckets));
        }
    }

    @Nested
    @DisplayName("offset")
    class Offset {

        @ParameterizedTest(name = "LAG by {0}")
        @ValueSource(ints = {1, 2, 3})
        void lagMatchesTheDefinition(int offset) {
            assertAgrees("LAG(" + offset + ")",
                    "WINDOW LAG(v, " + offset + ") SORT k ASC PER grp AS w (Events)",
                    WindowReference.lag(ROWS, true, offset));
        }

        @ParameterizedTest(name = "LEAD by {0}")
        @ValueSource(ints = {1, 2, 3})
        void leadMatchesTheDefinition(int offset) {
            assertAgrees("LEAD(" + offset + ")",
                    "WINDOW LEAD(v, " + offset + ") SORT k ASC PER grp AS w (Events)",
                    WindowReference.lead(ROWS, true, offset));
        }

        @Test
        @DisplayName("FIRST_VALUE and LAST_VALUE read the partition's edges")
        void edgesMatchTheDefinition() {
            assertAgrees("FIRST_VALUE",
                    "WINDOW FIRST_VALUE(v) SORT k ASC PER grp AS w (Events)",
                    WindowReference.firstValue(ROWS, true));
            assertAgrees("LAST_VALUE",
                    "WINDOW LAST_VALUE(v) SORT k ASC PER grp AS w (Events)",
                    WindowReference.lastValue(ROWS, true));
        }
    }

    @Nested
    @DisplayName("rolling")
    class Rolling {

        @ParameterizedTest(name = "{0} over {1} rows")
        @CsvSource({"SUM,2", "SUM,3", "SUM,0", "AVG,2", "AVG,0", "COUNT,2", "COUNT,0",
                    "MIN,2", "MIN,0", "MAX,3", "MAX,0"})
        void matchesTheDefinition(String aggregate, int frame) {
            String over = frame == 0 ? "ALL ROWS" : frame + " ROWS";
            assertAgrees(aggregate + " over " + over,
                    "ROLLING " + aggregate + "(v) OVER " + over
                    + " SORT k ASC PER grp AS w (Events)",
                    WindowReference.rolling(ROWS, true, aggregate, frame));
        }
    }
}
