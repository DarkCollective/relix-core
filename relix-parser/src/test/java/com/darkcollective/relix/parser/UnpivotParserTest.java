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
 * Parser tests for the columns-to-rows operator
 * {@code UNPIVOT (col1, col2, ...) AS (nameCol, valueCol) (R)} (issue #16).
 */
final class UnpivotParserTest extends ParserTestSupport {

    @Test
    void parsesBasicUnpivot() {
        assertParsesTo(
                "UNPIVOT (q1, q2, q3, q4) AS (quarter, revenue) (Sales)",
                unpivot(
                        List.of("q1", "q2", "q3", "q4"),
                        "quarter",
                        "revenue",
                        rel("Sales")));
    }

    @Test
    void parsesSingleColumn() {
        assertParsesTo(
                "UNPIVOT (val) AS (name, value) (R)",
                unpivot(List.of("val"), "name", "value", rel("R")));
    }

    @Test
    void parsesLowercaseKeywords() {
        assertParsesTo(
                "unpivot (a, b) as (col, val) (R)",
                unpivot(List.of("a", "b"), "col", "val", rel("R")));
    }

    @Test
    void parsesOverNestedOperator() {
        assertParsesTo(
                "UNPIVOT (x, y) AS (axis, value) (σ active = true (Metrics))",
                unpivot(
                        List.of("x", "y"),
                        "axis",
                        "value",
                        select(
                                cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                                rel("Metrics"))));
    }

    @Test
    void bindsTighterThanJoin() {
        assertParsesTo(
                "A ⋈ UNPIVOT (x, y) AS (axis, val) (B)",
                naturalJoin(
                        rel("A"),
                        unpivot(List.of("x", "y"), "axis", "val", rel("B"))));
    }

    @Test
    void prettyPrints() {
        assertPrettyPrints(
                unpivot(List.of("q1", "q2", "q3"), "quarter", "revenue", rel("Sales")),
                "UNPIVOT (q1, q2, q3) AS (quarter, revenue) (Sales)");
    }

    @Test
    void rejectsMissingOpenParen() {
        assertParseError("UNPIVOT q1, q2 AS (name, val) (R)").hasMessageContaining("(");
    }

    @Test
    void rejectsMissingAs() {
        assertParseError("UNPIVOT (q1, q2) (name, val) (R)").hasMessageContaining("AS");
    }

    @Test
    void rejectsMissingAsParen() {
        assertParseError("UNPIVOT (q1, q2) AS name, val (R)").hasMessageContaining("(");
    }

    @Test
    void rejectsMissingCloseParen() {
        assertParseError("UNPIVOT (q1, q2 AS (name, val) (R)").hasMessageContaining(")");
    }

    @Test
    void rejectsMissingAsCloseParen() {
        assertParseError("UNPIVOT (q1, q2) AS (name, val (R)").hasMessageContaining(")");
    }

    @Test
    void rejectsMissingInputParen() {
        assertParseError("UNPIVOT (q1, q2) AS (name, val) R").hasMessageContaining("(");
    }
}
