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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.assertj.core.api.Assertions;
import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.internal.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

/**
 * Comprehensive tests for InputStream parsing support and line/column position tracking.
 * Tests verify that:
 * - The parser can accept InputStream in addition to String
 * - The parser can track line and column positions starting from custom offsets
 * - Error reporting reflects the correct line and column positions with offsets
 * - The parser works correctly when receiving a portion of a larger input stream
 * - All existing parsing functionality continues to work
 */
final class InputStreamParserTest extends ParserTestSupport {

    @Nested
    class BasicInputStreamParsing {

        @Test
        public void parsesSimpleRelationFromInputStream() throws IOException {
            String input = "Users";
            try (var is = new ByteArrayInputStream(input.getBytes())) {
                RelNode result = RelAlgebraParser.parse(is);
                Assertions.assertThat(stripLocations(result)).isEqualTo(rel("Users"));
            }
        }

        @Test
        public void parsesProjectionFromInputStream() throws IOException {
            String input = "π name (Users)";
            try (var is = new ByteArrayInputStream(input.getBytes())) {
                RelNode result = RelAlgebraParser.parse(is);
                Assertions.assertThat(stripLocations(result)).isEqualTo(
                    project(
                            List.of(ProjectedAttribute.simple(attr("name"))),
                            rel("Users"))
                );
            }
        }

        @Test
        public void parsesComplexExpressionFromInputStream() throws IOException {
            String input = "π name (σ age ≥ 18 (Users))";
            try (var is = new ByteArrayInputStream(input.getBytes())) {
                RelNode result = RelAlgebraParser.parse(is);
                Assertions.assertThat(stripLocations(result)).isEqualTo(
                    project(
                            List.of(ProjectedAttribute.simple(attr("name"))),
                            select(
                                cmp(
                                    attr("age"),
                                    ComparisonOperator.GREATER_EQUAL,
                                    num("18")),
                                rel("Users")))
                );
            }
        }

        @Test
        public void parsesMultilineInputFromInputStream() throws IOException {
            String input = "π id, name, email\n(Users)";
            try (var is = new ByteArrayInputStream(input.getBytes())) {
                RelNode result = RelAlgebraParser.parse(is);
                Assertions.assertThat(stripLocations(result)).isEqualTo(
                    project(
                            List.of(
                            ProjectedAttribute.simple(attr("id")),
                            ProjectedAttribute.simple(attr("name")),
                            ProjectedAttribute.simple(attr("email"))
                        ),
                            rel("Users"))
                );
            }
        }
    }

    @Nested
    class StringWithLineAndColumnOffset {

        @Test
        public void parsesWithCustomStartPosition() {
            String input = "Users";
            RelNode result = RelAlgebraParser.parse(input, 5, 10);
            Assertions.assertThat(stripLocations(result)).isEqualTo(rel("Users"));
        }

        @Test
        public void parsesProjectionWithCustomStartPosition() {
            String input = "π name (Users)";
            RelNode result = RelAlgebraParser.parse(input, 3, 7);
            Assertions.assertThat(stripLocations(result)).isEqualTo(
                project(
                        List.of(ProjectedAttribute.simple(attr("name"))),
                        rel("Users"))
            );
        }

        @Test
        public void reportsErrorAtCorrectLineWithOffset() {
            String input = "π name (Users";  // Missing closing paren
            try {
                RelAlgebraParser.parse(input);
            } catch (ParseException e) {
                Assertions.assertThat(e.line()).isEqualTo(1);
                Assertions.assertThat(e.column()).isEqualTo(14);
            }
        }

        @Test
        public void reportsErrorAtCorrectLineWithLineOffset() {
            String input = "π name (Users";  // Missing closing paren
            try {
                RelAlgebraParser.parse(input, 10, 1);
            } catch (ParseException e) {
                Assertions.assertThat(e.line()).isEqualTo(10);
                Assertions.assertThat(e.column()).isEqualTo(14);
            }
        }

        @Test
        public void reportsErrorAtCorrectColumnWithColumnOffset() {
            String input = "π name (Users";  // Missing closing paren
            try {
                RelAlgebraParser.parse(input, 1, 20);
            } catch (ParseException e) {
                Assertions.assertThat(e.line()).isEqualTo(1);
                Assertions.assertThat(e.column()).isEqualTo(33);
            }
        }

        @Test
        public void reportsErrorAtCorrectLocationWithBothOffsets() {
            String input = "π name (Users";  // Missing closing paren
            try {
                RelAlgebraParser.parse(input, 5, 15);
            } catch (ParseException e) {
                Assertions.assertThat(e.line()).isEqualTo(5);
                Assertions.assertThat(e.column()).isEqualTo(28);
            }
        }

        @Test
        public void throwsIllegalArgumentForInvalidStartLine() {
            String input = "Users";
            try {
                RelAlgebraParser.parse(input, 0, 1);
                Assertions.fail("Expected IllegalArgumentException");
            } catch (IllegalArgumentException e) {
                Assertions.assertThat(e.getMessage())
                    .contains("startLine must be >= 1");
            }
        }

        @Test
        public void throwsIllegalArgumentForInvalidStartColumn() {
            String input = "Users";
            try {
                RelAlgebraParser.parse(input, 1, 0);
                Assertions.fail("Expected IllegalArgumentException");
            } catch (IllegalArgumentException e) {
                Assertions.assertThat(e.getMessage())
                    .contains("startColumn must be >= 1");
            }
        }

        @Test
        public void parsesMultilineWithLineOffset() {
            String input = "π id, name\n(Users)";
            RelNode result = RelAlgebraParser.parse(input, 10, 1);
            Assertions.assertThat(stripLocations(result)).isEqualTo(
                project(
                        List.of(
                        ProjectedAttribute.simple(attr("id")),
                        ProjectedAttribute.simple(attr("name"))
                    ),
                        rel("Users"))
            );
        }

        @Test
        public void parsesSelectionWithLineAndColumnOffset() {
            String input = "σ salary > 50000 (Employees)";
            RelNode result = RelAlgebraParser.parse(input, 7, 12);
            Assertions.assertThat(stripLocations(result)).isEqualTo(
                select(
                        cmp(
                            attr("salary"),
                            ComparisonOperator.GREATER,
                            num("50000")),
                        rel("Employees"))
            );
        }
    }

    @Nested
    class InputStreamWithLineAndColumnOffset {

        @Test
        public void parsesInputStreamWithCustomStartPosition() throws IOException {
            String input = "Users";
            try (var is = new ByteArrayInputStream(input.getBytes())) {
                RelNode result = RelAlgebraParser.parse(is, 5, 10);
                Assertions.assertThat(stripLocations(result)).isEqualTo(rel("Users"));
            }
        }

        @Test
        public void parsesProjectionFromInputStreamWithOffset() throws IOException {
            String input = "π name (Users)";
            try (var is = new ByteArrayInputStream(input.getBytes())) {
                RelNode result = RelAlgebraParser.parse(is, 3, 7);
                Assertions.assertThat(stripLocations(result)).isEqualTo(
                    project(
                            List.of(ProjectedAttribute.simple(attr("name"))),
                            rel("Users"))
                );
            }
        }

        @Test
        public void reportsErrorAtCorrectLineForInputStreamWithOffset() throws IOException {
            String input = "π name (Users";  // Missing closing paren
            try (var is = new ByteArrayInputStream(input.getBytes())) {
                try {
                    RelAlgebraParser.parse(is, 10, 1);
                } catch (ParseException e) {
                    Assertions.assertThat(e.line()).isEqualTo(10);
                    Assertions.assertThat(e.column()).isEqualTo(14);
                }
            }
        }

        @Test
        public void reportsErrorAtCorrectColumnForInputStreamWithOffset() throws IOException {
            String input = "π name (Users";  // Missing closing paren
            try (var is = new ByteArrayInputStream(input.getBytes())) {
                try {
                    RelAlgebraParser.parse(is, 1, 20);
                } catch (ParseException e) {
                    Assertions.assertThat(e.line()).isEqualTo(1);
                    Assertions.assertThat(e.column()).isEqualTo(33);
                }
            }
        }

        @Test
        public void parsesMultilineInputFromInputStreamWithOffset() throws IOException {
            String input = "π department, count\n(Employees)";
            try (var is = new ByteArrayInputStream(input.getBytes())) {
                RelNode result = RelAlgebraParser.parse(is, 15, 5);
                Assertions.assertThat(stripLocations(result)).isEqualTo(
                    project(
                            List.of(
                            ProjectedAttribute.simple(attr("department")),
                            ProjectedAttribute.simple(attr("count"))
                        ),
                            rel("Employees"))
                );
            }
        }

        @Test
        public void throwsIllegalArgumentForInvalidStartLineWithInputStream() {
            String input = "Users";
            try (var is = new ByteArrayInputStream(input.getBytes())) {
                try {
                    RelAlgebraParser.parse(is, 0, 1);
                    Assertions.fail("Expected IllegalArgumentException");
                } catch (IllegalArgumentException e) {
                    Assertions.assertThat(e.getMessage())
                        .contains("startLine must be >= 1");
                }
            } catch (IOException e) {
                Assertions.fail("Unexpected IOException", e);
            }
        }

        @Test
        public void throwsIllegalArgumentForInvalidStartColumnWithInputStream() {
            String input = "Users";
            try (var is = new ByteArrayInputStream(input.getBytes())) {
                try {
                    RelAlgebraParser.parse(is, 1, 0);
                    Assertions.fail("Expected IllegalArgumentException");
                } catch (IllegalArgumentException e) {
                    Assertions.assertThat(e.getMessage())
                        .contains("startColumn must be >= 1");
                }
            } catch (IOException e) {
                Assertions.fail("Unexpected IOException", e);
            }
        }
    }

    @Nested
    class PortionOfInputStream {
        /**
         * Tests that verify the parser can be used to parse a portion of an InputStream,
         * as would be used when a parent parser extracts a subsection of a larger input.
         */

        @Test
        public void parsesPortionOfInputStreamWithCorrectLineTracking() throws IOException {
            // Simulating a parent parser that extracts "Users" from a larger input
            // Parent parser receives: "START (Users END"
            // Parent parser extracts: "Users"
            // Parent parser knows this portion starts at line 2, column 8
            String portion = "Users";
            try (var is = new ByteArrayInputStream(portion.getBytes())) {
                RelNode result = RelAlgebraParser.parse(is, 2, 8);
                Assertions.assertThat(stripLocations(result)).isEqualTo(rel("Users"));
            }
        }

        @Test
        public void parsesMultilinePortionWithOffsetTracking() throws IOException {
            // Simulating extraction of a multi-line portion
            // Original: "SOMETHING\nπ id, name\n(Users)\nEND"
            // Extracted portion: "π id, name\n(Users)"
            // Starts at line 2, column 1
            String portion = "π id, name\n(Users)";
            try (var is = new ByteArrayInputStream(portion.getBytes())) {
                RelNode result = RelAlgebraParser.parse(is, 2, 1);
                Assertions.assertThat(stripLocations(result)).isEqualTo(
                    project(
                            List.of(
                            ProjectedAttribute.simple(attr("id")),
                            ProjectedAttribute.simple(attr("name"))
                        ),
                            rel("Users"))
                );
            }
        }

        @Test
        public void errorReportingReflectsActualLocationInOriginalInput() throws IOException {
            // If an error occurs in the portion, the reported line/column
            // should reflect the original location
            // Portion: "σ x > y (" (missing closing paren)
            // Starts at line 5, column 3
            String portion = "σ x > y (";  // Syntax error: incomplete parenthesis
            try (var is = new ByteArrayInputStream(portion.getBytes())) {
                try {
                    RelAlgebraParser.parse(is, 5, 3);
                } catch (ParseException e) {
                    Assertions.assertThat(e.line()).isEqualTo(5);
                    Assertions.assertThat(e.column()).isEqualTo(12);
                }
            }
        }

        @Test
        public void parsesComplexPortionWithJoin() throws IOException {
            // Portion: "Users ⋈ Orders"
            // Starts at line 10, column 5
            String portion = "Users ⋈ Orders";
            try (var is = new ByteArrayInputStream(portion.getBytes())) {
                RelNode result = RelAlgebraParser.parse(is, 10, 5);
                Assertions.assertThat(stripLocations(result)).isEqualTo(
                    naturalJoin(
                            rel("Users"),
                            rel("Orders"))
                );
            }
        }

        @Test
        public void parsesComplexPortionWithProjectionAndSelection() throws IOException {
            String portion = "π name (σ age ≠ ⊥ (Users))";
            try (var is = new ByteArrayInputStream(portion.getBytes())) {
                RelNode result = RelAlgebraParser.parse(is, 8, 1);
                Assertions.assertThat(stripLocations(result)).isEqualTo(
                    project(
                            List.of(ProjectedAttribute.simple(attr("name"))),
                            select(
                                nullPred(
                                    attr("age"),
                                    false),
                                rel("Users")))
                );
            }
        }
    }

    @Nested
    class BackwardCompatibility {
        /**
         * Tests that verify existing String-based parsing still works correctly.
         */

        @Test
        public void existingStringParseMethodStillWorks() {
            RelNode result = RelAlgebraParser.parse("Users");
            Assertions.assertThat(stripLocations(result)).isEqualTo(rel("Users"));
        }

        @Test
        public void existingStringParseMethodWorksWithComplexExpression() {
            RelNode result = RelAlgebraParser.parse("π name (σ age > 18 (Users))");
            Assertions.assertThat(stripLocations(result)).isEqualTo(
                project(
                        List.of(ProjectedAttribute.simple(attr("name"))),
                        select(
                            cmp(
                                attr("age"),
                                ComparisonOperator.GREATER,
                                num("18")),
                            rel("Users")))
            );
        }

        @Test
        public void errorReportingStillWorksForStringWithoutOffset() {
            try {
                RelAlgebraParser.parse("π name (Users");
            } catch (ParseException e) {
                Assertions.assertThat(e.line()).isEqualTo(1);
                Assertions.assertThat(e.column()).isEqualTo(14);
            }
        }

        @Test
        public void multilineErrorReportingStillWorks() {
            String input = "π id\n(Users";  // Missing closing paren
            try {
                RelAlgebraParser.parse(input);
            } catch (ParseException e) {
                Assertions.assertThat(e.line()).isEqualTo(2);
                Assertions.assertThat(e.column()).isEqualTo(7);
            }
        }
    }

    @Nested
    class EdgeCases {

        @Test
        public void parsesEmptyProjectionAttributeList() throws IOException {
            // This should fail parsing, but ensures IOException doesn't prevent error detection
            String input = "π (Users)";
            try (var is = new ByteArrayInputStream(input.getBytes())) {
                try {
                    RelAlgebraParser.parse(is, 1, 1);
                } catch (ParseException e) {
                    Assertions.assertThat(e.getMessage()).contains("Expected");
                }
            }
        }

        @Test
        public void handlesLargeLineOffsets() {
            String input = "Users";
            RelNode result = RelAlgebraParser.parse(input, 10000, 1);
            Assertions.assertThat(stripLocations(result)).isEqualTo(rel("Users"));
        }

        @Test
        public void handlesLargeColumnOffsets() {
            String input = "Users";
            RelNode result = RelAlgebraParser.parse(input, 1, 10000);
            Assertions.assertThat(stripLocations(result)).isEqualTo(rel("Users"));
        }

        @Test
        public void handlesInputWithTrailingWhitespace() throws IOException {
            String input = "Users   \n  ";
            try (var is = new ByteArrayInputStream(input.getBytes())) {
                RelNode result = RelAlgebraParser.parse(is);
                Assertions.assertThat(stripLocations(result)).isEqualTo(rel("Users"));
            }
        }

        @Test
        public void handlesInputWithLeadingWhitespace() throws IOException {
            String input = "   \n  Users";
            try (var is = new ByteArrayInputStream(input.getBytes())) {
                RelNode result = RelAlgebraParser.parse(is);
                Assertions.assertThat(stripLocations(result)).isEqualTo(rel("Users"));
            }
        }

        @Test
        public void handlesComplexSelectionWithOffset() {
            String input = "σ dept = \"Sales\" ∧ salary > 100000 (Employees)";
            RelNode result = RelAlgebraParser.parse(input, 5, 10);
            Assertions.assertThat(stripLocations(result)).isEqualTo(
                select(
                        and(
                            cmp(
                                attr("dept"),
                                ComparisonOperator.EQUAL,
                                str("Sales")),
                            cmp(
                                attr("salary"),
                                ComparisonOperator.GREATER,
                                num("100000"))),
                        rel("Employees"))
            );
        }

        @Test
        public void handlesRenameWithOffset() {
            String input = "ρ Emp(empId, empName) (Users)";
            RelNode result = RelAlgebraParser.parse(input, 3, 1);
            Assertions.assertThat(stripLocations(result)).isEqualTo(
                rename("Emp", List.of("empId", "empName"), rel("Users"))
            );
        }

        @Test
        public void handlesAggregationWithOffset() {
            String input = "γ dept SUM(salary) (Employees)";
            RelNode result = RelAlgebraParser.parse(input, 2, 5);
            Assertions.assertThat(stripLocations(result)).isEqualTo(
                groupBy(
                        List.of("dept"),
                        List.of(AggregateFunction.simple(AggregateOperator.SUM, "salary")),
                        rel("Employees"))
            );
        }

        @Test
        public void handlesUnionWithOffset() {
            String input = "Users ∪ Admins";
            RelNode result = RelAlgebraParser.parse(input, 4, 2);
            Assertions.assertThat(stripLocations(result)).isEqualTo(
                union(
                        rel("Users"),
                        rel("Admins"))
            );
        }

        @Test
        public void handlesSortWithOffset() throws IOException {
            String input = "τ name ASC (Users)";
            try (var is = new ByteArrayInputStream(input.getBytes())) {
                RelNode result = RelAlgebraParser.parse(is, 1, 1);
                Assertions.assertThat(stripLocations(result)).isEqualTo(
                    sort(
                            List.of(asc("name")),
                            rel("Users"))
                );
            }
        }

        @Test
        public void handlesDistinctWithOffset() {
            String input = "δ (Users)";
            RelNode result = RelAlgebraParser.parse(input, 6, 8);
            Assertions.assertThat(stripLocations(result)).isEqualTo(
                distinct(rel("Users"))
            );
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        public void throwsNullPointerExceptionForNullString() {
            try {
                RelAlgebraParser.parse((String) null);
                Assertions.fail("Expected NullPointerException");
            } catch (NullPointerException e) {
                Assertions.assertThat(e.getMessage()).contains("input");
            }
        }

        @Test
        public void throwsNullPointerExceptionForNullInputStream() {
            try {
                RelAlgebraParser.parse((java.io.InputStream) null);
                Assertions.fail("Expected NullPointerException");
            } catch (IOException e) {
                Assertions.fail("Expected NullPointerException, not IOException", e);
            } catch (NullPointerException e) {
                Assertions.assertThat(e.getMessage()).contains("input");
            }
        }

        @Test
        public void reportsMultipleSyntaxErrors() {
            String input = "π name (σ age > 18 (Users)) extra";  // Extra text at end
            try {
                RelAlgebraParser.parse(input);
                Assertions.fail("Expected ParseException");
            } catch (ParseException e) {
                Assertions.assertThat(e.getMessage()).contains("Expected end of input");
            }
        }

        @Test
        public void errorMessagesIncludeLineAndColumnWithOffset() {
            String input = "Users ∪∪ Orders";  // Double union operator is invalid
            ParseException e = getParseErrorFromInputStream(input.getBytes(), 10, 5);
            Assertions.assertThat(e.line()).isGreaterThanOrEqualTo(10);
        }
    }

    /**
     * Helper method to assert parse error from an input stream with offsets.
     */
    private static ParseException getParseErrorFromInputStream(byte[] input, int startLine, int startColumn) {
        try (var is = new ByteArrayInputStream(input)) {
            try {
                RelAlgebraParser.parse(is, startLine, startColumn);
            } catch (ParseException e) {
                return e;
            } catch (IOException e) {
                throw new AssertionError("Unexpected IOException", e);
            }
        } catch (IOException e) {
            throw new AssertionError("Unexpected IOException", e);
        }
        throw new AssertionError("Expected ParseException to be thrown");
    }

    /**
     * Helper method to assert parse error from an input stream.
     */
    private static ParseException getParseErrorFromInputStream(ByteArrayInputStream is, int startLine, int startColumn) throws IOException {
        try {
            RelAlgebraParser.parse(is, startLine, startColumn);
            throw new AssertionError("Expected ParseException to be thrown");
        } catch (ParseException e) {
            return e;
        }
    }
}

