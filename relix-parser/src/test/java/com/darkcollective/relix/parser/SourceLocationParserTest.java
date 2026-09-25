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

import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.internal.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;

/**
 * Tests that verify the RA parser attaches correct {@link SourceLocation}
 * information to each node it produces.
 *
 * <p>Every assertion checks the actual position data embedded in the parsed
 * AST, not structural equality.  {@link ParserTestSupport#assertParsesTo} is
 * intentionally <em>not</em> used here because that helper strips locations
 * before comparing.
 */
@DisplayName("RelAlgebraParser — source location tracking")
final class SourceLocationParserTest extends ParserTestSupport {

    // =========================================================================
    // Default filePath ("<unknown>") — positions still tracked
    // =========================================================================

    @Nested
    @DisplayName("Default filePath — '<unknown>'")
    class DefaultFilePath {

        @Test
        @DisplayName("Simple relation carries filePath '<unknown>'")
        void simpleRelationFilePath() {
            RelNode node = RelAlgebraParser.parse("Users");
            assertThat(node.location().filePath()).isEqualTo("<unknown>");
        }

        @Test
        @DisplayName("Simple relation is at line 1, column 1")
        void simpleRelationPosition() {
            RelNode node = RelAlgebraParser.parse("Users");
            assertThat(node.location().line()).isEqualTo(1);
            assertThat(node.location().column()).isEqualTo(1);
        }

        @Test
        @DisplayName("Projection operator is at line 1, column 1")
        void projectionPosition() {
            RelNode node = RelAlgebraParser.parse("π name (Users)");
            assertThat(node.location().line()).isEqualTo(1);
            assertThat(node.location().column()).isEqualTo(1);
        }

        @Test
        @DisplayName("Selection operator is at line 1, column 1")
        void selectionPosition() {
            RelNode node = RelAlgebraParser.parse("σ age > 18 (Users)");
            assertThat(node.location().line()).isEqualTo(1);
            assertThat(node.location().column()).isEqualTo(1);
        }

        @Test
        @DisplayName("Inner relation in projection carries its own position")
        void innerRelationPosition() {
            ProjectionNode proj = (ProjectionNode) RelAlgebraParser.parse("π name (Users)");
            RelNode inner = proj.input();
            // "Users" starts after "π name (" — column 9 (1-based, counting π as 1 char)
            assertThat(inner.location().line()).isEqualTo(1);
            assertThat(inner.location().column()).isGreaterThan(1);
        }
    }

    // =========================================================================
    // Custom filePath — embedded in every node
    // =========================================================================

    @Nested
    @DisplayName("Custom filePath embedded in every node")
    class CustomFilePath {

        @Test
        @DisplayName("filePath is embedded in root node")
        void filePathInRootNode() {
            RelNode node = RelAlgebraParser.parse("Users", "weather.relix", 1, 1);
            assertThat(node.location().filePath()).isEqualTo("weather.relix");
        }

        @Test
        @DisplayName("filePath is embedded in inner nodes")
        void filePathInInnerNodes() {
            ProjectionNode proj = (ProjectionNode)
                    RelAlgebraParser.parse("π name (Users)", "queries.relix", 1, 1);
            assertThat(proj.input().location().filePath()).isEqualTo("queries.relix");
        }

        @Test
        @DisplayName("Natural join children both carry filePath")
        void naturalJoinChildrenFilePath() {
            NaturalJoinNode join = (NaturalJoinNode)
                    RelAlgebraParser.parse("Users ⋈ Orders", "joins.relix", 1, 1);
            assertThat(join.left().location().filePath()).isEqualTo("joins.relix");
            assertThat(join.right().location().filePath()).isEqualTo("joins.relix");
        }
    }

    // =========================================================================
    // Line and column offsets
    // =========================================================================

    @Nested
    @DisplayName("Line and column offsets")
    class Offsets {

        @Test
        @DisplayName("startLine shifts the reported line number")
        void startLineShifts() {
            RelNode node = RelAlgebraParser.parse("Users", 10, 1);
            assertThat(node.location().line()).isEqualTo(10);
        }

        @Test
        @DisplayName("startColumn shifts the reported column number")
        void startColumnShifts() {
            RelNode node = RelAlgebraParser.parse("Users", 1, 20);
            assertThat(node.location().column()).isEqualTo(20);
        }

        @Test
        @DisplayName("Both offsets shift the root node's position")
        void bothOffsetsShiftRoot() {
            RelNode node = RelAlgebraParser.parse("Users", 5, 12);
            assertThat(node.location().line()).isEqualTo(5);
            assertThat(node.location().column()).isEqualTo(12);
        }

        @Test
        @DisplayName("Offsets propagate to inner nodes on the same line")
        void offsetsPropagateToInnerNodes() {
            ProjectionNode proj = (ProjectionNode) RelAlgebraParser.parse("π name (Users)", 7, 3);
            // π at column 3; Users is further right
            assertThat(proj.location().column()).isEqualTo(3);
            assertThat(proj.input().location().column()).isGreaterThan(3);
        }

        @Test
        @DisplayName("Multiline input: second line carries original startLine + 1")
        void multilineSecondLineTracking() {
            // π at line 10 (startLine), Users on line 11
            RelNode node = RelAlgebraParser.parse("π name\n(Users)", 10, 1);
            ProjectionNode proj = (ProjectionNode) node;
            assertThat(proj.location().line()).isEqualTo(10);
            assertThat(proj.input().location().line()).isEqualTo(11);
        }
    }

    // =========================================================================
    // InputStream overloads
    // =========================================================================

    @Nested
    @DisplayName("InputStream overloads")
    class InputStreamOverloads {

        @Test
        @DisplayName("parse(InputStream) defaults to '<unknown>', line 1, col 1")
        void inputStreamDefaultPosition() throws IOException {
            try (var is = new ByteArrayInputStream("Users".getBytes())) {
                RelNode node = RelAlgebraParser.parse(is);
                assertThat(node.location().filePath()).isEqualTo("<unknown>");
                assertThat(node.location().line()).isEqualTo(1);
                assertThat(node.location().column()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("parse(InputStream, startLine, startColumn) shifts position")
        void inputStreamWithOffset() throws IOException {
            try (var is = new ByteArrayInputStream("Users".getBytes())) {
                RelNode node = RelAlgebraParser.parse(is, 8, 4);
                assertThat(node.location().line()).isEqualTo(8);
                assertThat(node.location().column()).isEqualTo(4);
            }
        }
    }

    // =========================================================================
    // Node type coverage
    // =========================================================================

    @Nested
    @DisplayName("Position on various node types")
    class NodeTypeCoverage {

        @Test
        @DisplayName("RenameNode carries position of ρ operator")
        void renameNodePosition() {
            RelNode node = RelAlgebraParser.parse("ρ Emp(id, name) (Users)", 3, 5);
            assertThat(node.location().line()).isEqualTo(3);
            assertThat(node.location().column()).isEqualTo(5);
        }

        @Test
        @DisplayName("AggregationNode carries position of γ operator")
        void aggregationNodePosition() {
            RelNode node = RelAlgebraParser.parse("γ dept SUM(salary) (Employees)", 2, 1);
            assertThat(node.location().line()).isEqualTo(2);
            assertThat(node.location().column()).isEqualTo(1);
        }

        @Test
        @DisplayName("SortNode carries position of τ operator")
        void sortNodePosition() {
            RelNode node = RelAlgebraParser.parse("τ name ASC (Users)", 4, 7);
            assertThat(node.location().line()).isEqualTo(4);
            assertThat(node.location().column()).isEqualTo(7);
        }

        @Test
        @DisplayName("DistinctNode carries position of δ operator")
        void distinctNodePosition() {
            RelNode node = RelAlgebraParser.parse("δ (Users)", 6, 8);
            assertThat(node.location().line()).isEqualTo(6);
            assertThat(node.location().column()).isEqualTo(8);
        }

        @Test
        @DisplayName("LimitNode carries position of λ operator")
        void limitNodePosition() {
            RelNode node = RelAlgebraParser.parse("λ 0,10 (Users)", 1, 1);
            assertThat(node).isNode(LimitNode.class);
            assertThat(node.location().line()).isEqualTo(1);
            assertThat(node.location().column()).isEqualTo(1);
        }

        @Test
        @DisplayName("NaturalJoin carries position of ⋈ operator token")
        void naturalJoinPosition() {
            // "Users ⋈ Orders": Users at col 1, ⋈ at col 7, Orders further right
            NaturalJoinNode join = (NaturalJoinNode) RelAlgebraParser.parse("Users ⋈ Orders");
            assertThat(join.location().line()).isEqualTo(1);
            // The join position is at the ⋈ operator token
            assertThat(join.location().column()).isGreaterThan(1);
        }

        @Test
        @DisplayName("UnionNode carries position of ∪ operator")
        void unionNodePosition() {
            UnionNode union = (UnionNode) RelAlgebraParser.parse("Users ∪ Admins", 1, 1);
            assertThat(union.location().line()).isEqualTo(1);
            assertThat(union.location().column()).isGreaterThan(1);
        }
    }

    // =========================================================================
    // Predicate and operand locations
    // =========================================================================

    @Nested
    @DisplayName("Predicate and operand source locations")
    class PredicateAndOperandLocations {

        @Test
        @DisplayName("AttributeOperand in predicate carries its position")
        void attributeOperandInPredicate() {
            SelectionNode sel = (SelectionNode)
                    RelAlgebraParser.parse("σ age > 18 (Users)", "q.relix", 1, 1);
            ComparisonPredicate pred = (ComparisonPredicate) sel.predicate();
            AttributeOperand attr = (AttributeOperand) pred.left();
            assertThat(attr.location().filePath()).isEqualTo("q.relix");
            assertThat(attr.location().line()).isEqualTo(1);
            assertThat(attr.location().column()).isGreaterThan(0);
        }

        @Test
        @DisplayName("NumberOperand in predicate carries its position")
        void numberOperandInPredicate() {
            SelectionNode sel = (SelectionNode)
                    RelAlgebraParser.parse("σ age > 18 (Users)");
            ComparisonPredicate pred = (ComparisonPredicate) sel.predicate();
            NumberOperand num = (NumberOperand) pred.right();
            assertThat(num.location().line()).isEqualTo(1);
            assertThat(num.location().column()).isGreaterThan(1);
        }

        @Test
        @DisplayName("FunctionCall in projection carries its position")
        void functionCallInProjection() {
            ProjectionNode proj = (ProjectionNode)
                    RelAlgebraParser.parse("π Len(name) (Users)", "r.relix", 1, 1);
            Operand expr = proj.attributes().get(0).expression();
            assertThat(expr).isInstanceOf(FunctionCall.class);
            FunctionCall fc = (FunctionCall) expr;
            assertThat(fc.location().filePath()).isEqualTo("r.relix");
            assertThat(fc.location().line()).isEqualTo(1);
        }
    }
}
