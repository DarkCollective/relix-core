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

import com.darkcollective.relix.lang.ScriptParser;
import com.darkcollective.relix.semantic.SemanticError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.script;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ViewCycles — a view defined in terms of itself is an analysis error (#154)")
final class ViewCycleTest {

    private static final String ROOMS = """
            rooms := [
            | room_id | home_id |
            |---|---|
            | 1 | 9 |
            ];
            """;

    private static List<SemanticError> errors(String src) throws Exception {
        var analyzer = new SemanticAnalyzer(path -> script());
        return analyzer.analyze(ScriptParser.parse(src)).errors();
    }

    private static List<String> cycleMessages(String src) throws Exception {
        return errors(src).stream()
                .map(SemanticError::message)
                .filter(m -> m.contains("refers to itself"))
                .toList();
    }

    @Test
    @DisplayName("a view named after the table it filters is reported, naming the case folding")
    void caseInsensitiveSelfReference() throws Exception {
        var errors = errors(ROOMS + """
                Rooms := { SELECT home_id = 9 (rooms) };
                query { Rooms };
                """);

        assertThat(errors).filteredOn(e -> e.message().contains("refers to itself"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.message()).startsWith("View 'Rooms' refers to itself")
                            .contains("names are case-insensitive, so 'rooms' is 'Rooms'");
                    assertThat(e.line()).isEqualTo(6);
                });
    }

    @Test
    @DisplayName("an exact-case self-reference is reported without the case note")
    void exactSelfReference() throws Exception {
        assertThat(cycleMessages(ROOMS + """
                X := { SELECT home_id = 9 (X) };
                query { X };
                """))
                .singleElement()
                .satisfies(m -> assertThat(m).startsWith("View 'X' refers to itself;")
                        .doesNotContain("case-insensitive"));
    }

    @Test
    @DisplayName("a longer cycle is reported once, with its path")
    void mutualCycle() throws Exception {
        assertThat(cycleMessages("""
                A := { B };
                B := { C };
                C := { A };
                query { A };
                """))
                .singleElement()
                .satisfies(m -> assertThat(m)
                        .startsWith("View 'A' refers to itself through A → B → C → A"));
    }

    @Test
    @DisplayName("a view reaching a cycle it is not part of reports only the cycle")
    void cycleBehindAView() throws Exception {
        assertThat(cycleMessages("""
                Outer := { A };
                A := { B };
                B := { A };
                query { Outer };
                """))
                .singleElement()
                .satisfies(m -> assertThat(m).contains("A → B → A"));
    }

    @Test
    @DisplayName("an acyclic chain of views and FIX self-references are not cycles")
    void acyclicIsClean() throws Exception {
        assertThat(cycleMessages(ROOMS + """
                Nine := { SELECT home_id = 9 (rooms) };
                Ids := { PROJECT room_id (Nine) };
                Reach := { FIX R (Ids, R) };
                query { Reach };
                """)).isEmpty();
    }
}
