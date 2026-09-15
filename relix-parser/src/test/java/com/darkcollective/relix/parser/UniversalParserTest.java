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

import java.util.List;

/**
 * Tests for the group-wise universal-quantification operator (∀ / FORALL):
 * {@code ∀ keys : predicate (input)}.  Covers basic parsing, multiple keys,
 * the ASCII keyword, nesting, pretty printing, and error cases.
 */
final class UniversalParserTest extends ParserTestSupport {

    // ─── Basic ──────────────────────────────────────────────────────────────

    @Test
    void parsesBasicUniversal() {
        assertParsesTo("∀ customer_id : status = \"completed\" (Orders)",
                universal(
                        List.of("customer_id"),
                        cmp(attr("status"), ComparisonOperator.EQUAL, str("completed")),
                        rel("Orders")));
    }

    @Test
    void asciiKeywordMatchesUnicode() {
        assertParsesTo("FORALL customer_id : status = \"completed\" (Orders)",
                parse("∀ customer_id : status = \"completed\" (Orders)"));
    }

    @Test
    void parsesMultipleGroupingKeys() {
        assertParsesTo("∀ region, dept : active = true (Staff)",
                universal(
                        List.of("region", "dept"),
                        cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                        rel("Staff")));
    }

    @Test
    void parsesCompoundPredicate() {
        assertParsesTo("∀ cid : amount > 0 ∧ status = \"ok\" (Orders)",
                universal(
                        List.of("cid"),
                        and(
                                cmp(attr("amount"), ComparisonOperator.GREATER, num("0")),
                                cmp(attr("status"), ComparisonOperator.EQUAL, str("ok"))),
                        rel("Orders")));
    }

    @Test
    void emptyKeyListParsesForSemanticRejection() {
        // The parser accepts an empty key list; semantic analysis rejects it.
        assertParsesTo("∀ : x > 0 (R)",
                universal(
                        List.of(),
                        cmp(attr("x"), ComparisonOperator.GREATER, num("0")),
                        rel("R")));
    }

    // ─── Nesting ────────────────────────────────────────────────────────────

    @Test
    void nestsInsideAnotherOperator() {
        assertParsesTo("δ (∀ a : x > 0 (R))",
                distinct(
                        universal(
                                List.of("a"),
                                cmp(attr("x"), ComparisonOperator.GREATER, num("0")),
                                rel("R"))));
    }

    @Test
    void takesAParenthesizedComplexInput() {
        assertParsesTo("∀ a : x > 0 (R ⋈ S)",
                universal(
                        List.of("a"),
                        cmp(attr("x"), ComparisonOperator.GREATER, num("0")),
                        naturalJoin(rel("R"), rel("S"))));
    }

    // ─── Pretty printing ────────────────────────────────────────────────────

    @Test
    void prettyPrintsBasic() {
        RelNode node = universal(
                List.of("customer_id"),
                cmp(attr("status"), ComparisonOperator.EQUAL, str("completed")),
                rel("Orders"));
        assertPrettyPrints(node, "∀ customer_id : status = \"completed\" (Orders)");
    }

    @Test
    void prettyPrintRoundTrips() {
        RelNode original = universal(
                List.of("region", "dept"),
                cmp(attr("active"), ComparisonOperator.EQUAL, bool(true)),
                rel("Staff"));
        assertParsesTo(original.prettyPrint(), original);
    }

    // ─── Errors ─────────────────────────────────────────────────────────────

    @Test
    void failsWithoutColon() {
        assertParseError("∀ a b : x > 0 (R)").hasMessageContaining("Expected ':'");
    }

    @Test
    void failsWithoutInput() {
        assertParseError("∀ a : x > 0").hasMessageContaining("Expected '('");
    }

    @Test
    void failsOnTrailingCommaKey() {
        assertParseError("∀ a, : x > 0 (R)").hasMessageContaining("Expected grouping key");
    }
}
