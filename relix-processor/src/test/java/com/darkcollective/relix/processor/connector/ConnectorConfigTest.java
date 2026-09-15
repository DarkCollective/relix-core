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
package com.darkcollective.relix.processor.connector;

import com.darkcollective.relix.processor.eval.EvaluationException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link ConnectorConfig}. */
final class ConnectorConfigTest {

    @Test
    void getReturnsValueWhenPresentAndEmptyWhenAbsent() {
        ConnectorConfig config = new ConnectorConfig(Map.of("url", "jdbc:h2:mem:"));
        assertThat(config.get("url")).contains("jdbc:h2:mem:");
        assertThat(config.get("user")).isEmpty();
    }

    @Test
    void getOrDefaultFallsBackWhenAbsent() {
        ConnectorConfig config = new ConnectorConfig(Map.of("header", "false"));
        assertThat(config.getOrDefault("header", "true")).isEqualTo("false");
        assertThat(config.getOrDefault("path", "data.csv")).isEqualTo("data.csv");
    }

    @Test
    void requireReturnsValueWhenPresent() {
        ConnectorConfig config = new ConnectorConfig(Map.of("path", "/tmp/x.csv"));
        assertThat(config.require("path")).isEqualTo("/tmp/x.csv");
    }

    @Test
    void requireThrowsWhenAbsent() {
        ConnectorConfig config = ConnectorConfig.empty();
        assertThatExceptionOfType(EvaluationException.class)
                .isThrownBy(() -> config.require("url"))
                .withMessageContaining("missing required key 'url'");
    }

    @Test
    void emptyHasNoValues() {
        assertThat(ConnectorConfig.empty().values()).isEmpty();
    }

    @Test
    void isImmutableAndDefensivelyCopies() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("a", "1");
        ConnectorConfig config = new ConnectorConfig(mutable);

        mutable.put("b", "2");   // must not leak into the config

        assertThat(config.get("b")).isEmpty();
        assertThatThrownBy(() -> config.values().put("c", "3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
