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
 * Symbol table, type system, and relation/function symbol hierarchy for the relix system.
 *
 * <p>This module provides the structures needed to record what names are in scope
 * during semantic analysis — which relations exist, what columns they have, what
 * functions are available, and what types they work with.  It deliberately contains
 * no parsing and no query-execution logic; it is a pure data-representation and
 * lookup layer.
 *
 * <p>Key packages:
 * <ul>
 *   <li>{@code com.darkcollective.relix.symbol} — root types: {@code Symbol} sealed
 *       interface, enums ({@code ScalarType}, {@code Provenance}, {@code ShadowPolicy},
 *       {@code FunctionProperty}), and schema/error value objects.</li>
 *   <li>{@code com.darkcollective.relix.symbol.relation} — the relation symbol
 *       hierarchy ({@code DatabaseRelationSymbol}, {@code InlineRelationSymbol},
 *       {@code QueryRelationSymbol}, {@code SourceRelationSymbol},
 *       {@code SystemRelationSymbol}).</li>
 *   <li>{@code com.darkcollective.relix.symbol.function} — the function symbol
 *       hierarchy ({@code ScalarFunctionSymbol} with its {@code Builder}).</li>
 *   <li>{@code com.darkcollective.relix.symbol.table} — {@code SymbolTable}
 *       interface, {@code InMemorySymbolTable} implementation, and the
 *       {@code SymbolRepository} persistence contract.</li>
 *   <li>{@code com.darkcollective.relix.symbol.graph} — the schema graph: {@code SchemaGraph},
 *   {@code Relationship}, {@code Endpoint},
 *       {@code EdgeOrigin} — relationship metadata between relations.</li>
 * </ul>
 */
module com.darkcollective.relix.symbol {
    requires transitive com.darkcollective.relix.ast;

    exports com.darkcollective.relix.symbol;
    exports com.darkcollective.relix.symbol.internal to com.darkcollective.relix.optimizer, com.darkcollective.relix.processor;
    exports com.darkcollective.relix.symbol.relation;
    exports com.darkcollective.relix.symbol.function;
    exports com.darkcollective.relix.symbol.table;
    exports com.darkcollective.relix.symbol.table.internal to com.darkcollective.relix.embed, com.darkcollective.relix.semantic;
    exports com.darkcollective.relix.symbol.graph;
    exports com.darkcollective.relix.symbol.graph.internal to com.darkcollective.relix.semantic;
}
