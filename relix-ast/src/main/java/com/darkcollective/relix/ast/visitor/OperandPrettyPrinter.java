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
package com.darkcollective.relix.ast.visitor;

import com.darkcollective.relix.ast.*;

/**
 * Formats an {@link com.darkcollective.relix.ast.Operand} expression as a
 * Unicode relational algebra string.
 *
 * <p>Handles operator precedence for binary arithmetic (multiplication and
 * division bind tighter than addition and subtraction) and inserts parentheses
 * when a lower-precedence sub-expression appears in a higher-precedence context.
 * String values are escaped ({@code \"}, {@code \\}, {@code \n}, {@code \t}).
 *
 * <p>Used internally by {@link PrettyPrinter}; also useful for formatting
 * individual operand expressions in isolation.
 */
public final class OperandPrettyPrinter implements OperandVisitor<String> {
    private final int precedence;

    public OperandPrettyPrinter() {
        this(0);
    }

    private OperandPrettyPrinter(int precedence) {
        this.precedence = precedence;
    }

    @Override
    public String visit(AttributeOperand node) {
        // Backtick-delimit a column name that collides with a reserved word (or is
        // otherwise not identifier-shaped) so the reference parses back unchanged;
        // ordinary names are returned as-is.
        return Identifiers.render(node.name());
    }

    @Override
    public String visit(StringOperand node) {
        return "\"" + node.value()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t") + "\"";
    }

    @Override
    public String visit(NumberOperand node) {
        return node.value();
    }

    @Override
    public String visit(BooleanOperand node) {
        return String.valueOf(node.value());
    }

    @Override
    public String visit(DateOperand node) {
        return "DATE '" + node.value() + "'";
    }

    @Override
    public String visit(TimeOperand node) {
        return "TIME '" + node.value() + "'";
    }

    @Override
    public String visit(TimestampOperand node) {
        return "TIMESTAMP '" + node.value() + "'";
    }

    @Override
    public String visit(DurationOperand node) {
        return "DURATION '" + node.value() + "'";
    }

    @Override
    public String visit(BinaryArithmeticExpression node) {
        int opPrecedence = switch (node.operator()) {
            case PLUS, MINUS -> 10;
            case MULTIPLY, DIVIDE -> 20;
        };

        String operator = switch (node.operator()) {
            case PLUS -> "+";
            case MINUS -> "-";
            case MULTIPLY -> "*";
            case DIVIDE -> "/";
        };

        String result = node.left().accept(new OperandPrettyPrinter(opPrecedence)) + " " + operator + " " + node.right().accept(new OperandPrettyPrinter(opPrecedence));

        if (opPrecedence < precedence) {
            result = "(" + result + ")";
        }

        return result;
    }

    @Override
    public String visit(FunctionCall node) {
        StringBuilder sb = new StringBuilder(node.functionName());
        sb.append("(");
        for (int i = 0; i < node.arguments().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(node.arguments().get(i).accept(this));
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public String visit(SetLiteralOperand node) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < node.elements().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(node.elements().get(i).accept(this));
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public String visit(UnaryOperand node) {
        String inner = node.operand().accept(this);
        // Parenthesise binary expressions to make precedence unambiguous: -(a + b)
        if (node.operand() instanceof BinaryArithmeticExpression) {
            return "-(" + inner + ")";
        }
        return "-" + inner;
    }

    @Override
    public String visit(StructConstruction node) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < node.fields().size(); i++) {
            if (i > 0) sb.append(", ");
            StructConstruction.Field field = node.fields().get(i);
            sb.append(field.name()).append(": ").append(field.value().accept(this));
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public String visit(ArrayConstruction node) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < node.elements().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(node.elements().get(i).accept(this));
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public String visit(ConditionOperand node) {
        // Parenthesised, because this is an *operand*: a condition and its truth
        // value are different things in every position but a function argument, and
        // `a > b = ⊥` does not say which one it means. The grammar reads a
        // parenthesis here as opening a condition, so the parenthesised form
        // round-trips in all four positions a condition can occupy — a function
        // argument (`IIf((price > 100), …)`), a projected column, and either side of
        // a comparison or null test. The redundant pair inside a function call is
        // the price of one rule instead of a context-sensitive one.
        return "(" + node.predicate().accept(new PredicatePrettyPrinter()) + ")";
    }
}
