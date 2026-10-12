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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.function.Blocking;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("The similarity join's index")
final class SimilarityIndexTest {

    private static final Schema SCHEMA = new Schema(
            List.of(new ColumnDefinition("v", ScalarType.ANY)));

    /** Files a string under its first character; refuses anything else; NULL has none. */
    private static final Blocking FIRST_CHARACTER = new Blocking() {
        @Override
        public Collection<?> indexKeys(Value value) {
            return keys(value);
        }

        @Override
        public Collection<?> probeKeys(Value value) {
            return keys(value);
        }

        private Collection<?> keys(Value value) {
            if (value.isNull()) {
                return List.of();
            }
            if (!(value instanceof StringValue s)) {
                throw new IllegalArgumentException("not a string");
            }
            return s.value().isEmpty() ? List.of() : List.of(s.value().charAt(0));
        }
    };

    private static Row row(Value v) {
        return Row.of(SCHEMA, v);
    }

    private static final List<Row> ROWS = List.of(
            row(new StringValue("apple")),       // 0
            row(new StringValue("banana")),      // 1
            row(NumberValue.of("7")),            // 2: refused, so a wildcard
            row(new StringValue("avocado")),     // 3
            row(NullValue.INSTANCE));            // 4: no keys, never a candidate

    private static final SimilarityIndex INDEX = SimilarityIndex.build(ROWS, 0, FIRST_CHARACTER);

    @Test
    @DisplayName("finds the rows sharing a key, and the wildcards, in row order")
    void sharedKeys() {
        assertThat(INDEX.candidates(new StringValue("apricot"))).containsExactly(0, 2, 3);
        assertThat(INDEX.candidates(new StringValue("cherry"))).containsExactly(2);
    }

    @Test
    @DisplayName("a probe given no keys finds only the wildcards")
    void noKeys() {
        assertThat(INDEX.candidates(NullValue.INSTANCE)).containsExactly(2);
    }

    @Test
    @DisplayName("a probe the blocking refuses is compared with every row")
    void refusedProbe() {
        assertThat(INDEX.candidates(new NumberValue(BigDecimal.ONE)))
                .containsExactly(0, 1, 2, 3, 4);
    }
}
