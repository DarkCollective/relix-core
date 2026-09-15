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
 * Symbol table interface, in-memory implementation, and persistence repository contract.
 *
 * <ul>
 *   <li>{@link com.darkcollective.relix.symbol.table.SymbolTable} — the primary
 *       API for registering and looking up symbols.  All lookups are
 *       case-insensitive.</li>
 *   <li>{@link com.darkcollective.relix.symbol.table.InMemorySymbolTable} — the
 *       default implementation backed by {@link java.util.LinkedHashMap}s.
 *       Suitable for single-session, in-process use.  Not thread-safe.</li>
 *   <li>{@link com.darkcollective.relix.symbol.table.SymbolRepository} — a
 *       persistence contract that future implementations may fulfil to store symbols
 *       in a database or other durable store.  The in-memory table does not use
 *       this interface; it is provided as an extension point.</li>
 * </ul>
 *
 * <h2>Lookup order</h2>
 * <p>The single-argument overloads of
 * {@link com.darkcollective.relix.symbol.table.SymbolTable#lookupRelation(String)} and
 * {@link com.darkcollective.relix.symbol.table.SymbolTable#lookupFunction(String)} search
 * the {@code "default"} namespace first, then the {@code "builtin"} namespace.
 * The two-argument overloads allow the caller to specify an explicit namespace.
 *
 * <h2>Registration</h2>
 * <p>All registration calls return a
 * {@link com.darkcollective.relix.symbol.RegistrationResult} that describes whether the
 * symbol was accepted, replaced, or rejected, along with any diagnostics.
 * Errors are collected rather than thrown, so multiple conflicts in a batch
 * can be reported at once.
 */
package com.darkcollective.relix.symbol.table;
