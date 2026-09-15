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
 * Tests for the reservoir (fixed-count) sampling operator:
 * {@code SAMPLE n ROWS (input)} and {@code SAMPLE n ROWS SEED k (input)} —
 * distinguished from Bernoulli {@code SAMPLE p} by the {@code ROWS} keyword.
 */
final class ReservoirSampleParserTest extends ParserTestSupport {

    @Test
    void parsesBasicReservoir() {
        assertParsesTo("SAMPLE 100 ROWS (Events)",
                reservoirSample(100, rel("Events")));
    }

    @Test
    void parsesZeroRows() {
        assertParsesTo("SAMPLE 0 ROWS (R)", reservoirSample(0, rel("R")));
    }

    @Test
    void parsesOverComplexInput() {
        assertParsesTo("SAMPLE 5 ROWS (A ⋈ B)",
                reservoirSample(
                        5,
                        naturalJoin(rel("A"), rel("B"))));
    }

    @Test
    void nestsInsideAnotherOperator() {
        assertParsesTo("δ (SAMPLE 3 ROWS (R))",
                distinct(reservoirSample(3, rel("R"))));
    }

    @Test
    void capturesTheCount() {
        ReservoirSampleNode node = (ReservoirSampleNode) parse("SAMPLE 42 ROWS (R)");
        assertThat(node.count()).isEqualTo(42L);
    }

    @Test
    void bernoulliStillParsesWithoutRows() {
        // The ROWS keyword is the only discriminator; without it, the number is a
        // Bernoulli probability.
        assertParsesTo("SAMPLE 1 (R)", sample(1.0, rel("R")));
    }

    // ─── Seeded variants ────────────────────────────────────────────────────────

    @Test
    void parsesSeededReservoir() {
        ReservoirSampleNode node = (ReservoirSampleNode) parse("SAMPLE 500 ROWS SEED 2026 (Events)");
        assertThat(node.count()).isEqualTo(500L);
        assertThat(node.seed()).contains(2026L);
        assertThat(((RelationNode) node.input()).name()).isEqualTo("Events");
    }

    @Test
    void parsesSeededReservoirWithZeroSeed() {
        ReservoirSampleNode node = (ReservoirSampleNode) parse("SAMPLE 10 ROWS SEED 0 (R)");
        assertThat(node.seed()).contains(0L);
    }

    @Test
    void unseededReservoirHasEmptySeed() {
        ReservoirSampleNode node = (ReservoirSampleNode) parse("SAMPLE 10 ROWS (R)");
        assertThat(node.seed()).isEmpty();
    }

    // ─── Pretty printing ────────────────────────────────────────────────────

    @Test
    void prettyPrintsBasic() {
        assertPrettyPrints(reservoirSample(100, rel("Events")),
                "SAMPLE 100 ROWS (Events)");
    }

    @Test
    void prettyPrintRoundTrips() {
        RelNode original = reservoirSample(25, rel("R"));
        assertParsesTo(original.prettyPrint(), original);
    }

    @Test
    void prettyPrintsSeeded() {
        RelNode node = reservoirSample(500, Optional.of(2026L), rel("Events"));
        assertPrettyPrints(node, "SAMPLE 500 ROWS SEED 2026 (Events)");
    }

    @Test
    void prettyPrintSeededRoundTrips() {
        RelNode original = reservoirSample(100, Optional.of(42L), rel("R"));
        assertParsesTo(original.prettyPrint(), original);
    }

    // ─── Errors ─────────────────────────────────────────────────────────────

    @Test
    void failsWithNonIntegerCount() {
        assertParseError("SAMPLE 0.5 ROWS (R)")
                .hasMessageContaining("requires an integer row count");
    }

    @Test
    void failsWithoutInput() {
        assertParseError("SAMPLE 10 ROWS").hasMessageContaining("Expected '('");
    }

    @Test
    void failsWithNonIntegerSeed() {
        assertParseError("SAMPLE 10 ROWS SEED 3.14 (R)")
                .hasMessageContaining("'SEED' requires an integer value");
    }

    @Test
    void failsWithMissingSeedValue() {
        assertParseError("SAMPLE 10 ROWS SEED (R)")
                .hasMessageContaining("Expected an integer seed value after 'SEED'");
    }
}
