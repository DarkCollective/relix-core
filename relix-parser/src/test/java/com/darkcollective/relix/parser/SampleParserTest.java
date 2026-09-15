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
 * Tests for the Bernoulli-sampling operator (SAMPLE): {@code SAMPLE p (input)}
 * and {@code SAMPLE p SEED n (input)}.
 */
final class SampleParserTest extends ParserTestSupport {

    @Test
    void parsesBasicSample() {
        assertParsesTo("SAMPLE 0.1 (Events)",
                sample(0.1, rel("Events")));
    }

    @Test
    void parsesWholeNumberProbability() {
        assertParsesTo("SAMPLE 1 (R)", sample(1.0, rel("R")));
    }

    @Test
    void parsesOverComplexInput() {
        assertParsesTo("SAMPLE 0.5 (A ⋈ B)",
                sample(
                        0.5,
                        naturalJoin(rel("A"), rel("B"))));
    }

    @Test
    void nestsInsideAnotherOperator() {
        assertParsesTo("δ (SAMPLE 0.25 (R))",
                distinct(sample(0.25, rel("R"))));
    }

    @Test
    void capturesTheProbability() {
        SampleNode node = (SampleNode) parse("SAMPLE 0.33 (R)");
        assertThat(node.probability()).isEqualTo(0.33);
    }

    // ─── Seeded variants ────────────────────────────────────────────────────────

    @Test
    void parsesSeededSample() {
        SampleNode node = (SampleNode) parse("SAMPLE 0.8 SEED 42 (HistoricalData)");
        assertThat(node.probability()).isEqualTo(0.8);
        assertThat(node.seed()).contains(42L);
        assertThat(((RelationNode) node.input()).name()).isEqualTo("HistoricalData");
    }

    @Test
    void parsesSeededSampleWithLargeSeed() {
        SampleNode node = (SampleNode) parse("SAMPLE 0.5 SEED 9999999 (R)");
        assertThat(node.seed()).contains(9999999L);
    }

    @Test
    void parsesSeededSampleWithZeroSeed() {
        SampleNode node = (SampleNode) parse("SAMPLE 0.1 SEED 0 (R)");
        assertThat(node.seed()).contains(0L);
    }

    @Test
    void unseededSampleHasEmptySeed() {
        SampleNode node = (SampleNode) parse("SAMPLE 0.1 (R)");
        assertThat(node.seed()).isEmpty();
    }

    @Test
    void seededSampleParsesASCIIKeywordCase() {
        SampleNode node = (SampleNode) parse("SAMPLE 0.3 SEED 7 (R)");
        assertThat(node.seed()).contains(7L);
    }

    // ─── Pretty printing ────────────────────────────────────────────────────

    @Test
    void prettyPrintsBasic() {
        assertPrettyPrints(sample(0.1, rel("Events")),
                "SAMPLE 0.1 (Events)");
    }

    @Test
    void prettyPrintRoundTrips() {
        RelNode original = sample(0.42, rel("R"));
        assertParsesTo(original.prettyPrint(), original);
    }

    @Test
    void prettyPrintsSeeded() {
        RelNode node = sample(0.8, Optional.of(42L), rel("HistoricalData"));
        assertPrettyPrints(node, "SAMPLE 0.8 SEED 42 (HistoricalData)");
    }

    @Test
    void prettyPrintSeededRoundTrips() {
        RelNode original = sample(0.5, Optional.of(2026L), rel("R"));
        assertParsesTo(original.prettyPrint(), original);
    }

    // ─── Errors ─────────────────────────────────────────────────────────────

    @Test
    void failsWithoutProbability() {
        assertParseError("SAMPLE (R)").hasMessageContaining("Expected a sampling probability");
    }

    @Test
    void failsWithoutInput() {
        assertParseError("SAMPLE 0.1").hasMessageContaining("Expected '('");
    }

    @Test
    void failsWithNonIntegerSeed() {
        assertParseError("SAMPLE 0.5 SEED 3.14 (R)")
                .hasMessageContaining("'SEED' requires an integer value");
    }

    @Test
    void failsWithMissingSeedValue() {
        assertParseError("SAMPLE 0.5 SEED (R)")
                .hasMessageContaining("Expected an integer seed value after 'SEED'");
    }
}
