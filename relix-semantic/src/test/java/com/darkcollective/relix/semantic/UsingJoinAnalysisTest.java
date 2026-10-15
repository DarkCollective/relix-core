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

/** What analysis accepts and refuses in a {@code JOIN … USING}. */
@DisplayName("JOIN … USING, analysed")
final class UsingJoinAnalysisTest {

    private static final String DATA = """
            A := [| id | k | x |
            | 1 | 1 | a |];
            B := [| id | k | x |
            | 2 | 1 | b |];
            S := [| k   |
            | one |];
            N := [| k    |
            | NULL |];
            source J from json("j.json");
            """;

    @Test
    @DisplayName("a column either side lacks is refused, naming the side")
    void missingColumn() {
        assertThat(analyze(DATA + "query { A JOIN B USING (nope) };"))
                .hasErrorContaining("USING column 'nope' is not a column of the left input");
        assertThat(analyze(DATA + "query { A JOIN S USING (id) };"))
                .hasErrorContaining("USING column 'id' is not a column of the right input");
    }

    @Test
    @DisplayName("a column typed differently on each side is refused; one with no settled type is not")
    void types() {
        assertThat(analyze(DATA + "query { A JOIN S USING (k) };"))
                .hasErrorContaining("USING column 'k' is NUMBER on the left and STRING on the right");
        assertThat(analyze(DATA + "query { A JOIN N USING (k) };")).hasNoErrors();
        assertThat(analyze(DATA + "query { N JOIN A USING (k) };")).hasNoErrors();
    }

    @Test
    @DisplayName("a schema-on-read input on either side is refused")
    void openInput() {
        assertThat(analyze(DATA + "query { A JOIN J USING (k) };"))
                .hasErrorContaining("USING join: open (schema-on-read) input is not supported");
        assertThat(analyze(DATA + "query { J JOIN A USING (k) };"))
                .hasErrorContaining("USING join: open (schema-on-read) input is not supported");
    }

    @Test
    @DisplayName("an input that does not analyse leaves the join unannotated rather than failing")
    void brokenInput() {
        assertThat(analyze(DATA + "query { A JOIN π nope (B) USING (k) };"))
                .hasErrorContaining("nope");
        assertThat(analyze(DATA + "query { π nope (B) JOIN A USING (k) };"))
                .hasErrorContaining("nope");
        assertThat(analyze(DATA + "query { A JOIN Nope USING (k) };")).hasErrorContaining("Nope");
        assertThat(analyze(DATA + "query { Nope JOIN A USING (k) };")).hasErrorContaining("Nope");
    }
}
