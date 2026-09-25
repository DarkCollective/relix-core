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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.darkcollective.relix.embed.EmbedAssertions.assertThat;

/**
 * Iterating an object whose keys are data, end to end: {@code Entries} hands the object to
 * the unnest operator, which already explodes arrays, and path access reads the entries.
 *
 * <p>This is the composition the function exists for, so it is asserted over a running
 * engine rather than over the function alone — the unit suite in {@code relix-function-builtin}
 * proves what {@code Entries} returns, and nothing there proves that a relation can be
 * written around it.
 */
@DisplayName("Relix — iterating an object whose keys are data")
final class ObjectIterationTest {

    /** A keyed-counts column has no declared shape, so it is typed ANY. */
    private static final Schema REGION_STATS = new Schema(List.of(
            new ColumnDefinition("region", ScalarType.STRING),
            new ColumnDefinition("orders_by_country", ScalarType.ANY)));

    /** The query the reference page documents: one row per field of the keyed object. */
    private static final String PER_FIELD = """
            π region, e.key → country, e.value → orders (
              μ e ( π region, Entries(orders_by_country) → e (RegionStats) )
            )""";

    private static Value object(Object... namesAndValues) {
        Map<String, Value> fields = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            fields.put((String) namesAndValues[i], (Value) namesAndValues[i + 1]);
        }
        return new StructValue(fields);
    }

    private static Value num(String literal) {
        return new NumberValue(new BigDecimal(literal));
    }

    private static Row stats(String region, Value ordersByCountry) {
        return ArrayRow.of(REGION_STATS, List.of(new StringValue(region), ordersByCountry));
    }

    private static Relix sessionOver(Row... rows) {
        Relix relix = Relix.open();
        relix.source("RegionStats", REGION_STATS, () -> Stream.of(rows));
        return relix;
    }

    @Nested
    @DisplayName("The composition the function exists for")
    final class TheComposition {

        @Test
        @DisplayName("each field of the object becomes a row carrying its key and value")
        void eachFieldBecomesARow() {
            try (Relix relix = sessionOver(
                    stats("EMEA", object("gb", num("7"), "de", num("4"))),
                    stats("APAC", object("au", num("2"))))) {

                assertThat(relix.relation(PER_FIELD)).rows()
                        .hasRowCount(3)
                        .hasRowAt(0, "EMEA", "gb", "7")
                        .hasRowAt(1, "EMEA", "de", "4")
                        .hasRowAt(2, "APAC", "au", "2");
            }
        }

        @Test
        @DisplayName("the heading names the columns the projection asked for")
        void headingNamesTheProjectedColumns() {
            try (Relix relix = sessionOver(stats("EMEA", object("gb", num("7"))))) {
                assertThat(relix.relation(PER_FIELD)).schema()
                        .hasColumnNames("region", "country", "orders");
            }
        }

        @Test
        @DisplayName("the keys become rows, so an aggregate can group by one")
        void keysCanBeGroupedBy() {
            try (Relix relix = sessionOver(
                    stats("EMEA", object("gb", num("7"), "de", num("4"))),
                    stats("APAC", object("gb", num("1"))))) {

                assertThat(relix.relation("""
                        γ country, SUM(orders) → total (
                          π e.key → country, e.value → orders (
                            μ e ( π Entries(orders_by_country) → e (RegionStats) )
                          )
                        )""")).rows()
                        .hasRowCount(2)
                        .hasRowAt(0, "gb", "8")
                        .hasRowAt(1, "de", "4");
            }
        }
    }

    @Nested
    @DisplayName("Documents the object is not in")
    final class MissingObjects {

        @Test
        @DisplayName("a row whose field is NULL contributes none, the inner unnest dropping it")
        void aNullFieldContributesNoRows() {
            try (Relix relix = sessionOver(
                    stats("EMEA", object("gb", num("7"))),
                    stats("APAC", NullValue.INSTANCE),
                    stats("AMER", object()))) {

                assertThat(relix.relation(PER_FIELD)).rows()
                        .as("NULL and an empty object both reach μ as no elements")
                        .hasRowCount(1)
                        .hasRowAt(0, "EMEA", "gb", "7");
            }
        }

        @Test
        @DisplayName("a field holding a scalar contributes none rather than failing the query")
        void aScalarFieldContributesNoRows() {
            try (Relix relix = sessionOver(
                    stats("EMEA", object("gb", num("7"))),
                    stats("APAC", new StringValue("unavailable")))) {

                assertThat(relix.relation(PER_FIELD)).rows()
                        .as("heterogeneous data must not crash a query, which is why "
                            + "Entries answers NULL rather than raising")
                        .hasRowCount(1);
            }
        }

        @Test
        @DisplayName("a key the object does not have reads as NULL, not as an analysis error")
        void anAbsentEntryFieldIsNull() {
            try (Relix relix = sessionOver(stats("EMEA", object("gb", num("7"))))) {
                assertThat(relix.relation("""
                        π region, e.nosuchfield → missing (
                          μ e ( π region, Entries(orders_by_country) → e (RegionStats) )
                        )""")).rows()
                        .hasRowCount(1)
                        .hasRowAt(0, "EMEA", "NULL");
            }
        }
    }
}
