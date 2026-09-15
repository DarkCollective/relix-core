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
package com.darkcollective.relix.mongo;

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.StructValue;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link MongoConnector} — document-to-row mapping and per-type coercion,
 * exercised against a fake {@link DocumentSource} (no running MongoDB).
 */
final class MongoConnectorTest {

    private static Schema schema(ColumnDefinition... columns) {
        return new Schema(List.of(columns));
    }

    private static ColumnDefinition col(String name, ScalarType type) {
        return new ColumnDefinition(name, type);
    }

    /** A fake document source returning the given documents regardless of config/collection. */
    @SafeVarargs
    private static DocumentSource source(Map<String, Object>... docs) {
        List<Map<String, Object>> list = List.of(docs);
        return (config, collection) -> list.stream();
    }

    private static Map<String, Object> doc(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    @Test
    void handlesMongodbToken() {
        assertThat(new MongoConnector().handles()).containsExactly("mongodb");
    }

    @Test
    void mapsDocumentsToRowsByDeclaredSchema() {
        Schema schema = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
        MongoConnector connector = new MongoConnector(source(
                doc("id", 1, "name", "alice"),
                doc("id", 2, "name", "bob")));

        try (var rows = connector.open(ConnectorConfig.empty(), "users", schema)) {
            List<Row> result = rows.toList();
            assertThat(result).hasSize(2);
            assertThat(result.get(0).get("id")).isEqualTo(new NumberValue(new BigDecimal("1")));
            assertThat(result.get(0).get("name")).isEqualTo(new StringValue("alice"));
            assertThat(result.get(1).get("name")).isEqualTo(new StringValue("bob"));
        }
    }

    @Test
    void absentFieldBecomesNull() {
        Schema schema = schema(col("id", ScalarType.NUMBER), col("missing", ScalarType.STRING));
        MongoConnector connector = new MongoConnector(source(doc("id", 7)));

        Row row = connector.open(ConnectorConfig.empty(), "c", schema).toList().get(0);
        assertThat(row.get("missing")).isEqualTo(NullValue.INSTANCE);
    }

    @Test
    void numberCoercionFromStringAndNonNumeric() {
        assertThat(MongoConnector.coerce("42", ScalarType.NUMBER))
                .isEqualTo(new NumberValue(new BigDecimal("42")));
        assertThat(MongoConnector.coerce("not-a-number", ScalarType.NUMBER))
                .isEqualTo(NullValue.INSTANCE);
        assertThat(MongoConnector.coerce(3.5, ScalarType.NUMBER))
                .isEqualTo(new NumberValue(new BigDecimal("3.5")));
    }

    @Test
    void booleanCoercion() {
        assertThat(MongoConnector.coerce(true, ScalarType.BOOLEAN)).isEqualTo(BooleanValue.of(true));
        assertThat(MongoConnector.coerce("false", ScalarType.BOOLEAN)).isEqualTo(BooleanValue.of(false));
        assertThat(MongoConnector.coerce("maybe", ScalarType.BOOLEAN)).isEqualTo(NullValue.INSTANCE);
    }

    @Test
    void anyCoercionPreservesKind() {
        assertThat(MongoConnector.coerce(5, ScalarType.ANY)).isEqualTo(new NumberValue(new BigDecimal("5")));
        assertThat(MongoConnector.coerce(true, ScalarType.ANY)).isEqualTo(BooleanValue.of(true));
        assertThat(MongoConnector.coerce("hi", ScalarType.ANY)).isEqualTo(new StringValue("hi"));
    }

    @Test
    void nullRawIsNull() {
        assertThat(MongoConnector.coerce(null, ScalarType.STRING)).isEqualTo(NullValue.INSTANCE);
    }

    @Test
    void temporalCoercion() {
        Instant instant = Instant.parse("2026-06-15T13:40:00Z");
        Date bsonDate = Date.from(instant);

        // BSON Date (and java.time.Instant) → TimestampValue (the instant).
        assertThat(MongoConnector.coerce(bsonDate, ScalarType.TIMESTAMP))
                .isEqualTo(new TimestampValue(instant));
        assertThat(MongoConnector.coerce(instant, ScalarType.TIMESTAMP))
                .isEqualTo(new TimestampValue(instant));

        // A BSON Date read into a declared DATE / TIME column → its UTC civil parts.
        assertThat(MongoConnector.coerce(bsonDate, ScalarType.DATE))
                .isEqualTo(new DateValue(LocalDate.parse("2026-06-15")));
        assertThat(MongoConnector.coerce(bsonDate, ScalarType.TIME))
                .isEqualTo(new TimeValue(LocalTime.parse("13:40:00")));

        // ISO-8601 strings parse for schema-on-read / dynamic data.
        assertThat(MongoConnector.coerce("2026-06-15", ScalarType.DATE))
                .isEqualTo(new DateValue(LocalDate.parse("2026-06-15")));
        assertThat(MongoConnector.coerce("2026-06-15T14:40:00+01:00", ScalarType.TIMESTAMP))
                .isEqualTo(new TimestampValue(instant));   // offset normalised to UTC
        assertThat(MongoConnector.coerce("PT30M", ScalarType.DURATION))
                .isEqualTo(new DurationValue(Duration.parse("PT30M")));
    }

    // ── nested values ────────────────────────────────────────────────────────
    //
    // MongoDB is the one document store the engine supports, and coerce() used to read
    // `type instanceof ScalarType s ? s : ScalarType.ANY`, so a declared StructType or
    // ArrayType column fell through to the ANY arm and became the BSON document's Java
    // toString: StringValue("Document{{city=Berlin, zip=10115}}"). Unusable by ValuePath,
    // by μ, or by anything else that reads structure — and a divergence besides, since
    // MongoPushdownPlanner folds μ into $unwind and the *pushed* plan got it right.

    @Test
    void declaredStructColumnBecomesAStructValue() {
        Document address = new Document("city", "Berlin").append("zip", "10115");
        StructType type = new StructType(List.of(
                new StructType.Field("city", ScalarType.STRING),
                new StructType.Field("zip", ScalarType.STRING)));

        assertThat(MongoConnector.coerce(address, type)).isEqualTo(new StructValue(Map.of(
                "city", new StringValue("Berlin"),
                "zip", new StringValue("10115"))));
    }

    @Test
    void aStructTakesItsHeadingFromTheSchema() {
        // Declared-but-absent is NULL; present-but-undeclared is dropped. The same rule
        // toRow applies to a row, one level down — so the value has the heading the schema
        // promised rather than whatever this particular document happened to carry.
        Document sparse = new Document("city", "Berlin").append("undeclared", "x");
        StructType type = new StructType(List.of(
                new StructType.Field("city", ScalarType.STRING),
                new StructType.Field("zip", ScalarType.STRING)));

        assertThat(MongoConnector.coerce(sparse, type)).isEqualTo(new StructValue(Map.of(
                "city", new StringValue("Berlin"),
                "zip", NullValue.INSTANCE)));
    }

    @Test
    void declaredArrayColumnBecomesAnArrayValueOfTheElementType() {
        assertThat(MongoConnector.coerce(List.of(1, 2, 3), new ArrayType(ScalarType.NUMBER)))
                .isEqualTo(new ArrayValue(List.of(
                        new NumberValue(new BigDecimal("1")),
                        new NumberValue(new BigDecimal("2")),
                        new NumberValue(new BigDecimal("3")))));
    }

    @Test
    void nestingComposesToAnyDepth() {
        Document raw = new Document("items", List.of(
                new Document("sku", "A").append("qty", 2),
                new Document("sku", "B").append("qty", 5)));
        StructType item = new StructType(List.of(
                new StructType.Field("sku", ScalarType.STRING),
                new StructType.Field("qty", ScalarType.NUMBER)));
        StructType order = new StructType(List.of(
                new StructType.Field("items", new ArrayType(item))));

        assertThat(MongoConnector.coerce(raw, order)).isEqualTo(new StructValue(Map.of(
                "items", new ArrayValue(List.of(
                        new StructValue(Map.of("sku", new StringValue("A"),
                                "qty", new NumberValue(new BigDecimal("2")))),
                        new StructValue(Map.of("sku", new StringValue("B"),
                                "qty", new NumberValue(new BigDecimal("5")))))))));
    }

    @Test
    void aScalarUnderANestedDeclarationIsNullRatherThanAMalformedStruct() {
        // "not a document" is not the same claim as "a document whose fields are all
        // missing", and the second would say the field was present and empty.
        StructType type = new StructType(List.of(new StructType.Field("city", ScalarType.STRING)));

        assertThat(MongoConnector.coerce("Berlin", type)).isEqualTo(NullValue.INSTANCE);
        assertThat(MongoConnector.coerce("nope", new ArrayType(ScalarType.STRING)))
                .isEqualTo(NullValue.INSTANCE);
    }

    @Test
    void schemaOnReadRecursesRatherThanStringifying() {
        // An ANY column over a sub-document is the ordinary shape in a document store.
        Document raw = new Document("city", "Berlin").append("tags", List.of("a", "b"));

        assertThat(MongoConnector.coerce(raw, ScalarType.ANY)).isEqualTo(new StructValue(Map.of(
                "city", new StringValue("Berlin"),
                "tags", new ArrayValue(List.of(new StringValue("a"), new StringValue("b"))))));
    }

    @Test
    void schemaOnReadReadsABsonDateAsTheInstantItIs() {
        // java.util.Date.toString() is a display format, not a value — an undeclared
        // timestamp used to arrive as "Mon Jun 15 13:40:00 UTC 2026".
        Instant instant = Instant.parse("2026-06-15T13:40:00Z");

        assertThat(MongoConnector.coerce(Date.from(instant), ScalarType.ANY))
                .isEqualTo(new TimestampValue(instant));
        assertThat(MongoConnector.coerce(instant, ScalarType.ANY))
                .isEqualTo(new TimestampValue(instant));
    }

    @Test
    void schemaOnReadKeepsANullNestedElement() {
        Document raw = new Document("city", null);

        assertThat(MongoConnector.coerce(raw, ScalarType.ANY))
                .isEqualTo(new StructValue(Map.of("city", NullValue.INSTANCE)));
    }

    // ── the temporal paths the unit tests never reached ──────────────────────

    @Test
    void temporalStringsParseForEveryKind() {
        // TIME-from-a-string and the offset-less timestamp form were both uncovered; the
        // second is the interesting branch, since it is the one TemporalLiterals has a
        // fallback for.
        assertThat(MongoConnector.coerce("13:40:00", ScalarType.TIME))
                .isEqualTo(new TimeValue(LocalTime.parse("13:40:00")));
        assertThat(MongoConnector.coerce("2026-06-15T13:40:00", ScalarType.TIMESTAMP))
                .as("a payload with no offset is read as UTC")
                .isEqualTo(new TimestampValue(Instant.parse("2026-06-15T13:40:00Z")));
        assertThat(MongoConnector.coerce(Duration.parse("PT30M"), ScalarType.DURATION))
                .as("a java.time.Duration is taken as-is, not re-parsed")
                .isEqualTo(new DurationValue(Duration.parse("PT30M")));
    }

    @Test
    void temporalPayloadsAreStrippedLikeACsvCell() {
        // The CSV connector strips its cell before parsing. Without the same rule here,
        // " 2026-06-15 " parses from one source and fails from the other for no reason a
        // user could see.
        assertThat(MongoConnector.coerce("  2026-06-15  ", ScalarType.DATE))
                .isEqualTo(new DateValue(LocalDate.parse("2026-06-15")));
    }

    @Test
    void aMalformedTemporalNamesTheValueAndTheType() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> MongoConnector.coerce("not-a-date", ScalarType.DATE))
                .withMessageContaining("not-a-date")
                .withMessageContaining("DATE");
    }

    @Test
    void openQueryDelegatesToAggregateWhichIsUnsupportedByDefault() {
        // A document source that only implements documents() leaves aggregate() at its
        // default (throwing), so a pushed pipeline over it surfaces as unsupported.
        String envelope = "{\"collection\": \"c\", \"pipeline\": []}";
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() ->
                new MongoConnector(source()).openQuery(
                        ConnectorConfig.empty(), envelope, schema(col("x", ScalarType.ANY)))
                        .toList());
    }

    /** A recording document source that captures the aggregate call and returns canned docs. */
    private static final class RecordingSource implements DocumentSource {
        String collection;
        java.util.List<Map<String, Object>> pipeline;
        private final java.util.List<Map<String, Object>> result;

        RecordingSource(java.util.List<Map<String, Object>> result) {
            this.result = result;
        }

        @Override
        public java.util.stream.Stream<Map<String, Object>> documents(ConnectorConfig config, String collection) {
            throw new AssertionError("openQuery must not call documents()");
        }

        @Override
        public java.util.stream.Stream<Map<String, Object>> aggregate(
                ConnectorConfig config, String collection, java.util.List<Map<String, Object>> pipeline) {
            this.collection = collection;
            this.pipeline = pipeline;
            return result.stream();
        }
    }

    @Test
    void openQueryParsesEnvelopeAndRunsPipeline() {
        RecordingSource recording = new RecordingSource(List.of(doc("id", 1, "name", "alice")));
        Schema schema = schema(col("id", ScalarType.NUMBER), col("name", ScalarType.STRING));
        String envelope = "{\"collection\": \"users\", \"pipeline\": "
                + "[{\"$match\": {\"id\": {\"$gt\": 0}}}, {\"$unwind\": \"$tags\"}]}";

        try (var rows = new MongoConnector(recording).openQuery(ConnectorConfig.empty(), envelope, schema)) {
            List<Row> result = rows.toList();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("name")).isEqualTo(new StringValue("alice"));
        }

        assertThat(recording.collection).isEqualTo("users");
        assertThat(recording.pipeline).hasSize(2);
        assertThat(recording.pipeline.get(0)).containsKey("$match");
        assertThat(recording.pipeline.get(1)).containsEntry("$unwind", "$tags");
    }

    @Test
    void openQueryWithEmptyPipelineRunsAndMapsRows() {
        RecordingSource recording = new RecordingSource(List.of(doc("id", 2)));
        Schema schema = schema(col("id", ScalarType.NUMBER));

        try (var rows = new MongoConnector(recording).openQuery(
                ConnectorConfig.empty(), "{\"collection\": \"c\", \"pipeline\": []}", schema)) {
            assertThat(rows.toList().get(0).get("id")).isEqualTo(new NumberValue(new BigDecimal("2")));
        }
        assertThat(recording.pipeline).isEmpty();
    }
}
