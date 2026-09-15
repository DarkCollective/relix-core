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
package com.darkcollective.relix.processor.reference;

import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The adjacency-to-forest fold, stated the way the manual states it.
 *
 * <p>"A row whose parentKey is NULL, or references a key value absent from R, is a root
 * (forest semantics — there may be several). Every other row is a child of the row whose
 * key equals its parentKey. TREE emits one row per root: the root's own flat columns,
 * followed by childrenColumn holding an array of child documents. Each child document is a
 * struct of that child's input columns plus its own childrenColumn array, recursively. A
 * leaf carries an empty children array."
 *
 * <p>That is a recursive descent from the roots, and the implementation below is exactly
 * that — build a parent → children index, start at the roots, recurse. No memoisation, no
 * iterative flattening, no cycle detection beyond what the shape forces, because an oracle
 * is worth having only while reading it settles the question.
 *
 * <p>TREE never pushes down, so no agreement suite reaches it, and nesting is awkward to
 * assert by hand: a subtree is checked by looking for a substring of its rendering, which
 * passes for a tree that is right in the part quoted and wrong below it. Rendering both
 * sides canonically and comparing the whole thing is what that misses.
 */
final class TreeReference {

    private TreeReference() {
    }

    /**
     * The forest, as one canonical string per root in root order.
     *
     * @param rows          the adjacency rows, each column → displayed value, null for NULL
     * @param keyColumn     the node-identity column
     * @param parentColumn  the parent-pointer column
     * @param childrenName  the name of the added nested column
     * @param order         sibling and root ordering; empty means input order
     * @return one rendering per root
     */
    static List<String> forest(List<SequencedMap<String, String>> rows,
                               String keyColumn, String parentColumn, String childrenName,
                               Comparator<SequencedMap<String, String>> order) {
        // A row whose own key is NULL is not in the index: a null has no identity, so
        // nothing can point at it and it can only ever be a leaf, wherever it lands.
        Set<String> known = rows.stream()
                .map(row -> row.get(keyColumn))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        List<SequencedMap<String, String>> roots = new ArrayList<>();
        Map<String, List<SequencedMap<String, String>>> children = new LinkedHashMap<>();
        for (SequencedMap<String, String> row : rows) {
            String parent = row.get(parentColumn);
            // A pointer to a key the relation does not hold is a root, not a dangling
            // child: the forest is what the rows themselves can see.
            if (parent == null || !known.contains(parent)) {
                roots.add(row);
            } else {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(row);
            }
        }

        if (order != null) {
            roots.sort(order);
            children.values().forEach(siblings -> siblings.sort(order));
        }
        return roots.stream()
                .map(root -> render(root, children, keyColumn, childrenName))
                .toList();
    }

    /** One node and everything beneath it, in the same canonical form {@link #canonical} gives. */
    private static String render(SequencedMap<String, String> node,
                                 Map<String, List<SequencedMap<String, String>>> children,
                                 String keyColumn, String childrenName) {
        String nested = children.getOrDefault(node.get(keyColumn), List.of()).stream()
                .map(child -> render(child, children, keyColumn, childrenName))
                .collect(Collectors.joining(",", "[", "]"));
        String own = node.entrySet().stream()
                .map(field -> field.getKey() + "=" + (field.getValue() == null ? "NULL" : field.getValue()))
                .collect(Collectors.joining(","));
        return "{" + own + "," + childrenName + "=" + nested + "}";
    }

    /**
     * A nested value in the same canonical form, so the two sides can be compared whole.
     *
     * <p>Walking the value rather than reading its {@code asDisplayString} is the point: a
     * rendering is a format and comparing substrings of one is how a tree that is right at
     * the top and wrong underneath passes.
     *
     * @param value the value to render; may be nested to any depth
     * @return its canonical form
     */
    static String canonical(Value value) {
        return switch (value) {
            case ArrayValue array -> array.elements().stream()
                    .map(TreeReference::canonical)
                    .collect(Collectors.joining(",", "[", "]"));
            case StructValue struct -> struct.fields().entrySet().stream()
                    .map(field -> field.getKey() + "=" + canonical(field.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
            default -> value.isNull() ? "NULL" : value.asDisplayString();
        };
    }
}
