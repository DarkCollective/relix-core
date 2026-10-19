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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.semantic.SemanticError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;

@DisplayName("ComparisonTypes — a NUMBER never compares with a STRING (#176)")
final class ComparisonTypesTest {

    /** The table from #176: runner 6 did not finish, and its empty cell is NULL. */
    private static final String RESULTS = """
            results := [
            | runner_id | seconds |
            |-----------|---------|
            | 5         | 1320    |
            | 6         |         |
            ];
            """;

    private static SemanticResult select(String condition) {
        return analyze(RESULTS + "query { SELECT " + condition + " (results) };\n");
    }

    @Nested
    @DisplayName("the six queries from the issue")
    class FromTheIssue {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"seconds != ''", "seconds = ''", "seconds != 'abc'",
                "seconds = '1320'", "seconds > '1000'"})
        @DisplayName("each is refused with one error, at the comparison")
        void refusedOnceAtTheOperator(String condition) {
            SemanticResult result = select(condition);
            assertThat(result).hasDiagnosticCount(1)
                    .hasErrorContaining("cannot compare NUMBER with STRING");
            SemanticError error = result.errors().getFirst();
            // RESULTS is six lines, so the query is line 7; its operator follows "seconds ".
            int operator = "query { SELECT seconds ".length() + 1;
            org.assertj.core.api.Assertions.assertThat(error.line()).isEqualTo(7);
            org.assertj.core.api.Assertions.assertThat(error.column()).isEqualTo(operator);
        }

        @Test
        @DisplayName("= '' names IS NULL, since an empty cell is NULL")
        void emptyEqualsNamesIsNull() {
            assertThat(select("seconds = ''"))
                    .hasErrorContaining("an empty cell is NULL, not '': write seconds IS NULL");
        }

        @Test
        @DisplayName("!= '' names IS NOT NULL")
        void emptyNotEqualsNamesIsNotNull() {
            assertThat(select("seconds != ''"))
                    .hasErrorContaining("an empty cell is NULL, not '': write seconds IS NOT NULL");
        }

        @Test
        @DisplayName("text that holds a number names the number, and the CAST")
        void numericTextNamesTheNumber() {
            assertThat(select("seconds = '1320'"))
                    .hasErrorContaining("compare with the number 1320, or CAST(… AS NUMBER)");
            assertThat(select("seconds > '1000'"))
                    .hasErrorContaining("compare with the number 1000, or CAST(… AS NUMBER)");
        }

        @Test
        @DisplayName("other text names the CAST")
        void otherTextNamesTheCast() {
            assertThat(select("seconds != 'abc'"))
                    .hasErrorContaining("a STRING is never read as a number")
                    .hasErrorContaining("CAST(… AS NUMBER)");
        }

        @Test
        @DisplayName("IS NULL still validates")
        void nullTestStillValidates() {
            assertThat(select("seconds IS NULL")).hasNoErrors();
            assertThat(select("seconds IS NOT NULL")).hasNoErrors();
        }
    }

    @Nested
    @DisplayName("every way a comparison is written")
    class EveryForm {

        @Test
        @DisplayName("the STRING may stand on the left")
        void stringOnTheLeft() {
            assertThat(select("'' = seconds")).hasDiagnosticCount(1)
                    .hasErrorContaining("cannot compare STRING with NUMBER")
                    .hasErrorContaining("write seconds IS NULL");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"seconds < '1'", "seconds <= '1'", "seconds >= '1'",
                "seconds ≠ '1'", "seconds ≤ '1'", "seconds ≥ '1'"})
        @DisplayName("every operator is refused")
        void everyOperator(String condition) {
            assertThat(select(condition)).hasErrorContaining("cannot compare NUMBER with STRING");
        }

        @Test
        @DisplayName("an ordering against '' does not name a null test")
        void orderingAgainstEmpty() {
            assertThat(select("seconds > ''"))
                    .hasErrorContaining("cannot compare NUMBER with STRING")
                    .hasNoErrorContaining("IS NULL");
        }

        @Test
        @DisplayName("= '' against an expression does not name a null test, since no column is")
        void emptyAgainstAnExpression() {
            assertThat(select("seconds + 1 = ''"))
                    .hasErrorContaining("cannot compare NUMBER with STRING")
                    .hasErrorContaining("CAST(… AS NUMBER)")
                    .hasNoErrorContaining("IS NULL");
        }

        @Test
        @DisplayName("a STRING column against a number is refused too")
        void stringColumnAgainstNumber() {
            assertThat(analyze("""
                    T := [
                    | code |
                    |------|
                    | A1   |
                    ];
                    query { SELECT code = 1 (T) };
                    """)).hasErrorContaining("cannot compare STRING with NUMBER")
                    .hasErrorContaining("CAST(… AS NUMBER)");
        }

        @Test
        @DisplayName("BETWEEN is refused at a STRING bound")
        void between() {
            assertThat(select("seconds BETWEEN 1000 AND '2000'"))
                    .hasDiagnosticCount(1)
                    .hasErrorContaining("compare with the number 2000");
            assertThat(select("seconds BETWEEN 1000 AND 2000")).hasNoErrors();
        }

        @Test
        @DisplayName("IN is refused once, at the first STRING member")
        void membership() {
            assertThat(select("seconds IN {1320, '1400', ''}"))
                    .hasDiagnosticCount(1)
                    .hasErrorContaining("cannot test a NUMBER for membership in a set holding a STRING")
                    .hasErrorContaining("compare with the number 1400");
            assertThat(select("seconds ∈ {''}"))
                    .hasErrorContaining("write seconds IS NULL");
            assertThat(select("seconds IN {1320, 1400}")).hasNoErrors();
        }

        @Test
        @DisplayName("a comparison in operand position is refused as one in a selection is")
        void inOperandPosition() {
            assertThat(analyze(RESULTS
                    + "query { PROJECT IIf(seconds = '', 1, 0) -> dnf (results) };\n"))
                    .hasErrorContaining("cannot compare NUMBER with STRING")
                    .hasErrorContaining("write seconds IS NULL");
            assertThat(analyze(RESULTS
                    + "query { PROJECT IIf(seconds IN {1320, '1400'}, 1, 0) -> fast (results) };\n"))
                    .hasErrorContaining("cannot test a NUMBER for membership in a set holding a STRING")
                    .hasErrorContaining("compare with the number 1400");
            assertThat(analyze(RESULTS
                    + "query { PROJECT IIf(seconds IN {1320, 1400}, 1, 0) -> fast (results) };\n"))
                    .hasNoErrors();
        }

        @Test
        @DisplayName("a join condition is refused as a selection is")
        void joinCondition() {
            assertThat(analyze(RESULTS + """
                    runners := [
                    | id | name |
                    |----|------|
                    | A1 | Ann  |
                    ];
                    query { results ⨝ runner_id = id runners };
                    """)).hasErrorContaining("cannot compare NUMBER with STRING");
        }
    }

    @Nested
    @DisplayName("what stays legal")
    class StillLegal {

        @Test
        @DisplayName("a NUMBER against a number, and a CAST of the text")
        void numbersAndCasts() {
            assertThat(select("seconds = 1320")).hasNoErrors();
            assertThat(select("seconds > CAST('1000' AS NUMBER)")).hasNoErrors();
        }

        @Test
        @DisplayName("a STRING against a STRING")
        void stringWithString() {
            assertThat(analyze("""
                    T := [
                    | code |
                    |------|
                    | A1   |
                    ];
                    query { SELECT code = 'A1' ∧ code != '' (T) };
                    """)).hasNoErrors();
        }

        @Test
        @DisplayName("a STRING read as the temporal it holds")
        void stringWithTemporal() {
            assertThat(analyze("""
                    T := [
                    | at         |
                    |------------|
                    | 2024-01-15 |
                    ];
                    query { SELECT at > '2024-01-01' ∧ at = DATE '2024-01-15' (T) };
                    """)).hasNoErrors();
        }
    }
}
