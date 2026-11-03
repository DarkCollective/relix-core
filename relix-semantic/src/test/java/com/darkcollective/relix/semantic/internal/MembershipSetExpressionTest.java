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

import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.semantic.SemanticError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.darkcollective.relix.ast.AstBuilders.attr;
import static com.darkcollective.relix.ast.AstBuilders.elementOf;
import static com.darkcollective.relix.ast.AstBuilders.notElementOf;
import static com.darkcollective.relix.ast.AstBuilders.param;
import static com.darkcollective.relix.ast.AstBuilders.str;
import static com.darkcollective.relix.semantic.SemanticAssertions.assertThat;
import static com.darkcollective.relix.semantic.SemanticFixtures.analyze;

@DisplayName("IN over one value in parentheses is refused at analysis (#290)")
final class MembershipSetExpressionTest {

    private static final String ORDERS = """
            Orders := [
            | id | status |
            |----|--------|
            | 1  | OPEN   |
            ];
            """;

    private static SemanticResult select(String condition) {
        return analyze(ORDERS + "query { SELECT " + condition + " (Orders) };\n");
    }

    @Test
    @DisplayName("the query from the issue is refused once, at the operand, naming the braces")
    void fromTheIssue() {
        SemanticResult result = select("status IN ('OPEN')");
        assertThat(result).hasDiagnosticCount(1)
                .hasErrorContaining("one value in parentheses is that value, not a list")
                .hasErrorContaining("status IN {\"OPEN\"}");
        SemanticError error = result.errors().getFirst();
        // ORDERS is five lines, so the query is line 6; the operand is the string in the parenthesis.
        int operand = "query { SELECT status IN (".length() + 1;
        org.assertj.core.api.Assertions.assertThat(error.line()).isEqualTo(6);
        org.assertj.core.api.Assertions.assertThat(error.column()).isEqualTo(operand);
    }

    @Test
    @DisplayName("NOT IN and ∉ are refused the same way, the rewrite keeping the negation")
    void negated() {
        assertThat(select("status NOT IN ('OPEN')"))
                .hasErrorContaining("status NOT IN {\"OPEN\"}");
        assertThat(select("status ∉ ('OPEN')"))
                .hasErrorContaining("status NOT IN {\"OPEN\"}");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"id IN (1)", "id IN (1 + 1)", "status IN (Upper('open'))"})
    @DisplayName("any other operand that is no set is refused too")
    void otherScalars(String condition) {
        assertThat(select(condition)).hasErrorContaining("a list of one value is written in braces");
    }

    @Test
    @DisplayName("a condition in operand position is refused as one in a selection is")
    void inOperandPosition() {
        assertThat(analyze(ORDERS
                + "query { PROJECT IIf(status IN ('OPEN'), 1, 0) -> open (Orders) };\n"))
                .hasErrorContaining("status IN {\"OPEN\"}");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"status IN {'OPEN'}", "status IN ('OPEN', 'HELD')", "status NOT IN {}",
            "status IN (π status (Orders))"})
    @DisplayName("a set, SQL's list and a relation stay legal")
    void stillLegal(String condition) {
        assertThat(select(condition)).hasNoErrors();
    }

    @Test
    @DisplayName("a name may yet be bound to a set, and passes; a literal never is")
    void namesPass() {
        Operand status = attr("status");
        org.assertj.core.api.Assertions.assertThat(ComparisonTypes.setExpressionError(
                elementOf(status, attr("codes")))).isNull();
        org.assertj.core.api.Assertions.assertThat(ComparisonTypes.setExpressionError(
                elementOf(status, param("codes")))).isNull();
        org.assertj.core.api.Assertions.assertThat(ComparisonTypes.setExpressionError(
                notElementOf(status, str("OPEN"))))
                .endsWith("status NOT IN {\"OPEN\"}");
    }
}
