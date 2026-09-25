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
import com.darkcollective.relix.processor.connector.RelixConnector;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.TimeValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.Type;
import org.bson.Document;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A {@link RelixConnector} for MongoDB collections.
 *
 * <p>Handles the {@code "mongodb"} type token.  A {@code connection NAME from mongodb
 * { uri: …, database: … }} plus a {@code source S from NAME { table: "<collection>",
 * schema: { … } }} binding maps a collection to a relation: each document becomes a
 * row, reading the schema's declared columns by field name and coercing each value to
 * the column's type.
 *
 * <p>This is the reference <em>external</em> connector — it ships as a plugin JAR (with
 * its MongoDB driver) dropped into {@code ~/.relix/connectors/} and is discovered via
 * {@link java.util.ServiceLoader}.  Configuration keys: {@code uri} (required) and
 * {@code database} (required).
 *
 * <p>Whole-collection reads go through {@code open}; when the planner folds σ/μ (and
 * cheap π/λ) over a Mongo source into an aggregation pipeline,
 * {@link #openQuery} runs that pipeline instead, and the remaining operators run
 * in-engine.  The pushed query is a JSON envelope
 * {@code {"collection": "<name>", "pipeline": [ <stage>, … ]}} produced by
 * {@code MongoPushdownPlanner}.
 */
public final class MongoConnector implements RelixConnector {

    private final DocumentSource documentSource;

    /** Public no-arg constructor for {@link java.util.ServiceLoader}; uses the live driver. */
    public MongoConnector() {
        this(new MongoDocumentSource());
    }

    /** Package-private constructor injecting the document source (for tests). */
    MongoConnector(DocumentSource documentSource) {
        this.documentSource = Objects.requireNonNull(documentSource, "documentSource");
    }

    @Override
    public Set<String> handles() {
        return Set.of("mongodb");
    }

    /**
     * Releases the shared MongoDB client and its connection pool.
     *
     * <p>Called by {@code ConnectorRegistry.close()}, which the runtime's connector closes
     * in turn — so a query's clients live as long as the session that opened them rather
     * than as long as one stream.
     */
    @Override
    public void close() {
        documentSource.close();
    }

    @Override
    public Stream<Row> open(ConnectorConfig config, String table, Schema schema) {
        Objects.requireNonNull(schema, "schema");
        return documentSource.documents(config, table).map(doc -> toRow(doc, schema));
    }

    /**
     * Runs a pushed aggregation pipeline.  The {@code query} is the JSON
     * envelope {@code {"collection": "<name>", "pipeline": [ <stage>, … ]}} produced by
     * {@code MongoPushdownPlanner}; its pipeline runs against the named collection and
     * each result document maps to a row by the declared schema (as for {@link #open}).
     */
    @Override
    public Stream<Row> openQuery(ConnectorConfig config, String query, Schema schema) {
        Objects.requireNonNull(schema, "schema");
        Document envelope = Document.parse(query);
        String collection = envelope.getString("collection");
        List<Map<String, Object>> pipeline = new ArrayList<>();
        for (Document stage : envelope.getList("pipeline", Document.class)) {
            pipeline.add(stage);   // org.bson.Document is a Map<String,Object>
        }
        return documentSource.aggregate(config, collection, pipeline).map(doc -> toRow(doc, schema));
    }

    /**
     * Maps one document to a row: each declared column is read from the document by
     * name (case-sensitive, as stored) and coerced to the column's type; an absent or
     * null field becomes {@code NULL}.
     */
    static Row toRow(Map<String, Object> document, Schema schema) {
        List<Value> values = new ArrayList<>(schema.width());
        for (ColumnDefinition column : schema.columns()) {
            values.add(coerce(document.get(column.name()), column.type()));
        }
        return Row.of(schema, values);
    }

    /**
     * Coerces a raw BSON value to a relix {@link Value} of the column's declared type.
     *
     * <p>The switch is over the whole {@link Type}, not only its scalar shadow.  It used
     * to read {@code type instanceof ScalarType s ? s : ScalarType.ANY}, which quietly
     * discarded a nested declaration: a {@code StructType} column fell through to the
     * {@code ANY} arm and became {@code StringValue("Document{{city=Berlin}}")} — the BSON
     * document's Java {@code toString}, from the one connector whose store is built out of
     * nested documents.
     */
    static Value coerce(Object raw, Type type) {
        if (raw == null) {
            return NullValue.INSTANCE;
        }
        return switch (type) {
            case ScalarType scalar -> scalar(raw, scalar);
            case StructType struct -> struct(raw, struct);
            case ArrayType array   -> array(raw, array);
        };
    }

    private static Value scalar(Object raw, ScalarType type) {
        return switch (type) {
            case NUMBER  -> number(raw);
            case STRING  -> new StringValue(raw.toString());
            case BOOLEAN -> bool(raw);
            case ANY     -> any(raw);
            // BSON Date is an instant; DATE/TIME columns are schema-declared, since
            // Mongo has only the instant Date (ADR-0013 slice 5).
            case DATE, TIME, TIMESTAMP, DURATION -> temporal(raw, type);
        };
    }

    /**
     * Reads a BSON sub-document as a {@link StructValue}, one declared field at a time.
     *
     * <p>Fields are taken from the declaration rather than from the document, so the value
     * has the heading the schema promised: a field the document omits is {@code NULL}, and
     * one it carries but the schema does not declare is dropped.  That is the same rule
     * {@link #toRow} applies to a row, one level down.
     *
     * <p>A value that is not a document cannot be read as one, so it is {@code NULL} rather
     * than a struct with every field missing — the second would claim the field was present
     * and empty.
     */
    private static Value struct(Object raw, StructType type) {
        if (!(raw instanceof Map<?, ?> document)) {
            return NullValue.INSTANCE;
        }
        Map<String, Value> fields = new LinkedHashMap<>();
        for (StructType.Field field : type.fields()) {
            fields.put(field.name(), coerce(document.get(field.name()), field.type()));
        }
        return new StructValue(fields);
    }

    /** Reads a BSON array as an {@link ArrayValue}, coercing each element to the declared one. */
    private static Value array(Object raw, ArrayType type) {
        if (!(raw instanceof List<?> elements)) {
            return NullValue.INSTANCE;
        }
        List<Value> values = new ArrayList<>(elements.size());
        for (Object element : elements) {
            values.add(coerce(element, type.element()));
        }
        return new ArrayValue(values);
    }

    /**
     * Maps a BSON value to a temporal {@link Value}.  A BSON {@code Date} (a
     * {@link java.util.Date}/{@link Instant}) is an absolute instant: it becomes a
     * {@code TimestampValue} directly, or a UTC civil {@code DATE}/{@code TIME} when
     * the column is declared as such.  A string value is parsed against ISO-8601.
     */
    private static Value temporal(Object raw, ScalarType type) {
        Instant instant = switch (raw) {
            case Date d   -> d.toInstant();
            case Instant i -> i;
            default       -> null;
        };
        try {
            return switch (type) {
                case TIMESTAMP -> new TimestampValue(instant != null
                        ? instant : parseTimestamp(text(raw)));
                case DATE -> new DateValue(instant != null
                        ? LocalDate.ofInstant(instant, ZoneOffset.UTC)
                        : LocalDate.parse(text(raw)));
                case TIME -> new TimeValue(instant != null
                        ? LocalTime.ofInstant(instant, ZoneOffset.UTC)
                        : LocalTime.parse(text(raw)));
                case DURATION -> new DurationValue(raw instanceof Duration d
                        ? d : Duration.parse(text(raw)));
                default -> throw new IllegalStateException("not a temporal type: " + type);
            };
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    "Mongo: cannot read '" + raw + "' as " + type.name(), e);
        }
    }

    /**
     * The text of a temporal payload, stripped.
     *
     * <p>Stripped because the CSV connector strips its cell before parsing and these two
     * read the same ISO-8601 payloads: without it {@code " 2026-06-15 "} parses from one
     * source and fails from the other, for no reason a user could see.
     */
    private static String text(Object raw) {
        return raw.toString().strip();
    }

    private static Value number(Object raw) {
        if (raw instanceof Number n) {
            return new NumberValue(new BigDecimal(n.toString()));
        }
        try {
            return new NumberValue(new BigDecimal(raw.toString().strip()));
        } catch (NumberFormatException notANumber) {
            return NullValue.INSTANCE;
        }
    }

    private static Value bool(Object raw) {
        if (raw instanceof Boolean b) {
            return BooleanValue.of(b);
        }
        String text = raw.toString().strip().toLowerCase(Locale.ROOT);
        if (text.equals("true")) {
            return BooleanValue.of(true);
        }
        if (text.equals("false")) {
            return BooleanValue.of(false);
        }
        return NullValue.INSTANCE;
    }

    /**
     * Reads a value the schema did not describe — the schema-on-read case.
     *
     * <p>Nested values recurse rather than falling through to {@code toString()}: an
     * {@code ANY} column over a sub-document is the ordinary shape in a document store, and
     * a {@code StringValue} of a BSON {@code Document} is unusable by {@code ValuePath},
     * {@code μ} or anything else that reads structure.  A BSON {@code Date} becomes the
     * instant it is, for the same reason — {@code java.util.Date.toString()} is a display
     * format, not a value.
     */
    private static Value any(Object raw) {
        if (raw instanceof Map<?, ?> document) {
            Map<String, Value> fields = new LinkedHashMap<>();
            document.forEach((key, value) -> fields.put(String.valueOf(key), coerceAny(value)));
            return new StructValue(fields);
        }
        if (raw instanceof List<?> elements) {
            List<Value> values = new ArrayList<>(elements.size());
            for (Object element : elements) {
                values.add(coerceAny(element));
            }
            return new ArrayValue(values);
        }
        if (raw instanceof Number n) {
            return new NumberValue(new BigDecimal(n.toString()));
        }
        if (raw instanceof Boolean b) {
            return BooleanValue.of(b);
        }
        if (raw instanceof Date d) {
            return new TimestampValue(d.toInstant());
        }
        if (raw instanceof Instant i) {
            return new TimestampValue(i);
        }
        return new StringValue(raw.toString());
    }

    /** {@link #any} with the NULL check, for a nested element that may be absent. */
    private static Value coerceAny(Object raw) {
        return raw == null ? NullValue.INSTANCE : any(raw);
    }

    /** ISO-8601 text as an instant: with an offset as written, without one as UTC. */
    private static Instant parseTimestamp(String iso) {
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (DateTimeParseException withoutOffset) {
            return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC);
        }
    }
}
