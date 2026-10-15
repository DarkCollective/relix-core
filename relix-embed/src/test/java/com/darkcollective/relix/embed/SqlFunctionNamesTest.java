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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The SQL names of the built-ins resolve in a query, are listed, and are suggested. */
@DisplayName("SQL function names, end to end")
final class SqlFunctionNamesTest {

    private static final List<String> NAMES = List.of("UPPER", "LOWER", "LENGTH", "CHAR_LENGTH", "SUBSTRING",
            "SUBSTR", "STRPOS", "INSTR", "CHARINDEX", "FLOOR", "CEILING", "CEIL", "TRUNC", "TRUNCATE", "SQRT",
            "SIGN", "LN", "RANDOM", "CONCAT", "ASCII", "CHR", "CHAR");

    @Test
    @DisplayName("each SQL name is a function relix.functions lists")
    void listed() {
        try (Relix relix = Relix.open()) {
            List<String> listed = relix.relation("relix.functions").toList().stream()
                    .map(t -> t.get("name").asDisplayString().toUpperCase(java.util.Locale.ROOT)).toList();
            assertThat(listed).containsAll(NAMES);
        }
    }

    @Test
    @DisplayName("they answer in a query as their Relix names do, in SQL's spellings too")
    void inAQuery() {
        try (Relix relix = Relix.open()) {
            relix.define("""
                    R := [| s     | x    |
                    | hello | -3.5 |];
                    """);
            Tuple t = relix.relation("π UPPER(s) → u, LENGTH(s) → n, SUBSTRING(s FROM 2 FOR 3) → sub, "
                    + "POSITION('l' IN s) → pos, FLOOR(x) → f, CEILING(x) → c, TRUNC(x) → tr, "
                    + "CONCAT(s, ' ', x) → cat (R)").toList().get(0);
            assertThat(List.of("u", "n", "sub", "pos", "f", "c", "tr", "cat").stream()
                    .map(c -> t.get(c).asDisplayString()).toList())
                    .containsExactly("HELLO", "5", "ell", "3", "-4", "-3", "-3", "hello -3.5");
        }
    }

    @Test
    @DisplayName("a misspelt SQL name is offered the SQL name")
    void suggested() {
        try (Relix relix = Relix.open()) {
            relix.define("R := [| s |\n| a |];");
            assertThatThrownBy(() -> relix.relation("π LOWR(s) → l (R)").toList())
                    .hasMessageContaining("LOWER");
        }
    }
}
