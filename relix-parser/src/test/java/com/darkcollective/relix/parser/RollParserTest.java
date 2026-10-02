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
 * Tests for the endless-draw operator: {@code ROLL (faces)} and
 * {@code ROLL SEED k (faces)} — keyword-only, sharing the {@code SEED} clause with
 * the sampling operators.
 */
final class RollParserTest extends ParserTestSupport {

    @Test
    void parsesBasicRoll() {
        assertParsesTo("ROLL (Die)", roll(rel("Die")));
    }

    @Test
    void parsesOverComplexInput() {
        assertParsesTo("ROLL (A × B)",
                roll(product(rel("A"), rel("B"))));
    }

    @Test
    void nestsUnderLimit() {
        assertParsesTo("λ 3 (ROLL SEED 7 (Die))",
                limit(3, roll(Optional.of(7L), rel("Die"))));
    }

    // ─── Seeded variants ──────────────────────────────────────────────────────

    @Test
    void parsesSeededRoll() {
        RollNode node = (RollNode) parse("ROLL SEED 7 (Die)");
        assertThat(node.seed()).contains(7L);
        assertThat(((RelationNode) node.input()).name()).isEqualTo("Die");
    }

    @Test
    void parsesSeededRollWithZeroSeed() {
        RollNode node = (RollNode) parse("ROLL SEED 0 (R)");
        assertThat(node.seed()).contains(0L);
    }

    @Test
    void unseededRollHasEmptySeed() {
        RollNode node = (RollNode) parse("ROLL (R)");
        assertThat(node.seed()).isEmpty();
    }

    // ─── Pretty printing ────────────────────────────────────────────────────

    @Test
    void prettyPrintsBasic() {
        assertPrettyPrints(roll(rel("Die")), "ROLL (Die)");
    }

    @Test
    void prettyPrintRoundTrips() {
        RelNode original = roll(rel("R"));
        assertParsesTo(original.prettyPrint(), original);
    }

    @Test
    void prettyPrintsSeeded() {
        RelNode node = roll(Optional.of(2026L), rel("Die"));
        assertPrettyPrints(node, "ROLL SEED 2026 (Die)");
    }

    @Test
    void prettyPrintSeededRoundTrips() {
        RelNode original = roll(Optional.of(42L), rel("R"));
        assertParsesTo(original.prettyPrint(), original);
    }

    // ─── Errors ─────────────────────────────────────────────────────────────

    @Test
    void failsWithoutInput() {
        assertParseError("ROLL").hasMessageContaining("Expected '('");
    }

    @Test
    void failsWithNonIntegerSeed() {
        assertParseError("ROLL SEED 3.14 (R)")
                .hasMessageContaining("'SEED' requires an integer value");
    }

    @Test
    void failsWithMissingSeedValue() {
        assertParseError("ROLL SEED (R)")
                .hasMessageContaining("Expected an integer seed value after 'SEED'");
    }
}
