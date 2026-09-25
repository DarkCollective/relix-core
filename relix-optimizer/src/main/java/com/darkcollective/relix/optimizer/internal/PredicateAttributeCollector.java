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
package com.darkcollective.relix.optimizer.internal;

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.internal.AttributeNames;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.UnaryOperand;

import java.util.HashSet;
import java.util.Set;

/**
 * Utility that collects all {@link AttributeOperand} names referenced anywhere
 * in a {@link Predicate} tree.
 *
 * <p>Used by selection pushdown rules (SEL-003..005) to decide which columns a
 * predicate depends on, enabling safe movement of a selection below projections,
 * renames, and joins.
 *
 * <p>This class is package-private and stateless.
 */
public final class PredicateAttributeCollector {

    private PredicateAttributeCollector() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns an immutable set of every attribute name (including any
     * relation-qualifier prefix such as {@code "Users.id"}) that appears in
     * {@code predicate}.
     *
     * @param predicate the predicate to inspect; must not be null
     * @return unmodifiable set of attribute name strings; never null
     */
    static Set<String> collectNames(Predicate predicate) {
        var names = new HashSet<String>();
        collectFromPredicate(predicate, names);
        return Set.copyOf(names);
    }

    /**
     * Returns the column-name portion of an attribute reference, stripping any
     * leading relation qualifier.
     *
     * <p>Examples: {@code "Users.id"} → {@code "id"};
     *              {@code "age"} → {@code "age"}.
     *
     * @param attrName a raw attribute name; must not be null
     * @return the unqualified column name
     */
    static String columnPart(String attrName) {
        return AttributeNames.stripQualifier(attrName);
    }

    // =========================================================================
    // Private traversal
    // =========================================================================

    private static void collectFromPredicate(Predicate p, Set<String> names) {
        switch (p) {
            case ComparisonPredicate c -> {
                collectFromOperand(c.left(), names);
                collectFromOperand(c.right(), names);
            }
            case AndPredicate a -> {
                collectFromPredicate(a.left(), names);
                collectFromPredicate(a.right(), names);
            }
            case OrPredicate o -> {
                collectFromPredicate(o.left(), names);
                collectFromPredicate(o.right(), names);
            }
            case NotPredicate n       -> collectFromPredicate(n.predicate(), names);
            case NullPredicate n      -> collectFromOperand(n.operand(), names);
            case ElementOfPredicate e -> {
                collectFromOperand(e.element(), names);
                collectFromOperand(e.setExpression(), names);
            }
            case PatternPredicate pp -> {
                collectFromOperand(pp.operand(), names);
                collectFromOperand(pp.pattern(), names);
            }
        }
    }

    private static void collectFromOperand(Operand op, Set<String> names) {
        switch (op) {
            case AttributeOperand a           -> names.add(a.name());
            case BinaryArithmeticExpression b -> {
                collectFromOperand(b.left(), names);
                collectFromOperand(b.right(), names);
            }
            case FunctionCall f               -> f.arguments().forEach(arg -> collectFromOperand(arg, names));
            case UnaryOperand u               -> collectFromOperand(u.operand(), names);
            case SetLiteralOperand s          -> s.elements().forEach(el -> collectFromOperand(el, names));
            case StructConstruction struct    -> struct.fields().forEach(f -> collectFromOperand(f.value(), names));
            case ArrayConstruction array      -> array.elements().forEach(el -> collectFromOperand(el, names));
            default                           -> {} // NumberOperand, StringOperand, BooleanOperand — no attrs
        }
    }
}
