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
package com.darkcollective.relix.symbol.table;

import com.darkcollective.relix.symbol.RegistrationResult;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Symbol;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * A mutable, namespace-scoped registry of {@link Symbol} instances.
 *
 * <p>The table supports two categories of symbol:
 * <ul>
 *   <li>Relations — looked up by {@code (namespace, name)}.</li>
 *   <li>Functions — looked up by {@code (namespace, name, parameterSignature)},
 *       supporting overload resolution.</li>
 * </ul>
 *
 * <p>All name comparisons are <b>case-insensitive</b>.
 *
 * <h2>Lookup order</h2>
 * <p>The single-argument lookup overloads search the {@code "default"} namespace
 * first, then the {@code "builtin"} namespace.  Use the two-argument overloads
 * to target a specific namespace directly.
 *
 * <h2>Registration</h2>
 * <p>{@link #register(Symbol)} always returns a {@link RegistrationResult}; it
 * never throws on conflict.  Inspect the result to determine whether the symbol
 * was accepted, replaced with a warning, or rejected.
 *
 * @see InMemorySymbolTable
 */
public interface SymbolTable {

    /**
     * Attempts to register {@code symbol} in the table.
     *
     * <p>If a symbol with the same namespace and canonical name (and, for
     * functions, the same parameter signature) already exists, the existing
     * symbol's {@link com.darkcollective.relix.symbol.ShadowPolicy} governs
     * the outcome.
     *
     * @param symbol the symbol to register; must not be null
     * @return a result describing whether the symbol was accepted, warned, or rejected
     */
    RegistrationResult register(Symbol symbol);

    /**
     * Returns the relation with the given {@code name} from the {@code "default"}
     * namespace, or — if not found there — from the {@code "builtin"} namespace.
     *
     * @param name the relation name (case-insensitive)
     * @return the matching relation symbol, or empty if none found
     */
    Optional<RelationSymbol> lookupRelation(String name);

    /**
     * Returns the relation with the given {@code name} in the specified
     * {@code namespace}.
     *
     * @param namespace the target namespace (case-insensitive)
     * @param name      the relation name (case-insensitive)
     * @return the matching relation symbol, or empty if none found
     */
    Optional<RelationSymbol> lookupRelation(String namespace, String name);

    /**
     * Resolves a relation reference that may be either a flat name or a dotted
     * {@code "namespace.relation"} reference.
     *
     * <p>A dotted name like {@code "relix.relations"} or {@code "conn.orders"} is
     * first tried as a <em>namespace-qualified</em> lookup (relation
     * {@code relations} in namespace {@code relix}); if that misses, it falls back
     * to the flat {@link #lookupRelation(String)} (which also covers
     * connection-table symbols registered under their full dotted name). A name
     * with no dot uses the flat lookup directly.
     *
     * <p>This is the single resolution entry point shared by schema inference and
     * physical planning so that a reference resolves identically in both phases.
     *
     * @param name the relation reference (flat or dotted; case-insensitive)
     * @return the matching relation symbol, or empty if none found
     */
    default Optional<RelationSymbol> resolveRelation(String name) {
        int dot = name.indexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            Optional<RelationSymbol> qualified =
                    lookupRelation(name.substring(0, dot), name.substring(dot + 1));
            if (qualified.isPresent()) {
                return qualified;
            }
        }
        return lookupRelation(name);
    }

    /**
     * Returns all overloads of the function with the given {@code name} from
     * the {@code "default"} namespace, then the {@code "builtin"} namespace.
     *
     * @param name the function name (case-insensitive)
     * @return all matching function symbols; never null, may be empty
     */
    List<FunctionSymbol> lookupFunction(String name);

    /**
     * Resolves a (possibly dotted) table-valued-function reference to its
     * overloads, mirroring {@link #resolveRelation(String)} for relations.
     *
     * <p>A dotted name like {@code "relix.impact"} is first tried as a
     * <em>namespace-qualified</em> lookup (function {@code impact} in namespace
     * {@code relix}); if that misses, it falls back to the flat
     * {@link #lookupFunction(String)}.  A name with no dot uses the flat lookup
     * directly.  This is the single resolution entry point shared by schema
     * inference, validation, and physical planning, so a table-valued-function
     * call resolves identically in every phase — and lets the shipped
     * introspection stdlib live in the reserved {@code relix} namespace
     * (e.g. {@code relix.impact(r)}).
     *
     * @param name the function reference (flat or dotted; case-insensitive)
     * @return the matching function overloads; never null, may be empty
     */
    default List<FunctionSymbol> resolveFunction(String name) {
        int dot = name.indexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            List<FunctionSymbol> qualified =
                    lookupFunction(name.substring(0, dot), name.substring(dot + 1));
            if (!qualified.isEmpty()) {
                return qualified;
            }
        }
        return lookupFunction(name);
    }

    /**
     * Returns all overloads of the function with the given {@code name} in the
     * specified {@code namespace}.
     *
     * @param namespace the target namespace (case-insensitive)
     * @param name      the function name (case-insensitive)
     * @return all matching function symbols; never null, may be empty
     */
    List<FunctionSymbol> lookupFunction(String namespace, String name);

    /**
     * Returns the unique overload of the function named {@code name} whose
     * parameter signature exactly matches {@code paramTypes}, searching
     * {@code "default"} then {@code "builtin"}.
     *
     * @param name       the function name (case-insensitive)
     * @param paramTypes the ordered list of argument types to match
     * @return the matching overload, or empty if no exact match exists
     */
    Optional<FunctionSymbol> lookupFunction(String name, List<ScalarType> paramTypes);

    /**
     * Returns a snapshot of all symbols currently registered in the table,
     * in registration order.
     *
     * @return an unmodifiable collection of all symbols; never null
     */
    Collection<Symbol> allSymbols();
}
