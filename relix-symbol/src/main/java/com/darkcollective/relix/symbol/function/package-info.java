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
/**
 * Function symbol hierarchy.
 *
 * <p>All function symbols implement
 * {@link com.darkcollective.relix.symbol.function.FunctionSymbol}, which extends
 * {@link com.darkcollective.relix.symbol.Symbol}.
 *
 * <p>There are two implementations.
 * {@link com.darkcollective.relix.symbol.function.ScalarFunctionSymbol} takes zero
 * or more scalar arguments and returns a single scalar value;
 * {@link com.darkcollective.relix.symbol.function.RelationFunctionSymbol} is a
 * table-valued function, whose body is a relational-algebra expression and whose
 * result is a relation.
 *
 * <h2>Overloading</h2>
 * <p>Function overloading is supported.  The uniqueness key for a function in the
 * symbol table is the triple {@code (namespace, canonicalName, parameterSignature)},
 * where {@code parameterSignature} is the ordered list of parameter
 * {@link com.darkcollective.relix.symbol.ScalarType ScalarType}s.  Two functions with
 * the same name but different arities or parameter types are considered distinct
 * overloads and may coexist in the same namespace.
 *
 * <h2>Builder pattern</h2>
 * <p>{@link com.darkcollective.relix.symbol.function.ScalarFunctionSymbol} exposes a
 * {@link com.darkcollective.relix.symbol.function.ScalarFunctionSymbol#builder(String)
 * builder} to reduce construction verbosity, particularly for functions with many
 * optional attributes.  Sensible defaults are applied for every optional field so
 * that a minimal built-in can be expressed in a few lines.
 */
package com.darkcollective.relix.symbol.function;
