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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.lang.ast.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;

/**
 * A diagnostic about a declaration a session already holds names the file that
 * declaration was parsed from, so a caller who defines data first and then validates a
 * script against it can tell a finding in the data from one in the script.
 *
 * <p>Without that, a warning on line 5 of the data reads as line 5 of the script: the
 * session analyses everything it holds as one text.
 */
@DisplayName("A diagnostic about defined data names the data, not the script")
final class DefinedDataDiagnosticsTest {

    /** Two tables; the second has a quoted cell, which draws a warning on line 4. */
    private static final String DATA = """
            Numbers := [| n |
                        | 1 |
                        | 2 |];
            Words := [| w: STRING |
                      | 'a' |];
            """;

    /** Where {@code diagnostic} is placed, as {@code file:line:column}. */
    private static String at(Diagnostic diagnostic) {
        return diagnostic.location().orElseThrow().toString();
    }

    private static Relix loaded(String label) {
        Relix relix = Relix.builder().sandbox(Sandbox.builder().build()).build();
        relix.define(Relix.parse(DATA, label).statements().toArray(Statement[]::new));
        return relix;
    }

    @Test
    @DisplayName("a warning in the data is placed in the data's file")
    void warningInData() {
        try (Relix relix = loaded("data")) {
            List<Diagnostic> diagnostics = relix.validate("query { π n (Numbers) };");
            assertThat(diagnostics).singleElement().satisfies(d -> {
                assertThat(d.isError()).isFalse();
                assertThat(at(d)).isEqualTo("data:4:1");
            });
        }
    }

    @Test
    @DisplayName("an error in the script is placed on the script's own line")
    void errorInScript() {
        try (Relix relix = loaded("data")) {
            assertThat(relix.validate("\n\nquery { π missing (Numbers) };"))
                    .filteredOn(Diagnostic::isError)
                    .singleElement()
                    .extracting(DefinedDataDiagnosticsTest::at)
                    .isEqualTo(Relix.SESSION_PATH + ":3:11");
        }
    }

    @Test
    @DisplayName("data defined as text is the session's own, as before")
    void definedAsText() {
        try (Relix relix = Relix.builder().sandbox(Sandbox.builder().build()).build()) {
            relix.define(DATA);
            assertThat(relix.validate("query { Numbers };")).singleElement()
                    .extracting(DefinedDataDiagnosticsTest::at)
                    .isEqualTo(Relix.SESSION_PATH + ":4:1");
        }
    }
}
