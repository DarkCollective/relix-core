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
package com.darkcollective.relix.parser;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.ComparisonOperator;
import org.junit.jupiter.api.Test;

import static com.darkcollective.relix.ast.AstBuilders.*;

/**
 * Tests for the Unnest operator (μ / {@code UNNEST}).
 */
final class UnnestParserTest extends ParserTestSupport {

    @Test
    public void parsesBasicUnnestUnicode() {
        assertParsesTo("μ items (Orders)",
                unnest("items", rel("Orders")));
    }

    @Test
    public void parsesBasicUnnestAsciiKeyword() {
        assertParsesTo("UNNEST items (Orders)",
                unnest("items", rel("Orders")));
    }

    @Test
    public void unnestOnQualifiedAndUnderscoreColumns() {
        assertParsesTo("μ tag_list (user_data)",
                unnest("tag_list", rel("user_data")));
    }

    @Test
    public void unnestOverASelection() {
        assertParsesTo("μ items (σ status = \"open\" (Orders))",
                unnest(
                        "items",
                        select(
                                cmp(attr("status"), ComparisonOperator.EQUAL, str("open")),
                                rel("Orders"))));
    }

    @Test
    public void unnestNestedInsideProjection() {
        assertParsesTo("π name (μ items (Orders))",
                new com.darkcollective.relix.ast.ProjectionNode(
                        java.util.List.of(projected(attr("name"))),
                        unnest("items", rel("Orders"))));
    }

    @Test
    public void stackedUnnest() {
        assertParsesTo("μ b (μ a (R))",
                unnest("b", unnest("a", rel("R"))));
    }

    @Test
    public void prettyPrintsUnnest() {
        RelNode node = unnest("items", rel("Orders"));
        assertPrettyPrints(node, "μ items (Orders)");
    }

    @Test
    public void prettyPrintsOuterUnnest() {
        RelNode node = unnest("items", true, rel("Orders"));
        assertPrettyPrints(node, "μ items OUTER (Orders)");
    }

    @Test
    public void parsesUnnestWithOrdinality() {
        assertParsesTo("μ items WITH ORDINALITY pos (Orders)",
                unnest("items", false, java.util.Optional.of("pos"),
                        rel("Orders")));
    }

    @Test
    public void parsesUnnestWithOrdinalityAsciiKeyword() {
        assertParsesTo("UNNEST items WITH ORDINALITY pos (Orders)",
                unnest("items", false, java.util.Optional.of("pos"),
                        rel("Orders")));
    }

    @Test
    public void prettyPrintsUnnestWithOrdinality() {
        RelNode node = unnest("items", false, java.util.Optional.of("pos"),
                rel("Orders"));
        assertPrettyPrints(node, "μ items WITH ORDINALITY pos (Orders)");
    }

    @Test
    public void failsWithoutColumn() {
        assertParseError("μ (Orders)")
                .hasMessageContaining("Expected attribute name after 'μ'");
    }

    @Test
    public void failsWithoutParenthesizedInput() {
        assertParseError("μ items Orders")
                .hasMessageContaining("Expected '(' after unnest attribute");
    }

    @Test
    public void failsWithWithButNotOrdinality() {
        assertParseError("μ items WITH pos (Orders)")
                .hasMessageContaining("Expected 'ORDINALITY' after 'WITH'");
    }

    @Test
    public void failsWithOrdinalityButNoColumnName() {
        assertParseError("μ items WITH ORDINALITY (Orders)")
                .hasMessageContaining("Expected ordinality column name");
    }
}
