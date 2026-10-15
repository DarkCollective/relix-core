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
package com.darkcollective.relix.parser;

import com.darkcollective.relix.ast.AstLocations;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.SortNode;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.Spelling;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Operand#prettyPrint(Spelling)} and {@link SortSpecification#render(Spelling)}
 * are published as script text that parses back to an equal operand. These hold them
 * to it over every operand shape, in both spellings.
 */
@DisplayName("Operand.prettyPrint and SortSpecification.render — print → parse")
final class OperandRenderRoundTripTest extends ParserTestSupport {

    private static Operand parseOperand(String text) {
        return RelAlgebraParser.parseOperand(text, 1, 1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "price",
            "`select`",
            "Orders.customer_id",
            "42",
            "3.14",
            "\"it's \\\"quoted\\\"\\n\"",
            "TRUE",
            "DATE '2026-03-01'",
            "TIME '09:30:00'",
            "TIMESTAMP '2026-03-01T09:00:00Z'",
            "DURATION 'PT30M'",
            "$limit",
            "price * qty",
            "(price + tax) * qty",
            "price - (tax - discount)",
            "-price",
            "UCase(Trim(name))",
            "IIf(price >= 10, \"high\", \"low\")",
            "IIf(a > 1 AND NOT b = 2, 1, 0)",
            "a + (b + c)",
            "a / (b * c)",
            "-(-price)",
            "-(price * qty)",
    })
    @DisplayName("an operand prints as text that parses back to it, in either spelling")
    void operandRoundTrips(String text) {
        Operand operand = AstLocations.stripLocations(parseOperand(text));
        for (Spelling spelling : Spelling.values()) {
            String printed = operand.prettyPrint(spelling);
            assertThat(AstLocations.stripLocations(parseOperand(printed)))
                    .as("%s printed as %s", text, printed)
                    .isEqualTo(operand);
        }
    }

    @Test
    @DisplayName("prettyPrint() writes glyphs, prettyPrint(KEYWORDS) ASCII")
    void spellingChoosesConditionOperators() {
        Operand operand = parseOperand("IIf(price >= 10 AND qty ≠ 0, 1, 0)");

        assertThat(operand.prettyPrint()).isEqualTo(operand.prettyPrint(Spelling.GLYPHS));
        assertThat(operand.prettyPrint(Spelling.GLYPHS)).contains("≥").contains("≠");
        assertThat(operand.prettyPrint(Spelling.KEYWORDS)).contains(">=").doesNotContain("≥");
    }

    @Test
    @DisplayName("an expression sort key renders as the τ wrote it")
    void expressionSortKeyRenders() {
        SortNode sort = (SortNode) parse("SORT price * qty DESC, name (T)");

        assertThat(sort.sortSpecs()).extracting(SortSpecification::render)
                .containsExactly("price * qty DESC", "name");
        assertThat(sort.sortSpecs().get(0).columnName()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "price * qty DESC",
            "name",
            "UCase(name) DESC",
            "IIf(a >= 1, a, 0) DESC",
            "-price",
    })
    @DisplayName("a rendered sort key parses back to an equal key in a τ")
    void sortKeyRoundTrips(String key) {
        SortSpecification spec = ((SortNode) parse("SORT " + key + " (T)")).sortSpecs().get(0);
        for (Spelling spelling : Spelling.values()) {
            SortSpecification reparsed =
                    ((SortNode) parse("SORT " + spec.render(spelling) + " (T)")).sortSpecs().get(0);
            assertThat(reparsed.direction()).isEqualTo(spec.direction());
            assertThat(AstLocations.stripLocations(reparsed.expression()))
                    .isEqualTo(AstLocations.stripLocations(spec.expression()));
        }
        assertThat(spec.render()).isEqualTo(spec.render(Spelling.GLYPHS));
    }
}
