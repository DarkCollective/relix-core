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

import com.darkcollective.relix.ast.internal.AstEquivalence;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.internal.Predicates;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.darkcollective.relix.ast.AstAssertions.assertThat;

/**
 * {@link AstEquivalence} over fragments built by the <em>parser</em>.
 *
 * <p>This test exists because of the trap the structural-equality issue was filed to
 * record: <strong>every test-convenience constructor in {@code relix-ast} defaults to
 * {@link SourceLocation#UNKNOWN}</strong>, so an implementation that merely delegated
 * to record equality would pass a unit-test suite built from {@code AstBuilders} and
 * then find nothing on real input, where each fragment carries the position it was
 * written at. Only fragments that came through the parser are evidence, and the
 * parser lives in this module rather than in {@code relix-ast} — hence the split.
 *
 * <p>Each case therefore asserts <em>both</em> halves: that record equality really
 * does separate the two fragments (so the test is not vacuous), and that
 * {@code AstEquivalence} unifies them.
 */
@DisplayName("AstEquivalence (parsed fixtures)")
final class AstEquivalenceParsedTest {

    /** The predicate of a parsed `σ … (R)`, with its real source location intact. */
    private static Predicate predicateOf(String query) {
        RelNode node = RelAlgebraParser.parse(query);
        assertThat(node).isNode(SelectionNode.class);
        return ((SelectionNode) node).predicate();
    }

    @Test
    @DisplayName("the fixtures really do carry distinct locations — this test is not vacuous")
    void fixturesCarryRealLocations() {
        Predicate first = predicateOf("σ x > 5 (R)");
        Predicate second = predicateOf("   σ x > 5 (R)");
        assertThat(first.location()).isNotEqualTo(SourceLocation.UNKNOWN);
        assertThat(first.location()).isNotEqualTo(second.location());
    }

    @Test
    @DisplayName("the same predicate parsed at two positions: unequal as records, equivalent here")
    void samePredicateDifferentPositions() {
        Predicate first = predicateOf("σ x > 5 (R)");
        Predicate second = predicateOf("\n\n\n        σ x > 5 (R)");
        assertThat(first).isNotEqualTo(second);
        assertThat(AstEquivalence.equivalent(first, second)).isTrue();
    }

    @Test
    @DisplayName("the same tree parsed at two positions is equivalent")
    void sameTreeDifferentPositions() {
        RelNode first = RelAlgebraParser.parse("π a, b (σ x > 5 (R))");
        RelNode second = RelAlgebraParser.parse("\n\n   π a, b (σ x > 5 (R))");
        assertThat(first).isNotEqualTo(second);
        assertThat(AstEquivalence.equivalent(first, second)).isTrue();
        assertThat(AstEquivalence.digest(first)).isEqualTo(AstEquivalence.digest(second));
    }

    @Test
    @DisplayName("a genuinely different tree is still not equivalent")
    void differentTreesStayDifferent() {
        RelNode first = RelAlgebraParser.parse("σ x > 5 (R)");
        RelNode second = RelAlgebraParser.parse("σ x > 6 (R)");
        assertThat(AstEquivalence.equivalent(first, second)).isFalse();
    }

    @Test
    @DisplayName("duplicate conjuncts written at two positions are recognised as duplicates")
    void duplicateConjunctsAreRecognised() {
        // The shape `p ∧ p → p` needs: split into conjuncts, then ask which say the
        // same thing. Both conjuncts here parse at different columns of the same line.
        List<Predicate> conjuncts = Predicates.conjuncts(predicateOf("σ x > 5 ∧ x > 5 (R)"));
        assertThat(conjuncts).hasSize(2);
        assertThat(conjuncts.get(0)).isNotEqualTo(conjuncts.get(1));
        assertThat(AstEquivalence.equivalent(conjuncts.get(0), conjuncts.get(1))).isTrue();
    }

    @Test
    @DisplayName("differently spelled numeric bounds parse to equivalent operands")
    void numericSpellingIsNormalised() {
        Predicate five = predicateOf("σ x > 5 (R)");
        Predicate fivePointZero = predicateOf("σ x > 5.0 (R)");
        assertThat(five).isNotEqualTo(fivePointZero);
        assertThat(AstEquivalence.equivalent(five, fivePointZero)).isTrue();
    }

    @Test
    @DisplayName("a qualifier survives parsing and keeps the two references apart")
    void qualifierIsSignificantOnParsedInput() {
        Predicate qualified = predicateOf("σ A.x > 5 (R)");
        Predicate bare = predicateOf("σ x > 5 (R)");
        assertThat(AstEquivalence.equivalent(qualified, bare)).isFalse();
    }

    @Test
    @DisplayName("ASCII and Unicode spellings of the same operator parse to equivalent trees")
    void asciiAndUnicodeAgree() {
        // The two spellings parse to an identical AST by design, so this is really a
        // check that nothing in the comparison reads back the surface syntax.
        RelNode unicode = RelAlgebraParser.parse("σ x ≠ 5 ∧ y > 1 (R)");
        RelNode ascii = RelAlgebraParser.parse("SELECT x != 5 AND y > 1 (R)");
        assertThat(AstEquivalence.equivalent(unicode, ascii)).isTrue();
    }
}
