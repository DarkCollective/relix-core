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

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.ParameterOperand;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationFunctionCall;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.internal.OperandWalker;
import com.darkcollective.relix.ast.internal.RelNodeOperands;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.QueryStatement;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Symbol;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.function.ScalarFunctionSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The bound parameters ({@code $name}) a script uses, and the type each is used as.
 *
 * <p>A parameter declares no type: it takes the type of what it is compared with.
 * {@code σ order_id = $id} makes {@code $id} the type of {@code order_id}, wherever in
 * the script it is written — a query, a view, the body of a {@code def}. A parameter
 * compared with nothing whose type is known is {@code ANY}, and accepts any value. Two
 * uses that disagree — {@code $id} compared with a number in one place and a string in
 * another — are an error, since no one value could satisfy both.
 *
 * <p>Parameters are matched ignoring case, as columns are. The root queries are read
 * first and then the views and functions, and a parameter is reported under the
 * spelling met first in that order.
 */
public final class QueryParameters {

    private final SymbolTable symbolTable;
    private final SchemaAnnotations annotations;
    private final OperandTypeInferrer inferrer;

    /** Lower-cased name → first spelling, in order of first appearance. */
    private final Map<String, String> spellings = new LinkedHashMap<>();

    /** Lower-cased name → the type its uses settle on; absent while none has. */
    private final Map<String, Type> types = new LinkedHashMap<>();

    /** Lower-cased names already reported as conflicting, so each is reported once. */
    private final Set<String> conflicted = new HashSet<>();

    private final List<SemanticError> errors = new ArrayList<>();

    private QueryParameters(SymbolTable symbolTable, SchemaAnnotations annotations,
                            FunctionCatalog functions) {
        this.symbolTable = symbolTable;
        this.annotations = annotations;
        this.inferrer = new OperandTypeInferrer(symbolTable, functions);
    }

    /**
     * Every parameter the model's queries, views and functions use, with its type.
     *
     * @param model the analysed script; must not be null
     * @return parameter name to its type ({@code ANY} when no use says), the root
     *         queries' first and then the views' and functions'; unmodifiable
     */
    public static Map<String, Type> of(SemanticModel model) {
        return scan(model).typed();
    }

    /**
     * The uses of one parameter that disagree about its type, each reported once.
     *
     * @param model the analysed script; must not be null
     * @return the errors; empty when every parameter's uses agree
     */
    static List<SemanticError> conflicts(SemanticModel model) {
        return List.copyOf(scan(model).errors);
    }

    /**
     * The parameters {@code tree} reaches — its own, and those of every view it names
     * and every {@code def} it calls, followed as far as they go — lower-cased.
     *
     * <p>These are the ones a query needs values for before it can run; a parameter used
     * only by another query of the same script is not among them.
     *
     * @param tree        the expression; must not be null
     * @param symbolTable the table its names resolve in; must not be null
     * @return the parameter names, lower-cased; unmodifiable
     */
    public static Set<String> reachedBy(RelNode tree, SymbolTable symbolTable) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(symbolTable, "symbolTable");
        Set<String> names = new LinkedHashSet<>();
        reach(tree, symbolTable, names, new HashSet<>());
        return Collections.unmodifiableSet(names);
    }

    private static void reach(RelNode node, SymbolTable table, Set<String> names,
                              Set<String> visited) {
        Consumer<ParameterOperand> onParameter =
                p -> names.add(p.name().toLowerCase(Locale.ROOT));
        Consumer<com.darkcollective.relix.ast.FunctionCall> onFunction = call -> {
            if (visited.add("fn:" + call.functionName().toLowerCase(Locale.ROOT))) {
                for (var fn : table.resolveFunction(call.functionName())) {
                    if (fn instanceof ScalarFunctionSymbol scalar) {
                        scalar.body().ifPresent(body ->
                                OperandWalker.walk(body, a -> { }, f -> { }, onParameter));
                    }
                }
            }
        };
        RelNodeOperands.forEach(node,
                operand -> OperandWalker.walk(operand, a -> { }, onFunction, onParameter),
                predicate -> OperandWalker.walk(predicate, a -> { }, onFunction, onParameter));
        switch (node) {
            case RelationNode r when visited.add("rel:" + r.name().toLowerCase(Locale.ROOT)) ->
                    table.resolveRelation(r.name()).ifPresent(symbol -> {
                        if (symbol instanceof QueryRelationSymbol view) {
                            reach(view.body(), table, names, visited);
                        }
                    });
            case RelationFunctionCall call
                    when visited.add("tvf:" + call.functionName().toLowerCase(Locale.ROOT)) -> {
                for (var fn : table.resolveFunction(call.functionName())) {
                    if (fn instanceof RelationFunctionSymbol tvf) {
                        reach(tvf.body(), table, names, visited);
                    }
                }
            }
            default -> { }
        }
        for (RelNode child : node.children()) {
            reach(child, table, names, visited);
        }
    }

    private static QueryParameters scan(SemanticModel model) {
        Objects.requireNonNull(model, "model");
        QueryParameters scan = new QueryParameters(model.symbolTable(), model.nodeSchemas(),
                model.functions());
        for (QueryStatement query : model.rootQueries()) {
            if (query.target() instanceof ExpressionQueryTarget expression) {
                scan.walk(expression.expression());
            }
        }
        for (Symbol symbol : model.symbolTable().allSymbols()) {
            switch (symbol) {
                case QueryRelationSymbol view -> scan.walk(view.body());
                case RelationFunctionSymbol tvf -> scan.walk(tvf.body());
                case ScalarFunctionSymbol scalar -> scalar.body().ifPresent(body ->
                        OperandWalker.walk(body, a -> { }, f -> { }, scan::seen));
                default -> { }
            }
        }
        return scan;
    }

    private Map<String, Type> typed() {
        Map<String, Type> out = new LinkedHashMap<>();
        spellings.forEach((key, spelling) ->
                out.put(spelling, types.getOrDefault(key, ScalarType.ANY)));
        return Collections.unmodifiableMap(out);
    }

    private void walk(RelNode node) {
        RelNodeOperands.forEach(node,
                operand -> OperandWalker.walk(operand, a -> { }, f -> { }, this::seen),
                predicate -> typeWithin(predicate, node));
        for (RelNode child : node.children()) {
            walk(child);
        }
    }

    /** Records a parameter's spelling without saying anything about its type. */
    private void seen(ParameterOperand parameter) {
        spellings.putIfAbsent(parameter.name().toLowerCase(Locale.ROOT), parameter.name());
    }

    /** Types each parameter compared directly with an operand whose type is known. */
    private void typeWithin(Predicate predicate, RelNode node) {
        OperandWalker.walk(predicate, a -> { }, f -> { }, this::seen);
        switch (predicate) {
            case ComparisonPredicate c -> {
                compared(c.left(), c.right(), node);
                compared(c.right(), c.left(), node);
            }
            case AndPredicate a -> {
                typeWithin(a.left(), node);
                typeWithin(a.right(), node);
            }
            case OrPredicate o -> {
                typeWithin(o.left(), node);
                typeWithin(o.right(), node);
            }
            case NotPredicate n -> typeWithin(n.predicate(), node);
            case PatternPredicate p -> compared(p.pattern(), p.operand(), node);
            case NullPredicate ignored -> { }
            case ElementOfPredicate ignored -> { }
        }
    }

    private void compared(Operand side, Operand other, RelNode node) {
        if (!(side instanceof ParameterOperand parameter)) {
            return;
        }
        Type type = typeAt(other, node);
        if (type == ScalarType.ANY) {
            return;
        }
        String key = parameter.name().toLowerCase(Locale.ROOT);
        Type settled = types.putIfAbsent(key, type);
        if (settled != null && !settled.equals(type) && conflicted.add(key)) {
            var loc = parameter.location();
            errors.add(SemanticError.error(loc.filePath(), loc.line(), loc.column(),
                    "Parameter $" + parameter.name() + " is compared with a " + settled
                            + " in one place and a " + type + " in another;"
                            + " one value cannot be both"));
        }
    }

    /**
     * The type of {@code operand} evaluated at {@code node}: over the node's input, or
     * for a join over whichever input names the operand's columns.
     */
    private Type typeAt(Operand operand, RelNode node) {
        return node.children().stream()
                .map(annotations::get)
                .flatMap(Optional::stream)
                .map(schema -> inferrer.infer(operand, schema))
                .filter(type -> type != ScalarType.ANY)
                .findFirst()
                .orElse(ScalarType.ANY);
    }
}
