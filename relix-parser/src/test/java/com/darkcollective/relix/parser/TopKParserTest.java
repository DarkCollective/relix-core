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
import java.util.Optional;

import com.darkcollective.relix.ast.RelNode;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the top-k-per-group operator (TOP):
 * {@code TOP count [, offset+count] sortspecs PER keys (input)}.
 */
final class TopKParserTest extends ParserTestSupport {

    @Test
    void parsesBasic() {
        assertParsesTo("TOP 3 amount DESC PER customer_id (Orders)",
                topK(
                        List.of("customer_id"),
                        List.of(desc("amount")),
                        3,
                        rel("Orders")));
    }

    @Test
    void defaultsToAscending() {
        assertParsesTo("TOP 5 amount PER customer_id (Orders)",
                topK(
                        List.of("customer_id"),
                        List.of(asc("amount")),
                        5,
                        rel("Orders")));
    }

    @Test
    void parsesOffsetAndCount() {
        assertParsesTo("TOP 5, 3 amount DESC PER customer_id (Orders)",
                topK(List.of("customer_id"), List.of(desc("amount")),
                        Optional.of(5L), 3, rel("Orders")));
    }

    @Test
    void parsesMultipleKeysAndSortSpecs() {
        assertParsesTo("TOP 2 amount DESC, created ASC PER region, dept (Sales)",
                topK(
                        List.of("region", "dept"),
                        List.of(desc("amount"), asc("created")),
                        2,
                        rel("Sales")));
    }

    @Test
    void nestsInsideAnotherOperator() {
        assertParsesTo("δ (TOP 1 score DESC PER team (Players))",
                distinct(
                        topK(
                        List.of("team"),
                        List.of(desc("score")),
                        1,
                        rel("Players"))));
    }

    @Test
    void capturesComponents() {
        TopKNode node = (TopKNode) parse("TOP 5, 3 amount DESC PER customer_id (Orders)");
        assertThat(node.count()).isEqualTo(3L);
        assertThat(node.offset()).contains(5L);
        assertThat(node.groupingAttributes()).containsExactly("customer_id");
        // Compare by column + direction — the parsed spec's operand carries a source
        // location, so a whole-object equals against an UNKNOWN-location expected fails.
        assertThat(node.sortSpecs())
                .extracting(s -> s.columnName().orElseThrow() + " " + s.direction())
                .containsExactly("amount " + SortDirection.DESC);
    }

    // ─── Pretty printing ────────────────────────────────────────────────────

    @Test
    void prettyPrintsBasic() {
        assertPrettyPrints(
                topK(
                        List.of("customer_id"),
                        List.of(desc("amount")),
                        3,
                        rel("Orders")),
                "TOP 3 amount DESC PER customer_id (Orders)");
    }

    @Test
    void prettyPrintRoundTrips() {
        RelNode original = topK(List.of("region", "dept"),
                List.of(desc("amount"), asc("created")),
                Optional.of(5L), 3, rel("Sales"));
        assertParsesTo(original.prettyPrint(), original);
    }

    // ─── Errors ─────────────────────────────────────────────────────────────

    @Test
    void failsWithoutCount() {
        assertParseError("TOP amount DESC PER c (R)").hasMessageContaining("Expected a count");
    }

    @Test
    void perIsValidAsSortAttributeName() {
        // 'PER' is a word-shaped keyword and is valid as a sort-attribute name
        assertParsesTo("TOP 3 PER ASC PER c (R)",
                topK(
                        List.of("c"),
                        List.of(asc("PER")),
                        3,
                        rel("R")));
    }

    @Test
    void failsWithoutSortSpec() {
        // `(R)` after the count now parses as a parenthesized sort-key expression,
        // so the parser next expects the PER grouping keys and hits end of input.
        assertParseError("TOP 3 (R)").hasMessageContaining("Expected 'PER'");
    }

    /**
     * No {@code PER} is not a missing clause — it is the global group, the top N overall.
     *
     * <p>This is the form {@code LIM-003} produces when it fuses a {@code λ} over a
     * {@code τ}, and the form the printer emits for it, so a grammar that refused it made
     * the optimizer's own output unparseable: {@code --optimize} printed a rewritten query
     * that could not be run or pasted back. Both node corpora hold a {@code TOP} with
     * grouping keys, so the round-trip guards had never been shown this shape.
     */
    @Test
    void parsesWithoutPer() {
        assertParsesTo("TOP 3 amount DESC (R)",
                topK(List.of(), List.of(desc("amount")), Optional.empty(), 3, rel("R")));
    }

    @Test
    void parsesWithoutPerAndWithAnOffset() {
        assertParsesTo("TOP 5, 3 amount DESC (R)",
                topK(List.of(), List.of(desc("amount")), Optional.of(5L), 3, rel("R")));
    }

    @Test
    void printsWithoutPerAndReadsBack() {
        RelNode keyless =
                topK(List.of(), List.of(desc("amount")), Optional.empty(), 3, rel("R"));
        assertParsesTo(keyless.prettyPrint(), keyless);
    }

    @Test
    void saysWhatItWantedWhenNeitherFollowsTheSortKeys() {
        assertParseError("TOP 3 amount DESC R")
                .hasMessageContaining("Expected 'PER' or '('");
    }

    @Test
    void failsWithoutKey() {
        assertParseError("TOP 3 amount DESC PER (R)").hasMessageContaining("grouping key");
    }
}
