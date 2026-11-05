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

import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.internal.OperandWalker;
import com.darkcollective.relix.ast.internal.RelNodeOperands;
import com.darkcollective.relix.ast.internal.Subqueries;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.symbol.Symbol;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.function.ScalarFunctionSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rejects a {@code def} whose body reaches itself — {@code def f(x: NUMBER): NUMBER := { f(x) }},
 * or a longer loop such as {@code f → g → f}, scalar or table-valued, directly or through
 * a view the body names.
 *
 * <p>A function's body is substituted into its call, so a body that reaches itself has no
 * finite expansion: left alone it overflows the stack when the call is evaluated, or fails
 * at plan time with a message that says nothing about recursion. The legitimate way to
 * repeat a step, {@code FIX} or {@code ITERATE}, never goes through a function's own
 * name. This is {@link ViewCycles}'s counterpart for functions, and reports the same way:
 * once per cycle, at the call that closes it.
 */
final class DefCycles {

    private final SymbolTable symbols;
    private final List<SemanticError> errors = new ArrayList<>();
    /** Functions whose walk has finished; nothing reachable from them closes a new cycle. */
    private final Set<FunctionSymbol> done = new HashSet<>();
    /** The functions on the current walk, in order. */
    private final Map<FunctionSymbol, Boolean> path = new LinkedHashMap<>();

    private DefCycles(SymbolTable symbols) {
        this.symbols = symbols;
    }

    /**
     * Returns one error per definition cycle among the user-defined functions in {@code symbols}.
     *
     * @param symbols the collected symbol table; must not be null
     * @return the cycle errors, empty for an acyclic script
     */
    static List<SemanticError> check(SymbolTable symbols) {
        var check = new DefCycles(symbols);
        for (Symbol symbol : symbols.allSymbols()) {
            if (symbol instanceof FunctionSymbol function) {
                check.visit(function);
            }
        }
        return List.copyOf(check.errors);
    }

    private void visit(FunctionSymbol function) {
        if (done.contains(function)) {
            return;
        }
        path.put(function, Boolean.TRUE);
        switch (function) {
            case ScalarFunctionSymbol scalar -> scalar.body().ifPresent(
                    body -> operand(body, new HashSet<>()));
            case RelationFunctionSymbol table -> relation(table.body(), new HashSet<>());
        }
        path.remove(function);
        done.add(function);
    }

    private void operand(com.darkcollective.relix.ast.Operand operand, Set<String> views) {
        OperandWalker.walk(operand, unused -> { }, call -> call(call, views));
        Subqueries.forEachSubquery(operand, s -> relation(s.query(), views));
    }

    private void relation(RelNode node, Set<String> views) {
        // A subquery inside an operand or predicate is reached below, through the node
        RelNodeOperands.forEach(node,
                operand -> OperandWalker.walk(operand, unused -> { }, call -> call(call, views)),
                predicate -> OperandWalker.walk(predicate, unused -> { }, call -> call(call, views)));
        switch (node) {
            case RelationFunctionCall call -> reach(call.functionName(), call.arguments().size(),
                    call.location(), views);
            case RelationNode reference -> symbols.lookupRelation(reference.name())
                    .filter(QueryRelationSymbol.class::isInstance)
                    .map(QueryRelationSymbol.class::cast)
                    // A view is walked through, once: a loop among views is ViewCycles's to report
                    .filter(view -> views.add(view.namespace() + ":" + view.canonicalName()))
                    .ifPresent(view -> relation(view.body(), views));
            default -> { }
        }
        node.children().forEach(child -> relation(child, views));
        Subqueries.subqueriesOf(node).forEach(s -> relation(s.query(), views));
    }

    private void call(FunctionCall call, Set<String> views) {
        reach(call.functionName(), call.arguments().size(), call.location(), views);
    }

    private void reach(String name, int arguments, SourceLocation at, Set<String> views) {
        for (FunctionSymbol target : symbols.resolveFunction(name)) {
            if (target.parameters().size() != arguments) {
                continue;
            }
            if (path.containsKey(target)) {
                report(name, target, at);
            } else {
                // Each function is walked with its own record of the views it has passed through
                visit(target);
            }
        }
    }

    private void report(String reference, FunctionSymbol target, SourceLocation at) {
        List<String> loop = new ArrayList<>();
        boolean inLoop = false;
        for (FunctionSymbol entry : path.keySet()) {
            inLoop |= entry.equals(target);
            if (inLoop) {
                loop.add(entry.declaredName());
            }
        }
        String name = target.declaredName();
        String message = "Function '" + name + "' refers to itself"
                + (loop.size() == 1 ? ""
                        : " through " + loop.stream().collect(Collectors.joining(" → ", "", " → " + name)));
        if (!reference.equals(name) && reference.equalsIgnoreCase(name)) {
            message += " (names are case-insensitive, so '" + reference + "' is '" + name + "')";
        }
        message += "; a def cannot be defined in terms of itself — use FIX or ITERATE"
                + " for a recursive definition";
        errors.add(SemanticError.error(at.filePath(), at.line(), at.column(), message));
    }
}
