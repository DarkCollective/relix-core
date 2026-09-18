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
package com.darkcollective.relix.connectors.std;

import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.connector.RelixConnector;
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.DateValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads a GEDCOM genealogy file as a pair of relations.
 *
 * <p>One file holds many kinds of record, which is why this is a <em>connection</em>
 * rather than a file source: a CSV file is one relation and a {@code .ged} file is
 * several, and a connection is exactly the thing several tables share.
 *
 * <pre>{@code
 * connection ancestry from gedcom { path: "family.ged" };
 *
 * source Individuals from ancestry { table: "individuals",
 *     schema: { id: STRING, name: STRING, surname: STRING, sex: STRING,
 *               birth: { date: DATE, text: STRING, place: STRING } } };
 * }</pre>
 *
 * <h2>Two tables</h2>
 * {@code individuals} and {@code families}. Their fields are
 * {@code id, name, surname, sex, birth, death} and
 * {@code id, husband, wife, children} respectively, where an event
 * ({@code birth}/{@code death}) has {@code date}, {@code text} and {@code place}, and
 * {@code children} is an array of individual ids.
 *
 * <h2>The declared schema decides the heading</h2>
 * Every column is filled <strong>by name</strong>, at every level, and a column this
 * connector has nothing for is NULL rather than an error. So a schema naming only
 * {@code id} and {@code name} gets two columns, and one naming a nested
 * {@code birth: { place: STRING }} gets a one-field struct. That rule is what makes a
 * format as extensible as GEDCOM safe to declare a heading over: the file cannot widen
 * a relation behind the query's back.
 *
 * <p>A family is a <strong>hyperedge</strong> — two parents and any number of children —
 * so the binary parent→child edge the graph operators take is a projection over
 * {@code μ children (families)} rather than something this connector invents.
 */
public final class GedcomConnector implements RelixConnector {

    /** The two tables a GEDCOM file presents. */
    private static final String INDIVIDUALS = "individuals";
    private static final String FAMILIES = "families";

    /** A dated, placed event — the shape {@code birth} and {@code death} share. */
    private static final StructType EVENT = new StructType(List.of(
            new StructType.Field("date", ScalarType.DATE),
            new StructType.Field("text", ScalarType.STRING),
            new StructType.Field("place", ScalarType.STRING)));

    /** Public no-arg constructor required for {@link java.util.ServiceLoader} discovery. */
    public GedcomConnector() {
    }

    @Override
    public Set<String> handles() {
        return Set.of("gedcom");
    }

    /**
     * {@inheritDoc}
     *
     * <p>GEDCOM's shape is fixed by the format rather than by the file, so both tables are
     * described without opening anything. That is also what makes a declared schema
     * optional rather than obligatory: a dotted reference resolves its columns here, and a
     * script that wants fewer columns — or different names for them — still declares them.
     *
     * <p>Answering without reading the file means an unreadable or absent one does not stop
     * analysis, which is the same degradation a database that will not connect gets.
     */
    @Override
    public Optional<Schema> tableSchema(ConnectorConfig config, String table) {
        String requested = table == null ? "" : table.toLowerCase(Locale.ROOT);
        return switch (requested) {
            case INDIVIDUALS -> Optional.of(new Schema(List.of(
                    new ColumnDefinition("id", ScalarType.STRING),
                    new ColumnDefinition("name", ScalarType.STRING),
                    new ColumnDefinition("surname", ScalarType.STRING),
                    new ColumnDefinition("sex", ScalarType.STRING),
                    new ColumnDefinition("birth", EVENT),
                    new ColumnDefinition("death", EVENT))));
            case FAMILIES -> Optional.of(new Schema(List.of(
                    new ColumnDefinition("id", ScalarType.STRING),
                    new ColumnDefinition("husband", ScalarType.STRING),
                    new ColumnDefinition("wife", ScalarType.STRING),
                    new ColumnDefinition("children", new ArrayType(ScalarType.STRING)))));
            default -> Optional.empty();
        };
    }

    @Override
    public Stream<Row> open(ConnectorConfig config, String table, Schema schema) {
        Path path = Path.of(filePath(config));
        String requested = table == null ? "" : table.toLowerCase(Locale.ROOT);

        List<GedcomReader.Record> records = GedcomReader.read(path);
        List<Row> rows = new ArrayList<>();
        switch (requested) {
            case INDIVIDUALS -> collect(records, "INDI", GedcomReader::individual, schema, rows);
            case FAMILIES -> collect(records, "FAM", GedcomReader::family, schema, rows);
            default -> throw new EvaluationException(
                    "GEDCOM source has no table '" + table + "'; a .ged file presents '"
                    + INDIVIDUALS + "' and '" + FAMILIES + "'");
        }
        return rows.stream();
    }

    /**
     * {@return the path to the {@code .ged} file} Accepts {@code path}, or {@code url}
     * with an optional {@code file:} scheme — a connection's URL is what every other
     * connector type is configured with, so a GEDCOM connection answers to both rather
     * than making the user remember which one this is.
     */
    private static String filePath(ConnectorConfig config) {
        String path = config.get("path").orElse("");
        if (!path.isBlank()) {
            return path;
        }
        String url = config.require("url");
        return url.startsWith("file:") ? url.substring("file:".length()) : url;
    }

    private static void collect(List<GedcomReader.Record> records, String tag,
                                java.util.function.Function<GedcomReader.Record,
                                        Map<String, Object>> reader,
                                Schema schema, List<Row> rows) {
        for (GedcomReader.Record record : records) {
            if (record.tag().equals(tag)) {
                rows.add(row(reader.apply(record), schema));
            }
        }
    }

    /** One record projected onto {@code schema}, by column name at every level. */
    private static Row row(Map<String, Object> fields, Schema schema) {
        List<Value> values = new ArrayList<>(schema.columns().size());
        for (ColumnDefinition column : schema.columns()) {
            values.add(value(fields.get(column.name()), column.type()));
        }
        return ArrayRow.of(schema, values);
    }

    /**
     * {@return {@code raw} as the {@link Value} {@code type} asks for} A nested type
     * descends into the map or list beneath it; anything absent is NULL.
     */
    @SuppressWarnings("unchecked")
    private static Value value(Object raw, Type type) {
        if (raw == null) {
            return NullValue.INSTANCE;
        }
        if (type instanceof StructType struct && raw instanceof Map<?, ?> map) {
            Map<String, Value> out = new LinkedHashMap<>();
            struct.fields().forEach(field ->
                    out.put(field.name(), value(((Map<String, Object>) map).get(field.name()),
                            field.type())));
            return new StructValue(out);
        }
        if (raw instanceof List<?> list) {
            List<Value> out = new ArrayList<>(list.size());
            Type element = type instanceof com.darkcollective.relix.symbol.ArrayType array
                    ? array.element() : null;
            list.forEach(item -> out.add(element == null
                    ? scalar(item) : value(item, element)));
            return new ArrayValue(out);
        }
        return scalar(raw);
    }

    private static Value scalar(Object raw) {
        return switch (raw) {
            case null -> NullValue.INSTANCE;
            case LocalDate d -> new DateValue(d);
            case Value v -> v;
            default -> new StringValue(raw.toString());
        };
    }
}
