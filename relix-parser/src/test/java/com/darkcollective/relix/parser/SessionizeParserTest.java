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

import java.time.Duration;
import java.util.List;
import com.darkcollective.relix.ast.Expr;

/**
 * Tests for the gap-and-island / sessionization operator
 * {@code SESSIONIZE col GAP threshold [PER keys] AS session (R)} (issue #15).
 */
final class SessionizeParserTest extends ParserTestSupport {

    @Test
    void parsesWithPartition() {
        assertParsesTo("SESSIONIZE ts GAP DURATION 'PT30M' PER user_id AS session (Events)",
                sessionize(
                        "ts",
                        Expr.duration(Duration.ofMinutes(30)),
                        List.of("user_id"),
                        "session",
                        rel("Events")));
    }

    @Test
    void parsesWithoutPartition() {
        assertParsesTo("SESSIONIZE seq GAP 5 AS run (Readings)",
                sessionize(
                        "seq",
                        num("5"),
                        List.of(),
                        "run",
                        rel("Readings")));
    }

    @Test
    void parsesMultiKeyPartition() {
        assertParsesTo("SESSIONIZE ts GAP 60 PER host, region AS s (Metrics)",
                sessionize(
                        "ts",
                        num("60"),
                        List.of("host", "region"),
                        "s",
                        rel("Metrics")));
    }

    @Test
    void parsesAsciiLowercaseKeywords() {
        assertParsesTo("sessionize ts gap 5 per u as session (Events)",
                sessionize(
                        "ts",
                        num("5"),
                        List.of("u"),
                        "session",
                        rel("Events")));
    }

    @Test
    void parsesOverNestedOperator() {
        assertParsesTo("SESSIONIZE ts GAP 5 AS s (δ (Events))",
                sessionize(
                        "ts",
                        num("5"),
                        List.of(),
                        "s",
                        distinct(rel("Events"))));
    }

    @Test
    void bindsTighterThanJoin() {
        assertParsesTo("A ⋈ SESSIONIZE ts GAP 5 AS s (Events)",
                naturalJoin(
                        rel("A"),
                        sessionize(
                                "ts",
                                num("5"),
                                List.of(),
                                "s",
                                rel("Events"))));
    }

    @Test
    void prettyPrints() {
        assertPrettyPrints(
                sessionize(
                        "ts",
                        Expr.duration(Duration.ofMinutes(30)),
                        List.of("user_id"),
                        "session",
                        rel("Events")),
                "SESSIONIZE ts GAP DURATION 'PT30M' PER user_id AS session (Events)");
    }

    @Test
    void rejectsMissingGap() {
        assertParseError("SESSIONIZE ts 5 AS s (Events)").hasMessageContaining("GAP");
    }

    @Test
    void rejectsMissingAs() {
        assertParseError("SESSIONIZE ts GAP 5 (Events)").hasMessageContaining("AS");
    }

    @Test
    void rejectsMissingSessionColumn() {
        assertParseError("SESSIONIZE ts GAP 5 AS (Events)").hasMessageContaining("session");
    }

    @Test
    void rejectsNonNameOrderColumn() {
        assertParseError("SESSIONIZE 5 GAP 3 AS s (Events)").hasMessageContaining("order");
    }

    @Test
    void rejectsMissingOpeningParen() {
        assertParseError("SESSIONIZE ts GAP 5 AS s Events").hasMessageContaining("(");
    }
}
