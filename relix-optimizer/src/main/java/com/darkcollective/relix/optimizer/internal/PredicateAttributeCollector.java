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

import com.darkcollective.relix.ast.internal.AttributeNames;
import com.darkcollective.relix.ast.internal.OperandWalker;
import com.darkcollective.relix.ast.Predicate;

import java.util.HashSet;
import java.util.Set;

/**
 * Utility that collects all {@link com.darkcollective.relix.ast.AttributeOperand} names referenced anywhere
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
        // The walker is exhaustive over the sealed operand kinds, so a column read inside
        // a condition argument — IIf(x > 3, …), or a CASE, which is Switch — is counted.
        OperandWalker.walk(predicate, attr -> names.add(attr.name()), fn -> { });
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
}
