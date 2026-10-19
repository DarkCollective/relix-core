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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;

/**
 * A binder's recursive relation joined as the right input of a θ-join whose inputs
 * share column names: the condition names each side by its own relation, whichever
 * side the recursive one is on (#162).
 */
@DisplayName("FIX / ITERATE — the recursive relation as a θ-join's right input")
final class RecursiveRightInputJoinTest {

    private static final String REPORTS = """
            reports := [
            | employee | manager |
            |----------|---------|
            | Eli      | Dana    |
            | Fay      | Dana    |
            | Gus      | Eli     |
            | Hal      | Gus     |
            ];
            """;

    private Relix session;

    @BeforeEach
    void open() {
        session = Relix.builder().build();
    }

    @AfterEach
    void close() {
        session.close();
    }

    private Relation last(String script) {
        return session.script(REPORTS + script).getLast();
    }

    @Test
    @DisplayName("FIX with the recursive relation on the right reaches every report")
    void fixRight() {
        assertThat(last("""
                query { FIX T (
                  PROJECT employee, manager (SELECT manager = 'Dana' (reports)),
                  PROJECT employee, manager (reports >< reports.manager = T.employee T)
                ) };
                """)).rows().hasRowCount(4)
                .hasRow("Eli", "Dana").hasRow("Fay", "Dana").hasRow("Gus", "Eli").hasRow("Hal", "Gus");
    }

    @Test
    @DisplayName("the condition written the other way round gives the same rows")
    void fixRightReversedCondition() {
        assertThat(last("""
                query { FIX T (
                  PROJECT employee, manager (SELECT manager = 'Dana' (reports)),
                  PROJECT employee, manager (reports >< T.employee = reports.manager T)
                ) };
                """)).rows().hasRowCount(4).hasRow("Hal", "Gus");
    }

    @Test
    @DisplayName("FIX with the recursive relation on the left gives the same rows")
    void fixLeft() {
        assertThat(last("""
                query { FIX T (
                  PROJECT employee, manager (SELECT manager = 'Dana' (reports)),
                  PROJECT reports.employee, reports.manager (T >< T.employee = reports.manager reports)
                ) };
                """)).rows().hasRowCount(4).hasRow("Hal", "Gus");
    }

    @Test
    @DisplayName("ITERATE binds its relation the same way, and steps through it on the right")
    void iterateRight() {
        // Each round replaces T with the next level down: Dana's reports, theirs, and so on.
        assertThat(last("""
                query { ITERATE T (
                  PROJECT employee, manager (SELECT manager = 'Dana' (reports)),
                  PROJECT employee, manager (reports >< reports.manager = T.employee T)
                ) ROUNDS 2 };
                """)).rows().hasRowCount(1).hasRow("Hal", "Gus");
    }
}
