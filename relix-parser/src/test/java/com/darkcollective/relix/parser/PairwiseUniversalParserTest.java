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

final class PairwiseUniversalParserTest extends ParserTestSupport {

    @Test
    void parsesUsemiWithSimplePredicate() {
        assertParsesTo("Products USEMI p.price < c.price Competitors",
                pairwiseUniversal(
                        rel("Products"),
                        rel("Competitors"),
                        cmp(attr("p.price"), ComparisonOperator.LESS, attr("c.price"))));
    }

    @Test
    void parsesUsemiWithEqualityPredicate() {
        assertParsesTo("L USEMI L.id = R.id R",
                pairwiseUniversal(
                        rel("L"),
                        rel("R"),
                        cmp(attr("L.id"), ComparisonOperator.EQUAL, attr("R.id"))));
    }

    @Test
    void parsesUsemiWithComplexPredicate() {
        assertParsesTo("L USEMI L.x > R.x ∧ L.y = R.y R",
                pairwiseUniversal(
                        rel("L"),
                        rel("R"),
                        and(
                                cmp(attr("L.x"), ComparisonOperator.GREATER, attr("R.x")),
                                cmp(attr("L.y"), ComparisonOperator.EQUAL, attr("R.y")))));
    }

    @Test
    void parsesUsemiWithOrPredicate() {
        assertParsesTo("A USEMI A.x = B.x ∨ A.y = B.y B",
                pairwiseUniversal(
                        rel("A"),
                        rel("B"),
                        or(
                                cmp(attr("A.x"), ComparisonOperator.EQUAL, attr("B.x")),
                                cmp(attr("A.y"), ComparisonOperator.EQUAL, attr("B.y")))));
    }

    @Test
    void parsesUsemiWithParenthesizedPredicate() {
        assertParsesTo("L USEMI (L.a > R.a ∧ L.b = R.b) R",
                pairwiseUniversal(
                        rel("L"),
                        rel("R"),
                        and(
                                cmp(attr("L.a"), ComparisonOperator.GREATER, attr("R.a")),
                                cmp(attr("L.b"), ComparisonOperator.EQUAL, attr("R.b")))));
    }

    @Test
    void parsesUsemiPrecedenceBindsBeforeUnion() {
        assertParsesTo("A USEMI A.id = B.id B ∪ C",
                union(
                        pairwiseUniversal(
                                rel("A"),
                                rel("B"),
                                cmp(attr("A.id"), ComparisonOperator.EQUAL, attr("B.id"))),
                        rel("C")));
    }

    @Test
    void parsesUsemiWithProjectionInputs() {
        assertParsesTo("π id (Left) USEMI Left.id = Right.id π id (Right)",
                pairwiseUniversal(
                        project(List.of(projected(attr("id"))), rel("Left")),
                        project(List.of(projected(attr("id"))), rel("Right")),
                        cmp(attr("Left.id"), ComparisonOperator.EQUAL, attr("Right.id"))));
    }

    @Test
    void prettyPrintsUsemi() {
        RelNode node = pairwiseUniversal(
                rel("Products"),
                rel("Competitors"),
                cmp(attr("p.price"), ComparisonOperator.LESS, attr("c.price")));
        assertPrettyPrints(node, "(Products) USEMI p.price < c.price (Competitors)");
    }

    @Test
    void prettyPrintRoundTrip() {
        RelNode original = pairwiseUniversal(
                rel("A"),
                rel("B"),
                cmp(attr("A.id"), ComparisonOperator.EQUAL, attr("B.id")));
        assertParsesTo(original.prettyPrint(), original);
    }

    @Test
    void failsOnMissingPredicate() {
        assertParseError("L USEMI R")
                .hasMessageContaining("'USEMI' needs a join condition")
                .at(1, 3);
    }

    @Test
    void failsOnMissingRightOperand() {
        assertParseError("L USEMI L.id = R.id")
                .hasMessageContaining("Expected relation name");
    }
}
