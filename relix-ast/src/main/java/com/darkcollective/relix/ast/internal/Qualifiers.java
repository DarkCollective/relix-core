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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RenameNode;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The relation names an expression's columns answer to as qualifiers.
 *
 * <p>{@code A.x} names the column {@code x} of relation {@code A} only where {@code A}
 * is in scope, and which names are in scope is a property of the tree: a leaf brings its
 * own name, and a {@code ρ} that renames the relation replaces every name beneath it.
 * Both the planner (routing a join condition's references to a side) and the optimizer
 * (deciding which input of a join a selection may be pushed into) ask this question, so
 * it is answered once, here.
 */
public final class Qualifiers {

    private Qualifiers() {
    }

    /**
     * Returns the relation names in scope as qualifiers for {@code node}'s columns.
     *
     * <p>A leaf relation answers to its own name. A {@code ρ} that gives a new relation
     * name answers to that name only, shadowing everything beneath it; a column-only
     * {@code ρ} leaves the names beneath it in scope. Every other operator answers to the
     * names of all of its inputs.
     *
     * @param node the expression; must not be null
     * @return the names, lowercased; a new mutable set
     */
    public static Set<String> inScope(RelNode node) {
        Objects.requireNonNull(node, "node");
        Set<String> names = new HashSet<>();
        collect(node, names);
        return names;
    }

    private static void collect(RelNode node, Set<String> out) {
        switch (node) {
            case RelationNode r -> out.add(r.name().toLowerCase(Locale.ROOT));
            case RenameNode r -> {
                if (r.relationName().isPresent()) {
                    out.add(r.relationName().get().toLowerCase(Locale.ROOT));
                } else {
                    collect(r.input(), out);
                }
            }
            default -> node.children().forEach(child -> collect(child, out));
        }
    }
}
