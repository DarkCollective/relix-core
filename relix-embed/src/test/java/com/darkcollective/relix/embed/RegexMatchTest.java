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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The regular-expression match predicate, its negations and NULLs, evaluated (#138). */
@DisplayName("Regular-expression match, evaluated")
final class RegexMatchTest {

    private static final String REFS = """
            Refs := [| id | ref        |
            | 1  | ABC-1042   |
            | 2  | abc-77     |
            | 3  | X-9        |
            | 4  | DEF-0001-B |
            | 5  | NULL       |];
            """;

    private static List<String> ids(Relix relix, String condition) {
        return relix.relation("τ id (σ " + condition + " (Refs))").toList().stream()
                .map(t -> t.get("id").asDisplayString()).toList();
    }

    @Test
    @DisplayName("~ and MATCHES match anywhere, ~* ignores case, and the worked example's rows")
    void matches() {
        try (Relix relix = Relix.open()) {
            relix.define(REFS);
            assertThat(ids(relix, "ref ~ '^[A-Z]{3}-[0-9]+'")).containsExactly("1", "4");
            assertThat(ids(relix, "ref ~* '^[A-Z]{3}-[0-9]+$'")).containsExactly("1", "2");
            assertThat(ids(relix, "ref MATCHES '-9'")).containsExactly("3");
            assertThat(ids(relix, "(ref ~ 'B') ∨ id = 3")).containsExactly("1", "3", "4");
        }
    }

    @Test
    @DisplayName("the negations keep exactly the rows the match drops, a NULL in neither")
    void negations() {
        try (Relix relix = Relix.open()) {
            relix.define(REFS);
            assertThat(ids(relix, "ref !~ '^[A-Z]{3}'")).containsExactly("2", "3");
            assertThat(ids(relix, "ref !~* '^[A-Z]{3}'")).containsExactly("3");
            assertThat(ids(relix, "ref NOT MATCHES '-'")).isEmpty();
            assertThat(ids(relix, "¬(ref ~ '^[A-Z]{3}')")).containsExactly("2", "3");
        }
    }
}
