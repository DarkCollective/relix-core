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

import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Type;

import java.math.BigDecimal;
import java.util.List;

/**
 * Whether two operands can be compared, read from their types and, for the hint, the
 * operands themselves. The one rule shared by every place a comparison is written: a
 * selection, a join condition, and a boolean in operand position.
 *
 * <p>Temporal pairings answer to {@link TemporalArithmetic#comparisonError}. Beyond those,
 * a {@code NUMBER} never compares with a {@code STRING}. The executor reads a
 * {@code STRING} as the boolean or temporal it spells, never as a number, so
 * {@code =} and {@code ≠} between the two are never true and {@code <} fails when run.
 * Accepting it let {@code seconds = ''} validate and return no rows, which reads as an
 * answer (#176). The error names the fix the text suggests: a null test for {@code ''},
 * since an empty cell is {@code NULL}; the number itself for text that holds one; and
 * otherwise a {@code CAST}.
 */
final class ComparisonTypes {

    private ComparisonTypes() {
    }

    /**
     * The error for {@code left op right}, given each side's inferred type, or
     * {@code null} when the two can be compared.
     */
    static String comparisonError(Operand left, Type leftType, ComparisonOperator op,
                                  Operand right, Type rightType) {
        String temporal = TemporalArithmetic.comparisonError(leftType, rightType);
        if (temporal != null) {
            return temporal;
        }
        if (!isNumberWithString(leftType, rightType)) {
            return null;
        }
        boolean stringOnLeft = leftType == ScalarType.STRING;
        Operand number = stringOnLeft ? right : left;
        Operand text = stringOnLeft ? left : right;
        return "cannot compare " + scalarName(leftType) + " with " + scalarName(rightType)
                + "; " + hint(number, op, text);
    }

    /**
     * The error for {@code element ∈ {members…}}, given the element's type and each
     * member's, or {@code null} when every member can be compared with the element.
     * Reported once for the set, with the hint of the first member that cannot.
     */
    static String membershipError(Operand element, Type elementType,
                                  List<Operand> members, List<Type> memberTypes) {
        for (int i = 0; i < members.size(); i++) {
            Type memberType = memberTypes.get(i);
            String temporal = TemporalArithmetic.comparisonError(elementType, memberType);
            if (temporal != null) {
                return temporal;
            }
            if (isNumberWithString(elementType, memberType)) {
                return "cannot test a " + scalarName(elementType) + " for membership in a set"
                        + " holding a " + scalarName(memberType) + "; "
                        + hint(elementType == ScalarType.NUMBER ? element : members.get(i),
                                null, elementType == ScalarType.STRING ? element : members.get(i));
            }
        }
        return null;
    }

    private static boolean isNumberWithString(Type left, Type right) {
        return (left == ScalarType.NUMBER && right == ScalarType.STRING)
                || (left == ScalarType.STRING && right == ScalarType.NUMBER);
    }

    /**
     * What to write instead, given the side that is a {@code NUMBER} and the side that is
     * text. {@code op} is {@code null} for set membership, which is an equality test.
     */
    private static String hint(Operand number, ComparisonOperator op, Operand text) {
        if (text instanceof StringOperand s) {
            String value = s.value().trim();
            if (value.isEmpty() && number instanceof AttributeOperand column) {
                if (op == null || op == ComparisonOperator.EQUAL) {
                    return "an empty cell is NULL, not '': write " + column.name() + " IS NULL";
                }
                if (op == ComparisonOperator.NOT_EQUAL) {
                    return "an empty cell is NULL, not '': write " + column.name() + " IS NOT NULL";
                }
            }
            String asNumber = numberIn(value);
            if (asNumber != null) {
                return "compare with the number " + asNumber + ", or CAST(… AS NUMBER)";
            }
        }
        return "a STRING is never read as a number, so = never matches and < fails when run:"
                + " write CAST(… AS NUMBER) for text that holds a number";
    }

    /** The number {@code text} spells, written as a literal, or {@code null} if none. */
    private static String numberIn(String text) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text).toPlainString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String scalarName(Type t) {
        return ((ScalarType) t).name();
    }
}
