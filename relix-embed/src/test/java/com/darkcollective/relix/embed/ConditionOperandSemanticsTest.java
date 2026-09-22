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

/**
 * A parenthesised condition in operand position, end to end — grammar, analysis and
 * evaluation.
 *
 * <p>Both claims here are made in `docs/reference`, which is checked for parsing and
 * analysis but not for what a body example <em>returns</em>. They are the two that a
 * reader would act on: that a projected condition is a BOOLEAN column, and that its
 * value for a row the comparison cannot decide is NULL rather than false.
 *
 * <p>The second is the whole reason the optimizer's selection complement exists — a
 * row whose condition is UNKNOWN is one a σ drops and {@code ¬} does not give back —
 * so it is worth an assertion that names the rows rather than a sentence.
 */
@DisplayName("A condition in operand position — what it evaluates to")
final class ConditionOperandSemanticsTest {

    private static final String READINGS = """
            Readings := [
            | id | amount |
            |----|--------|
            | 1  | 10     |
            | 2  | 500    |
            | 3  |        |
            ];
            """;

    @Test
    @DisplayName("a projected condition is NULL, not false, where it cannot be decided")
    void projectedConditionCarriesThreeValuedLogic() {
        try (Relix session = Relix.builder().build()) {
            List<String> rows = session
                    .script(READINGS + "query { π id, (amount > 100) → big (Readings) };")
                    .getFirst()
                    .toList().stream()
                    .map(t -> t.longValue("id") + "=" + t.booleanValue("big"))
                    .toList();

            assertThat(rows).containsExactly("1=false", "2=true", "3=null");
        }
    }

    @Test
    @DisplayName("a null test over a condition is true for exactly the undecided rows")
    void nullTestOverConditionFindsTheUndecided() {
        try (Relix session = Relix.builder().build()) {
            List<String> rows = session
                    .script(READINGS + "query { σ (amount > 100) = ⊥ (Readings) };")
                    .getFirst()
                    .toList().stream()
                    .map(t -> String.valueOf(t.longValue("id")))
                    .toList();

            // Not row 1: `10 > 100` is decidably false, which is a different thing from
            // undecidable. That distinction is what `¬` cannot express.
            assertThat(rows).containsExactly("3");
        }
    }
}
