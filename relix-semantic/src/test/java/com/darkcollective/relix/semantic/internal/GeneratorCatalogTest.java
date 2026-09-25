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
package com.darkcollective.relix.semantic.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seam's own answers — the ones a catalog gets for free.
 *
 * <p>{@link GeneratorCatalog#NONE} and the {@code generatorCardinality} default are what
 * an analysis runs against when no generator registry is supplied, which is every
 * analysis the engine performs without a {@code relix-processor} on the other side of the
 * seam. Both were unexercised: every catalog in the suite is a registry or an anonymous
 * class that overrides the default, so the defaults themselves had never run.
 */
@DisplayName("GeneratorCatalog's defaults")
final class GeneratorCatalogTest {

    @Test
    @DisplayName("NONE knows no generator's schema")
    void noneKnowsNoSchema() {
        assertThat(GeneratorCatalog.NONE.generatorSchema("Range", Map.of("lo", "1", "hi", "9")))
                .isEmpty();
    }

    /**
     * A functional-interface catalog implements only {@code generatorSchema}, so asking it
     * for a cardinality takes the interface's default — the answer that makes a generator
     * cost as unknown rather than as zero.
     */
    @Test
    @DisplayName("a catalog that declares no cardinality reports none, rather than zero")
    void cardinalityDefaultsToUnknown() {
        assertThat(GeneratorCatalog.NONE.generatorCardinality("Range", Map.of("lo", "1", "hi", "9")))
                .isEmpty();

        GeneratorCatalog schemaOnly = (name, args) -> java.util.Optional.empty();
        assertThat(schemaOnly.generatorCardinality("Anything", Map.of())).isEmpty();
    }
}
