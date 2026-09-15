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
package com.darkcollective.relix.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Constructor validation tests for predicate, operand, and helper entity AST nodes:
 * {@link ComparisonPredicate}, {@link AndPredicate}, {@link OrPredicate},
 * {@link NotPredicate}, {@link NullPredicate}, {@link ElementOfPredicate},
 * {@link AttributeOperand}, {@link NumberOperand}, {@link UnaryOperand},
 * {@link BinaryArithmeticExpression}, {@link FunctionCall}, {@link SetLiteralOperand},
 * {@link SortSpecification}, {@link AggregateFunction}, and {@link ProjectedAttribute}.
 */
@DisplayName("AST — predicate, operand, and helper entity validation")
final class AstPredicateOperandTest extends AstTestSupport {

    // =========================================================================
    // Predicate nodes
    // =========================================================================

    @Nested
    @DisplayName("Predicate Validations")
    class PredicateValidation {

        @Test
        @DisplayName("AndPredicate rejects null left predicate")
        void andPredicateRejectsNullLeft() {
            assertThatThrownBy(() -> new AndPredicate(
                    null, cmp(attr("a"), ComparisonOperator.EQUAL, num("1"))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("AndPredicate rejects null right predicate")
        void andPredicateRejectsNullRight() {
            assertThatThrownBy(() -> new AndPredicate(
                    cmp(attr("a"), ComparisonOperator.EQUAL, num("1")), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("OrPredicate rejects null left predicate")
        void orPredicateRejectsNullLeft() {
            assertThatThrownBy(() -> new OrPredicate(
                    null, cmp(attr("a"), ComparisonOperator.EQUAL, num("1"))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("NotPredicate rejects null operand")
        void notPredicateRejectsNullOperand() {
            assertThatThrownBy(() -> new NotPredicate(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("ComparisonPredicate rejects null operator")
        void comparisonPredicateRejectsNullOperator() {
            assertThatThrownBy(() -> new ComparisonPredicate(attr("a"), null, num("1")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("ComparisonPredicate rejects null left operand")
        void comparisonPredicateRejectsNullLeft() {
            assertThatThrownBy(() -> new ComparisonPredicate(null, ComparisonOperator.EQUAL, num("1")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("ComparisonPredicate rejects null right operand")
        void comparisonPredicateRejectsNullRight() {
            assertThatThrownBy(() -> new ComparisonPredicate(attr("a"), ComparisonOperator.EQUAL, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("NullPredicate rejects null attribute operand")
        void nullPredicateRejectsNullAttribute() {
            assertThatThrownBy(() -> new NullPredicate(null, true))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("ElementOfPredicate rejects null element operand")
        void elementOfPredicateRejectsNullElement() {
            assertThatThrownBy(() -> new ElementOfPredicate(
                    null, new SetLiteralOperand(List.of(num("1"))), false))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("ElementOfPredicate rejects null set expression")
        void elementOfPredicateRejectsNullSet() {
            assertThatThrownBy(() -> new ElementOfPredicate(attr("status"), null, false))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Accepts valid and predicate")
        void acceptsValidPredicate() {
            AndPredicate pred = new AndPredicate(
                    cmp(attr("age"), ComparisonOperator.GREATER, num("18")),
                    cmp(attr("status"), ComparisonOperator.EQUAL, str("active")));
            assertThat(pred.left())
                    .isEqualTo(cmp(attr("age"), ComparisonOperator.GREATER, num("18")));
            assertThat(pred.right())
                    .isEqualTo(cmp(attr("status"), ComparisonOperator.EQUAL, str("active")));
        }
    }

    // =========================================================================
    // Operand nodes
    // =========================================================================

    @Nested
    @DisplayName("Operand Validations")
    class OperandValidation {

        @Test
        @DisplayName("AttributeOperand rejects null name")
        void attributeOperandRejectsNullName() {
            assertThatThrownBy(() -> new AttributeOperand(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("AttributeOperand rejects blank name")
        void attributeOperandRejectsBlankName() {
            assertThatThrownBy(() -> new AttributeOperand(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Accepts valid attribute operand")
        void acceptsValidAttributeOperand() {
            AttributeOperand op = new AttributeOperand("name");
            assertThat(op.name()).isEqualTo("name");
        }

        @Test
        @DisplayName("unqualifiedName strips a relation qualifier")
        void unqualifiedNameStripsQualifier() {
            assertThat(new AttributeOperand("Users.id").unqualifiedName()).isEqualTo("id");
        }

        @Test
        @DisplayName("unqualifiedName returns a bare name unchanged")
        void unqualifiedNameLeavesBareNameUnchanged() {
            assertThat(new AttributeOperand("id").unqualifiedName()).isEqualTo("id");
        }

        @Test
        @DisplayName("unqualifiedName keeps only the segment after the last dot")
        void unqualifiedNameKeepsLastSegment() {
            assertThat(new AttributeOperand("a.b.c").unqualifiedName()).isEqualTo("c");
        }

        @Test
        @DisplayName("NumberOperand rejects blank value")
        void numberOperandRejectsBlankValue() {
            assertThatThrownBy(() -> new NumberOperand(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("UnaryOperand rejects null operand")
        void unaryOperandRejectsNullOperand() {
            assertThatThrownBy(() -> new UnaryOperand(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Accepts valid unary operand wrapping an attribute")
        void acceptsValidUnaryOperand() {
            UnaryOperand op = new UnaryOperand(attr("price"));
            assertThat(op.operand()).isEqualTo(attr("price"));
        }

        @Test
        @DisplayName("UnaryOperand accepts nested unary operand")
        void unaryOperandAcceptsNestedUnary() {
            UnaryOperand op = new UnaryOperand(new UnaryOperand(attr("x")));
            assertThat(op.operand()).isEqualTo(new UnaryOperand(attr("x")));
        }

        @Test
        @DisplayName("BinaryArithmeticExpression rejects null operator")
        void binaryArithmeticRejectsNullOperator() {
            assertThatThrownBy(() -> new BinaryArithmeticExpression(attr("a"), null, attr("b")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("BinaryArithmeticExpression rejects null left operand")
        void binaryArithmeticRejectsNullLeft() {
            assertThatThrownBy(() -> new BinaryArithmeticExpression(
                    null, ArithmeticOperator.PLUS, attr("b")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("BinaryArithmeticExpression rejects null right operand")
        void binaryArithmeticRejectsNullRight() {
            assertThatThrownBy(() -> new BinaryArithmeticExpression(
                    attr("a"), ArithmeticOperator.PLUS, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("SetLiteralOperand rejects null elements list")
        void setLiteralRejectsNullElements() {
            assertThatThrownBy(() -> new SetLiteralOperand(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("SetLiteralOperand accepts empty elements list")
        void setLiteralAcceptsEmptyElements() {
            SetLiteralOperand set = new SetLiteralOperand(List.of());
            assertThat(set.elements()).isEmpty();
        }

        @Test
        @DisplayName("FunctionCall accepts null function name")
        void functionCallAcceptsNullName() {
            FunctionCall func = new FunctionCall(null, List.of());
            assertThat(func.functionName()).isNull();
        }

        @Test
        @DisplayName("FunctionCall accepts null arguments list")
        void functionCallAcceptsNullArguments() {
            FunctionCall func = new FunctionCall("UPPER", null);
            assertThat(func.arguments()).isNull();
        }

        @Test
        @DisplayName("Accepts valid function call")
        void acceptsValidFunctionCall() {
            FunctionCall func = new FunctionCall("UPPER", List.of(attr("name")));
            assertThat(func.functionName()).isEqualTo("UPPER");
        }
    }

    // =========================================================================
    // Helper entity nodes
    // =========================================================================

    @Nested
    @DisplayName("Helper Entities Validations")
    class HelperValidation {

        @Test
        @DisplayName("SortSpecification rejects null attribute")
        void sortSpecificationRejectsNullAttribute() {
            assertThatThrownBy(() -> new SortSpecification((String) null, SortDirection.ASC))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("SortSpecification rejects a blank column (wrapped as an AttributeOperand)")
        void sortSpecificationRejectsBlankAttribute() {
            assertThatThrownBy(() -> new SortSpecification("", SortDirection.ASC))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("SortSpecification rejects null direction")
        void sortSpecificationRejectsNullDirection() {
            assertThatThrownBy(() -> new SortSpecification("name", null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Accepts valid sort specification")
        void acceptsValidSortSpecification() {
            SortSpecification spec = new SortSpecification("name", SortDirection.DESC);
            assertThat(spec.columnName()).contains("name");
            assertThat(spec.expression()).isEqualTo(new AttributeOperand("name"));
            assertThat(spec.direction()).isEqualTo(SortDirection.DESC);
        }

        @Test
        @DisplayName("AggregateFunction rejects null operator")
        void aggregateFunctionRejectsNullOperator() {
            assertThatThrownBy(() -> new AggregateFunction(
                    null, new AttributeOperand("id"), Optional.empty(), Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("AggregateFunction rejects null argument")
        void aggregateFunctionRejectsNullArgument() {
            assertThatThrownBy(() -> new AggregateFunction(
                    AggregateOperator.COUNT, null, Optional.empty(), Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("AggregateFunction.simple rejects a blank attribute name")
        void aggregateFunctionRejectsBlankAttribute() {
            assertThatThrownBy(() -> AggregateFunction.simple(AggregateOperator.COUNT, ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("AggregateFunction rejects null alias optional")
        void aggregateFunctionRejectsNullAlias() {
            assertThatThrownBy(() -> new AggregateFunction(
                    AggregateOperator.COUNT, new AttributeOperand("id"), Optional.empty(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("AggregateFunction.aliased rejects blank alias string")
        void aggregateFunctionAliasedRejectsBlankAlias() {
            assertThatThrownBy(() -> AggregateFunction.aliased(AggregateOperator.SUM, "salary", ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("ProjectedAttribute rejects null expression")
        void projectedAttributeRejectsNullExpression() {
            assertThatThrownBy(() -> new ProjectedAttribute(null, Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("ProjectedAttribute rejects null alias optional")
        void projectedAttributeRejectsNullAlias() {
            assertThatThrownBy(() -> new ProjectedAttribute(attr("a"), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("ProjectedAttribute.aliased rejects blank alias string")
        void projectedAttributeAliasedRejectsBlankAlias() {
            assertThatThrownBy(() -> ProjectedAttribute.aliased(attr("a"), ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
