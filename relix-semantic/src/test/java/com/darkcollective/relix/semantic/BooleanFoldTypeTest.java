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
 * BOOL_AND and BOOL_OR fold BOOLEANs, so any other argument is refused as the query is
 * analysed rather than when the first row arrives (#295).
 */
@DisplayName("BOOL_AND and BOOL_OR over a value that is not a BOOLEAN are refused")
final class BooleanFoldTypeTest {

    private static final String ISSUES = """
            I := [
            | sprint_id | status | done |
            |---|---|---|
            | 1 | open | 0 |
            | 2 | closed | 1 |
            ];
            """;

    @Test
    @DisplayName("a 0/1 flag is refused with the comparison that folds it, and a STRING without one")
    void nonBooleanRefused() {
        assertThat(analyze(ISSUES + "query { GROUP sprint_id, BOOL_AND(done) -> all_done (I) };"))
                .hasErrorContaining("BOOL_AND takes a condition, and done is a NUMBER: to fold a 0/1 flag,"
                        + " write BOOL_AND(done = 1)");
        assertThat(analyze(ISSUES + "query { GROUP EVERY(status) -> s (I) };"))
                .hasErrorContaining("BOOL_AND takes a condition, and status is a STRING")
                .hasNoErrorContaining("0/1 flag");
        assertThat(analyze(ISSUES + "query { ROLLING BOOL_OR(done) OVER ALL ROWS SORT sprint_id AS any_done (I) };"))
                .hasErrorContaining("BOOL_OR takes a condition, and done is a NUMBER");
    }

    @Test
    @DisplayName("a condition, a FILTER and a rolling window are accepted")
    void conditionsAccepted() {
        assertThat(analyze(ISSUES + "query { GROUP sprint_id, BOOL_AND(done = 1) -> all_done,"
                + " BOOL_OR(status = 'open') FILTER (WHERE done = 0) -> any_open (I) };"))
                .hasNoErrors();
        assertThat(analyze(ISSUES + "query { ROLLING BOOL_OR(done = 1) OVER ALL ROWS SORT sprint_id AS any_done (I) };"))
                .hasNoErrors();
        // A BOOLEAN column, and one whose type is not known until a row arrives.
        assertThat(analyze("source F from csv(\"f.csv\") { schema: { k: NUMBER, flag: BOOLEAN, raw: ANY } };\n"
                + "query { GROUP k, BOOL_AND(flag) -> a, BOOL_OR(raw) -> o (F) };"))
                .hasNoErrors();
    }
}
