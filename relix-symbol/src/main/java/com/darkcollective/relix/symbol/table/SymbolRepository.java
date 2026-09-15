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

import com.darkcollective.relix.symbol.Symbol;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;

import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for symbol storage — the extension point for a durable
 * back-end (relational database, document store, etc.).
 *
 * <p>This interface is intentionally kept narrow: it covers the CRUD operations
 * that a durable symbol store must support.  It is not used by
 * {@link InMemorySymbolTable}; a persistence-backed implementation of
 * {@link SymbolTable} would delegate to a {@code SymbolRepository}.
 *
 * <h2>Key structure</h2>
 * <p>Relations are identified by {@code (namespace, canonicalName)} — a two-part
 * key.  Functions add a third part: the parameter signature (ordered list of
 * {@link com.darkcollective.relix.symbol.ScalarType} values), enabling overloading.
 * All name comparisons must be performed on canonical (lower-cased) forms.
 *
 * <h2>Design note</h2>
 * <p>Symbols do not carry a generated surrogate key; the natural business key
 * above is used as the primary key.  A concrete implementation is free to add
 * surrogate keys internally as needed by the target store.
 */
public interface SymbolRepository {

    /**
     * Persists a symbol, replacing any existing entry with the same key.
     *
     * @param symbol the symbol to save; must not be null
     */
    void save(Symbol symbol);

    /**
     * Retrieves a relation symbol by its canonical key.
     *
     * @param namespace     the namespace (canonical, lower-cased)
     * @param canonicalName the canonical name (lower-cased)
     * @return the stored symbol, or empty if not found
     */
    Optional<RelationSymbol> findRelation(String namespace, String canonicalName);

    /**
     * Retrieves all overloads of a function by its namespace and canonical name.
     *
     * @param namespace     the namespace (canonical, lower-cased)
     * @param canonicalName the canonical function name (lower-cased)
     * @return all matching function symbols; never null, may be empty
     */
    List<FunctionSymbol> findFunctions(String namespace, String canonicalName);

    /**
     * Retrieves all symbols currently persisted in the repository.
     *
     * @return all symbols; never null, may be empty
     */
    List<Symbol> findAll();
}
