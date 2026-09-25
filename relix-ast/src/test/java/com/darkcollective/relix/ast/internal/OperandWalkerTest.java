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
package com.darkcollective.relix.ast.internal;

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.StructConstruction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import com.darkcollective.relix.ast.AstBuilders;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared {@link OperandWalker} — the single recursion that the
 * semantic validator's argument/projection checks and the processor's COVER
 * factor-dependency collector both drive.
 */
@DisplayName("OperandWalker — shared operand/predicate-tree traversal")
final class OperandWalkerTest {

    private final List<String> attributes = new ArrayList<>();
    private final List<String> functions  = new ArrayList<>();

    private void walk(Operand expr) {
        OperandWalker.walk(expr,
                a -> attributes.add(a.name()),
                f -> functions.add(f.functionName()));
    }

    private void walk(Predicate pred) {
        OperandWalker.walk(pred,
                a -> attributes.add(a.name()),
                f -> functions.add(f.functionName()));
    }

    @Nested
    @DisplayName("operand traversal")
    class OperandTraversal {

        @Test
        @DisplayName("visits a bare attribute")
        void visitsAttribute() {
            walk(attr("Users.id"));
            assertThat(attributes).containsExactly("Users.id");
            assertThat(functions).isEmpty();
        }

        @Test
        @DisplayName("ignores literals")
        void ignoresLiterals() {
            walk(num("1"));
            walk(str("x"));
            walk(bool(true));
            assertThat(attributes).isEmpty();
            assertThat(functions).isEmpty();
        }

        @Test
        @DisplayName("recurses arithmetic and unary operands")
        void recursesArithmeticAndUnary() {
            walk(arith(
                    attr("a"),
                    ArithmeticOperator.PLUS,
                    unary(attr("b"))));
            assertThat(attributes).containsExactly("a", "b");
        }

        @Test
        @DisplayName("recurses set literals, structs, and arrays")
        void recursesCompoundConstructions() {
            walk(set(attr("s")));
            walk(structOf(
                    new StructConstruction.Field("f", attr("st"))));
            walk(arrayOf(attr("ar")));
            assertThat(attributes).containsExactly("s", "st", "ar");
        }

        @Test
        @DisplayName("walks function arguments before invoking the function callback")
        void walksArgumentsBeforeFunction() {
            walk(func("UCase",attr("name")));
            assertThat(attributes).containsExactly("name");
            assertThat(functions).containsExactly("UCase");
        }

        @Test
        @DisplayName("recurses nested function calls")
        void recursesNestedFunctionCalls() {
            walk(func("Outer",
                    func("Inner",attr("c"))));
            assertThat(attributes).containsExactly("c");
            // Inner is walked (and reported) before Outer, mirroring argument-first order.
            assertThat(functions).containsExactly("Inner", "Outer");
        }
    }

    @Nested
    @DisplayName("predicate traversal")
    class PredicateTraversal {

        @Test
        @DisplayName("walks both sides of a comparison")
        void comparison() {
            walk(cmp(
                    attr("a"), ComparisonOperator.EQUAL,
                    attr("b")));
            assertThat(attributes).containsExactly("a", "b");
        }

        @Test
        @DisplayName("recurses AND / OR connectives")
        void andOr() {
            Predicate left  = cmp(
                    attr("a"), ComparisonOperator.EQUAL, num("1"));
            Predicate right = cmp(
                    attr("b"), ComparisonOperator.EQUAL, num("2"));
            walk(or(and(left, right), right));
            assertThat(attributes).containsExactly("a", "b", "b");
        }

        @Test
        @DisplayName("recurses NOT")
        void not() {
            walk(AstBuilders.not(nullPred(attr("a"), true)));
            assertThat(attributes).containsExactly("a");
        }

        @Test
        @DisplayName("walks a null-test operand")
        void nullTest() {
            walk(nullPred(attr("a"), false));
            assertThat(attributes).containsExactly("a");
        }

        @Test
        @DisplayName("walks both the element and the set expression of IN")
        void elementOf() {
            walk(AstBuilders.elementOf(
                    attr("a"),
                    set(attr("b"))));
            assertThat(attributes).containsExactly("a", "b");
        }

        @Test
        @DisplayName("reaches a function call buried inside a predicate")
        void functionInsidePredicate() {
            walk(cmp(
                    func("UCase",attr("name")),
                    ComparisonOperator.EQUAL,
                    str("X")));
            assertThat(attributes).containsExactly("name");
            assertThat(functions).containsExactly("UCase");
        }
    }
}
