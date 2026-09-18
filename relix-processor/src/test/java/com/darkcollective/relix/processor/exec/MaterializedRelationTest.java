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

import com.darkcollective.relix.processor.ProcessorTestSupport;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ScalarType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MaterializedRelation — sealed collection hierarchy")
final class MaterializedRelationTest extends ProcessorTestSupport {

    private static final com.darkcollective.relix.symbol.Schema SCHEMA = schema(
            col("id",   ScalarType.NUMBER),
            col("name", ScalarType.STRING));

    // ── BagRelation ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("BagRelation")
    class BagRelationTests {

        @Test
        @DisplayName("stream returns all rows in insertion order")
        void streamOrder() {
            Row r1 = row(SCHEMA, num(1), str("Alice"));
            Row r2 = row(SCHEMA, num(2), str("Bob"));
            Row r3 = row(SCHEMA, num(1), str("Alice")); // duplicate allowed

            BagRelation bag = BagRelation.of(SCHEMA, List.of(r1, r2, r3));

            assertThat(bag.stream().toList()).containsExactly(r1, r2, r3);
        }

        @Test
        @DisplayName("allows duplicate rows")
        void allowsDuplicates() {
            Row r = row(SCHEMA, num(1), str("Alice"));
            BagRelation bag = BagRelation.of(SCHEMA, List.of(r, r, r));

            assertThat(bag.stream().count()).isEqualTo(3);
        }

        @Test
        @DisplayName("empty bag streams nothing")
        void emptyBag() {
            BagRelation bag = BagRelation.of(SCHEMA, List.of());
            assertThat(bag.stream().toList()).isEmpty();
        }

        @Test
        @DisplayName("schema accessor returns the correct schema")
        void schemaAccessor() {
            BagRelation bag = BagRelation.of(SCHEMA, List.of());
            assertThat(bag.schema()).isSameAs(SCHEMA);
        }
    }

    // ── SetRelation ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SetRelation")
    class SetRelationTests {

        @Test
        @DisplayName("deduplicates rows on construction")
        void deduplicates() {
            Row r1 = row(SCHEMA, num(1), str("Alice"));
            Row r2 = row(SCHEMA, num(2), str("Bob"));

            SetRelation set = SetRelation.of(SCHEMA, List.of(r1, r2, r1, r2));

            assertThat(set.size()).isEqualTo(2);
            assertThat(set.stream().toList()).containsExactlyInAnyOrder(r1, r2);
        }

        @Test
        @DisplayName("preserves insertion order of first occurrence")
        void preservesInsertionOrder() {
            Row r1 = row(SCHEMA, num(1), str("Alice"));
            Row r2 = row(SCHEMA, num(2), str("Bob"));
            Row r3 = row(SCHEMA, num(3), str("Carol"));

            SetRelation set = SetRelation.of(SCHEMA, List.of(r2, r1, r3, r2, r1));

            assertThat(set.stream().toList()).containsExactly(r2, r1, r3);
        }

        @Test
        @DisplayName("contains() returns true for a row that is in the set")
        void containsRow() {
            Row r = row(SCHEMA, num(1), str("Alice"));
            SetRelation set = SetRelation.of(SCHEMA, List.of(r));

            assertThat(set.contains(r)).isTrue();
            assertThat(set.contains(row(SCHEMA, num(99), str("X")))).isFalse();
        }

        @Test
        @DisplayName("empty set has size 0")
        void emptySet() {
            SetRelation set = SetRelation.of(SCHEMA, List.of());
            assertThat(set.size()).isEqualTo(0);
        }
    }

    // ── SortedBagRelation ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("SortedBagRelation")
    class SortedBagRelationTests {

        @Test
        @DisplayName("stream returns rows in the order they were stored")
        void streamOrder() {
            Row r1 = row(SCHEMA, num(1), str("Alice"));
            Row r2 = row(SCHEMA, num(2), str("Bob"));
            Row r3 = row(SCHEMA, num(3), str("Carol"));

            SortedBagRelation sorted = SortedBagRelation.of(SCHEMA, List.of(r1, r2, r3));

            assertThat(sorted.stream().toList()).containsExactly(r1, r2, r3);
        }

        @Test
        @DisplayName("allows duplicate rows (bag semantics)")
        void allowsDuplicates() {
            Row r = row(SCHEMA, num(1), str("Alice"));
            SortedBagRelation sorted = SortedBagRelation.of(SCHEMA, List.of(r, r));
            assertThat(sorted.stream().count()).isEqualTo(2);
        }
    }

    // ── IndexedRelation ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("IndexedRelation")
    class IndexedRelationTests {

        @Test
        @DisplayName("build() partitions rows by key extractor")
        void buildPartitions() {
            var s = schema(col("dept", ScalarType.STRING), col("name", ScalarType.STRING));
            Row eng1 = row(s, str("eng"),  str("Alice"));
            Row eng2 = row(s, str("eng"),  str("Bob"));
            Row mkt  = row(s, str("mkt"),  str("Carol"));

            IndexedRelation idx = IndexedRelation.build(s,
                    Stream.of(eng1, eng2, mkt),
                    r -> List.of(r.get("dept")));

            assertThat(idx.groupCount()).isEqualTo(2);
            assertThat(idx.group(List.of(str("eng")))).containsExactly(eng1, eng2);
            assertThat(idx.group(List.of(str("mkt")))).containsExactly(mkt);
            assertThat(idx.group(List.of(str("unknown")))).isEmpty();
        }

        @Test
        @DisplayName("stream() flattens all groups in insertion order")
        void streamFlattens() {
            var s = schema(col("k", ScalarType.NUMBER), col("v", ScalarType.NUMBER));
            Row a = row(s, num(1), num(10));
            Row b = row(s, num(1), num(20));
            Row c = row(s, num(2), num(30));

            IndexedRelation idx = IndexedRelation.build(s,
                    Stream.of(a, b, c),
                    r -> List.of(r.get("k")));

            assertThat(idx.stream().toList()).containsExactly(a, b, c);
        }

        @Test
        @DisplayName("empty input produces empty index")
        void emptyInput() {
            IndexedRelation idx = IndexedRelation.build(SCHEMA,
                    Stream.empty(), r -> List.of());
            assertThat(idx.groupCount()).isEqualTo(0);
            assertThat(idx.stream().toList()).isEmpty();
        }
    }

    // ── Sealed hierarchy ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Sealed hierarchy — exhaustive pattern matching")
    class SealedHierarchy {

        @Test
        @DisplayName("all four permitted types are coverable in a switch")
        void exhaustiveSwitch() {
            List<MaterializedRelation> relations = List.of(
                    BagRelation.of(SCHEMA, List.of()),
                    SetRelation.of(SCHEMA, List.of()),
                    SortedBagRelation.of(SCHEMA, List.of()),
                    IndexedRelation.build(SCHEMA, Stream.empty(), r -> List.of()));

            for (MaterializedRelation rel : relations) {
                String kind = switch (rel) {
                    case BagRelation      ignored -> "bag";
                    case SetRelation      ignored -> "set";
                    case SortedBagRelation ignored -> "sortedBag";
                    case IndexedRelation  ignored -> "indexed";
                };
                assertThat(kind).isNotBlank();
            }
        }
    }
}
