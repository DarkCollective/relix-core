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
package com.darkcollective.relix.cost;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.DatabaseRelationSymbol;
import com.darkcollective.relix.symbol.table.InMemorySymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("StatisticsDistinctnessSource — a declared key makes a leaf duplicate-free")
final class StatisticsDistinctnessSourceTest {

    private static final Schema SCHEMA =
            new Schema(List.of(new ColumnDefinition("id", ScalarType.NUMBER)));

    /** Statistics carrying a row count and the given candidate keys. */
    private static RelationStatistics stats(List<List<String>> keys) {
        return new RelationStatistics(OptionalLong.of(1_000L), Map.of(), keys);
    }

    private static StatisticsDistinctnessSource sourceOf(Map<String, RelationStatistics> byName) {
        return new StatisticsDistinctnessSource(
                name -> Optional.ofNullable(byName.get(name)));
    }

    // =========================================================================
    // The rule itself
    // =========================================================================

    @Nested
    @DisplayName("duplicateFreeLeaf")
    class DuplicateFreeLeaf {

        @Test
        @DisplayName("a single-column primary key ⇒ duplicate-free")
        void singleColumnKey() {
            var source = sourceOf(Map.of("Orders", stats(List.of(List.of("order_id")))));
            assertThat(source.duplicateFreeLeaf("Orders")).isTrue();
        }

        @Test
        @DisplayName("a composite key ⇒ duplicate-free")
        void compositeKey() {
            var source = sourceOf(Map.of(
                    "LineItems", stats(List.of(List.of("order_id", "line_no")))));
            assertThat(source.duplicateFreeLeaf("LineItems")).isTrue();
        }

        @Test
        @DisplayName("statistics WITHOUT a key ⇒ NOT duplicate-free (a row count proves nothing)")
        void rowCountButNoKey() {
            var source = sourceOf(Map.of("Events", stats(List.of())));
            assertThat(source.duplicateFreeLeaf("Events")).isFalse();
        }

        @Test
        @DisplayName("no statistics at all (CSV/Mongo/HTTP are never introspected) ⇒ NOT distinct")
        void noStatistics() {
            var source = sourceOf(Map.of());
            assertThat(source.duplicateFreeLeaf("Anything")).isFalse();
        }

        @Test
        @DisplayName("StatisticsSource.NONE degrades to DistinctnessSource.NONE")
        void noneIsANoOp() {
            var source = new StatisticsDistinctnessSource(StatisticsSource.NONE);
            assertThat(source.duplicateFreeLeaf("Orders")).isFalse();
        }

        @Test
        @DisplayName("rejects a null statistics source")
        void rejectsNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new StatisticsDistinctnessSource(null));
        }
    }

    // =========================================================================
    // Name resolution — StatisticsSource.of
    // =========================================================================

    @Nested
    @DisplayName("StatisticsSource.of — resolves the written name to a canonical one")
    class Resolution {

        @Test
        @DisplayName("a keyed relation resolves through the symbol table")
        void resolvesCanonicalName() {
            var table = new InMemorySymbolTable();
            var symbol = DatabaseRelationSymbol.of("Orders", SCHEMA);
            table.register(symbol);
            var source = StatisticsSource.of(table,
                    Map.of(symbol.canonicalName(), stats(List.of(List.of("order_id")))));

            assertThat(new StatisticsDistinctnessSource(source).duplicateFreeLeaf("Orders"))
                    .isTrue();
        }

        @Test
        @DisplayName("an unregistered name resolves to nothing rather than throwing")
        void unknownName() {
            var source = StatisticsSource.of(new InMemorySymbolTable(), Map.of());
            assertThat(source.forRelation("Nope")).isEmpty();
        }

        @Test
        @DisplayName("rejects null arguments")
        void rejectsNulls() {
            assertThatNullPointerException()
                    .isThrownBy(() -> StatisticsSource.of(null, Map.of()));
            assertThatNullPointerException()
                    .isThrownBy(() -> StatisticsSource.of(new InMemorySymbolTable(), null));
        }
    }

    // =========================================================================
    // Composition — DistinctnessSource.anyOf
    // =========================================================================

    @Nested
    @DisplayName("DistinctnessSource.anyOf — generators and keyed relations are both consultable")
    class Composition {

        private static final DistinctnessSource GENERATOR =
                name -> name.equals("Range");

        @Test
        @DisplayName("reports true when the first source claims the leaf")
        void firstClaims() {
            var combined = DistinctnessSource.anyOf(GENERATOR,
                    sourceOf(Map.of("Orders", stats(List.of(List.of("order_id"))))));
            assertThat(combined.duplicateFreeLeaf("Range")).isTrue();
        }

        @Test
        @DisplayName("reports true when a later source claims the leaf")
        void laterClaims() {
            var combined = DistinctnessSource.anyOf(GENERATOR,
                    sourceOf(Map.of("Orders", stats(List.of(List.of("order_id"))))));
            assertThat(combined.duplicateFreeLeaf("Orders")).isTrue();
        }

        @Test
        @DisplayName("reports false when no source claims the leaf")
        void noneClaim() {
            var combined = DistinctnessSource.anyOf(GENERATOR, sourceOf(Map.of()));
            assertThat(combined.duplicateFreeLeaf("Events")).isFalse();
        }

        @Test
        @DisplayName("with no sources is DistinctnessSource.NONE")
        void empty() {
            assertThat(DistinctnessSource.anyOf()).isSameAs(DistinctnessSource.NONE);
        }

        @Test
        @DisplayName("rejects null arguments")
        void rejectsNulls() {
            assertThatNullPointerException()
                    .isThrownBy(() -> DistinctnessSource.anyOf((DistinctnessSource[]) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> DistinctnessSource.anyOf(GENERATOR, null));
        }
    }

    // =========================================================================
    // End-to-end through PropertyDeriver — what DIST-001 actually asks
    // =========================================================================

    @Nested
    @DisplayName("PropertyDeriver — δ over a keyed leaf derives duplicate-free")
    class ThroughPropertyDeriver {

        @Test
        @DisplayName("a keyed base relation is duplicate-free")
        void keyedLeafIsDistinct() {
            var source = sourceOf(Map.of("Orders", stats(List.of(List.of("order_id")))));
            RelNode leaf = rel("Orders");
            assertThat(PropertyDeriver.derive(leaf, source)
                    .isDuplicateFree()).isTrue();
        }

        @Test
        @DisplayName("an unkeyed base relation is not")
        void unkeyedLeafIsNot() {
            var source = sourceOf(Map.of("Events", stats(List.of())));
            RelNode leaf = rel("Events");
            assertThat(PropertyDeriver.derive(leaf, source)
                    .isDuplicateFree()).isFalse();
        }

        @Test
        @DisplayName("δ over the keyed leaf is itself duplicate-free (so DIST-001 may remove it)")
        void distinctOverKeyedLeaf() {
            var source = sourceOf(Map.of("Orders", stats(List.of(List.of("order_id")))));
            RelNode delta = distinct(rel("Orders"));
            assertThat(PropertyDeriver.derive(delta, source)
                    .isDuplicateFree()).isTrue();
        }
    }
}
