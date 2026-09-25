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

import com.darkcollective.relix.function.Arity;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.ScalarFunction;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.function.ScalarFunctionSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a call site's function name resolved to — the symbols visible to the rest of
 * the pipeline, and the catalogue definition behind them when there is one.
 *
 * <p>This is the one place the two directories of functions meet. A name is looked up
 * in the symbol table first, so a {@code def} always wins over a library function of
 * the same name; a name the table does not hold is looked up in the
 * {@link FunctionCatalog} and, when found, <em>registered</em> into the table. That
 * registration is the laziness the IR depends on: a symbol table lists the functions
 * its script actually calls, not the whole installed library, so an IR dump stays the
 * size of the script.
 *
 * <p>Arity is answered from the catalogue whenever the catalogue is what defined the
 * name, because a signature's {@link Arity} is a range and a symbol's parameter list is
 * not. {@code Mid} declares three parameters and accepts two or three arguments; asking
 * the symbol would reject {@code Mid(s, 2)} the moment the first reference registered
 * it. A user-defined function has no range, so its parameter count is the answer.
 *
 * @param symbols    every symbol the name resolves to, in the order the symbol table
 *                   returned them; empty when the name is unknown
 * @param definition the catalogue definition, when the catalogue is what defined this
 *                   name; empty for a user-defined function or an unknown name
 */
public record ResolvedFunction(List<FunctionSymbol> symbols, Optional<ScalarFunction> definition) {

    public ResolvedFunction {
        symbols = List.copyOf(Objects.requireNonNull(symbols, "symbols"));
        Objects.requireNonNull(definition, "definition");
    }

    /**
     * Resolves {@code name} against the symbol table and, failing that, the catalogue —
     * registering a catalogue function into {@code table} on its first reference.
     *
     * @param functions the installed function catalogue
     * @param name      the function name as written at the call site
     * @param table     the live symbol table, registered into on first reference
     * @return the resolution; {@link #isUnknown()} when nothing defines the name
     */
    static ResolvedFunction of(FunctionCatalog functions, String name, SymbolTable table) {
        List<FunctionSymbol> declared = table.lookupFunction(name);
        if (!declared.isEmpty()) {
            // Only a symbol this class put there speaks for a catalogue definition: a
            // user `def` of the same name is the definition of that name, not an
            // overload of the library's.
            boolean fromLibrary = declared.stream()
                    .allMatch(fn -> fn.provenance() == Provenance.BUILTIN);
            return new ResolvedFunction(declared,
                    fromLibrary ? functions.scalar(name) : Optional.empty());
        }
        Optional<ScalarFunction> definition = functions.scalar(name);
        if (definition.isEmpty()) {
            return new ResolvedFunction(List.of(), Optional.empty());
        }
        ScalarFunctionSymbol symbol = symbolFor(definition.get());
        table.register(symbol);
        return new ResolvedFunction(List.of(symbol), definition);
    }

    /** @return {@code true} when neither the symbol table nor the catalogue knows the name */
    boolean isUnknown() {
        return symbols.isEmpty();
    }

    /**
     * @param argumentCount the number of arguments passed at the call site
     * @return {@code true} when a call passing that many arguments is legal
     */
    boolean accepts(int argumentCount) {
        return definition
                .map(fn -> fn.signature().arity().accepts(argumentCount))
                .orElseGet(() -> symbols.stream()
                        .anyMatch(fn -> fn.parameters().size() == argumentCount));
    }

    /**
     * How an error message spells what this function does accept — {@code "2"},
     * {@code "1 to 3"}, {@code "at least 1"}.
     *
     * @return the accepted argument count, described
     */
    String expectedArity() {
        return definition
                .map(fn -> fn.signature().arity().describe())
                .orElseGet(() -> symbols.isEmpty()
                        ? "0"
                        : String.valueOf(symbols.get(0).parameters().size()));
    }

    /**
     * The type a call returns, given its argument types.
     *
     * <p>A catalogue definition answers for itself — a conditional returns whichever
     * branch it selects, so the answer depends on the arguments — while a user-defined
     * function returns what it declared.
     *
     * @param argumentTypes the argument types at the call site, in order
     * @return the result type; {@link ScalarType#ANY} when the name is unknown
     */
    ScalarType returnType(List<ScalarType> argumentTypes) {
        return definition
                .map(fn -> fn.returnTypeFor(argumentTypes))
                .orElseGet(() -> symbols.isEmpty()
                        ? ScalarType.ANY
                        : symbols.get(0).returnType());
    }

    /**
     * The symbol-layer form of a catalogue function: what the symbol table holds and
     * the IR prints.
     *
     * <p>The parameter list is the one the signature names, which for a function whose
     * arity is a range is the widest form it has names for — the range itself is
     * carried by the definition, and {@link #accepts(int)} is what reads it.
     */
    private static ScalarFunctionSymbol symbolFor(ScalarFunction function) {
        var signature = function.signature();
        var builder = ScalarFunctionSymbol.builder(signature.name())
                .namespace("builtin")
                .provenance(Provenance.BUILTIN)
                .shadowPolicy(ShadowPolicy.FORBIDDEN)
                .returnType(signature.returnType());
        signature.properties().forEach(builder::property);
        signature.parameters().forEach(p -> builder.parameter(p.name(), p.type()));
        return builder.build();
    }
}
