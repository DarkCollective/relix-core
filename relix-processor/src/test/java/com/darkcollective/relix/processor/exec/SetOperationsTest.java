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
import com.darkcollective.relix.semantic.SemanticModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

@DisplayName("RelNodeExecutor — set operations")
final class SetOperationsTest extends ProcessorTestSupport {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RelNode queryNode(QueryStatement query) {
        return switch (query.target()) {
            case NamedQueryTarget   named -> rel(named.name());
            case ExpressionQueryTarget e  -> e.expression();
        };
    }

    private static List<Row> collect(String src) {
        SemanticModel model = model(src);
        ExecutionContext ctx = ExecutionContext.inlineOnly(model);
        var query = model.rootQueries().getFirst();
        try (Stream<Row> stream = EXECUTOR.execute(queryNode(query), ctx)) {
            return stream.toList();
        }
    }

    @Nested
    @DisplayName("a number is one value however it was written")
    class NumericIdentity {

        private static final String SCALES = """
                Nums := [
                | x |
                |---|
                | 5 |
                | 5.0 |
                ];
                """;

        /**
         * The engine used to hold two answers to <em>are these the same number</em>. A
         * comparison said yes and a hash said no, so a σ kept both rows while a δ kept
         * them apart — and since the display normalises the scale, DISTINCT returned the
         * same visible number twice.
         */
        @Test
        @DisplayName("δ keeps one of 5 and 5.0")
        void distinctDeduplicatesAcrossScales() {
            assertThat(collect(SCALES + "query { δ (Nums) };\n")).hasSize(1);
        }

        @Test
        @DisplayName("γ puts them in one group")
        void groupingIsByValue() {
            assertThat(collect(SCALES + "query { γ x, COUNT(x) → n (Nums) };\n"))
                    .singleElement()
                    .satisfies(r -> assertThat(r.get("n").asDisplayString()).isEqualTo("2"));
        }

        @Test
        @DisplayName("∩ and − read them as the same row too")
        void setOperationsAgree() {
            String pair = """
                    A := [
                    | x |
                    |---|
                    | 5.0 |
                    ];
                    B := [
                    | x |
                    |---|
                    | 5 |
                    ];
                    """;
            assertThat(collect(pair + "query { A ∩ B };\n"))
                    .as("the intersection is not empty — they are one value")
                    .hasSize(1);
            assertThat(collect(pair + "query { A − B };\n"))
                    .as("and the difference is")
                    .isEmpty();
        }

        @Test
        @DisplayName("σ agreed all along, and still does")
        void comparisonUnchanged() {
            assertThat(collect(SCALES + "query { σ x = 5 (Nums) };\n"))
                    .as("both rows are the number 5; δ is what decides they are one row")
                    .hasSize(2);
        }
    }

    // ── Positional identity ───────────────────────────────────────────────────

    @Nested
    @DisplayName("a tuple is the same tuple whatever the heading calls its columns")
    class PositionalIdentity {

        /**
         * Two relations that hold one tuple between them, under the rule the analyser
         * applies.
         *
         * <p>{@code RelAlgebraValidator.checkSetOpCompatibility} compares width and
         * positional column <em>types</em> and never compares names, so these are legal
         * operands of ∪, ∩ and −. That is the unnamed reading of union compatibility,
         * and under it {@code ⟨1⟩} and {@code ⟨1⟩} are one tuple.
         *
         * <p>The executor asks a different question. All three operators compare rows
         * through {@code ArrayRow.equals}, which includes the row's {@code Schema}, and a
         * row carries the heading of the node that produced it — no set operation
         * re-projects its inputs onto the output heading the way ⊔ does. So the analyser
         * reads set compatibility positionally while the executor reads tuple identity by
         * name. Either reading is defensible; holding both at once is not, and they
         * disagree on every pair of branches whose headings differ.
         *
         * <p>{@code SetRelation}'s own javadoc states the assumption being broken —
         * <em>the schema shared by all rows</em> — so the type is documented for a
         * precondition its callers do not establish.
         */
        private static final String DIFFERENT_NAMES =
                "L := [| a |\n" +
                "       | 1 |];\n" +
                "R := [| b |\n" +
                "       | 1 |];\n";

        @Test
        @DisplayName("∪ returns the tuple once")
        void unionDeduplicatesAcrossHeadings() {
            assertThat(collect(DIFFERENT_NAMES + "query { L ∪ R };"))
                    .as("one tuple, written under two headings")
                    .hasSize(1);
        }

        @Test
        @DisplayName("∩ finds the tuple on both sides")
        void intersectionMatchesAcrossHeadings() {
            assertThat(collect(DIFFERENT_NAMES + "query { L ∩ R };"))
                    .as("the tuple is in both, so it is in the intersection")
                    .hasSize(1);
        }

        @Test
        @DisplayName("− subtracts it")
        void differenceSubtractsAcrossHeadings() {
            assertThat(collect(DIFFERENT_NAMES + "query { L − R };"))
                    .as("everything in L is in R, so nothing survives")
                    .isEmpty();
        }

        /**
         * The cheaper half of the same defect. {@code ColumnDefinition.equals} compares
         * names with {@code String.equals}, so a heading differing only in case is a
         * different heading to the executor — while name resolution everywhere above it
         * is case-insensitive.
         */
        @Test
        @DisplayName("a heading differing only in case is the same heading")
        void caseIsNotADifferenceOfHeading() {
            String src =
                    "L := [| a |\n" +
                    "       | 1 |];\n" +
                    "R := [| A |\n" +
                    "       | 1 |];\n";
            assertThat(collect(src + "query { L ∪ R };")).hasSize(1);
        }
    }

    // ── UnionNode ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("UnionNode (∪ / UNION) — set union, no duplicates")
    class Union {

        @Test
        @DisplayName("rows from both sides are included")
        void includesBothSides() {
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "B := [| id | name |\n" +
                    "       | 3  | Carol |\n" +
                    "       | 4  | Dave  |];\n" +
                    "query { A ∪ B };");

            assertThat(rows).hasSize(4);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Bob", "Carol", "Dave");
        }

        @Test
        @DisplayName("duplicate rows from both sides are eliminated")
        void eliminatesDuplicates() {
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "B := [| id | name |\n" +
                    "       | 2  | Bob   |\n" +
                    "       | 3  | Carol |];\n" +
                    "query { A ∪ B };");

            // Bob(2) appears in both but counted only once
            assertThat(rows).hasSize(3);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Bob", "Carol");
        }

        @Test
        @DisplayName("union with self returns same distinct rows")
        void unionWithSelf() {
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "query { A ∪ A };");

            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("union with empty right returns left rows deduplicated")
        void emptyRight() {
            // Use a selection that returns no rows to get a typed-empty relation
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "Empty := { σ id > 1000 (A) };\n" +
                    "query { A ∪ Empty };");

            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("union of two empty relations is empty")
        void bothEmpty() {
            var rows = collect(
                    "A := [| id | name |];\n" +
                    "B := [| id | name |];\n" +
                    "query { A ∪ B };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("output schema uses left relation columns")
        void outputSchema() {
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |];\n" +
                    "B := [| id | name |\n" +
                    "       | 2  | Bob  |];\n" +
                    "query { A ∪ B };");

            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("id", "name");
        }

        @Test
        @DisplayName("left-first insertion order preserved for unique rows")
        void insertionOrderPreserved() {
            var rows = collect(
                    "A := [| id | val |\n" +
                    "       | 1  | a   |\n" +
                    "       | 2  | b   |];\n" +
                    "B := [| id | val |\n" +
                    "       | 3  | c   |\n" +
                    "       | 4  | d   |];\n" +
                    "query { A ∪ B };");

            // Left rows first, then right rows (all distinct)
            assertThat(rows).extracting(r -> r.get("id").asDisplayString())
                    .containsExactly("1", "2", "3", "4");
        }
    }

    // ── UnionAllNode ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("UnionAllNode (⊎ / UALL) — bag union, duplicates kept")
    class UnionAll {

        @Test
        @DisplayName("all rows from both sides included, including duplicates")
        void keepsDuplicates() {
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "B := [| id | name |\n" +
                    "       | 2  | Bob   |\n" +
                    "       | 3  | Carol |];\n" +
                    "query { A ⊎ B };");

            // Bob(2) appears twice — both copies kept
            assertThat(rows).hasSize(4);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactly("Alice", "Bob", "Bob", "Carol");
        }

        @Test
        @DisplayName("left rows appear before right rows")
        void preservesOrder() {
            var rows = collect(
                    "A := [| id | v |\n" +
                    "       | 1  | x |\n" +
                    "       | 2  | y |];\n" +
                    "B := [| id | v |\n" +
                    "       | 3  | z |];\n" +
                    "query { A ⊎ B };");

            assertThat(rows).extracting(r -> r.get("id").asDisplayString())
                    .containsExactly("1", "2", "3");
        }

        @Test
        @DisplayName("empty right: returns left rows unchanged")
        void emptyRight() {
            var rows = collect(
                    "A := [| id | v |\n" +
                    "       | 1  | x |];\n" +
                    "Empty := { σ id > 1000 (A) };\n" +
                    "query { A ⊎ Empty };");

            assertThat(rows).hasSize(1);
        }

        @Test
        @DisplayName("union all of self doubles the row count")
        void selfDoubles() {
            var rows = collect(
                    "A := [| id | v |\n" +
                    "       | 1  | x |\n" +
                    "       | 2  | y |];\n" +
                    "query { A ⊎ A };");

            assertThat(rows).hasSize(4);
        }

        @Test
        @DisplayName("union vs union-all: UALL has more rows when duplicates exist")
        void uallHasMoreThanUnion() {
            String data =
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "B := [| id | name |\n" +
                    "       | 2  | Bob   |\n" +
                    "       | 3  | Carol |];\n";

            var unionRows  = collect(data + "query { A ∪ B };");
            var unionAllRows = collect(data + "query { A ⊎ B };");

            assertThat(unionRows).hasSize(3);      // deduped
            assertThat(unionAllRows).hasSize(4);   // Bob kept twice
        }
    }

    // ── IntersectionNode ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("IntersectionNode (∩ / INTER) — rows in both sides")
    class Intersection {

        @Test
        @DisplayName("returns rows present in both sides")
        void returnsCommonRows() {
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |\n" +
                    "       | 3  | Carol |];\n" +
                    "B := [| id | name |\n" +
                    "       | 2  | Bob   |\n" +
                    "       | 3  | Carol |\n" +
                    "       | 4  | Dave  |];\n" +
                    "query { A ∩ B };");

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Bob", "Carol");
        }

        @Test
        @DisplayName("disjoint sets produce empty intersection")
        void disjointSetsEmpty() {
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "B := [| id | name |\n" +
                    "       | 3  | Carol |\n" +
                    "       | 4  | Dave  |];\n" +
                    "query { A ∩ B };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("intersection with empty set returns empty")
        void intersectEmpty() {
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |];\n" +
                    "Empty := { σ id > 1000 (A) };\n" +
                    "query { A ∩ Empty };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("intersection with self returns all rows (once each)")
        void intersectSelf() {
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "query { A ∩ A };");

            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("a row duplicated on the left comes back once — ∩ is a set operation on both sides")
        void deduplicatesTheLeftInput() {
            // The neighbouring self-intersection case cannot see this: its fixture has no
            // duplicate, so a bag intersection and a set one agree on it. Found by the
            // generated search (#722), which drew δ (A ∩ A) and watched DIST-001 remove
            // the δ — sound only if ∩ really is duplicate-free, which it was not.
            var rows = collect(
                    "A := [| id | name  |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "query { A ∩ A };");

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Bob");
        }

        @Test
        @DisplayName("only rows matching on ALL columns are included")
        void matchesOnAllColumns() {
            var rows = collect(
                    "A := [| id | name  |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 1  | Bob   |];\n" +  // same id, different name
                    "B := [| id | name  |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 1  | Carol |];\n" +  // same id, different name
                    "query { A ∩ B };");

            // Only (1, Alice) appears in both
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("name", "Alice");
        }
    }

    // ── DifferenceNode ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DifferenceNode (− / DIFF) — left minus right")
    class Difference {

        @Test
        @DisplayName("returns rows in left but not in right")
        void leftMinusRight() {
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |\n" +
                    "       | 3  | Carol |];\n" +
                    "B := [| id | name |\n" +
                    "       | 2  | Bob   |];\n" +
                    "query { A − B };");

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactlyInAnyOrder("Alice", "Carol");
        }

        @Test
        @DisplayName("difference with empty right returns all left rows")
        void emptyRightReturnsAll() {
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "Empty := { σ id > 1000 (A) };\n" +
                    "query { A − Empty };");

            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("difference where right is superset returns empty")
        void supersetRightEmpty() {
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |];\n" +
                    "B := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "query { A − B };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("a row duplicated on the left comes back once — − is a set operation on both sides")
        void deduplicatesTheLeftInput() {
            var rows = collect(
                    "A := [| id | name  |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "B := [| id | name  |\n" +
                    "       | 2  | Bob   |];\n" +
                    "query { A − B };");

            assertThat(rows).hasSize(1);
            assertThat(rows).extracting(r -> r.get("name").asDisplayString())
                    .containsExactly("Alice");
        }

        @Test
        @DisplayName("self-difference returns empty")
        void selfDifferenceEmpty() {
            var rows = collect(
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "query { A − A };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("difference is not commutative")
        void notCommutative() {
            String data =
                    "A := [| id | name |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 2  | Bob   |];\n" +
                    "B := [| id | name |\n" +
                    "       | 2  | Bob   |\n" +
                    "       | 3  | Carol |];\n";

            var aMinusB = collect(data + "query { A − B };");  // Alice only
            var bMinusA = collect(data + "query { B − A };");  // Carol only

            assertThat(aMinusB).extracting(r -> r.get("name").asDisplayString())
                    .containsExactly("Alice");
            assertThat(bMinusA).extracting(r -> r.get("name").asDisplayString())
                    .containsExactly("Carol");
        }

        @Test
        @DisplayName("difference matches on all columns, not just one")
        void matchesOnAllColumns() {
            var rows = collect(
                    "A := [| id | name  |\n" +
                    "       | 1  | Alice |\n" +
                    "       | 1  | Bob   |];\n" +
                    "B := [| id | name  |\n" +
                    "       | 1  | Alice |];\n" +
                    "query { A − B };");

            // Only (1, Alice) removed; (1, Bob) differs in name → kept
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("name", "Bob");
        }
    }

    // ── DivisionNode ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DivisionNode (÷ / DIV) — relational for-all")
    class Division {

        @Test
        @DisplayName("basic division: students enrolled in all required courses")
        void basicDivision() {
            var rows = collect(
                    "Enrollment := [| student_id | course_id |\n" +
                    "               | 1           | c1        |\n" +
                    "               | 1           | c2        |\n" +
                    "               | 2           | c1        |\n" +
                    "               | 3           | c1        |\n" +
                    "               | 3           | c2        |];\n" +
                    "Required := [| course_id |\n" +
                    "              | c1        |\n" +
                    "              | c2        |];\n" +
                    "query { Enrollment ÷ Required };");

            // Students 1 and 3 took both c1 and c2; student 2 only took c1
            assertThat(rows).hasSize(2);
            Set<String> ids = rows.stream()
                    .map(r -> r.get("student_id").asDisplayString())
                    .collect(Collectors.toSet());
            assertThat(ids).containsExactlyInAnyOrder("1", "3");
        }

        @Test
        @DisplayName("output schema is left columns minus right columns")
        void outputSchemaCorrect() {
            var rows = collect(
                    "Enrollment := [| student_id | course_id |\n" +
                    "               | 1           | c1        |];\n" +
                    "Required := [| course_id |\n" +
                    "              | c1        |];\n" +
                    "query { Enrollment ÷ Required };");

            assertThat(rows.get(0).schema().columns())
                    .extracting(c -> c.name())
                    .containsExactly("student_id");
        }

        @Test
        @DisplayName("no candidate satisfies all divisor rows → empty result")
        void noSatisfyingCandidates() {
            var rows = collect(
                    "Pairs := [| a | b |\n" +
                    "           | 1 | x |\n" +
                    "           | 2 | y |];\n" +
                    "Divisor := [| b |\n" +
                    "             | x |\n" +
                    "             | y |];\n" +
                    "query { Pairs ÷ Divisor };");

            // No single value of a appears with both x and y
            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("empty left relation returns empty result")
        void emptyLeftEmpty() {
            var rows = collect(
                    "Pairs := [| a | b |];\n" +
                    "Divisor := [| b |\n" +
                    "             | x |];\n" +
                    "query { Pairs ÷ Divisor };");

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("empty divisor: vacuous truth returns all distinct payload projections")
        void emptyDivisorReturnsAll() {
            // Use a filtered projection to get a typed-empty divisor relation
            var rows = collect(
                    "Pairs := [| a | b |\n" +
                    "           | 1 | x |\n" +
                    "           | 2 | y |\n" +
                    "           | 1 | z |];\n" +
                    "AllB := { π b (Pairs) };\n" +
                    "EmptyDivisor := { σ b = \"zzz\" (AllB) };\n" +
                    "query { Pairs ÷ EmptyDivisor };");

            // Vacuously true — all distinct a values: 1 and 2
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r.get("a").asDisplayString())
                    .containsExactlyInAnyOrder("1", "2");
        }

        @Test
        @DisplayName("three-column left: payload has two columns")
        void threeColumnLeft() {
            var rows = collect(
                    "Data := [| x | y | z |\n" +
                    "          | 1 | a | p |\n" +
                    "          | 1 | b | p |\n" +
                    "          | 2 | a | p |\n" +
                    "          | 2 | b | q |];\n" +  // (2,b) misses z=p
                    "Divisor := [| z |\n" +
                    "             | p |];\n" +
                    "query { Data ÷ Divisor };");

            // (1,a), (1,b), (2,a) have z=p; (2,b) does not → x=1 qualifies, x=2 does not
            // Result payload: (x, y)
            // (1,a) has z=p ✓; (1,b) has z=p ✓ → payload (1,a) and (1,b) qualify
            // (2,a) has z=p ✓; (2,b) does NOT have z=p ✗ → (2,a) qualifies but (2,b) does not
            // Divisor = {z=p}: check each (x,y) candidate
            // (1,a): ∃ row (1,a,p) ✓
            // (1,b): ∃ row (1,b,p) ✓
            // (2,a): ∃ row (2,a,p) ✓
            // (2,b): ∃ row (2,b,p)? NO (only (2,b,q)) ✗
            assertThat(rows).hasSize(3);
            Set<String> keys = rows.stream()
                    .map(r -> r.get("x").asDisplayString() + "/" + r.get("y").asDisplayString())
                    .collect(Collectors.toSet());
            assertThat(keys).containsExactlyInAnyOrder("1/a", "1/b", "2/a");
        }

        @Test
        @DisplayName("composed: division result used as input to selection")
        void divisionThenFilter() {
            var rows = collect(
                    "Enrollment := [| student_id | course_id |\n" +
                    "               | 1           | c1        |\n" +
                    "               | 1           | c2        |\n" +
                    "               | 2           | c1        |\n" +
                    "               | 3           | c1        |\n" +
                    "               | 3           | c2        |];\n" +
                    "Required := [| course_id |\n" +
                    "              | c1        |\n" +
                    "              | c2        |];\n" +
                    "Qualified := { Enrollment ÷ Required };\n" +
                    "query { σ student_id > 1 (Qualified) };");

            // Student 1 and 3 qualify; after σ student_id > 1, only student 3
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).hasValue("student_id", "3");
        }
    }

    // ── Set-operation identities ──────────────────────────────────────────────

    @Nested
    @DisplayName("Set-operation algebraic identities")
    class Identities {

        @Test
        @DisplayName("A ∩ B = A − (A − B)")
        void intersectionVsDifference() {
            String data =
                    "A := [| id | v |\n" +
                    "       | 1  | a |\n" +
                    "       | 2  | b |\n" +
                    "       | 3  | c |];\n" +
                    "B := [| id | v |\n" +
                    "       | 2  | b |\n" +
                    "       | 3  | c |\n" +
                    "       | 4  | d |];\n";

            var intersect = collect(data + "query { A ∩ B };");
            var diffOfDiff = collect(data +
                    "AminusB := { A − B };\n" +
                    "query { A − AminusB };");

            Set<String> interIds = intersect.stream()
                    .map(r -> r.get("id").asDisplayString()).collect(Collectors.toSet());
            Set<String> diffIds  = diffOfDiff.stream()
                    .map(r -> r.get("id").asDisplayString()).collect(Collectors.toSet());

            assertThat(interIds).isEqualTo(diffIds);
        }

        @Test
        @DisplayName("union-all row count equals sum of both sides")
        void unionAllRowCount() {
            var rows = collect(
                    "A := [| id | v |\n" +
                    "       | 1  | a |\n" +
                    "       | 2  | b |\n" +
                    "       | 3  | c |];\n" +
                    "B := [| id | v |\n" +
                    "       | 4  | d |\n" +
                    "       | 5  | e |];\n" +
                    "query { A ⊎ B };");

            assertThat(rows).hasSize(5);  // 3 + 2
        }

        @Test
        @DisplayName("union row count = |A| + |B| - |A ∩ B|")
        void unionCardinality() {
            String data =
                    "A := [| id | v |\n" +
                    "       | 1  | a |\n" +
                    "       | 2  | b |\n" +
                    "       | 3  | c |];\n" +
                    "B := [| id | v |\n" +
                    "       | 2  | b |\n" +
                    "       | 3  | c |\n" +
                    "       | 4  | d |];\n";

            var union = collect(data + "query { A ∪ B };");
            assertThat(union).hasSize(4);  // |A|=3, |B|=3, |A∩B|=2 → 3+3-2=4
        }
    }
}
