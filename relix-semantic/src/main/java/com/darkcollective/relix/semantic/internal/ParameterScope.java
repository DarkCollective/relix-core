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

import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.RegistrationResult;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Symbol;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.function.RelationFunctionSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The names a table-valued function's body sees: its relation parameters, each a relation
 * with the heading it declares, in front of everything the script defines.
 *
 * <p>A body is analysed once, at the {@code def}, and a relation parameter is what makes
 * that possible: {@code E} in {@code def f(E: RELATION(src, dst)) … { π src (E) }} is a
 * relation with exactly those columns, whatever is eventually passed. A parameter shadows a
 * script relation of the same name, as a parameter shadows anything in the scope around a
 * function — the body means the parameter, and the inliner substitutes it on that reading.
 *
 * <p>Only unqualified relation lookups are answered here; a dotted name
 * ({@code conn.table}) cannot be a parameter. Everything else, registration included, is the
 * underlying table's.
 */
final class ParameterScope implements SymbolTable {

    private final SymbolTable base;
    private final Map<String, RelationSymbol> parameters;

    private ParameterScope(SymbolTable base, Map<String, RelationSymbol> parameters) {
        this.base = base;
        this.parameters = parameters;
    }

    /**
     * The table {@code fn}'s body is analysed against: {@code base} itself when the function
     * takes no relation, otherwise {@code base} behind its relation parameters.
     */
    static SymbolTable of(SymbolTable base, RelationFunctionSymbol fn) {
        Objects.requireNonNull(base, "base");
        Map<String, RelationSymbol> parameters = new LinkedHashMap<>();
        for (ParameterDefinition p : fn.parameters()) {
            p.heading().ifPresent(heading ->
                    parameters.put(key(p.name()), SourceRelationSymbol.of(p.name(), heading)));
        }
        return parameters.isEmpty() ? base : new ParameterScope(base, parameters);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    @Override
    public RegistrationResult register(Symbol symbol) {
        return base.register(symbol);
    }

    @Override
    public Optional<RelationSymbol> lookupRelation(String name) {
        RelationSymbol parameter = parameters.get(key(name));
        return parameter != null ? Optional.of(parameter) : base.lookupRelation(name);
    }

    @Override
    public Optional<RelationSymbol> lookupRelation(String namespace, String name) {
        return base.lookupRelation(namespace, name);
    }

    @Override
    public List<FunctionSymbol> lookupFunction(String name) {
        return base.lookupFunction(name);
    }

    @Override
    public List<FunctionSymbol> lookupFunction(String namespace, String name) {
        return base.lookupFunction(namespace, name);
    }

    @Override
    public Optional<FunctionSymbol> lookupFunction(String name, List<ScalarType> paramTypes) {
        return base.lookupFunction(name, paramTypes);
    }

    @Override
    public Collection<Symbol> allSymbols() {
        return base.allSymbols();
    }
}
