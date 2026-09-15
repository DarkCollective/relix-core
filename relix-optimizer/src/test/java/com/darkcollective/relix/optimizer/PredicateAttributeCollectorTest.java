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
package com.darkcollective.relix.optimizer;

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which columns a predicate touches — the question the pushdown rules act on.
 *
 * <p>A predicate reporting <em>no</em> attributes is treated as constant, and a constant
 * may be pushed anywhere: into either input of a join, below anything. So an arm of this
 * walk that failed to descend would not merely lose a name, it would hand the pushdown
 * rules a licence to move a predicate onto a relation it says nothing about. That is the
 * defect {@code JoinSides} was fixed for twice over, arriving from the other direction.
 *
 * <p>Mutation found the descent unasserted almost everywhere: fourteen recursive calls
 * could be deleted with nothing failing, because the suites reach this through σ over a
 * bare {@code col = literal} and little else. Each form is asked here directly, and each
 * hides its attribute somewhere only the recursion can find.
 */
@DisplayName("PredicateAttributeCollector — every form is descended into")
final class PredicateAttributeCollectorTest {

    /** {@code x = 1}, whose attribute is reachable without any recursion. */
    private static Predicate leaf(String column) {
        return cmp(attr(column), ComparisonOperator.EQUAL, num("1"));
    }

    @TestFactory
    @DisplayName("predicate forms")
    Stream<DynamicTest> predicateForms() {
        Map<String, Predicate> forms = new LinkedHashMap<>();
        forms.put("∧", and(leaf("a"), leaf("b")));
        forms.put("∨", or(leaf("a"), leaf("b")));
        forms.put("¬", not(and(leaf("a"), leaf("b"))));
        forms.put("comparison, both sides", cmp(attr("a"), ComparisonOperator.LESS, attr("b")));
        forms.put("IS NULL", and(nullPred(attr("a"), true), nullPred(attr("b"), false)));
        forms.put("∈", elementOf(attr("a"), set(attr("b"))));
        forms.put("LIKE", like(attr("a"), attr("b")));

        return forms.entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey(),
                () -> assertThat(PredicateAttributeCollector.collectNames(e.getValue()))
                        .as("%s hides a column on each side", e.getKey())
                        .containsExactlyInAnyOrder("a", "b")));
    }

    @TestFactory
    @DisplayName("operand forms, each with its column buried inside")
    Stream<DynamicTest> operandForms() {
        Map<String, Operand> forms = new LinkedHashMap<>();
        forms.put("arithmetic", arith(attr("a"), ArithmeticOperator.PLUS, attr("b")));
        forms.put("function call", func("Coalesce", attr("a"), attr("b")));
        forms.put("unary", unary(arith(attr("a"), ArithmeticOperator.PLUS, attr("b"))));
        forms.put("set literal", set(attr("a"), attr("b")));
        forms.put("struct", structOf(field("l", attr("a")), field("r", attr("b"))));
        forms.put("array", arrayOf(attr("a"), attr("b")));
        forms.put("nested two deep", func("Abs",
                arith(attr("a"), ArithmeticOperator.PLUS, func("Len", attr("b")))));

        return forms.entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey(),
                () -> assertThat(PredicateAttributeCollector.collectNames(
                        cmp(e.getValue(), ComparisonOperator.EQUAL, num("1"))))
                        .as("%s carries both columns", e.getKey())
                        .containsExactlyInAnyOrder("a", "b")));
    }

    @Nested
    @DisplayName("what it does not collect")
    final class NotCollected {

        @Test
        @DisplayName("a predicate over literals alone touches no column")
        void literalsOnly() {
            assertThat(PredicateAttributeCollector.collectNames(
                    cmp(num("1"), ComparisonOperator.EQUAL, num("1")))).isEmpty();
        }

        @Test
        @DisplayName("the qualifier is part of the name, and stripped only on request")
        void qualifiers() {
            assertThat(PredicateAttributeCollector.collectNames(leaf("Users.id")))
                    .containsExactly("Users.id");
            assertThat(PredicateAttributeCollector.columnPart("Users.id")).isEqualTo("id");
            assertThat(PredicateAttributeCollector.columnPart("id")).isEqualTo("id");
        }
    }
}
