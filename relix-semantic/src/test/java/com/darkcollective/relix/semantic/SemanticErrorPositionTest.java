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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;


import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;

/**
 * Tests that verify semantic errors carry accurate source-position information
 * from the AST {@link com.darkcollective.relix.ast.SourceLocation} attached to
 * nodes by the parser.
 *
 * <p>All tests drive real {@code .relix} source text through the full five-phase
 * pipeline (parse → import graph → symbol collection → schema inference →
 * validation) and inspect the {@link SemanticError} diagnostics produced.
 * The key assertion in each test is that {@code error.line()} and/or
 * {@code error.column()} are non-zero and point to the offending construct.
 */
@DisplayName("Semantic error source-position tracking — end-to-end")
final class SemanticErrorPositionTest {

    // =========================================================================
    // Test helpers (same pattern as EndToEndTest)
    // =========================================================================

    /** Returns the first error from the result's error list. */
    private static SemanticError firstError(SemanticResult r) {
        assertThat(r.errors()).isNotEmpty();
        return r.errors().get(0);
    }

    // =========================================================================
    // Error file path — should be "<stdin>" for InputStream-based analysis
    // =========================================================================

    @Nested
    @DisplayName("Error filePath is '<stdin>' for stdin input")
    class ErrorFilePath {

        @Test
        @DisplayName("Unknown relation error carries '<stdin>' filePath")
        void unknownRelationErrorFilePath() {
            String src = """
                    source Users from database { url: "${DB}", table: "users",
                        schema: { id: NUMBER, name: STRING } };
                    A := { UnknownRelation };
                    query A;
                    """;
            SemanticResult r = analyze(src);
            SemanticError err = firstError(r);
            assertThat(err.filePath()).isEqualTo(SemanticAnalyzer.STDIN_PATH);
        }
    }

    // =========================================================================
    // Error positions — line and column must be non-zero
    // =========================================================================

    @Nested
    @DisplayName("Errors carry non-zero line and column numbers")
    class ErrorPositions {

        @Test
        @DisplayName("Unknown relation error has non-zero line")
        void unknownRelationNonZeroLine() {
            String src = """
                    source Users from database { url: "${DB}", table: "users",
                        schema: { id: NUMBER, name: STRING } };
                    A := { UnknownRelation };
                    query A;
                    """;
            SemanticResult r = analyze(src);
            SemanticError err = firstError(r);
            assertThat(err.line()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Unknown relation error has non-zero column")
        void unknownRelationNonZeroColumn() {
            String src = """
                    source Users from database { url: "${DB}", table: "users",
                        schema: { id: NUMBER, name: STRING } };
                    A := { UnknownRelation };
                    query A;
                    """;
            SemanticResult r = analyze(src);
            SemanticError err = firstError(r);
            assertThat(err.column()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Rename arity error has non-zero line")
        void renameArityErrorNonZeroLine() {
            // Users has 2 columns; rename provides 3 names — arity mismatch
            String src = """
                    source Users from database { url: "${DB}", table: "users",
                        schema: { id: NUMBER, name: STRING } };
                    A := { ρ R(a, b, c) (Users) };
                    query A;
                    """;
            SemanticResult r = analyze(src);
            assertThat(r).hasErrors();
            SemanticError err = r.errors().stream()
                    .filter(e -> e.message().contains("Rename"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected rename error, got: " + r.errors()));
            assertThat(err.line()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Unknown projection attribute error has non-zero line")
        void unknownProjectionAttributeNonZeroLine() {
            String src = """
                    source Users from database { url: "${DB}", table: "users",
                        schema: { id: NUMBER, name: STRING } };
                    A := { π missing_col (Users) };
                    query A;
                    """;
            SemanticResult r = analyze(src);
            assertThat(r).hasErrors();
            SemanticError err = r.errors().stream()
                    .filter(e -> e.message().contains("missing_col"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected projection error, got: " + r.errors()));
            assertThat(err.line()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Unknown sort attribute error has non-zero line")
        void unknownSortAttributeNonZeroLine() {
            String src = """
                    source Users from database { url: "${DB}", table: "users",
                        schema: { id: NUMBER, name: STRING } };
                    A := { τ missing_attr ASC (Users) };
                    query A;
                    """;
            SemanticResult r = analyze(src);
            assertThat(r).hasErrors();
            SemanticError err = r.errors().stream()
                    .filter(e -> e.message().contains("Sort"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected sort error, got: " + r.errors()));
            assertThat(err.line()).isGreaterThan(0);
        }
    }

    // =========================================================================
    // Line numbers correspond to the actual source line
    // =========================================================================

    @Nested
    @DisplayName("Error line numbers correspond to the source line of the offending construct")
    class ErrorLineMapping {

        @Test
        @DisplayName("Error on line 3 when offending construct is on line 3")
        void errorOnLine3() {
            // Line 1: source declaration
            // Line 2: (continuation of source)
            // Line 3: the assignment with the bad relation
            String src =
                    "source Users from database { url: \"${DB}\", table: \"users\",\n" +
                    "    schema: { id: NUMBER, name: STRING } };\n" +
                    "A := { BadRelation };\n" +
                    "query A;\n";
            SemanticResult r = analyze(src);
            assertThat(r).hasErrors();
            // The error for BadRelation should reference line 3
            boolean foundLine3 = r.errors().stream()
                    .anyMatch(e -> e.message().contains("BadRelation") && e.line() == 3);
            assertThat(foundLine3)
                    .as("Expected an error for 'BadRelation' on line 3, errors were: " + r.errors())
                    .isTrue();
        }

        @Test
        @DisplayName("Error on line 4 when offending construct is on line 4")
        void errorOnLine4() {
            String src =
                    "source Users from database { url: \"${DB}\", table: \"users\",\n" +
                    "    schema: { id: NUMBER, name: STRING } };\n" +
                    "A := { Users };\n" +
                    "B := { Missing };\n" +
                    "query B;\n";
            SemanticResult r = analyze(src);
            assertThat(r).hasErrors();
            boolean foundLine4 = r.errors().stream()
                    .anyMatch(e -> e.message().contains("Missing") && e.line() == 4);
            assertThat(foundLine4)
                    .as("Expected error for 'Missing' on line 4, errors were: " + r.errors())
                    .isTrue();
        }
    }

    // =========================================================================
    // toString format
    // =========================================================================

    @Nested
    @DisplayName("SemanticError.toString includes file:line:column prefix")
    class ToStringFormat {

        @Test
        @DisplayName("toString includes '<stdin>' and non-zero position")
        void toStringHasPosition() {
            String src = """
                    source Users from database { url: "${DB}", table: "users",
                        schema: { id: NUMBER, name: STRING } };
                    A := { NoSuchRelation };
                    query A;
                    """;
            SemanticResult r = analyze(src);
            SemanticError err = firstError(r);
            // toString format: "<file>:<line>:<col>: error: <message>"
            assertThat(err.toString()).startsWith("<stdin>:");
            assertThat(err.toString()).contains(": error:");
        }
    }
}
