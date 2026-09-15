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
package com.darkcollective.relix.symbol.function;

import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Symbol;

import java.util.List;
import java.util.Set;

/**
 * Sealed sub-interface of {@link Symbol} for all function-typed symbols.
 *
 * <p>A function symbol describes a callable entity: its parameter list, return
 * type, optimizer hints, and (for user-defined functions) an optional body
 * expression.
 *
 * <p>Function overloading is supported.  Two {@code FunctionSymbol} instances
 * with the same {@link #namespace()} and {@link #canonicalName()} are considered
 * distinct overloads if and only if their {@link #parameterSignature()} lists
 * differ.
 *
 * <p>Two implementations exist: {@link ScalarFunctionSymbol} (scalar-valued) and
 * {@link RelationFunctionSymbol} (relation-valued / table-valued).
 */
public sealed interface FunctionSymbol extends Symbol
        permits ScalarFunctionSymbol, RelationFunctionSymbol {

    /**
     * The ordered list of typed, named parameters.
     *
     * @return parameter definitions; never null, may be empty for zero-argument functions
     */
    List<ParameterDefinition> parameters();

    /**
     * The scalar type of the value this function returns.
     *
     * <p>Meaningful only for a {@link ScalarFunctionSymbol}; a
     * {@link RelationFunctionSymbol} returns a whole relation (see
     * {@link RelationFunctionSymbol#returnSchema()}) and reports
     * {@link ScalarType#ANY} here as a neutral placeholder.
     *
     * @return return type; never null
     */
    ScalarType returnType();

    /**
     * The set of optimizer hint flags declared for this function.
     *
     * @return an unmodifiable set of properties; never null, may be empty
     */
    Set<FunctionProperty> properties();

    /**
     * The ordered list of parameter types, derived from {@link #parameters()}.
     * This list is the discriminator used for overload resolution.
     *
     * @return an ordered list of {@link ScalarType} values, one per parameter
     */
    default List<ScalarType> parameterSignature() {
        return parameters().stream()
                .map(ParameterDefinition::type)
                .toList();
    }
}
