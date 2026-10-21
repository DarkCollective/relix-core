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

import com.darkcollective.relix.lang.ast.AssignmentStatement;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.ScriptBuilders;
import com.darkcollective.relix.lang.ast.Statement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;
import static com.darkcollective.relix.embed.EmbedAssertions.assertThatThrownBy;

/**
 * {@code validate} and {@code script} over a script already parsed, as a caller holds one
 * after reading it: one that drops a statement declaring a name it has already defined,
 * say, and runs the rest.
 */
@DisplayName("A parsed script")
final class ParsedScriptTest {

    private static final String NUMBERS = """
            Numbers := [| n |
                        | 1 |
                        | 2 |
                        | 3 |];
            """;

    /** A script that declares Numbers again, then queries it on line 4. */
    private static final String SCRIPT = """
            Numbers := [| n |
                        | 9 |];

            query { σ n > 1 (Numbers) };
            """;

    private static Relix numbers() {
        Relix relix = Relix.builder().sandbox(Sandbox.builder().build()).build();
        relix.define(NUMBERS);
        return relix;
    }

    /** {@code script} without its declarations of {@code name}. */
    private static Script without(Script script, String name) {
        return ScriptBuilders.script(script.statements().stream()
                .filter(st -> !(st instanceof AssignmentStatement a && a.name().equals(name)))
                .toArray(Statement[]::new));
    }

    @Test
    @DisplayName("runs without a statement the caller removed")
    void runsWhatIsLeft() {
        try (Relix relix = numbers()) {
            List<Relation> results = relix.script(without(Relix.parse(SCRIPT), "Numbers"));
            assertThat(results).singleElement()
                    .satisfies(r -> assertThat(r).rows().hasRowCount(2).hasRow("2").hasRow("3"));
        }
    }

    @Test
    @DisplayName("places a diagnostic on the line it was parsed from, after a removal")
    void keepsLines() {
        try (Relix relix = numbers()) {
            Script parsed = Relix.parse(SCRIPT.replace("σ n > 1", "π missing"));
            assertThat(relix.validate(without(parsed, "Numbers")))
                    .singleElement()
                    .satisfies(d -> assertThat(d.location().orElseThrow().toString())
                            .isEqualTo(Relix.SESSION_PATH + ":4:11"));
        }
    }

    @Test
    @DisplayName("finds nothing wrong where the text finds nothing wrong")
    void agreesWithText() {
        try (Relix relix = numbers()) {
            assertThat(relix.validate(Relix.parse(SCRIPT))).isEmpty();
            assertThat(relix.validate(SCRIPT)).isEmpty();
        }
    }

    @Test
    @DisplayName("is held to what the sandbox permits a declaration to reach")
    void sandboxed() {
        String source = "source Orders from csv(\"orders.csv\") { schema: { id: NUMBER } };";
        try (Relix relix = numbers()) {
            assertThat(relix.validate(Relix.parse(source))).singleElement()
                    .satisfies(d -> assertThat(d.message()).contains("does not permit"));
            assertThatThrownBy(() -> relix.script(Relix.parse(source)))
                    .isInstanceOf(SandboxViolationException.class);
            assertThat(relix.statements()).hasSize(1);
        }
    }

    @Test
    @DisplayName("is not held to the sandbox's limit on text, having none")
    void noTextLimit() {
        try (Relix relix = Relix.builder().sandbox(Sandbox.builder().maxInputChars(10).build()).build()) {
            Script parsed = Relix.parse(NUMBERS + "query { Numbers };");
            assertThat(relix.validate(parsed)).isEmpty();
            assertThat(relix.script(parsed)).singleElement()
                    .satisfies(r -> assertThat(r).rows().hasRowCount(3));
        }
    }
}
