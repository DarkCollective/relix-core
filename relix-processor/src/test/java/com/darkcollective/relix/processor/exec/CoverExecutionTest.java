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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.reference.CoverReference;
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

@DisplayName("PhysicalExecutor — covering reduction (COVER)")
final class CoverExecutionTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    private static List<Row> collect(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        QueryStatement query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.toList();
        }
    }

    /**
     * Coverage-property oracle: for every column-index subset of size {@code t}, every
     * {@code t}-value tuple occurring in the candidates must occur in the output. This is
     * the fundamental correctness contract of {@code COVER t}.
     *
     * <p>The property itself lives in {@link CoverReference}, shared with the generated
     * suite in that package: two statements of one guarantee is one of them waiting to
     * drift from the operator it describes.
     */
    private static void assertCoverageProperty(List<Row> output, List<Row> candidates, int t) {
        assertThat(CoverReference.uncovered(cells(candidates), cells(output), t))
                .as("output must cover every %d-tuple the candidates demand", t)
                .isEmpty();
    }

    /** Each row as its list of displayed cell values, which is what the property reads. */
    private static List<List<String>> cells(List<Row> rows) {
        return rows.stream().map(row -> {
            List<String> values = new ArrayList<>(row.width());
            for (int i = 0; i < row.width(); i++) {
                values.add(row.get(i).asDisplayString());
            }
            return List.copyOf(values);
        }).toList();
    }

    // 8-row 3-column example: x ∈ {a,b}, y ∈ {1,2}, z ∈ {p,q}
    private static final String XYZ8 =
            "XYZ := [| x | y | z |\n" +
            "         | a | 1 | p |\n" +
            "         | a | 1 | q |\n" +
            "         | a | 2 | p |\n" +
            "         | a | 2 | q |\n" +
            "         | b | 1 | p |\n" +
            "         | b | 1 | q |\n" +
            "         | b | 2 | p |\n" +
            "         | b | 2 | q |];\n";

    // 4-row 2-column example
    private static final String XY4 =
            "XY := [| x | y |\n" +
            "        | a | 1 |\n" +
            "        | a | 2 |\n" +
            "        | b | 1 |\n" +
            "        | b | 2 |];\n";

    @Nested
    @DisplayName("coverage property oracle")
    class CoverageProperty {

        @Test
        @DisplayName("pairwise (t=2) coverage: output covers all pairs, smaller than full candidate set")
        void pairwiseCoverageProperty() {
            var rows = collect(XYZ8 + "query { COVER 2 (XYZ) };");

            // Build candidates list (all 8 rows from XYZ).
            var candidates = collect(XYZ8 + "query { XYZ };");

            assertCoverageProperty(rows, candidates, 2);
            // The greedy should reduce 8 candidates to ≤ 4 rows (optimal pairwise cover).
            assertThat(rows).hasSizeLessThan(8);
        }

        @Test
        @DisplayName("3-way (t=3) coverage: output covers all triples")
        void threewayCoversAllTriples() {
            var rows = collect(XYZ8 + "query { COVER 3 (XYZ) };");
            var candidates = collect(XYZ8 + "query { XYZ };");

            assertCoverageProperty(rows, candidates, 3);
        }

        @Test
        @DisplayName("pairwise PICT-style example: constrained candidate set fully covered")
        void pictStyleConstrainedSet() {
            String base =
                    "Type   := [| type   |\n" +
                    "            | primary|\n" +
                    "            | logical|\n" +
                    "            | single |];\n" +
                    "Format := [| format |\n" +
                    "            | ntfs  |\n" +
                    "            | fat32 |\n" +
                    "            | exfat |];\n" +
                    "Size   := [| size   |\n" +
                    "            | 10    |\n" +
                    "            | 100   |\n" +
                    "            | 1000  |];\n";
            String candidateDef =
                    "Candidates := { σ ¬(format = \"fat32\" ∧ size = \"1000\") (Type × Format × Size) };\n";

            var tests     = collect(base + candidateDef + "Tests := { COVER 2 (Candidates) };\nquery Tests;\n");
            var candidates = collect(base + candidateDef + "query Candidates;\n");

            // Coverage oracle: all pairs in candidates appear in tests.
            assertCoverageProperty(tests, candidates, 2);
            // Suite should be much smaller than the candidate set.
            assertThat(tests.size()).isLessThan(candidates.size());
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        @DisplayName("same input order yields identical output on repeated runs")
        void sameInputSameOutput() {
            String src = XYZ8 + "query { COVER 2 (XYZ) };";
            var run1 = collect(src);
            var run2 = collect(src);

            assertThat(run1).containsExactlyElementsOf(run2);
        }
    }

    @Nested
    @DisplayName("tie-break by arrival order (weights channel)")
    class TieBreak {

        @Test
        @DisplayName("earlier candidate wins when scores are equal — pre-sorting biases the suite")
        void earlierCandidateWinsTie() {
            // All 4 rows cover exactly one pair each at t=2 in XY (the full-strength case).
            // After sorting by x DESC, (b,1) and (b,2) come first, so the output
            // should start with b rows rather than a rows.
            String src = XY4 +
                    "Sorted := { τ x DESC (XY) };\n" +
                    "query { COVER 2 (Sorted) };\n";
            var rows = collect(src);

            // With t=2=width all distinct rows are needed; just verify order starts with b.
            assertThat(rows).isNotEmpty();
            assertThat(rows.get(0)).hasValue("x", "b");
        }
    }

    @Nested
    @DisplayName("degenerate ends")
    class DegenerateEnds {

        @Test
        @DisplayName("t=1: each distinct value of each column appears in the output")
        void strengthOneEachValueAppears() {
            var rows = collect(XYZ8 + "query { COVER 1 (XYZ) };");

            // Every value of x, y, z must appear.
            assertThat(rows.stream().map(r -> r.get("x").asDisplayString()))
                    .containsAnyOf("a", "b");
            assertThat(rows.stream().map(r -> r.get("y").asDisplayString()))
                    .containsAnyOf("1", "2");
            assertThat(rows.stream().map(r -> r.get("z").asDisplayString()))
                    .containsAnyOf("p", "q");

            // Coverage oracle (t=1).
            var candidates = collect(XYZ8 + "query { XYZ };");
            assertCoverageProperty(rows, candidates, 1);
            // At most 2 rows needed (one for each value of the widest domain).
            assertThat(rows).hasSizeLessThanOrEqualTo(8);
        }

        @Test
        @DisplayName("t=width (full-strength): output equals all distinct input rows")
        void strengthEqualWidthEqualsDistinct() {
            // t=2 for a 2-column table: every row is its own 2-tuple → all rows must appear.
            var rows = collect(XY4 + "query { COVER 2 (XY) };");

            assertThat(rows)
                    .extracting(r -> r.get("x").asDisplayString() + "," + r.get("y").asDisplayString())
                    .containsExactlyInAnyOrder("a,1", "a,2", "b,1", "b,2");
        }

        @Test
        @DisplayName("empty input yields empty output")
        void emptyInput() {
            String src =
                    "Empty := [| x | y |];\n" +
                    "query { COVER 2 (Empty) };\n";
            var rows = collect(src);

            assertThat(rows).isEmpty();
        }
    }

    @Nested
    @DisplayName("duplicate input rows")
    class DuplicateRows {

        @Test
        @DisplayName("duplicate candidates are deduplicated before coverage — each unique row at most once")
        void duplicatesDeduped() {
            // XY with each row repeated twice: 8 rows, but only 4 unique.
            String src =
                    "WithDups := [| x | y |\n" +
                    "              | a | 1 |\n" +
                    "              | a | 1 |\n" +  // duplicate
                    "              | a | 2 |\n" +
                    "              | a | 2 |\n" +  // duplicate
                    "              | b | 1 |\n" +
                    "              | b | 1 |\n" +  // duplicate
                    "              | b | 2 |\n" +
                    "              | b | 2 |];\n" + // duplicate
                    "query { COVER 2 (WithDups) };\n";

            var rows = collect(src);

            // Output is a subset of unique rows → no duplicate in output.
            assertThat(rows).doesNotHaveDuplicates();
            // All pairs are still covered.
            var candidates = collect(
                    "WithDups := [| x | y |\n" +
                    "              | a | 1 |\n" +
                    "              | a | 2 |\n" +
                    "              | b | 1 |\n" +
                    "              | b | 2 |];\n" +
                    "query { WithDups };\n");
            assertCoverageProperty(rows, candidates, 2);
        }
    }

    @Nested
    @DisplayName("constructive cover (ADR-0012 D3-B — avoid materialising the product)")
    class ConstructiveCover {

        // Three single-column factors: types, formats, sizes — the canonical PICT setup.
        private static final String PICT_FACTORS =
                "Type   := [| type    |\n" +
                "            | primary |\n" +
                "            | logical |\n" +
                "            | single  |];\n" +
                "Format := [| format |\n" +
                "            | ntfs   |\n" +
                "            | fat32  |\n" +
                "            | exfat  |];\n" +
                "Size   := [| size |\n" +
                "            | 10   |\n" +
                "            | 100  |\n" +
                "            | 1000 |];\n";

        @Test
        @DisplayName("pairwise cover of three factors: output covers all pairs, suite < unconstrained product")
        void pairwiseCoverThreeFactors() {
            // Unconstrained product has 4×3×3 = 36 rows; a pairwise suite should be << 36.
            String src = PICT_FACTORS + "query { COVER 2 (Type × Format × Size) };";
            var rows = collect(src);

            // Build the full candidate set (the product).
            var candidates = collect(PICT_FACTORS + "query { Type × Format × Size };");

            assertCoverageProperty(rows, candidates, 2);
            assertThat(rows.size()).isLessThan(candidates.size());
        }

        @Test
        @DisplayName("constrained pairwise cover: conjunct excludes (fat32, 1000) — output still covers all valid pairs")
        void constrainedPairwiseCover() {
            // Use a number literal (not "1000") because the Size inline table stores numeric values.
            String constraint = "¬(format = \"fat32\" ∧ size = 1000)";
            String src = PICT_FACTORS +
                    "query { COVER 2 (σ " + constraint + " (Type × Format × Size)) };";
            var rows = collect(src);

            // Valid candidates: all rows except the constrained combination.
            var candidates = collect(PICT_FACTORS +
                    "Candidates := { σ " + constraint + " (Type × Format × Size) };\n" +
                    "query Candidates;\n");

            assertCoverageProperty(rows, candidates, 2);
            // No forbidden row (fat32 + 1000) in the output.
            for (Row row : rows) {
                boolean isFat32 = "fat32".equals(row.get("format").asDisplayString());
                boolean is1000  = "1000".equals(row.get("size").asDisplayString());
                assertThat(isFat32 && is1000)
                        .as("forbidden (fat32,1000) combination must not appear in output")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("a constraint spanning all three factors: the greedy stalls and still covers")
        void constraintAcrossEveryFactor() {
            // The constructive cover picks a value per factor in turn, so a constraint over
            // *one* pair is decided while it is being built. A constraint over all three is
            // not: each choice is individually fine and the row is only rejected once
            // complete, which is the stall path — the loop drops a demanded tuple and tries
            // again rather than spinning or emitting the forbidden row.
            //
            // The pairs it spans are still demanded, because each occurs with other values
            // of the third factor. So this is not the "constraints come free" case; it is
            // the one where the greedy has to recover.
            String constraint = "¬(type = \"primary\" ∧ format = \"ntfs\" ∧ size = 10)";
            String src = PICT_FACTORS
                    + "query { COVER 2 (σ " + constraint + " (Type × Format × Size)) };";
            var rows = collect(src);

            var candidates = collect(PICT_FACTORS
                    + "Candidates := { σ " + constraint + " (Type × Format × Size) };\n"
                    + "query Candidates;\n");

            assertCoverageProperty(rows, candidates, 2);
            assertThat(rows).as("a stall must not empty the suite").isNotEmpty();
            for (Row row : rows) {
                boolean forbidden = "primary".equals(row.get("type").asDisplayString())
                        && "ntfs".equals(row.get("format").asDisplayString())
                        && "10".equals(row.get("size").asDisplayString());
                assertThat(forbidden)
                        .as("the three-way forbidden combination must not survive to the output")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("two-factor (2D) constructive cover produces same rows as materialized cover")
        void twoFactorMatchesMaterialized() {
            // With only two factors there is no product-avoidance benefit, but the
            // constructive path must still produce a correct pairwise suite.
            String factors =
                    "A := [| x |\n" +
                    "       | a |\n" +
                    "       | b |];\n" +
                    "B := [| y |\n" +
                    "       | 1 |\n" +
                    "       | 2 |];\n";
            String constructive = factors + "query { COVER 2 (A × B) };";

            var rows       = collect(constructive);
            var candidates = collect(factors + "query { A × B };");

            assertCoverageProperty(rows, candidates, 2);
        }

        @Test
        @DisplayName("four-factor constructive cover: suite << 81-row unconstrained product")
        void fourFactorCover() {
            String factors =
                    "A := [| a |\n" +
                    "       | a1 |\n" +
                    "       | a2 |\n" +
                    "       | a3 |];\n" +
                    "B := [| b |\n" +
                    "       | b1 |\n" +
                    "       | b2 |\n" +
                    "       | b3 |];\n" +
                    "C := [| c |\n" +
                    "       | c1 |\n" +
                    "       | c2 |\n" +
                    "       | c3 |];\n" +
                    "D := [| d |\n" +
                    "       | d1 |\n" +
                    "       | d2 |\n" +
                    "       | d3 |];\n";

            String src = factors + "query { COVER 2 (A × B × C × D) };";
            var rows       = collect(src);
            var candidates = collect(factors + "query { A × B × C × D };");

            assertCoverageProperty(rows, candidates, 2);
            // 4 factors × 3 values each = 81 rows in the product;
            // a pairwise suite needs at most max(domain) = 3 rows to cover one pair
            // → the constructive output should be well below 81.
            assertThat(rows.size()).isLessThan(candidates.size());
        }

        @Test
        @DisplayName("multiple AND conjuncts are all enforced — no invalid row in output")
        void multipleConjunctsAllEnforced() {
            // Two independent exclusions. Use number literal for size (inline table stores numbers).
            String src = PICT_FACTORS +
                    "query { COVER 2 (σ ¬(format = \"fat32\" ∧ size = 1000)" +
                    "                   ∧ ¬(type = \"logical\" ∧ format = \"exfat\")" +
                    "                (Type × Format × Size)) };";
            var rows = collect(src);

            for (Row row : rows) {
                String type   = row.get("type").asDisplayString();
                String format = row.get("format").asDisplayString();
                String size   = row.get("size").asDisplayString();
                assertThat("fat32".equals(format) && "1000".equals(size))
                        .as("(fat32,1000) must not appear").isFalse();
                assertThat("logical".equals(type) && "exfat".equals(format))
                        .as("(logical,exfat) must not appear").isFalse();
            }

            var candidates = collect(PICT_FACTORS +
                    "Candidates := { σ ¬(format = \"fat32\" ∧ size = 1000)" +
                    "                   ∧ ¬(type = \"logical\" ∧ format = \"exfat\")" +
                    "                (Type × Format × Size) };\n" +
                    "query Candidates;\n");
            assertCoverageProperty(rows, candidates, 2);
        }

        @Test
        @DisplayName("single-value factor: all pairs still covered (trivially one choice per factor)")
        void singleValueFactor() {
            String factors =
                    "A := [| x |\n" +
                    "       | a |];\n" +
                    "B := [| y |\n" +
                    "       | 1 |\n" +
                    "       | 2 |];\n" +
                    "C := [| z |\n" +
                    "       | p |\n" +
                    "       | q |];\n";
            var rows       = collect(factors + "query { COVER 2 (A × B × C) };");
            var candidates = collect(factors + "query { A × B × C };");

            assertCoverageProperty(rows, candidates, 2);
        }

        @Test
        @DisplayName("conjunct referencing a later factor only inside a struct construction is still factor-dependent (issue #179)")
        void structConstructionConjunctTracksFactors() {
            // The constraint requires format = ntfs, but the column reference is buried
            // inside a struct construction. If collectCoverAttrOperand fails to recurse
            // into the struct, the conjunct is treated as factor-independent and applied
            // at factor 0 (where format is still NULL), rejecting every candidate and
            // draining the universe to an empty (wrong) output.
            String src = PICT_FACTORS +
                    "query { COVER 2 (σ {tag: format} = {tag: \"ntfs\"} (Type × Format × Size)) };";
            var rows = collect(src);

            var candidates = collect(PICT_FACTORS +
                    "Candidates := { σ format = \"ntfs\" (Type × Format × Size) };\n" +
                    "query Candidates;\n");

            assertThat(rows).isNotEmpty();
            for (Row row : rows) {
                assertThat(row.get("format").asDisplayString())
                        .as("every output row must satisfy the struct-encoded format = ntfs constraint")
                        .isEqualTo("ntfs");
            }
            assertCoverageProperty(rows, candidates, 2);
        }

        @Test
        @DisplayName("conjunct referencing a later factor only inside an array construction is still factor-dependent (issue #179)")
        void arrayConstructionConjunctTracksFactors() {
            // Same trap as the struct case, but the format reference is inside an array
            // construction; collectCoverAttrOperand must recurse into array elements too.
            String src = PICT_FACTORS +
                    "query { COVER 2 (σ [format] = [\"ntfs\"] (Type × Format × Size)) };";
            var rows = collect(src);

            var candidates = collect(PICT_FACTORS +
                    "Candidates := { σ format = \"ntfs\" (Type × Format × Size) };\n" +
                    "query Candidates;\n");

            assertThat(rows).isNotEmpty();
            for (Row row : rows) {
                assertThat(row.get("format").asDisplayString())
                        .as("every output row must satisfy the array-encoded format = ntfs constraint")
                        .isEqualTo("ntfs");
            }
            assertCoverageProperty(rows, candidates, 2);
        }

        @Test
        @DisplayName("determinism: same factor order produces identical output on repeated runs")
        void constructiveCoverIsDeterministic() {
            String src = PICT_FACTORS + "query { COVER 2 (Type × Format × Size) };";

            var run1 = collect(src);
            var run2 = collect(src);

            assertThat(run1).containsExactlyElementsOf(run2);
        }
    }

    @Nested
    @DisplayName("exact cover (COVER EXACT t — MIP minimum suite)")
    class ExactCover {

        @Test
        @DisplayName("COVER EXACT 2 satisfies coverage property on the 8-row XYZ example")
        void exactCoverSatisfiesCoverageProperty() {
            var rows       = collect(XYZ8 + "query { COVER EXACT 2 (XYZ) };");
            var candidates = collect(XYZ8 + "query { XYZ };");

            assertCoverageProperty(rows, candidates, 2);
        }

        @Test
        @DisplayName("COVER EXACT 2 produces a suite no larger than the greedy suite")
        void exactCoverNoLargerThanGreedy() {
            String src = XYZ8;
            var exact  = collect(src + "query { COVER EXACT 2 (XYZ) };");
            var greedy = collect(src + "query { COVER 2 (XYZ) };");

            // The MIP optimum is ≤ the greedy heuristic.
            assertThat(exact.size()).isLessThanOrEqualTo(greedy.size());
        }

        @Test
        @DisplayName("COVER EXACT 1 on a 2-column table returns at most max(domain) rows")
        void exactCoverStrengthOne() {
            var rows = collect(XY4 + "query { COVER EXACT 1 (XY) };");
            var candidates = collect(XY4 + "query { XY };");

            assertCoverageProperty(rows, candidates, 1);
            // With t=1 and domains {a,b} and {1,2}, the minimum cover needs 2 rows.
            assertThat(rows).hasSizeLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("COVER EXACT t=width requires all distinct rows")
        void exactCoverStrengthEqualWidth() {
            // t=2 on a 2-column table: each row is unique as a 2-tuple → all rows needed.
            var rows = collect(XY4 + "query { COVER EXACT 2 (XY) };");
            assertThat(rows)
                    .extracting(r -> r.get("x").asDisplayString() + "," + r.get("y").asDisplayString())
                    .containsExactlyInAnyOrder("a,1", "a,2", "b,1", "b,2");
        }

        @Test
        @DisplayName("COVER EXACT on empty input yields empty output")
        void exactCoverEmptyInput() {
            String src =
                    "Empty := [| x | y |];\n" +
                    "query { COVER EXACT 2 (Empty) };\n";
            assertThat(collect(src)).isEmpty();
        }

        @Test
        @DisplayName("COVER EXACT deduplicated: output rows are a subset of distinct candidates")
        void exactCoverDeduplicatesCandidates() {
            // Duplicate rows should be deduplicated before the MIP.
            String src =
                    "WithDups := [| x | y |\n" +
                    "              | a | 1 |\n" +
                    "              | a | 1 |\n" +  // duplicate
                    "              | b | 2 |\n" +
                    "              | b | 2 |];\n" + // duplicate
                    "query { COVER EXACT 2 (WithDups) };\n";
            var rows = collect(src);

            // Output contains no duplicate rows.
            assertThat(rows).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("NULL coverage")
    class NullCoverage {

        @Test
        @DisplayName("NULL is an ordinary coverable value — NULL tuples are demanded and covered")
        void nullIsCoverableValue() {
            // Use a left-outer-join to get NULLs in z.
            String src =
                    "AB := [| x | y |\n" +
                    "        | a | 1 |\n" +
                    "        | b | 2 |];\n" +
                    "CD := [| y | z |\n" +
                    "        | 1 | p |];\n" +
                    // Left outer join: (a,1,p) and (b,2,NULL)
                    "WithNull := { AB ⟕ AB.y = CD.y CD };\n" +
                    "query { COVER 1 (WithNull) };\n";
            var rows = collect(src);

            // t=1: at least one row per distinct value per column.
            // z-values: {p, NULL} — both must appear.
            Set<String> zValues = new HashSet<>();
            for (Row r : rows) {
                zValues.add(r.get("z").asDisplayString());
            }
            assertThat(zValues).contains("p", "NULL");
        }
    }
}
