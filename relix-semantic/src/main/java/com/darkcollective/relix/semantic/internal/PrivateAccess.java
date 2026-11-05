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
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.internal.OperandWalker;
import com.darkcollective.relix.ast.internal.RelNodeOperands;
import com.darkcollective.relix.ast.internal.Subqueries;
import com.darkcollective.relix.lang.ast.AssignmentStatement;
import com.darkcollective.relix.lang.ast.DefRelationStatement;
import com.darkcollective.relix.lang.ast.DefStatement;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.NamedQueryTarget;
import com.darkcollective.relix.lang.ast.QueryAssignmentBody;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.lang.ast.Script;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.lang.ast.Statement;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rejects a reference to a {@code private} declaration from a file other than the one that
 * declared it.
 *
 * <p>All the files of a script register into one symbol table, so by the time a name is
 * looked up nothing says which file is asking. This pass restores that: it walks each file's
 * own statements and checks every relation, function and connection name they use against
 * the declaring file of the symbol it resolves to. A public view that reads a private source
 * of its own file is untouched, because the reference sits in the declaring file; the same
 * view used from an importer is untouched too, because the importer never names the source.
 */
final class PrivateAccess {

    private final SymbolTable symbols;
    private final Map<String, String> owners;
    private final Set<String> privateKeys;
    private final Map<String, String> privateConnections;
    private final List<SemanticError> errors;

    PrivateAccess(SymbolTable symbols, Map<String, String> owners, Set<String> privateKeys,
                  Map<String, String> privateConnections, List<SemanticError> errors) {
        this.symbols = symbols;
        this.owners = owners;
        this.privateKeys = privateKeys;
        this.privateConnections = privateConnections;
        this.errors = errors;
    }

    void checkFile(String file, Script script, boolean isRoot) {
        for (Statement statement : script.statements()) {
            switch (statement) {
                case AssignmentStatement as -> {
                    if (as.body() instanceof QueryAssignmentBody query) {
                        relation(query.expression(), file, Set.of());
                    }
                }
                case DefStatement def -> operand(def.body(), file, Set.of());
                case DefRelationStatement def -> relation(def.body(), file, relationParameters(def));
                case QueryStatement q -> {
                    if (isRoot) {
                        switch (q.target()) {
                            case ExpressionQueryTarget e -> relation(e.expression(), file, Set.of());
                            case NamedQueryTarget n -> reachRelation(n.name(), q.location(), file, Set.of());
                        }
                    }
                }
                case SourceDeclaration src -> {
                    if (src.config() instanceof ConnectionTableSourceConfig ct) {
                        reachConnection(ct.connection(), src.location(), file);
                    }
                }
                default -> { }
            }
        }
    }

    private static Set<String> relationParameters(DefRelationStatement def) {
        Set<String> names = new HashSet<>();
        for (ParameterDefinition p : def.parameters()) {
            names.add(p.name().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private void operand(Operand operand, String file, Set<String> params) {
        OperandWalker.walk(operand, unused -> { }, call -> call(call, file));
        Subqueries.forEachSubquery(operand, s -> relation(s.query(), file, params));
    }

    private void relation(RelNode node, String file, Set<String> params) {
        RelNodeOperands.forEach(node,
                operand -> OperandWalker.walk(operand, unused -> { }, call -> call(call, file)),
                predicate -> OperandWalker.walk(predicate, unused -> { }, call -> call(call, file)));
        switch (node) {
            case RelationFunctionCall call ->
                    reachFunction(call.functionName(), call.arguments().size(), call.location(), file);
            case RelationNode reference ->
                    reachRelation(reference.name(), reference.location(), file, params);
            default -> { }
        }
        node.children().forEach(child -> relation(child, file, params));
        Subqueries.subqueriesOf(node).forEach(s -> relation(s.query(), file, params));
    }

    private void call(FunctionCall call, String file) {
        reachFunction(call.functionName(), call.arguments().size(), call.location(), file);
    }

    private void reachRelation(String name, SourceLocation at, String file, Set<String> params) {
        if (params.contains(name.toLowerCase(Locale.ROOT))) {
            return;
        }
        int dot = name.indexOf('.');
        if (dot > 0) {
            reachConnection(name.substring(0, dot), at, file);
        }
        symbols.lookupRelation(name).ifPresent(sym -> reach(sym.declaredName(), SymbolCollector.keyOf(sym),
                "relation", at, file));
    }

    private void reachFunction(String name, int arguments, SourceLocation at, String file) {
        for (FunctionSymbol target : symbols.resolveFunction(name)) {
            if (target.parameters().size() == arguments) {
                reach(target.declaredName(), SymbolCollector.keyOf(target), "function", at, file);
            }
        }
    }

    private void reachConnection(String name, SourceLocation at, String file) {
        String owner = privateConnections.get(name.toLowerCase(Locale.ROOT));
        if (owner != null && !owner.equals(file)) {
            report(name, "connection", owner, at, file);
        }
    }

    private void reach(String name, String key, String kind, SourceLocation at, String file) {
        if (privateKeys.contains(key)) {
            String owner = owners.get(key);
            if (!owner.equals(file)) {
                report(name, kind, owner, at, file);
            }
        }
    }

    private void report(String name, String kind, String owner, SourceLocation at, String file) {
        errors.add(SemanticError.error(at.line() > 0 ? at.filePath() : file, at.line(), at.column(),
                "The " + kind + " '" + name + "' is private to '" + owner
                        + "' and cannot be used from '" + file + "'"));
    }
}
