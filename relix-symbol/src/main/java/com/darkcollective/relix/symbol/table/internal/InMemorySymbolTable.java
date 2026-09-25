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
package com.darkcollective.relix.symbol.table.internal;

import com.darkcollective.relix.symbol.table.SymbolTable;
import com.darkcollective.relix.symbol.RegistrationResult;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.Symbol;
import com.darkcollective.relix.symbol.SymbolError;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory implementation of {@link SymbolTable} suitable for single-session,
 * in-process use.
 *
 * <p><b>Not thread-safe.</b>  External synchronisation is required if the table
 * is shared across threads.
 *
 * <h2>Internal structure</h2>
 * <p>Relations are stored in a two-level map:
 * {@code namespace (lower-cased) → canonicalName → RelationSymbol}.
 *
 * <p>Functions are stored in a three-level map:
 * {@code namespace → canonicalName → parameterSignature → FunctionSymbol}.
 * The parameter signature is the ordered list of {@link ScalarType} values,
 * which is used as a {@link java.util.List} key (equality is value-based).
 *
 * <p>Both maps use {@link LinkedHashMap} so that iteration order reflects
 * registration order.
 *
 * <h2>Shadow policy</h2>
 * <p>When a symbol conflicts with an existing one, the <em>existing</em>
 * symbol's {@link ShadowPolicy} is consulted:
 * <ul>
 *   <li>{@link ShadowPolicy#FORBIDDEN} — the new symbol is rejected; the table
 *       is unchanged.</li>
 *   <li>{@link ShadowPolicy#PERMITTED} — the new symbol replaces the old one
 *       silently.</li>
 *   <li>{@link ShadowPolicy#WARN_AND_PERMIT} — the new symbol replaces the old
 *       one, and a {@link SymbolError.Kind#SHADOW_WARNING} is added to the
 *       result.</li>
 * </ul>
 */
public final class InMemorySymbolTable implements SymbolTable {

    // namespace → canonicalName → RelationSymbol
    private final Map<String, Map<String, RelationSymbol>> relations = new LinkedHashMap<>();

    // namespace → canonicalName → paramSignature → FunctionSymbol
    private final Map<String, Map<String, Map<List<ScalarType>, FunctionSymbol>>> functions =
            new LinkedHashMap<>();

    @Override
    public RegistrationResult register(Symbol symbol) {
        List<SymbolError> errors = new ArrayList<>();
        boolean registered;
        if (symbol instanceof RelationSymbol rel) {
            registered = registerRelation(rel, errors);
        } else if (symbol instanceof FunctionSymbol fn) {
            registered = registerFunction(fn, errors);
        } else {
            // Cannot happen while Symbol is sealed with only two branches
            throw new IllegalArgumentException("Unknown symbol type: " + symbol.getClass());
        }
        return new RegistrationResult(symbol, registered, errors);
    }

    // -------------------------------------------------------------------------
    // Relation lookup
    // -------------------------------------------------------------------------

    @Override
    public Optional<RelationSymbol> lookupRelation(String name) {
        // Search "default" first, then "builtin", then any other registered namespace
        // so that unqualified names resolve regardless of the declaring namespace.
        Optional<RelationSymbol> result = lookupRelation("default", name);
        if (result.isPresent()) return result;
        result = lookupRelation("builtin", name);
        if (result.isPresent()) return result;
        String nameKey = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Map<String, RelationSymbol>> entry : relations.entrySet()) {
            String ns = entry.getKey();
            if (!ns.equals("default") && !ns.equals("builtin")) {
                RelationSymbol sym = entry.getValue().get(nameKey);
                if (sym != null) return Optional.of(sym);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<RelationSymbol> lookupRelation(String namespace, String name) {
        String nsKey = namespace.toLowerCase(Locale.ROOT);
        String nameKey = name.toLowerCase(Locale.ROOT);
        Map<String, RelationSymbol> nsMap = relations.get(nsKey);
        if (nsMap == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(nsMap.get(nameKey));
    }

    // -------------------------------------------------------------------------
    // Function lookup
    // -------------------------------------------------------------------------

    @Override
    public List<FunctionSymbol> lookupFunction(String name) {
        // Search "default" first, then "builtin", then any other registered namespace
        // so that unqualified names resolve regardless of the declaring namespace.
        List<FunctionSymbol> result = new ArrayList<>(lookupFunction("default", name));
        if (result.isEmpty()) {
            result.addAll(lookupFunction("builtin", name));
        }
        if (result.isEmpty()) {
            String nameKey = name.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Map<String, Map<List<ScalarType>, FunctionSymbol>>> entry
                    : functions.entrySet()) {
                String ns = entry.getKey();
                if (!ns.equals("default") && !ns.equals("builtin")) {
                    Map<List<ScalarType>, FunctionSymbol> overloads = entry.getValue().get(nameKey);
                    if (overloads != null && !overloads.isEmpty()) {
                        result.addAll(overloads.values());
                        break; // take first matching namespace (same as relation lookup)
                    }
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<FunctionSymbol> lookupFunction(String namespace, String name) {
        String nsKey = namespace.toLowerCase(Locale.ROOT);
        String nameKey = name.toLowerCase(Locale.ROOT);
        Map<String, Map<List<ScalarType>, FunctionSymbol>> nsMap = functions.get(nsKey);
        if (nsMap == null) {
            return List.of();
        }
        Map<List<ScalarType>, FunctionSymbol> overloads = nsMap.get(nameKey);
        if (overloads == null) {
            return List.of();
        }
        return List.copyOf(overloads.values());
    }

    @Override
    public Optional<FunctionSymbol> lookupFunction(String name, List<ScalarType> paramTypes) {
        Optional<FunctionSymbol> result = lookupFunctionBySignature("default", name, paramTypes);
        if (result.isPresent()) return result;
        result = lookupFunctionBySignature("builtin", name, paramTypes);
        if (result.isPresent()) return result;
        // Fallthrough to other namespaces (mirrors lookupRelation behaviour).
        String nameKey = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Map<String, Map<List<ScalarType>, FunctionSymbol>>> entry
                : functions.entrySet()) {
            String ns = entry.getKey();
            if (!ns.equals("default") && !ns.equals("builtin")) {
                Map<List<ScalarType>, FunctionSymbol> overloads = entry.getValue().get(nameKey);
                if (overloads != null) {
                    FunctionSymbol sym = overloads.get(paramTypes);
                    if (sym != null) return Optional.of(sym);
                }
            }
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // All symbols
    // -------------------------------------------------------------------------

    @Override
    public Collection<Symbol> allSymbols() {
        List<Symbol> all = new ArrayList<>();
        relations.values().forEach(ns -> all.addAll(ns.values()));
        functions.values().forEach(ns ->
                ns.values().forEach(overloads -> all.addAll(overloads.values())));
        return Collections.unmodifiableList(all);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean registerRelation(RelationSymbol rel, List<SymbolError> errors) {
        String nsKey = rel.namespace().toLowerCase(Locale.ROOT);
        String nameKey = rel.canonicalName();
        Map<String, RelationSymbol> nsMap =
                relations.computeIfAbsent(nsKey, k -> new LinkedHashMap<>());

        RelationSymbol existing = nsMap.get(nameKey);
        if (existing != null) {
            switch (existing.shadowPolicy()) {
                case FORBIDDEN -> {
                    errors.add(new SymbolError(
                            SymbolError.Kind.SHADOW_FORBIDDEN,
                            rel.namespace(),
                            rel.declaredName(),
                            "Relation '" + rel.declaredName() + "' already exists in namespace '"
                                    + rel.namespace() + "' and cannot be replaced (shadow policy: FORBIDDEN)."));
                    return false;
                }
                case WARN_AND_PERMIT -> errors.add(new SymbolError(
                        SymbolError.Kind.SHADOW_WARNING,
                        rel.namespace(),
                        rel.declaredName(),
                        "Relation '" + rel.declaredName() + "' in namespace '" + rel.namespace()
                                + "' shadows an existing definition."));
                case PERMITTED -> { /* no diagnostic */ }
            }
        }
        nsMap.put(nameKey, rel);
        return true;
    }

    private boolean registerFunction(FunctionSymbol fn, List<SymbolError> errors) {
        String nsKey = fn.namespace().toLowerCase(Locale.ROOT);
        String nameKey = fn.canonicalName();
        List<ScalarType> sig = fn.parameterSignature();

        Map<List<ScalarType>, FunctionSymbol> overloads = functions
                .computeIfAbsent(nsKey, k -> new LinkedHashMap<>())
                .computeIfAbsent(nameKey, k -> new LinkedHashMap<>());

        FunctionSymbol existing = overloads.get(sig);
        if (existing != null) {
            switch (existing.shadowPolicy()) {
                case FORBIDDEN -> {
                    errors.add(new SymbolError(
                            SymbolError.Kind.SHADOW_FORBIDDEN,
                            fn.namespace(),
                            fn.declaredName(),
                            "Function '" + fn.declaredName() + "' with signature " + sig
                                    + " already exists in namespace '" + fn.namespace()
                                    + "' and cannot be replaced (shadow policy: FORBIDDEN)."));
                    return false;
                }
                case WARN_AND_PERMIT -> errors.add(new SymbolError(
                        SymbolError.Kind.SHADOW_WARNING,
                        fn.namespace(),
                        fn.declaredName(),
                        "Function '" + fn.declaredName() + "' with signature " + sig
                                + " in namespace '" + fn.namespace()
                                + "' shadows an existing overload."));
                case PERMITTED -> { /* no diagnostic */ }
            }
        }
        overloads.put(sig, fn);
        return true;
    }

    private Optional<FunctionSymbol> lookupFunctionBySignature(
            String namespace, String name, List<ScalarType> paramTypes) {
        String nsKey = namespace.toLowerCase(Locale.ROOT);
        String nameKey = name.toLowerCase(Locale.ROOT);
        Map<String, Map<List<ScalarType>, FunctionSymbol>> nsMap = functions.get(nsKey);
        if (nsMap == null) {
            return Optional.empty();
        }
        Map<List<ScalarType>, FunctionSymbol> overloads = nsMap.get(nameKey);
        if (overloads == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(overloads.get(paramTypes));
    }
}
