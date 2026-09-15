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

import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.symbol.function.FunctionSymbol;
import com.darkcollective.relix.symbol.function.ScalarFunctionSymbol;
import com.darkcollective.relix.symbol.relation.DatabaseRelationSymbol;
import com.darkcollective.relix.symbol.relation.InlineRelationSymbol;
import com.darkcollective.relix.symbol.relation.QueryRelationSymbol;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.ast.AstBuilders.*;

/**
 * Shared test infrastructure for the relix-symbol test suite.
 *
 * <p>Provides factory helpers for commonly constructed test objects so that
 * individual tests stay concise and focused on the behaviour under test.
 *
 * <p>Extend this class in any test that builds symbols or interacts with a
 * {@link SymbolTable}.
 */
public abstract class SymbolTestSupport {

    // -------------------------------------------------------------------------
    // Schema factories
    // -------------------------------------------------------------------------

    /**
     * Returns a one-column schema with the given column name typed {@link ScalarType#ANY}.
     */
    protected static Schema schema(String column) {
        return new Schema(List.of(new ColumnDefinition(column, ScalarType.ANY)));
    }

    /**
     * Returns a schema with two columns typed {@link ScalarType#ANY}.
     */
    protected static Schema schema(String col1, String col2) {
        return new Schema(List.of(
                new ColumnDefinition(col1, ScalarType.ANY),
                new ColumnDefinition(col2, ScalarType.ANY)));
    }

    /**
     * Returns a schema built from the given column definitions.
     */
    protected static Schema schema(ColumnDefinition... columns) {
        return new Schema(List.of(columns));
    }

    /**
     * Returns a typed column definition.
     */
    protected static ColumnDefinition col(String name, ScalarType type) {
        return new ColumnDefinition(name, type);
    }

    // -------------------------------------------------------------------------
    // Relation symbol factories
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link DatabaseRelationSymbol} in the {@code "default"} namespace
     * with a single {@link ScalarType#ANY} column.
     */
    protected static DatabaseRelationSymbol dbRelation(String name) {
        return DatabaseRelationSymbol.of(name, schema("id"));
    }

    /**
     * Creates a {@link DatabaseRelationSymbol} in the {@code "default"} namespace
     * with the given schema.
     */
    protected static DatabaseRelationSymbol dbRelation(String name, Schema schema) {
        return DatabaseRelationSymbol.of(name, schema);
    }

    /**
     * Creates an {@link InlineRelationSymbol} with a single numeric row.
     */
    protected static InlineRelationSymbol inlineRelation(String name) {
        Schema s = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
        List<Map<String, Operand>> rows = List.of(
                Map.of("id", num("1"), "name", str("Alice")));
        return InlineRelationSymbol.of(name, s, rows);
    }

    /**
     * Creates a {@link QueryRelationSymbol} whose body is a base relation reference.
     */
    protected static QueryRelationSymbol queryRelation(String name, RelNode body) {
        return QueryRelationSymbol.of(name, schema("id"), body);
    }

    /**
     * Returns a simple {@link RelNode} leaf node for use as a query body.
     */
    protected static RelNode relationNode(String name) {
        return AstBuilders.rel(name);
    }

    // -------------------------------------------------------------------------
    // Function symbol factories
    // -------------------------------------------------------------------------

    /**
     * Creates a zero-argument user-defined function with return type {@link ScalarType#ANY}.
     */
    protected static ScalarFunctionSymbol function(String name) {
        return ScalarFunctionSymbol.builder(name).build();
    }

    /**
     * Creates a one-argument user-defined function.
     */
    protected static ScalarFunctionSymbol function(String name, ScalarType paramType) {
        return ScalarFunctionSymbol.builder(name)
                .parameter("x", paramType)
                .build();
    }

    /**
     * Creates a two-argument user-defined function.
     */
    protected static ScalarFunctionSymbol function(String name, ScalarType p1, ScalarType p2) {
        return ScalarFunctionSymbol.builder(name)
                .parameter("a", p1)
                .parameter("b", p2)
                .build();
    }

    /**
     * Creates a built-in function with {@link ShadowPolicy#FORBIDDEN}.
     */
    protected static ScalarFunctionSymbol builtinFunction(String name) {
        return ScalarFunctionSymbol.builder(name)
                .namespace("builtin")
                .provenance(Provenance.BUILTIN)
                .shadowPolicy(ShadowPolicy.FORBIDDEN)
                .build();
    }

    // -------------------------------------------------------------------------
    // Symbol table factory
    // -------------------------------------------------------------------------

    /**
     * Returns a fresh, empty {@link InMemorySymbolTable}.
     */
    protected static SymbolTable emptyTable() {
        return new InMemorySymbolTable();
    }

    // -------------------------------------------------------------------------
    // Assertion helpers
    // -------------------------------------------------------------------------

    /**
     * Registers a symbol in the given table and asserts that registration succeeded
     * (no errors, symbol is present).
     */
    protected static void registerOk(SymbolTable table, Symbol symbol) {
        var result = table.register(symbol);
        if (!result.isSuccess()) {
            throw new AssertionError(
                    "Expected registration of '" + symbol.declaredName()
                            + "' to succeed, but got: " + result.errors());
        }
    }
}
