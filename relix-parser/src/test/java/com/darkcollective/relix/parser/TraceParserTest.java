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

/**
 * Tests for the optimal-path extraction operator
 * {@code TRACE from, to VIA weight MINIMIZE|MAXIMIZE AS path (R)} (issue #215).
 */
final class TraceParserTest extends ParserTestSupport {

    @Test
    void parsesMinimizeTrace() {
        assertParsesTo("TRACE origin, dest VIA cost MINIMIZE AS route (Flights)",
                trace(
                        "origin",
                        "dest",
                        "cost",
                        ObjectiveSense.MINIMIZE,
                        "route",
                        rel("Flights")));
    }

    @Test
    void parsesMaximizeTrace() {
        assertParsesTo("TRACE src, dst VIA score MAXIMIZE AS path (Graph)",
                trace(
                        "src",
                        "dst",
                        "score",
                        ObjectiveSense.MAXIMIZE,
                        "path",
                        rel("Graph")));
    }

    @Test
    void parsesAsciiLowercaseKeywords() {
        assertParsesTo("trace origin, dest via cost minimize as route (Flights)",
                trace(
                        "origin",
                        "dest",
                        "cost",
                        ObjectiveSense.MINIMIZE,
                        "route",
                        rel("Flights")));
    }

    @Test
    void parsesTraceOverNestedOperator() {
        assertParsesTo("TRACE a, b VIA w MINIMIZE AS p (δ (Edges))",
                trace(
                        "a",
                        "b",
                        "w",
                        ObjectiveSense.MINIMIZE,
                        "p",
                        distinct(rel("Edges"))));
    }

    @Test
    void traceBindsTighterThanJoin() {
        assertParsesTo("A ⋈ TRACE src, dst VIA cost MINIMIZE AS p (Edges)",
                naturalJoin(
                        rel("A"),
                        trace(
                                "src",
                                "dst",
                                "cost",
                                ObjectiveSense.MINIMIZE,
                                "p",
                                rel("Edges"))));
    }

    @Test
    void prettyPrintsMinimize() {
        assertPrettyPrints(
                trace(
                        "origin",
                        "dest",
                        "cost",
                        ObjectiveSense.MINIMIZE,
                        "route",
                        rel("Flights")),
                "TRACE origin, dest VIA cost MINIMIZE AS route (Flights)");
    }

    @Test
    void prettyPrintsMaximize() {
        assertPrettyPrints(
                trace(
                        "src",
                        "dst",
                        "score",
                        ObjectiveSense.MAXIMIZE,
                        "path",
                        rel("Graph")),
                "TRACE src, dst VIA score MAXIMIZE AS path (Graph)");
    }

    @Test
    void rejectsMissingComma() {
        assertParseError("TRACE src dst VIA cost MINIMIZE AS p (Edges)").hasMessageContaining(",");
    }

    @Test
    void rejectsMissingVia() {
        assertParseError("TRACE src, dst cost MINIMIZE AS p (Edges)").hasMessageContaining("VIA");
    }

    @Test
    void rejectsMissingSense() {
        assertParseError("TRACE src, dst VIA cost AS p (Edges)").hasMessageContaining("MINIMIZE");
    }

    @Test
    void rejectsMissingAs() {
        assertParseError("TRACE src, dst VIA cost MINIMIZE p (Edges)").hasMessageContaining("AS");
    }

    @Test
    void rejectsMissingPath() {
        assertParseError("TRACE src, dst VIA cost MINIMIZE AS (Edges)").hasMessageContaining("path");
    }

    @Test
    void rejectsMissingOpeningParen() {
        assertParseError("TRACE src, dst VIA cost MINIMIZE AS p Edges").hasMessageContaining("(");
    }
}
