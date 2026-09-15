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
 * Tests for the connected-components operator {@code CLUSTER from, to AS label (R)}
 * (issue #216).
 */
final class ClusterParserTest extends ParserTestSupport {

    @Test
    void parsesBasicCluster() {
        assertParsesTo("CLUSTER src, dst AS cid (Edges)",
                cluster("src", "dst", "cid", rel("Edges")));
    }

    @Test
    void parsesAsciiLowercaseKeywords() {
        assertParsesTo("cluster src, dst as cid (Edges)",
                cluster("src", "dst", "cid", rel("Edges")));
    }

    @Test
    void parsesClusterOverNestedOperator() {
        assertParsesTo("CLUSTER a, b AS comp (δ (Edges))",
                cluster("a", "b", "comp", distinct(rel("Edges"))));
    }

    @Test
    void clusterBindsTighterThanJoin() {
        // A ⋈ CLUSTER … parses as A ⋈ (CLUSTER …)
        assertParsesTo("A ⋈ CLUSTER src, dst AS cid (Edges)",
                naturalJoin(
                        rel("A"),
                        cluster("src", "dst", "cid", rel("Edges"))));
    }

    @Test
    void prettyPrintsCluster() {
        assertPrettyPrints(
                cluster("src", "dst", "cid", rel("Edges")),
                "CLUSTER src, dst AS cid (Edges)");
    }

    @Test
    void rejectsMissingComma() {
        assertParseError("CLUSTER src dst AS cid (Edges)").hasMessageContaining(",");
    }

    @Test
    void rejectsMissingAs() {
        assertParseError("CLUSTER src, dst cid (Edges)").hasMessageContaining("AS");
    }

    @Test
    void rejectsMissingLabel() {
        assertParseError("CLUSTER src, dst AS (Edges)").hasMessageContaining("label");
    }

    @Test
    void rejectsMissingColumns() {
        assertParseError("CLUSTER AS cid (Edges)").hasMessageContaining("from");
    }

    @Test
    void rejectsMissingOpeningParen() {
        assertParseError("CLUSTER src, dst AS cid Edges").hasMessageContaining("(");
    }
}
