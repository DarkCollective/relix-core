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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;

/** A library function's arguments are held to the types it accepts at analysis (#252). */
@DisplayName("a function argument of a type the function does not take is refused at analysis")
final class ArgumentTypesTest {

    private static final String T = """
            T := [
            | k | ts                  | n | s |
            |---|---------------------|---|---|
            | 1 | 2026-03-02T10:15:00 | 4 | x |
            ];
            """;

    @Test
    @DisplayName("a DATE where DATE_TRUNC takes a timestamp names the CAST to its midnight")
    void dateTruncOfADate() {
        assertThat(analyze(T + "query { PROJECT DATE_TRUNC('month', DATE '2026-03-02') -> m (T) };"))
                .messages()
                .containsExactly("Projection π: DATE_TRUNC's argument ts must be a TIMESTAMP or"
                        + " TIMESTAMPTZ, not a DATE; write CAST(DATE '2026-03-02' AS TIMESTAMP)"
                        + " for the date's midnight");
    }

    @Test
    @DisplayName("text where a timestamp or a number is wanted names the CAST")
    void textArgument() {
        assertThat(analyze(T + "query { PROJECT DATE_TRUNC('month', ts) -> m (T) };"))
                .hasErrorContaining("DATE_TRUNC's argument ts must be a TIMESTAMP or TIMESTAMPTZ,"
                        + " not a STRING; write CAST(ts AS TIMESTAMP) for text that holds one");
        assertThat(analyze(T + "query { PROJECT SQRT('x') -> r (T) };"))
                .hasErrorContaining("SQRT's argument x must be a NUMBER, not a STRING;"
                        + " write CAST(\"x\" AS NUMBER) for text that holds one");
    }

    @Test
    @DisplayName("a date or time part says which types it reads")
    void datePartsSayWhatTheyRead() {
        assertThat(analyze(T + "query { PROJECT YEAR(5) -> y (T) };"))
                .hasErrorContaining("YEAR's argument d must be a TIMESTAMP, DATE or TIMESTAMPTZ,"
                        + " not a NUMBER");
        assertThat(analyze(T + "query { PROJECT HOUR(DATE '2026-03-02') -> h (T) };"))
                .hasErrorContaining("HOUR's argument t must be a TIMESTAMP, TIME or TIMESTAMPTZ,"
                        + " not a DATE");
        assertThat(analyze(T + "query { PROJECT UPPER(DATE '2026-01-01') -> u (T) };"))
                .hasErrorContaining("UPPER's argument s must be a STRING, not a DATE");
    }

    @Test
    @DisplayName("a condition and a GROUP are checked as a projection is")
    void otherOperators() {
        assertThat(analyze(T + "query { SELECT SQRT(s) > 1 (T) };"))
                .hasErrorContaining("SQRT's argument x must be a NUMBER, not a STRING");
        assertThat(analyze(T + "query { GROUP YEAR(n) -> y, COUNT(*) -> c (T) };"))
                .hasErrorContaining("YEAR's argument d must be a TIMESTAMP, DATE or TIMESTAMPTZ");
    }

    @Test
    @DisplayName("an argument of the right type, or of a type analysis cannot tell, passes")
    void acceptedArguments() {
        assertThat(analyze(T + "query { PROJECT DATE_TRUNC('month', CAST(ts AS TIMESTAMP)) -> m,"
                + " YEAR(DATE '2026-03-02') -> y, HOUR(TIMESTAMP '2026-03-02T10:15:00') -> h,"
                + " SQRT(n) -> r, UPPER(NULL) -> u (T) };")).hasNoErrors();
        // Arithmetic over an operand of unknown type is not known to be a NUMBER.
        assertThat(analyze("source R from csv(\"r.csv\") { schema: { closed: ANY, opened: ANY } };\n"
                + "query { PROJECT DAYS(closed - opened) -> d (R) };")).hasNoErrors();
        // A function with optional arguments may shift its parameters: InStr(start, s, find).
        assertThat(analyze(T + "query { PROJECT InStr(2, s, 'x') -> i (T) };")).hasNoErrors();
    }
}
