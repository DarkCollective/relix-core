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
package com.darkcollective.relix.semantic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;

/**
 * SUM and AVG add only numbers, so over a BOOLEAN or a STRING they returned NULL for
 * every group without a word (#220). SQL's way of counting a condition,
 * {@code SUM(status = 'open')}, is how a reader gets there.
 */
@DisplayName("SUM and AVG over a value that is not a number are refused")
final class SummedTypeTest {

    private static final String ISSUES = """
            I := [
            | sprint_id | status | points |
            |---|---|---|
            | 1 | open | 3 |
            | 2 | closed | 5 |
            ];
            """;

    @Test
    @DisplayName("SUM of a condition names the count that works")
    void sumOfConditionNamesTheCount() {
        assertThat(analyze(ISSUES + "query { GROUP sprint_id, SUM((status = 'open')) -> open (I) };"))
                .hasErrorContaining("SUM takes numbers, and status = \"open\" is a BOOLEAN: to count the rows"
                        + " where it holds, write SUM(IIf(status = \"open\", 1, 0))"
                        + " or COUNT(*) FILTER (WHERE status = \"open\")");
    }

    @Test
    @DisplayName("SUM or AVG of a STRING is refused, and AVG of a condition names the share")
    void sumOfStringAndAvgOfCondition() {
        assertThat(analyze(ISSUES + "query { GROUP sprint_id, SUM(status) -> s (I) };"))
                .hasErrorContaining("SUM takes numbers, and status is a STRING");
        // A struct or an array adds to NULL just the same.
        assertThat(analyze(ISSUES + "query { GROUP SUM([points]) -> s (I) };"))
                .hasErrorContaining("SUM takes numbers, and [points] is array<number>");
        assertThat(analyze(ISSUES + "query { GROUP AVG((status = 'open')) -> share (I) };"))
                .hasErrorContaining("AVG takes numbers, and status = \"open\" is a BOOLEAN: for the share"
                        + " of rows where it holds, write AVG(IIf(status = \"open\", 1, 0))");
    }

    @Test
    @DisplayName("a ROLLING SUM of a condition is refused too, naming IIf alone")
    void rollingSumOfCondition() {
        assertThat(analyze(ISSUES + "query { ROLLING SUM((status = 'open')) OVER ALL ROWS SORT sprint_id"
                + " AS running (I) };"))
                .hasErrorContaining("SUM takes numbers, and status = \"open\" is a BOOLEAN: to count the rows"
                        + " where it holds, write SUM(IIf(status = \"open\", 1, 0))")
                .hasNoErrorContaining("FILTER");
    }

    @Test
    @DisplayName("the spellings the message suggests are valid, as is a SUM of numbers")
    void suggestionsAreValid() {
        assertThat(analyze(ISSUES + "query { GROUP sprint_id, SUM(IIf(status = 'open', 1, 0)) -> open,"
                + " COUNT(*) FILTER (WHERE status = 'open') -> also, AVG(IIf(status = 'open', 1, 0)) -> share,"
                + " SUM(points) -> points (I) };"))
                .hasNoErrors();
    }
}
