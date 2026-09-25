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
import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.internal.*;

import java.util.List;

/**
 * Tests for the adjacency-to-forest nesting operator
 * {@code TREE key BY parentKey [ORDER cols] AS children (R)} (ADR-0019, issue #324).
 */
final class TreeParserTest extends ParserTestSupport {

    @Test
    void parsesWithOrder() {
        assertParsesTo("TREE node_id BY parent_id ORDER ordinal AS children (Plan)",
                tree(
                        "node_id",
                        "parent_id",
                        List.of(asc("ordinal")),
                        "children",
                        rel("Plan")));
    }

    @Test
    void parsesWithoutOrder() {
        assertParsesTo("TREE id BY manager_id AS reports (Employees)",
                tree(
                        "id",
                        "manager_id",
                        List.of(),
                        "reports",
                        rel("Employees")));
    }

    @Test
    void parsesDescendingMultiKeyOrder() {
        assertParsesTo("TREE id BY parent ORDER rank DESC, name AS kids (Nodes)",
                tree(
                        "id",
                        "parent",
                        List.of(desc("rank"),
                                asc("name")),
                        "kids",
                        rel("Nodes")));
    }

    @Test
    void parsesAsciiLowercaseKeywords() {
        assertParsesTo("tree id by pid order ord as children (T)",
                tree(
                        "id",
                        "pid",
                        List.of(asc("ord")),
                        "children",
                        rel("T")));
    }

    @Test
    void parsesOverNestedOperator() {
        assertParsesTo("TREE id BY pid AS kids (σ depth > 0 (Plan))",
                tree(
                        "id",
                        "pid",
                        List.of(),
                        "kids",
                        select(
                                cmp(
                                        attr("depth"),
                                        ComparisonOperator.GREATER,
                                        num("0")),
                                rel("Plan"))));
    }

    @Test
    void prettyPrints() {
        assertPrettyPrints(
                tree(
                        "node_id",
                        "parent_id",
                        List.of(asc("ordinal")),
                        "children",
                        rel("Plan")),
                "TREE node_id BY parent_id ORDER ordinal AS children (Plan)");
    }

    @Test
    void rejectsMissingBy() {
        assertParseError("TREE node_id parent_id AS children (Plan)").hasMessageContaining("BY");
    }

    @Test
    void rejectsMissingAs() {
        assertParseError("TREE node_id BY parent_id (Plan)").hasMessageContaining("AS");
    }

    @Test
    void rejectsMissingChildrenColumn() {
        assertParseError("TREE node_id BY parent_id AS (Plan)").hasMessageContaining("children");
    }

    @Test
    void rejectsMissingOpeningParen() {
        assertParseError("TREE node_id BY parent_id AS children Plan").hasMessageContaining("(");
    }
}
