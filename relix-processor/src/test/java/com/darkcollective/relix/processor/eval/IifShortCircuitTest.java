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
package com.darkcollective.relix.processor.eval;

import com.darkcollective.relix.processor.QueryExecutor;
import com.darkcollective.relix.processor.QueryResult;
import com.darkcollective.relix.semantic.SemanticResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code IIf} is a lazy special form, and a bad value in the data is a diagnostic
 * rather than a crash (issue #519).
 *
 * <p>Both halves came from one defect. The reference documented
 * {@code IIf(IsNumeric(raw), CDbl(raw), 0)} as the way to "guard a conversion",
 * on a page recommending {@code IsNumeric} for validating messy imports. Because
 * IIf evaluated <em>both</em> branches, that expression called {@code CDbl} on
 * exactly the rows the condition excluded and died with an uncaught
 * {@link NumberFormatException} — a Java stack trace, for an ordinary bad value in
 * imported data.
 */
@DisplayName("IIf short-circuits, and conversion failures are diagnostics")
final class IifShortCircuitTest {

    /** Two rows: one numeric, one not — the shape that broke. */
    private static final String IMPORTED = """
            Imported := [
            | id | raw  |
            |----|------|
            | 1  | 12.5 |
            | 2  | abc  |
            ];
            """;

    @Nested
    @DisplayName("laziness")
    final class Laziness {

        @Test
        @DisplayName("the documented guard idiom no longer evaluates the excluded branch")
        void guardIdiomWorks() {
            List<String> amounts = column(
                    IMPORTED + "query { π id, IIf(IsNumeric(raw), CDbl(raw), 0) → amount (Imported) };",
                    "amount");
            assertThat(amounts)
                    .as("row 2 is non-numeric: the condition is false, so CDbl is never called")
                    .containsExactly("12.5", "0");
        }

        @Test
        @DisplayName("the true branch is skipped when the condition is false")
        void trueBranchSkipped() {
            List<String> out = column(
                    IMPORTED + "query { π id, IIf(false, CDbl(raw), -1) → v (Imported) };", "v");
            assertThat(out).containsExactly("-1", "-1");
        }

        @Test
        @DisplayName("the false branch is skipped when the condition is true")
        void falseBranchSkipped() {
            List<String> out = column(
                    IMPORTED + "query { π id, IIf(true, 1, CDbl(raw)) → v (Imported) };", "v");
            assertThat(out).containsExactly("1", "1");
        }

        @Test
        @DisplayName("division by zero in the untaken branch does not fire")
        void untakenDivisionByZero() {
            List<String> out = column(
                    IMPORTED + "query { π id, IIf(id > 0, 7, id / 0) → v (Imported) };", "v");
            assertThat(out).containsExactly("7", "7");
        }
    }

    @Nested
    @DisplayName("condition semantics are unchanged")
    final class Conditions {

        @Test
        @DisplayName("a NULL condition still yields NULL")
        void nullCondition() {
            String src = """
                    D := [
                    | id | flag |
                    |----|------|
                    | 1  | NULL |
                    ];
                    query { π id, IIf(flag, "yes", "no") → v (D) };
                    """;
            assertThat(column(src, "v")).containsExactly("NULL");
        }

        @Test
        @DisplayName("a non-BOOLEAN condition is still rejected")
        void nonBooleanCondition() {
            assertThatThrownBy(() -> column(
                    IMPORTED + "query { π id, IIf(id, 1, 0) → v (Imported) };", "v"))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("IIf")
                    .hasMessageContaining("BOOLEAN");
        }

        @Test
        @DisplayName("both branches still select correctly per row")
        void selectsPerRow() {
            List<String> out = column(
                    IMPORTED + "query { π id, IIf(id = 1, \"first\", \"other\") → v (Imported) };", "v");
            assertThat(out).containsExactly("first", "other");
        }
    }

    @Nested
    @DisplayName("Coalesce and Nz are lazy in the same way")
    final class NullFallbacks {

        /** One row with a value, one without — so each argument matters on some row. */
        private static final String PARTIAL = """
                D := [
                | id | code | fallback |
                |----|------|----------|
                | 1  | ok   | abc      |
                | 2  | NULL | 7        |
                ];
                """;

        @Test
        @DisplayName("Coalesce stops at the first non-NULL, leaving later arguments unevaluated")
        void coalesceStopsEarly() {
            List<String> out = column(
                    PARTIAL + "query { π id, Coalesce(code, CDbl(fallback)) → v (D) };", "v");
            assertThat(out)
                    .as("row 1 has a code, so CDbl(\"abc\") is never reached")
                    .containsExactly("ok", "7");
        }

        @Test
        @DisplayName("Nz leaves its replacement unevaluated when the value is present")
        void nzSkipsReplacement() {
            List<String> out = column(
                    PARTIAL + "query { π id, Nz(code, CStr(CDbl(fallback))) → v (D) };", "v");
            assertThat(out).containsExactly("ok", "7");
        }

        @Test
        @DisplayName("Coalesce over all-NULL arguments is still NULL")
        void coalesceAllNull() {
            String src = """
                    D := [
                    | id | a    | b    |
                    |----|------|------|
                    | 1  | NULL | NULL |
                    ];
                    query { π id, Coalesce(a, b) → v (D) };
                    """;
            assertThat(column(src, "v")).containsExactly("NULL");
        }

        @Test
        @DisplayName("Nz returns its replacement as-is, even when that is itself NULL")
        void nzNullReplacement() {
            String src = """
                    D := [
                    | id | a    | b    |
                    |----|------|------|
                    | 1  | NULL | NULL |
                    ];
                    query { π id, Nz(a, b) → v (D) };
                    """;
            assertThat(column(src, "v"))
                    .as("not the empty-string default — that is only the one-argument form")
                    .containsExactly("NULL");
        }

        @Test
        @DisplayName("one-argument Nz still defaults a NULL to the empty string")
        void nzSingleArgument() {
            String src = """
                    D := [
                    | id | a    |
                    |----|------|
                    | 1  | NULL |
                    ];
                    query { π id, Nz(a) → v (D) };
                    """;
            assertThat(column(src, "v")).containsExactly("");
        }

        @Test
        @DisplayName("Coalesce with no arguments is rejected before execution")
        void coalesceNoArguments() {
            // Arity is caught during semantic analysis, so the lazy form's own
            // guard is a backstop rather than the primary check.
            SemanticResult result = analyze(PARTIAL + "query { π id, Coalesce() → v (D) };");
            assertThat(result.errors()).isNotEmpty();
            assertThat(result.errors().toString()).contains("Coalesce");
        }
    }

    @Nested
    @DisplayName("conversion failures")
    final class Conversions {

        @Test
        @DisplayName("an unguarded bad value is an EvaluationException naming the value")
        void badValueIsDiagnostic() {
            assertThatThrownBy(() -> column(
                    IMPORTED + "query { π id, CDbl(raw) → amount (Imported) };", "amount"))
                    .as("not a raw NumberFormatException with a Java stack trace")
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("CDbl")
                    .hasMessageContaining("abc");
        }

        @Test
        @DisplayName("CInt reports a bad value the same way")
        void cintBadValue() {
            assertThatThrownBy(() -> column(
                    IMPORTED + "query { π id, CInt(raw) → n (Imported) };", "n"))
                    .isInstanceOf(EvaluationException.class)
                    .hasMessageContaining("CInt")
                    .hasMessageContaining("abc");
        }

        @Test
        @DisplayName("filtering first is still the way to convert a mixed column")
        void filterThenConvert() {
            List<String> out = column(
                    IMPORTED
                            + "query { π id, CDbl(raw) → amount (σ IsNumeric(raw) = true (Imported)) };",
                    "amount");
            assertThat(out).containsExactly("12.5");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static List<String> column(String src, String name) {
        SemanticResult result = analyze(src);
        assertThat(result.errors()).as("semantic errors").isEmpty();
        List<QueryResult> results = new QueryExecutor().execute(result);
        return results.get(0).rows().stream()
                .map(r -> r.get(name).asDisplayString())
                .toList();
    }
}
