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
 * Tests for the bounded variable-length path operator
 * {@code PATH from, to HOPS m TO n AS depth (R)} (issue #17).
 */
final class PathParserTest extends ParserTestSupport {

    @Test
    void parsesRangeForm() {
        assertParsesTo("PATH src, dst HOPS 1 TO 3 AS depth (Edges)",
                path("src", "dst", 1, 3, "depth", rel("Edges")));
    }

    @Test
    void parsesSingleBoundFormAsOneToN() {
        // HOPS n is shorthand for HOPS 1 TO n
        assertParsesTo("PATH src, dst HOPS 3 AS depth (Edges)",
                path("src", "dst", 1, 3, "depth", rel("Edges")));
    }

    @Test
    void parsesAsciiLowercaseKeywords() {
        assertParsesTo("path src, dst hops 2 to 4 as depth (Edges)",
                path("src", "dst", 2, 4, "depth", rel("Edges")));
    }

    @Test
    void parsesPathOverNestedOperator() {
        assertParsesTo("PATH a, b HOPS 1 TO 2 AS d (δ (Edges))",
                path("a", "b", 1, 2, "d", distinct(rel("Edges"))));
    }

    @Test
    void pathBindsTighterThanJoin() {
        // A ⋈ PATH … parses as A ⋈ (PATH …)
        assertParsesTo("A ⋈ PATH src, dst HOPS 1 TO 3 AS depth (Edges)",
                naturalJoin(
                        rel("A"),
                        path("src", "dst", 1, 3, "depth", rel("Edges"))));
    }

    @Test
    void prettyPrintsCanonicalRangeForm() {
        assertPrettyPrints(
                path("src", "dst", 1, 3, "depth", rel("Edges")),
                "PATH src, dst HOPS 1 TO 3 AS depth (Edges)");
    }

    @Test
    void prettyPrintsSingleBoundAsRange() {
        // A single-bound parse becomes the canonical 1 TO n form on round-trip.
        assertPrettyPrints(
                parse("PATH src, dst HOPS 3 AS depth (Edges)"),
                "PATH src, dst HOPS 1 TO 3 AS depth (Edges)");
    }

    @Test
    void rejectsMissingComma() {
        assertParseError("PATH src dst HOPS 1 TO 3 AS depth (Edges)").hasMessageContaining(",");
    }

    @Test
    void rejectsMissingHops() {
        assertParseError("PATH src, dst 1 TO 3 AS depth (Edges)").hasMessageContaining("HOPS");
    }

    @Test
    void rejectsNonIntegerHopCount() {
        assertParseError("PATH src, dst HOPS 1.5 AS depth (Edges)").hasMessageContaining("integer");
    }

    @Test
    void rejectsZeroMinimum() {
        assertParseError("PATH src, dst HOPS 0 TO 3 AS depth (Edges)").hasMessageContaining("≥ 1");
    }

    @Test
    void rejectsInvertedRange() {
        assertParseError("PATH src, dst HOPS 5 TO 2 AS depth (Edges)").hasMessageContaining("≥ minimum");
    }

    @Test
    void rejectsMissingAs() {
        assertParseError("PATH src, dst HOPS 1 TO 3 depth (Edges)").hasMessageContaining("AS");
    }

    @Test
    void rejectsMissingDepthColumn() {
        assertParseError("PATH src, dst HOPS 1 TO 3 AS (Edges)").hasMessageContaining("hop-distance");
    }

    @Test
    void rejectsMissingOpeningParen() {
        assertParseError("PATH src, dst HOPS 1 TO 3 AS depth Edges").hasMessageContaining("(");
    }
}
