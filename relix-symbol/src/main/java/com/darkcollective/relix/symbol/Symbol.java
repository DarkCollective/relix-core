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
package com.darkcollective.relix.symbol;

import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.relation.RelationSymbol;

import java.util.Locale;

/**
 * Root sealed interface for every named entity registered in a
 * {@link com.darkcollective.relix.symbol.table.SymbolTable}.
 *
 * <p>The two immediate branches of the hierarchy are:
 * <ul>
 *   <li>{@link RelationSymbol} — database tables, inline (markdown) tables,
 *       and named query expressions.</li>
 *   <li>{@link FunctionSymbol} — scalar functions, both built-in and
 *       user-defined.</li>
 * </ul>
 *
 * <h2>Naming</h2>
 * <p>Each symbol stores its name in two forms:
 * <ul>
 *   <li>{@link #declaredName()} — case-preserved, exactly as the user typed it.</li>
 *   <li>{@link #canonicalName()} — lower-cased; used as the lookup key so that
 *       all name resolution is case-insensitive.</li>
 * </ul>
 *
 * <h2>Namespaces</h2>
 * <p>Every symbol belongs to a {@link #namespace()}.  Symbols declared in a
 * {@code .relix} file without an explicit namespace declaration are placed in
 * the {@code "default"} namespace.  Built-in symbols use the {@code "builtin"}
 * namespace.
 *
 * <h2>Shadow policy</h2>
 * <p>When a new symbol is registered under a name that already exists in the
 * same namespace, the {@link #shadowPolicy()} of the <em>existing</em> symbol
 * governs whether the replacement is allowed.
 *
 * @see RelationSymbol
 * @see FunctionSymbol
 */
public sealed interface Symbol permits RelationSymbol, FunctionSymbol {

    /**
     * The namespace this symbol belongs to.
     *
     * @return a non-blank namespace string; {@code "default"} for user symbols,
     *         {@code "builtin"} for runtime-provided symbols
     */
    String namespace();

    /**
     * The name exactly as declared by the user or the runtime, with original casing.
     *
     * @return the declared name; never blank
     */
    String declaredName();

    /**
     * The lower-cased form of {@link #declaredName()}, used as the lookup key
     * in the symbol table to ensure case-insensitive resolution.
     *
     * @return {@code declaredName().toLowerCase(Locale.ROOT)}
     */
    default String canonicalName() {
        return declaredName().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether this symbol was supplied by the runtime or declared by a user.
     *
     * @return the provenance; never null
     */
    Provenance provenance();

    /**
     * The policy that governs whether this symbol may be replaced by a later
     * registration under the same name.
     *
     * @return the shadow policy; never null
     */
    ShadowPolicy shadowPolicy();
}
