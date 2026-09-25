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

import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.ScriptParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link Relix#parse}: text to a tree, and every failure as the published parse failure. */
@DisplayName("Relix.parse")
final class ParseTest {

    @Test
    @DisplayName("parses without resolving, so an undeclared name is fine")
    void parsesWithoutResolving() {
        Script script = Relix.parse("query { σ amount > 100 (Nowhere) };");
        assertThat(script.statements()).singleElement().isInstanceOf(QueryStatement.class);
    }

    @Test
    @DisplayName("a grammar failure carries its line and column")
    void positioned() {
        assertThatThrownBy(() -> Relix.parse("X := { σ a > 1 (R) };\nquery { π (R) };", "x.relix"))
                .isInstanceOfSatisfying(ScriptParseException.class, e -> {
                    assertThat(e.line()).isEqualTo(2);
                    assertThat(e.column()).isPositive();
                });
    }

    @Test
    @DisplayName("a literal of the right shape but the wrong value is placed at its token")
    void malformedLiteral() {
        assertThatThrownBy(() -> Relix.parse("query { λ 1.5 (R) };"))
                .isInstanceOfSatisfying(ScriptParseException.class, e -> {
                    assertThat(e.line()).isEqualTo(1);
                    assertThat(e.column()).isEqualTo(11);
                });
    }

    @Test
    @DisplayName("refuses null")
    void nulls() {
        assertThatThrownBy(() -> Relix.parse(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Relix.parse("query R;", null)).isInstanceOf(NullPointerException.class);
    }
}
