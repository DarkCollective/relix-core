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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.symbol.table.SymbolTable;

/**
 * Hook for registering built-in symbols into the symbol table before any
 * user-defined symbols are processed.
 *
 * <p>Implement this interface to provide functions and/or relations that are
 * available in every {@code .relix} script without an explicit {@code import}.
 * Common examples include mathematical functions ({@code abs}, {@code ceil},
 * {@code floor}), string utilities ({@code length}, {@code upper}), and
 * aggregate helpers ({@code coalesce}, {@code now}).
 *
 * <p>The interface is {@link FunctionalInterface} so it can be expressed as a
 * lambda or method reference:
 * <pre>
 *   BuiltinProvider myBuiltins = table -> {
 *       table.register(ScalarFunctionSymbol.builder("length")
 *           .namespace("builtin")
 *           .provenance(Provenance.BUILTIN)
 *           .shadowPolicy(ShadowPolicy.FORBIDDEN)
 *           .parameter("s", ScalarType.STRING)
 *           .returnType(ScalarType.NUMBER)
 *           .build());
 *   };
 *
 *   SemanticAnalyzer analyzer = new SemanticAnalyzer(loader, myBuiltins);
 * </pre>
 *
 * <p>Use {@link #none()} to explicitly pass a no-op provider.
 */
@FunctionalInterface
public interface BuiltinProvider {

    /**
     * Registers built-in symbols into {@code symbolTable}.
     *
     * <p>Called once, before user-defined symbols are collected.  Any symbol
     * registered here is visible to every script processed by the analyser.
     *
     * @param symbolTable the symbol table to populate; never null
     */
    void register(SymbolTable symbolTable);

    /**
     * Returns a {@code BuiltinProvider} that registers nothing.
     *
     * @return a no-op provider
     */
    static BuiltinProvider none() {
        return symbolTable -> { };
    }
}
