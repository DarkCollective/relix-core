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

import com.darkcollective.relix.semantic.internal.SemanticResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What the operators that enumerate a heading do with a schema-on-read input.
 *
 * <p>An open schema resolves any name to {@code ANY} but enumerates <em>nothing</em>.
 * Operators that ask "what columns are there?" got an empty list back and reported a
 * conclusion drawn from it as a fact about the data: composition said the inputs
 * shared no columns, division said every left column appeared in the right, a
 * positional rename called the input "0-column", and the natural join threw
 * {@code IllegalArgumentException} out of inference (#653).
 *
 * <p>Two rules come out of it, and both are checked here. An operator that can work
 * without knowing the heading does, and one that cannot says so — naming the open
 * input as the reason, the way {@code COVER} always has. Neither may throw.
 */
@DisplayName("Operators that enumerate a heading, over a schema-on-read input")
final class OpenSchemaHeadingTest {

    /** Two JSON sources with no schema: nothing is declared, and nothing is read. */
    private static final String OPEN_SOURCES = """
            source L from json("l.json");
            source R from json("r.json");
            source C from database { url: "${DB}", table: "c",
                schema: { a: NUMBER, b: NUMBER } };
            """;

    private static SemanticResult analysisOf(String query) {
        return SemanticFixtures.analyze(OPEN_SOURCES + "\nquery { " + query + " };\n");
    }

    @Nested
    @DisplayName("cannot proceed — and says why")
    class Rejected {

        @Test
        @DisplayName("∘ — the result drops the shared columns, so it must know them")
        void composition() {
            assertThat(analysisOf("L ∘ R")).messages()
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("open (schema-on-read) input")
                    .doesNotContain("share at least one column");   // the old falsehood
        }

        @Test
        @DisplayName("÷ — the result heading is a subtraction of two headings")
        void division() {
            assertThat(analysisOf("L ÷ R")).messages()
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("open (schema-on-read) input")
                    .doesNotContain("every left column appears");    // the old falsehood
        }

        @Test
        @DisplayName("positional ρ — a document has no first or second column")
        void positionalRename() {
            assertThat(analysisOf("ρ N(a, b) (L)")).messages()
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("no column positions to rename")
                    .doesNotContain("0-column");                     // the old falsehood
        }

        @Test
        @DisplayName("⋈ — the shared columns are settled before any row is read")
        void naturalJoin() {
            // Rejected rather than accepted, because the join keys are fixed at plan
            // time from the two schemas: an open result would analyse, run, and
            // return nothing at all, which is worse than a diagnosis.
            assertThat(analysisOf("L ⋈ R")).messages()
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("open (schema-on-read) input")
                    // The suggestion has to be one that works: a theta join over two
                    // open relations did not, until #657 made its qualified references
                    // resolve against the document.
                    .contains("Use a theta join with an explicit condition");
        }

        @Test
        @DisplayName("…and it is the OPEN side that matters, not which side it is on")
        void anOpenRightIsRejectedToo() {
            // Every one of these reads `left.isOpen() || right.isOpen()`, and a fixture
            // with both sides open short-circuits on the first arm — so nothing had shown
            // the right side is consulted at all. `C` is declared; only the other side is
            // a document. This is the realistic shape: a table joined to a JSON feed.
            for (String query : List.of("C ∘ R", "C ⋈ R", "UNIT ÷ R")) {
                assertThat(analysisOf(query)).messages()
                        .as("analysing: %s", query)
                        .anySatisfy(m -> assertThat(m).contains("open (schema-on-read) input"));
            }
        }

        @Test
        @DisplayName("a closed pair is not rejected — the guard fires on openness, not on the operator")
        void aClosedPairIsAccepted() {
            // The control. Without it, "no error mentioning open input" would be
            // satisfied by a guard that had stopped running at all.
            for (String query : List.of("C ∘ C", "C ⋈ C")) {
                assertThat(analysisOf(query)).messages()
                        .as("analysing: %s", query)
                        .noneSatisfy(m -> assertThat(m).contains("open (schema-on-read) input"));
            }
        }

        @Test
        @DisplayName("an input whose own inference failed stops the check, from either side")
        void anUnresolvableInputStopsTheCheck() {
            // The other guard these methods open with: both sides must have a schema at
            // all. An undefined relation is reported once, by name — the set-op check
            // above it must not pile a second, derived complaint on top.
            for (String query : List.of("Ghost ∘ C", "C ∘ Ghost", "Ghost ÷ C", "C ÷ Ghost")) {
                assertThat(analysisOf(query)).messages()
                        .as("analysing: %s", query)
                        .noneSatisfy(m -> assertThat(m).contains("open (schema-on-read) input"));
            }
        }

        @Test
        @DisplayName("nothing throws — an unsatisfiable inference is an error, not an exception")
        void neverThrows() {
            // The natural join used to escape inference as IllegalArgumentException,
            // which reached the user labelled "parse error" (#654).
            for (String query : List.of("L ⋈ R", "L ∘ R", "L ÷ R", "ρ N(a, b) (L)")) {
                assertThatCode(() -> analysisOf(query))
                        .as("analysing: %s", query)
                        .doesNotThrowAnyException();
            }
        }
    }

    @Nested
    @DisplayName("proceeds — the heading is not needed, or the result is open too")
    class Accepted {

        @Test
        @DisplayName("the operators that defer to run time")
        void deferToRuntime() {
            for (String query : List.of(
                    "L × R", "L ⊔ R", "L ∆ R", "L ∪ R", "L ∩ R", "L − R", "L ⊎ R",
                    "σ a > 1 (L)", "π a, b (L)", "γ a, COUNT(b) → n (L)",
                    "τ a (L)", "λ 3 (L)", "δ (L)", "μ tags (L)",
                    "ρ NewName (L)", "ρ (a → b) (L)")) {
                assertThat(analysisOf(query)).messages().as("analysing: %s", query).isEmpty();
            }
        }
    }
}
