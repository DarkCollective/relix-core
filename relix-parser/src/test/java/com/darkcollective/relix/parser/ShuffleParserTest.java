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

import java.util.Optional;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the random-permutation operator: {@code SHUFFLE (input)} and
 * {@code SHUFFLE SEED k (input)} — keyword-only, sharing the {@code SEED} clause
 * with the sampling operators.
 */
final class ShuffleParserTest extends ParserTestSupport {

    @Test
    void parsesBasicShuffle() {
        assertParsesTo("SHUFFLE (Deck)", shuffle(rel("Deck")));
    }

    @Test
    void parsesOverComplexInput() {
        assertParsesTo("SHUFFLE (A ⋈ B)",
                shuffle(naturalJoin(rel("A"), rel("B"))));
    }

    @Test
    void nestsInsideAnotherOperator() {
        assertParsesTo("λ 5 (SHUFFLE (R))",
                limit(5, shuffle(rel("R"))));
    }

    // ─── Seeded variants ──────────────────────────────────────────────────────

    @Test
    void parsesSeededShuffle() {
        ShuffleNode node = (ShuffleNode) parse("SHUFFLE SEED 7 (Deck)");
        assertThat(node.seed()).contains(7L);
        assertThat(((RelationNode) node.input()).name()).isEqualTo("Deck");
    }

    @Test
    void parsesSeededShuffleWithZeroSeed() {
        ShuffleNode node = (ShuffleNode) parse("SHUFFLE SEED 0 (R)");
        assertThat(node.seed()).contains(0L);
    }

    @Test
    void unseededShuffleHasEmptySeed() {
        ShuffleNode node = (ShuffleNode) parse("SHUFFLE (R)");
        assertThat(node.seed()).isEmpty();
    }

    // ─── Pretty printing ────────────────────────────────────────────────────

    @Test
    void prettyPrintsBasic() {
        assertPrettyPrints(shuffle(rel("Deck")), "SHUFFLE (Deck)");
    }

    @Test
    void prettyPrintRoundTrips() {
        RelNode original = shuffle(rel("R"));
        assertParsesTo(original.prettyPrint(), original);
    }

    @Test
    void prettyPrintsSeeded() {
        RelNode node = shuffle(Optional.of(2026L), rel("Deck"));
        assertPrettyPrints(node, "SHUFFLE SEED 2026 (Deck)");
    }

    @Test
    void prettyPrintSeededRoundTrips() {
        RelNode original = shuffle(Optional.of(42L), rel("R"));
        assertParsesTo(original.prettyPrint(), original);
    }

    // ─── Errors ─────────────────────────────────────────────────────────────

    @Test
    void failsWithoutInput() {
        assertParseError("SHUFFLE").hasMessageContaining("Expected '('");
    }

    @Test
    void failsWithNonIntegerSeed() {
        assertParseError("SHUFFLE SEED 3.14 (R)")
                .hasMessageContaining("'SEED' requires an integer value");
    }

    @Test
    void failsWithMissingSeedValue() {
        assertParseError("SHUFFLE SEED (R)")
                .hasMessageContaining("Expected an integer seed value after 'SEED'");
    }
}
