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

/** Inline-table cells that would fail at run time are warned about at analysis (#123). */
@DisplayName("Inline-table cells read as temporals")
final class InlineCellChecksTest {

    @Test
    @DisplayName("a cell written as a typed literal is named, with the bare value to write")
    void literalCell() {
        assertThat(analyze("""
                Clicks := [
                | user | ts                               |
                |------|----------------------------------|
                | ann  | TIMESTAMP '2026-03-01T09:00:00Z' |
                | bob  | TIMESTAMP '2026-03-01T09:05:00Z' |
                | cy   | timestamp '2026-03-01T09:07:00Z' |
                ];
                query { Clicks };
                """))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("Clicks.ts: table cells are bare values: write 2026-03-01T09:00:00Z, "
                        + "not TIMESTAMP '2026-03-01T09:00:00Z' (row 1, and 2 more cells)");
    }

    @Test
    @DisplayName("each typed literal form is recognised")
    void everyLiteralForm() {
        assertThat(analyze("""
                T := [
                | d                   | t               | g                |
                |---------------------|-----------------|------------------|
                | DATE '2026-03-01'   | TIME '09:00:00' | DURATION 'PT5M'  |
                ];
                query { T };
                """))
                .hasNoErrors()
                .hasDiagnosticCount(3)
                .hasErrorContaining("T.d: table cells are bare values: write 2026-03-01, not DATE '2026-03-01' (row 1)")
                .hasErrorContaining("T.t: table cells are bare values: write 09:00:00")
                .hasErrorContaining("T.g: table cells are bare values: write PT5M");
    }

    @Test
    @DisplayName("one cell that is not the temporal its column holds is named by row and value")
    void strayCell() {
        assertThat(analyze("""
                Clicks := [
                | user | ts                   |
                |------|----------------------|
                | ann  | 2026-03-01T09:00:00Z |
                | ann  | 2026-03-01T09:05:00Z |
                | bob  | 2026-03-1T10:45:00Z  |
                | bob  | 2026-03-01T11:00:00Z |
                | cy   |                      |
                ];
                query { Clicks };
                """))
                .hasNoErrors()
                .hasDiagnosticCount(1)
                .hasErrorContaining("Clicks.ts: 3 of 4 cells are ISO timestamps; row 3, "
                        + "'2026-03-1T10:45:00Z', is not");
    }

    @Test
    @DisplayName("several stray cells are counted")
    void severalStrayCells() {
        assertThat(analyze("""
                T := [
                | d          |
                |------------|
                | 2026-03-01 |
                | 2026-03-02 |
                | 2026-03-03 |
                | soon       |
                | later      |
                ];
                query { T };
                """))
                .hasErrorContaining("T.d: 3 of 5 cells are ISO dates; row 4, 'soon', is not, nor are 1 more");
    }

    @Test
    @DisplayName("a column only partly temporal is text, and left alone")
    void minorityTemporal() {
        assertThat(analyze("""
                T := [
                | d          |
                |------------|
                | 2026-03-01 |
                | 2026-03-02 |
                | soon       |
                | later      |
                | never      |
                ];
                query { T };
                """))
                .isFullyValid();
    }

    @Test
    @DisplayName("text, numbers, clean temporals and a column that is mostly text are left alone")
    void cleanColumns() {
        assertThat(analyze("""
                T := [
                | name | n  | at                   | note       |
                |------|----|----------------------|------------|
                | ann  | 1  | 2026-03-01T09:00:00Z | 2026-03-01 |
                | bob  | 2  | 2026-03-01T09:05:00Z | tbd        |
                | cy   | 3  | NULL                 | tbd        |
                ];
                query { T };
                """))
                .isFullyValid();
    }

    @Test
    @DisplayName("a quoted markdown cell keeps its quotes, and is named with the bare value to write (#165)")
    void quotedCell() {
        assertThat(analyze("""
                T := [
                | s     | t      |
                |-------|--------|
                | "45%" | 'a'    |
                | 50%   | 'b'    |
                | 'x    | 'c'    |
                ];
                query { T };
                """))
                .hasNoErrors()
                .hasDiagnosticCount(2)
                .hasErrorContaining("T.s, row 1: the cell \"45%\" keeps its quotes, because table cells are "
                        + "bare values; write 45%")
                .hasErrorContaining("T.t, row 1: the cell 'a' keeps its quotes, because table cells are "
                        + "bare values; write a (and 2 more cells)");
    }

    @Test
    @DisplayName("a quoted temporal is named for its quotes, not as a stray in its column")
    void quotedTemporal() {
        assertThat(analyze("""
                T := [
                | d            |
                |--------------|
                | 2026-03-01   |
                | 2026-03-02   |
                | '2026-03-03' |
                ];
                query { T };
                """))
                .hasDiagnosticCount(1)
                .hasErrorContaining("T.d, row 3: the cell '2026-03-03' keeps its quotes");
    }

    @Test
    @DisplayName("bare cells, a lone quote mark and the CSV form are left alone")
    void unquotedCells() {
        assertThat(analyze("""
                T := [
                | s      |
                |--------|
                | 45%    |
                | '      |
                | it's   |
                ];
                C := csv[
                  s, n
                  "London, UK", 1
                  "45%", 2
                ];
                query { T };
                """))
                .isFullyValid();
    }
}
