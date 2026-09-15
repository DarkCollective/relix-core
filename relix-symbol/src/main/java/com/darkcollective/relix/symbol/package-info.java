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
 * Root package for the relix symbol-table module.
 *
 * <p>Contains the core abstractions shared across the full symbol hierarchy:
 *
 * <ul>
 *   <li>{@link com.darkcollective.relix.symbol.Symbol} — sealed root interface
 *       for every named entity known to the system.  All symbols carry a
 *       {@link com.darkcollective.relix.symbol.Provenance} (built-in vs user-defined),
 *       a {@link com.darkcollective.relix.symbol.ShadowPolicy} governing whether they
 *       may be replaced, and a case-insensitive name within a namespace.</li>
 *   <li>{@link com.darkcollective.relix.symbol.ScalarType} — the type lattice for
 *       column values and function parameters/returns ({@code ANY}, {@code STRING},
 *       {@code NUMBER}, {@code BOOLEAN}).</li>
 *   <li>{@link com.darkcollective.relix.symbol.Schema} and
 *       {@link com.darkcollective.relix.symbol.ColumnDefinition} — structural
 *       description of a relation's columns.</li>
 *   <li>{@link com.darkcollective.relix.symbol.ParameterDefinition} — a typed,
 *       named parameter used in function signatures.</li>
 *   <li>{@link com.darkcollective.relix.symbol.RegistrationResult} and
 *       {@link com.darkcollective.relix.symbol.SymbolError} — the result contract
 *       for symbol-table registration, supporting full error collection rather than
 *       fail-fast exceptions.</li>
 * </ul>
 *
 * <p><b>Naming convention:</b> every symbol stores both a
 * {@link com.darkcollective.relix.symbol.Symbol#declaredName()} (case-preserved, as
 * written by the user) and a {@link com.darkcollective.relix.symbol.Symbol#canonicalName()}
 * (lower-cased), ensuring lookups are always case-insensitive regardless of how the
 * symbol was originally typed.
 *
 * <p><b>Default namespace:</b> user-defined symbols that do not declare an explicit
 * namespace are placed in the {@code "default"} namespace.  Built-in symbols use the
 * {@code "builtin"} namespace.
 */
package com.darkcollective.relix.symbol;
