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

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.symbol.Symbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rejects a view whose definition reaches itself — {@code X := { σ … (X) }}, or a longer
 * loop such as {@code A := { B }; B := { A };}.
 *
 * <p>A view is a name for its body, so a reference to a view is expanded in place (the
 * optimizer's {@code ViewInliner}). A definition that reaches itself has no finite
 * expansion: left alone, schema inference silently leaves it unresolved and execution
 * overflows the stack. The legitimate ways to define a relation in terms of itself —
 * {@code FIX} and {@code ITERATE} — bind their name to a {@code RecursiveRefNode}, never
 * a {@link RelationNode}, so they are not edges here.
 *
 * <p>Names are case-insensitive, which makes the common case easy to miss: a view named
 * after the table it filters ({@code Rooms := { σ … (rooms) }}) replaces that table and
 * then reads itself. The message spells that out when the spellings differ.
 *
 * <p>Each cycle is reported once, at the reference that closes it.
 */
final class ViewCycles {

    private final SymbolTable symbols;
    private final List<SemanticError> errors = new ArrayList<>();
    /** Views whose walk has finished; nothing reachable from them closes a new cycle. */
    private final Set<String> done = new HashSet<>();
    /** The views on the current walk, in order, each with the key it was entered by. */
    private final Map<String, QueryRelationSymbol> path = new LinkedHashMap<>();

    private ViewCycles(SymbolTable symbols) {
        this.symbols = symbols;
    }

    /**
     * Returns one error per definition cycle among the views in {@code symbols}.
     *
     * @param symbols the collected symbol table; must not be null
     * @return the cycle errors, empty for an acyclic script
     */
    static List<SemanticError> check(SymbolTable symbols) {
        var check = new ViewCycles(symbols);
        for (Symbol symbol : symbols.allSymbols()) {
            if (symbol instanceof QueryRelationSymbol view) {
                check.visit(view);
            }
        }
        return List.copyOf(check.errors);
    }

    private void visit(QueryRelationSymbol view) {
        String key = key(view);
        if (done.contains(key)) {
            return;
        }
        path.put(key, view);
        walk(view.body());
        path.remove(key);
        done.add(key);
    }

    private void walk(RelNode node) {
        if (node instanceof RelationNode reference) {
            view(reference).ifPresent(target -> {
                if (path.containsKey(key(target))) {
                    report(reference, target);
                } else {
                    visit(target);
                }
            });
        }
        node.children().forEach(this::walk);
    }

    /** The view {@code reference} expands to, resolved as the optimizer resolves it. */
    private Optional<QueryRelationSymbol> view(RelationNode reference) {
        return symbols.lookupRelation(reference.name())
                .filter(QueryRelationSymbol.class::isInstance)
                .map(QueryRelationSymbol.class::cast);
    }

    private void report(RelationNode reference, QueryRelationSymbol target) {
        List<QueryRelationSymbol> loop = new ArrayList<>();
        boolean inLoop = false;
        for (Map.Entry<String, QueryRelationSymbol> entry : path.entrySet()) {
            inLoop |= entry.getKey().equals(key(target));
            if (inLoop) {
                loop.add(entry.getValue());
            }
        }
        String name = target.declaredName();
        String message;
        if (loop.size() == 1) {
            message = "View '" + name + "' refers to itself";
        } else {
            message = "View '" + name + "' refers to itself through "
                    + loop.stream().map(QueryRelationSymbol::declaredName)
                            .collect(Collectors.joining(" → ", "", " → " + name));
        }
        if (!reference.name().equals(name)) {   // resolved, so the spellings differ only in case
            message += " (names are case-insensitive, so '" + reference.name()
                    + "' is '" + name + "')";
        }
        message += "; a view cannot be defined in terms of itself — use FIX or ITERATE"
                + " for a recursive definition, or give the view a name of its own";
        SourceLocation at = reference.location();
        errors.add(SemanticError.error(at.filePath(), at.line(), at.column(), message));
    }

    private static String key(QueryRelationSymbol view) {
        return view.namespace() + ":" + view.canonicalName();
    }
}
