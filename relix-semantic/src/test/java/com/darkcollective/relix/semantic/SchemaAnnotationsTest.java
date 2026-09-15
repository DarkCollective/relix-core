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

import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SchemaAnnotations — identity-keyed schema map")
final class SchemaAnnotationsTest {

    private static Schema schema(String... columns) {
        return new Schema(
                java.util.Arrays.stream(columns)
                        .map(c -> new ColumnDefinition(c, ScalarType.STRING))
                        .toList());
    }

    @Test
    @DisplayName("empty() factory returns annotations with no entries")
    void emptyFactoryIsEmpty() {
        var annotations = SchemaAnnotations.empty();
        assertThat(annotations.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("get() on empty annotations returns Optional.empty()")
    void getOnEmptyReturnsEmpty() {
        var annotations = SchemaAnnotations.empty();
        var node = rel("Users");
        assertThat(annotations.get(node)).isEmpty();
    }

    @Test
    @DisplayName("hasSchema() returns false for unannotated node")
    void hasSchemaFalseForUnannotated() {
        var annotations = SchemaAnnotations.empty();
        assertThat(annotations.hasSchema(rel("Users"))).isFalse();
    }

    @Test
    @DisplayName("After put(), get() returns the stored schema")
    void putThenGet() {
        var annotations = new SchemaAnnotations();
        var node = rel("Users");
        var s = schema("id", "name");
        annotations.put(node, s);
        assertThat(annotations.get(node)).contains(s);
    }

    @Test
    @DisplayName("After put(), hasSchema() returns true")
    void putThenHasSchema() {
        var annotations = new SchemaAnnotations();
        var node = rel("Orders");
        annotations.put(node, schema("id"));
        assertThat(annotations.hasSchema(node)).isTrue();
    }

    @Test
    @DisplayName("size() reflects number of annotated nodes")
    void sizeReflectsAnnotations() {
        var annotations = new SchemaAnnotations();
        annotations.put(rel("A"), schema("x"));
        annotations.put(rel("B"), schema("y"));
        assertThat(annotations.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Uses identity equality — two structurally equal nodes are tracked independently")
    void identitySemantics() {
        var annotations = new SchemaAnnotations();
        var node1 = rel("Users");
        var node2 = rel("Users"); // structurally equal but different instance
        var schema1 = schema("id");
        var schema2 = schema("name");

        assertThat(node1).isEqualTo(node2); // confirm records are equal by value

        annotations.put(node1, schema1);
        annotations.put(node2, schema2);

        // Both are tracked independently
        assertThat(annotations.size()).isEqualTo(2);
        assertThat(annotations.get(node1)).contains(schema1);
        assertThat(annotations.get(node2)).contains(schema2);
    }

    @Test
    @DisplayName("Multiple distinct nodes can be annotated independently")
    void multipleNodes() {
        var annotations = new SchemaAnnotations();
        var users = rel("Users");
        var orders = rel("Orders");
        var sUsers = schema("id", "name");
        var sOrders = schema("order_id", "total");

        annotations.put(users, sUsers);
        annotations.put(orders, sOrders);

        assertThat(annotations.get(users)).contains(sUsers);
        assertThat(annotations.get(orders)).contains(sOrders);
    }

    @Test
    @DisplayName("Overwriting a node's annotation replaces the previous schema")
    void overwriteReplacesSchema() {
        var annotations = new SchemaAnnotations();
        var node = rel("R");
        annotations.put(node, schema("a"));
        annotations.put(node, schema("b")); // overwrite
        assertThat(annotations.get(node).orElseThrow().column("b")).isPresent();
        assertThat(annotations.get(node).orElseThrow().column("a")).isEmpty();
    }

    // =========================================================================
    // asMap()
    // =========================================================================

    @Test
    @DisplayName("asMap() returns empty map when no annotations exist")
    void asMapEmptyWhenNoAnnotations() {
        var annotations = SchemaAnnotations.empty();
        assertThat(annotations.asMap()).isEmpty();
    }

    @Test
    @DisplayName("asMap() reflects all annotated nodes")
    void asMapContainsAllEntries() {
        var annotations = new SchemaAnnotations();
        var node1 = rel("A");
        var node2 = rel("B");
        var s1 = schema("x");
        var s2 = schema("y");
        annotations.put(node1, s1);
        annotations.put(node2, s2);

        var map = annotations.asMap();
        assertThat(map).hasSize(2);
        assertThat(map.get(node1)).isEqualTo(s1);
        assertThat(map.get(node2)).isEqualTo(s2);
    }

    @Test
    @DisplayName("asMap() uses identity equality — two structurally equal nodes are distinct keys")
    void asMapUsesIdentityEquality() {
        var annotations = new SchemaAnnotations();
        var node1 = rel("Users");
        var node2 = rel("Users"); // same value, different instance
        annotations.put(node1, schema("id"));
        annotations.put(node2, schema("name"));

        var map = annotations.asMap();
        // Both instances must appear as separate entries
        assertThat(map).hasSize(2);
        assertThat(map.get(node1).columns().get(0).name()).isEqualTo("id");
        assertThat(map.get(node2).columns().get(0).name()).isEqualTo("name");
    }

    @Test
    @DisplayName("asMap() returns an unmodifiable map")
    void asMapIsUnmodifiable() {
        var annotations = new SchemaAnnotations();
        var node = rel("R");
        annotations.put(node, schema("a"));

        var map = annotations.asMap();
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> map.put(rel("X"), schema("z")));
    }

    @Test
    @DisplayName("asMap() is a snapshot — subsequent puts are not reflected")
    void asMapIsSnapshot() {
        var annotations = new SchemaAnnotations();
        var node1 = rel("A");
        annotations.put(node1, schema("x"));

        var snapshot = annotations.asMap();
        assertThat(snapshot).hasSize(1);

        // Put another entry after taking the snapshot
        annotations.put(rel("B"), schema("y"));

        // Snapshot must still reflect the original state
        assertThat(snapshot).hasSize(1);
    }

    @Test
    @DisplayName("require() names the node and the caller when the annotation is missing")
    void requireThrowsNamingTheNodeAndContext() {
        // require() is the "inference must have annotated this by now" assertion the
        // planner and the pushdown renderers lean on. When it fires, the message is the
        // only clue about *which* node and *which* consumer, so both belong in it.
        var annotations = SchemaAnnotations.empty();
        var node = rel("Users");
        assertThatThrownBy(() -> annotations.require(node, "during SQL pushdown"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RelationNode")
                .hasMessageContaining("during SQL pushdown");
    }

    @Test
    @DisplayName("require() returns the schema when one is annotated")
    void requireReturnsAnAnnotatedSchema() {
        var node = rel("Users");
        var schema = new Schema(List.of(new ColumnDefinition("id", ScalarType.NUMBER)));
        var annotations = new SchemaAnnotations(Map.of(node, schema));
        assertThat(annotations.require(node, "anywhere")).isEqualTo(schema);
    }
}
