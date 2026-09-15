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
package com.darkcollective.relix.processor;

import com.darkcollective.relix.semantic.SemanticResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

/**
 * End-to-end tests for the nullary truth-relation literals (issue #51): parse →
 * semantic analysis → planning → execution, for {@code UNIT}/{@code DEE} (one
 * empty tuple) and {@code EMPTY}/{@code DUM} (no tuple).
 */
@DisplayName("Truth relations — UNIT / EMPTY end-to-end")
final class TruthRelationIntegrationTest extends ProcessorTestSupport {

    private static final QueryExecutor EXECUTOR = new QueryExecutor();

    private static final String TRADES =
            "Trades := [| trade_id | desk  | status  |\n" +
            "           | 1        | FX    | settled |\n" +
            "           | 2        | FX    | pending |\n" +
            "           | 3        | RATES | settled |];\n";

    /** Analyzes, asserts no semantic errors, then executes. */
    private static List<QueryResult> run(String src) {
        SemanticResult result = analyze(src);
        assertThat(result.errors()).as("semantic errors").isEmpty();
        return EXECUTOR.execute(result);
    }

    private static QueryResult only(String src) {
        List<QueryResult> results = run(src);
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    @Nested
    @DisplayName("the literals themselves")
    class Literals {

        @Test
        @DisplayName("UNIT yields exactly one row, with no columns")
        void unitYieldsOneEmptyRow() {
            QueryResult result = only("query { UNIT };");
            assertThat(result.schema().width()).isZero();
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0).schema().columns()).isEmpty();
        }

        @Test
        @DisplayName("EMPTY yields no rows, with no columns")
        void emptyYieldsNoRows() {
            QueryResult result = only("query { EMPTY };");
            assertThat(result.schema().width()).isZero();
            assertThat(result.rows()).isEmpty();
        }

        @Test
        @DisplayName("DEE and DUM execute identically to UNIT and EMPTY")
        void aliasesBehaveIdentically() {
            assertThat(only("query { DEE };").rows()).hasSize(1);
            assertThat(only("query { DUM };").rows()).isEmpty();
        }
    }

    @Nested
    @DisplayName("UNIT is the identity of ×")
    class ProductIdentity {

        @Test
        @DisplayName("R × UNIT returns R — same rows, same column order")
        void productWithUnitReturnsInputUnchanged() {
            QueryResult withUnit = only(TRADES + "query { Trades × UNIT };");
            QueryResult plain    = only(TRADES + "query { Trades };");

            assertThat(withUnit.schema().columns().stream().map(c -> c.name()).toList())
                    .isEqualTo(plain.schema().columns().stream().map(c -> c.name()).toList());
            assertThat(withUnit.rows()).hasSize(3);
            assertThat(withUnit.rows()).extracting(r -> r.get("trade_id").asDisplayString())
                    .containsExactly("1", "2", "3");
        }

        @Test
        @DisplayName("UNIT × R is likewise R")
        void unitOnTheLeftIsAlsoTheIdentity() {
            QueryResult result = only(TRADES + "query { UNIT × Trades };");
            assertThat(result.rows()).hasSize(3);
            assertThat(result.rows()).extracting(r -> r.get("desk").asDisplayString())
                    .containsExactly("FX", "FX", "RATES");
        }

        @Test
        @DisplayName("UNIT × UNIT is UNIT — two column-less headings still concatenate")
        void productOfTwoUnitsIsUnit() {
            QueryResult result = only("query { UNIT × UNIT };");
            assertThat(result.rows()).hasSize(1);
            assertThat(result.schema().columns()).isEmpty();
        }

        @Test
        @DisplayName("EMPTY × UNIT has no rows and no columns")
        void productOfEmptyAndUnitIsEmpty() {
            QueryResult result = only("query { EMPTY × UNIT };");
            assertThat(result.rows()).isEmpty();
            assertThat(result.schema().columns()).isEmpty();
        }

        @Test
        @DisplayName("R × EMPTY is empty but keeps R's heading — it is not EMPTY itself")
        void productWithEmptyKeepsTheHeading() {
            QueryResult result = only(TRADES + "query { Trades × EMPTY };");
            assertThat(result.rows()).isEmpty();
            assertThat(result.schema()).hasColumnNames("trade_id", "desk", "status");
        }
    }

    @Nested
    @DisplayName("gating a result on a no-key ∀")
    class Gating {

        private static final String BANNER =
                "Banner := [| message                    |\n" +
                "           | Book is flat, signed off.  |];\n";

        @Test
        @DisplayName("the gate is closed while some trade is unsettled")
        void gateClosedWhenPredicateFails() {
            QueryResult result = only(TRADES + BANNER
                    + "AllSettled := { ∀ : status = \"settled\" (Trades) };\n"
                    + "query { Banner × AllSettled };");
            assertThat(result.rows()).isEmpty();
        }

        @Test
        @DisplayName("the gate opens — one banner row — once every trade is settled")
        void gateOpenWhenPredicateHolds() {
            String settled =
                    "Trades := [| trade_id | desk  | status  |\n" +
                    "           | 1        | FX    | settled |\n" +
                    "           | 2        | RATES | settled |];\n";
            QueryResult result = only(settled + BANNER
                    + "AllSettled := { ∀ : status = \"settled\" (Trades) };\n"
                    + "query { Banner × AllSettled };");
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0).get("message").asDisplayString())
                    .isEqualTo("Book is flat, signed off.");
        }
    }

    @Nested
    @DisplayName("composition with other operators")
    class Composition {

        @Test
        @DisplayName("δ (UNIT) is still the one empty row")
        void distinctOverUnit() {
            assertThat(only("query { δ (UNIT) };").rows()).hasSize(1);
        }

        @Test
        @DisplayName("UNIT ∪ EMPTY is UNIT — union-compatible, both zero-column")
        void unionOfTheTwoLiterals() {
            QueryResult result = only("query { UNIT ∪ EMPTY };");
            assertThat(result.schema().width()).isZero();
            assertThat(result.rows()).hasSize(1);
        }

        @Test
        @DisplayName("UNIT − UNIT is EMPTY")
        void differenceOfUnitWithItself() {
            assertThat(only("query { UNIT − UNIT };").rows()).isEmpty();
        }

        @Test
        @DisplayName("a relation named 'unit' is declarable, and reachable via backticks")
        void backtickedSpellingReachesAUserRelation() {
            QueryResult result = only(
                    "unit := [| symbol |\n"
                    + "        | kg     |];\n"
                    + "query { `unit` };");
            assertThat(result.rows()).hasSize(1);
            assertThat(result.rows().get(0)).hasValue("symbol", "kg");
        }

        @Test
        @DisplayName("without backticks the same reference is the literal, not the relation")
        void bareSpellingIsTheLiteral() {
            QueryResult result = only(
                    "unit := [| symbol |\n"
                    + "        | kg     |];\n"
                    + "query { unit };");
            assertThat(result.schema().width()).as("the zero-column literal, not the table").isZero();
            assertThat(result.rows()).hasSize(1);
        }
    }
}
