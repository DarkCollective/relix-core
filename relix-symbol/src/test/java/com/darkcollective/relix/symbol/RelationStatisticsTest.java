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
package com.darkcollective.relix.symbol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RelationStatistics — cardinality metadata")
final class RelationStatisticsTest {

    @Test
    @DisplayName("UNKNOWN carries no information")
    void unknownIsEmpty() {
        assertThat(RelationStatistics.UNKNOWN.rowCount()).isEmpty();
        assertThat(RelationStatistics.UNKNOWN.columnStatistics()).isEmpty();
        assertThat(RelationStatistics.UNKNOWN.keys()).isEmpty();
    }

    @Test
    @DisplayName("of(rowCount) stores only the row count")
    void ofRowCount() {
        RelationStatistics stats = RelationStatistics.of(100);
        assertThat(stats.rowCount()).hasValue(100L);
        assertThat(stats.columnStatistics()).isEmpty();
        assertThat(stats.keys()).isEmpty();
    }

    @Test
    @DisplayName("of(negative) is rejected")
    void ofNegativeRejected() {
        assertThatThrownBy(() -> RelationStatistics.of(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("column(name) returns recorded statistics or empty")
    void columnLookup() {
        var col = new ColumnStatistics(OptionalLong.of(5), OptionalLong.of(0));
        var stats = new RelationStatistics(OptionalLong.of(10), Map.of("id", col), List.of());
        assertThat(stats.column("id")).contains(col);
        assertThat(stats.column("missing")).isEmpty();
    }

    @Nested
    @DisplayName("Defensive copying")
    class DefensiveCopy {

        @Test
        @DisplayName("the column map is copied")
        void columnMapCopied() {
            Map<String, ColumnStatistics> mutable = new HashMap<>();
            mutable.put("a", ColumnStatistics.UNKNOWN);
            var stats = new RelationStatistics(OptionalLong.empty(), mutable, List.of());
            mutable.put("b", ColumnStatistics.UNKNOWN);
            assertThat(stats.columnStatistics()).containsOnlyKeys("a");
        }

        @Test
        @DisplayName("key lists are copied")
        void keysCopied() {
            List<String> innerKey = new ArrayList<>(List.of("id"));
            List<List<String>> keys = new ArrayList<>();
            keys.add(innerKey);
            var stats = new RelationStatistics(OptionalLong.empty(), Map.of(), keys);
            innerKey.add("tampered");
            keys.add(List.of("other"));
            assertThat(stats.keys()).containsExactly(List.of("id"));
        }
    }

    @Nested
    @DisplayName("Null guards")
    class NullGuards {

        @Test
        @DisplayName("null rowCount rejected")
        void nullRowCount() {
            assertThatThrownBy(() -> new RelationStatistics(null, Map.of(), List.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null column map rejected")
        void nullColumns() {
            assertThatThrownBy(() -> new RelationStatistics(OptionalLong.empty(), null, List.of()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null keys rejected")
        void nullKeys() {
            assertThatThrownBy(() -> new RelationStatistics(OptionalLong.empty(), Map.of(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("ColumnStatistics")
    class Column {

        @Test
        @DisplayName("UNKNOWN carries no counts")
        void unknown() {
            assertThat(ColumnStatistics.UNKNOWN.distinctCount()).isEmpty();
            assertThat(ColumnStatistics.UNKNOWN.nullCount()).isEmpty();
        }

        @Test
        @DisplayName("stores distinct and null counts")
        void storesCounts() {
            var col = new ColumnStatistics(OptionalLong.of(7), OptionalLong.of(2));
            assertThat(col.distinctCount()).hasValue(7L);
            assertThat(col.nullCount()).hasValue(2L);
        }

        @Test
        @DisplayName("null fields rejected")
        void nullFields() {
            assertThatThrownBy(() -> new ColumnStatistics(null, OptionalLong.empty()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new ColumnStatistics(OptionalLong.empty(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
