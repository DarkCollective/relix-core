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
package com.darkcollective.relix.processor;

import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.symbol.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a connector gets for free from the seam.
 *
 * <p>{@link DataSourceConnector} is a {@code @FunctionalInterface}, so the simplest one
 * is a lambda that implements {@code open} and nothing else. Both defaults matter to it:
 * {@code close} must be a no-op, or a lambda connector could not be used in
 * try-with-resources at all, and {@code openQuery} must refuse rather than return
 * nothing, since a connector that silently answered no rows to a pushed-down query would
 * turn a missing capability into a wrong answer.
 */
@DisplayName("DataSourceConnector's defaults")
final class DataSourceConnectorDefaultsTest {

    /** The simplest possible connector: one lambda, no resources, no pushdown. */
    private static DataSourceConnector lambdaConnector() {
        return (relationName, schema) -> Stream.of();
    }

    @Test
    @DisplayName("closing a lambda connector is a no-op, not an error")
    void closeIsANoOp() {
        assertThatCode(() -> {
            try (DataSourceConnector connector = lambdaConnector()) {
                assertThat(connector.open("anything", Schema.empty())).isEmpty();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("closing twice is still a no-op")
    void closeIsIdempotent() {
        DataSourceConnector connector = lambdaConnector();
        connector.close();
        assertThatCode(connector::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a connector that declares no pushdown refuses one, naming the connection")
    void pushdownIsRefusedRatherThanSilentlyEmpty() {
        assertThatThrownBy(() ->
                lambdaConnector().openQuery("warehouse", "SELECT 1", Schema.empty()))
                .isInstanceOf(EvaluationException.class)
                .hasMessageContaining("warehouse");
    }
}
