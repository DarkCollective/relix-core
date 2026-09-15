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


final class OperatorPrecedenceParserTest extends ParserTestSupport {

    @Test
    public void parsesProduct() {
        assertParsesTo("A × B",
                product(rel("A"), rel("B")));
    }

    @Test
    public void parsesUnion() {
        assertParsesTo("A ∪ B",
                union(rel("A"), rel("B")));
    }

    @Test
    public void parsesDifference() {
        assertParsesTo("A − B",
                difference(rel("A"), rel("B")));
    }

    @Test
    public void parsesIntersection() {
        assertParsesTo("A ∩ B",
                intersection(rel("A"), rel("B")));
    }

    @Test
    public void parsesDivision() {
        assertParsesTo("A ÷ B",
                division(rel("A"), rel("B")));
    }

    @Test
    public void joinBindsTighterThanUnion() {
        assertParsesTo("A ∪ B ⋈ C",
                union(rel("A"), naturalJoin(rel("B"), rel("C"))));
    }

    @Test
    public void intersectionBindsTighterThanUnion() {
        assertParsesTo("A ∪ B ∩ C",
                union(rel("A"), intersection(rel("B"), rel("C"))));
    }

    @Test
    public void binaryOperatorsAreLeftAssociative() {
        assertParsesTo("A ∪ B ∪ C",
                union(union(rel("A"), rel("B")), rel("C")));
    }

    @Test
    public void unaryBindsTighterThanBinary() {
        assertParsesTo("π id (Users) ∪ Admins",
                union(project(List.of(projected(attr("id"))), rel("Users")), rel("Admins")));
    }

    @Test
    public void outerJoinBindsTighterThanUnion() {
        assertParsesTo("A ∪ B ⟕ B.id = C.id C",
                union(rel("A"), leftJoin(rel("B"), rel("C"), cmp(attr("B.id"), ComparisonOperator.EQUAL, attr("C.id")))));
    }

    @Test
    public void outerJoinIsLeftAssociative() {
        assertParsesTo("A ⟕ A.id = B.id B ⟖ B.id = C.id C",
                rightJoin(leftJoin(rel("A"), rel("B"), cmp(attr("A.id"), ComparisonOperator.EQUAL, attr("B.id"))), rel("C"), cmp(attr("B.id"), ComparisonOperator.EQUAL, attr("C.id"))));
    }

    @Test
    public void differenceIsLeftAssociative() {
        assertParsesTo("A − B − C",
                difference(difference(rel("A"), rel("B")), rel("C")));
    }

    @Test
    public void unionAndDifferenceAreLeftAssociativeTogether() {
        assertParsesTo("A ∪ B − C",
                difference(union(rel("A"), rel("B")), rel("C")));
    }

    @Test
    public void productBindsTighterThanDifference() {
        assertParsesTo("A − B × C",
                difference(rel("A"), product(rel("B"), rel("C"))));
    }

    @Test
    public void divisionIsLeftAssociative() {
        assertParsesTo("A ÷ B ÷ C",
                division(division(rel("A"), rel("B")), rel("C")));
    }

    @Test
    public void productAndDivisionAreLeftAssociativeTogether() {
        assertParsesTo("A × B ÷ C",
                division(product(rel("A"), rel("B")), rel("C")));
    }

    @Test
    public void parenthesesOverrideRelationalPrecedence() {
        assertParsesTo("(A ∪ B) ⋈ C",
                naturalJoin(union(rel("A"), rel("B")), rel("C")));
    }

    @Test
    public void parsesUnionAll() {
        assertParsesTo("A ⊎ B",
                unionAll(rel("A"), rel("B")));
    }

    @Test
    public void joinBindsTighterThanUnionAll() {
        assertParsesTo("A ⊎ B ⋈ C",
                unionAll(rel("A"), naturalJoin(rel("B"), rel("C"))));
    }

    @Test
    public void unionAllIsLeftAssociative() {
        assertParsesTo("A ⊎ B ⊎ C",
                unionAll(unionAll(rel("A"), rel("B")), rel("C")));
    }

    @Test
    public void unionAllSharesPrecedenceWithUnion() {
        assertParsesTo("A ∪ B ⊎ C",
                unionAll(union(rel("A"), rel("B")), rel("C")));
    }

    @Test
    public void unionAllSharesPrecedenceWithDifference() {
        assertParsesTo("A ⊎ B − C",
                difference(unionAll(rel("A"), rel("B")), rel("C")));
    }

    @Test
    public void distinctBindsTighterThanBinaryOperators() {
        assertParsesTo("δ (A) ∪ B",
                union(distinct(rel("A")), rel("B")));
    }
}
