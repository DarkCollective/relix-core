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
 * Formats a {@link com.darkcollective.relix.ast.Predicate} as a Unicode
 * relational algebra string.
 *
 * <p>Uses Unicode logical and comparison symbols: {@code ∧} (and), {@code ∨}
 * (or), {@code ¬} (not), {@code ∈}/{@code ∉} (set membership), {@code ⊥}
 * (null), and {@code =  ≠  <  ≤  >  ≥} for comparisons. Binary predicates
 * are parenthesised ({@code (left) ∧ (right)}) to make associativity
 * unambiguous when nested.
 *
 * <p>Operand formatting is delegated to {@link OperandPrettyPrinter}.
 */
public final class PredicatePrettyPrinter implements PredicateVisitor<String> {
    private final OperandPrettyPrinter operandPrinter = new OperandPrettyPrinter();

    @Override
    public String visit(ComparisonPredicate node) {
        return node.left().accept(operandPrinter) + " " + node.operator().symbol() + " " + node.right().accept(operandPrinter);
    }

    @Override
    public String visit(AndPredicate node) {
        return "(" + node.left().accept(this) + ") ∧ (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(OrPredicate node) {
        return "(" + node.left().accept(this) + ") ∨ (" + node.right().accept(this) + ")";
    }

    @Override
    public String visit(NotPredicate node) {
        return "¬(" + node.predicate().accept(this) + ")";
    }

    @Override
    public String visit(NullPredicate node) {
        String operator = node.isNull() ? "=" : "≠";
        return node.operand().accept(operandPrinter) + " " + operator + " ⊥";
    }

    @Override
    public String visit(ElementOfPredicate node) {
        String operator = node.isNegated() ? " ∉ " : " ∈ ";
        return node.element().accept(operandPrinter) + operator + node.setExpression().accept(operandPrinter);
    }

    @Override
    public String visit(PatternPredicate node) {
        String operator = node.negated() ? " NOT LIKE " : " LIKE ";
        return node.operand().accept(operandPrinter) + operator + node.pattern().accept(operandPrinter);
    }

}
