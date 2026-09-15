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
 * The function SPI — how a library of scalar functions and aggregates is described to
 * the engine, and how the engine finds one.
 *
 * <p>The split this package draws is between a function's <em>form</em> and its
 * <em>processing</em>. The form — name, parameters, arity, return type, purity, the
 * spelling a backend would understand — is what the engine reasons about while it is
 * checking a script, rewriting it and planning it. The processing is the lambda that
 * turns arguments into a value. {@code Sin} and {@code Cos} have the same form; nothing
 * in analysis or planning is served by knowing which of them a call names.
 *
 * <p>So the engine holds the form and a library supplies the processing:
 *
 * <ul>
 *   <li>{@link com.darkcollective.relix.function.FunctionSignature} and
 *       {@link com.darkcollective.relix.function.AggregateSignature} describe a
 *       function; {@link com.darkcollective.relix.function.Arity} is how many arguments
 *       it takes, checked by the engine so an implementation never counts its own.</li>
 *   <li>{@link com.darkcollective.relix.function.ScalarFunction} pairs a signature with
 *       an implementation — {@link com.darkcollective.relix.function.StrictScalarFunction}
 *       for the ordinary kind, {@link com.darkcollective.relix.function.LazyScalarFunction}
 *       for a special form that chooses which arguments to evaluate.
 *       {@link com.darkcollective.relix.function.AggregateFunction} does the same for a
 *       reduction, through an {@link com.darkcollective.relix.function.Accumulator} per
 *       group.</li>
 *   <li>{@link com.darkcollective.relix.function.PushdownSpelling} is how a call is
 *       written for a backend that could evaluate it itself, so a library ships its
 *       implementation and its SQL together and declining is always safe.</li>
 *   <li>{@link com.darkcollective.relix.function.FunctionLibrary} is the unit that is
 *       installed and discovered;
 *       {@link com.darkcollective.relix.function.FunctionCatalog} indexes what was
 *       found.</li>
 * </ul>
 *
 * <p>Nothing here is privileged. The library the engine ships with is discovered by the
 * same mechanism, described by the same records and reached through the same catalogue
 * as one written elsewhere, so anything a built-in function does is available to any
 * function.
 *
 * <p>What the SPI deliberately does not open is the set of value types: a library adds
 * functions over {@link com.darkcollective.relix.value.Value}, not new kinds of value.
 * A domain type is carried as a struct with a discriminating field, with the library's
 * own functions over it.
 */
package com.darkcollective.relix.function;
