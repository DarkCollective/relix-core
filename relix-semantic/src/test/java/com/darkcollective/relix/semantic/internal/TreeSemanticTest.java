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

import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.semantic.SemanticFixtures;
import com.darkcollective.relix.semantic.internal.RelAlgebraValidator;
import com.darkcollective.relix.semantic.internal.SchemaInferenceVisitor;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SortSpecification;
import com.darkcollective.relix.ast.TreeNode;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Provenance;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ShadowPolicy;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.internal.InMemorySymbolTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests for TREE schema inference and validation (ADR-0019, issue #324). */
@DisplayName("TREE — schema inference and validation")
final class TreeSemanticTest {

    private InMemorySymbolTable table;
    private SchemaAnnotations   annotations;

    @BeforeEach
    void setUp() {
        table       = new InMemorySymbolTable();
        annotations = new SchemaAnnotations();

        // Nodes(node_id: NUMBER, parent_id: NUMBER, ordinal: NUMBER, label: STRING)
        table.register(new SourceRelationSymbol("default", "Nodes", Provenance.BUILTIN,
                ShadowPolicy.PERMITTED, new Schema(List.of(
                        col("node_id",   ScalarType.NUMBER),
                        col("parent_id", ScalarType.NUMBER),
                        col("ordinal",   ScalarType.NUMBER),
                        col("label",     ScalarType.STRING)))));
    }

    private static ColumnDefinition col(String name, ScalarType type) {
        return new ColumnDefinition(name, type);
    }

    private SchemaInferenceVisitor visitor() {
        var errors = new ArrayList<SemanticError>();
        return new SchemaInferenceVisitor(table, annotations, errors, "<test>",
                SemanticFixtures.FUNCTIONS);
    }

    private List<SemanticError> inferAndValidate(RelNode tree) {
        var inferErrors = new ArrayList<SemanticError>();
        tree.accept(new SchemaInferenceVisitor(table, annotations, inferErrors, "<test>",
                SemanticFixtures.FUNCTIONS));
        var valErrors = new ArrayList<SemanticError>();
        tree.accept(new RelAlgebraValidator(table, annotations, SemanticFixtures.FUNCTIONS,
                valErrors, "<test>"));
        return valErrors;
    }

    private static TreeNode node(String key, String parent, List<SortSpecification> order,
                                 String children, String relation) {
        return tree(key, parent, order, children,rel(relation));
    }

    @Nested
    @DisplayName("Schema inference")
    class SchemaInferenceTests {

        @Test
        @DisplayName("Appends the ANY children column to the input schema, last")
        void appendsChildrenColumn() {
            var n = node("node_id", "parent_id",
                    List.of(asc("ordinal")), "children", "Nodes");
            Optional<Schema> result = n.accept(visitor());

            assertThat(result).isPresent();
            Schema schema = result.get();
            assertThat(schema.column("node_id")).isPresent();
            assertThat(schema.column("label")).isPresent();
            assertThat(schema.column("children")).isPresent();
            assertThat(schema.column("children").get().type()).isEqualTo(ScalarType.ANY);
            assertThat(schema.columns().get(schema.width() - 1).name()).isEqualTo("children");
        }

        @Test
        @DisplayName("A children-name clash with an existing column skips the append")
        void nameClashSkipsAppend() {
            var n = node("node_id", "parent_id", List.of(), "label", "Nodes");
            Optional<Schema> result = n.accept(visitor());
            assertThat(result).isPresent();
            // still 4 columns — the clashing append was skipped (validator reports it)
            assertThat(result.get().width()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("A well-formed TREE produces no errors")
        void valid() {
            var n = node("node_id", "parent_id",
                    List.of(asc("ordinal")), "children", "Nodes");
            assertThat(inferAndValidate(n)).isEmpty();
        }

        @Test
        @DisplayName("An unknown key column is reported")
        void unknownKey() {
            var n = node("missing", "parent_id", List.of(), "children", "Nodes");
            assertThat(inferAndValidate(n))
                    .anySatisfy(e -> assertThat(e.message()).contains("key column"));
        }

        @Test
        @DisplayName("An unknown parent-key column is reported")
        void unknownParent() {
            var n = node("node_id", "missing", List.of(), "children", "Nodes");
            assertThat(inferAndValidate(n))
                    .anySatisfy(e -> assertThat(e.message()).contains("parent-key column"));
        }

        @Test
        @DisplayName("An unknown ORDER column is reported")
        void unknownOrder() {
            var n = node("node_id", "parent_id",
                    List.of(asc("missing")), "children", "Nodes");
            assertThat(inferAndValidate(n))
                    .anySatisfy(e -> assertThat(e.message()).contains("ORDER column"));
        }

        @Test
        @DisplayName("A children column that clashes with an existing column is reported")
        void childrenClash() {
            var n = node("node_id", "parent_id", List.of(), "label", "Nodes");
            assertThat(inferAndValidate(n))
                    .anySatisfy(e -> assertThat(e.message()).contains("children column"));
        }
    }
}
