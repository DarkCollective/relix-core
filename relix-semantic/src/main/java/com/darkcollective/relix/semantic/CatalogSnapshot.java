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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.json.JsonReader;
import com.darkcollective.relix.json.JsonWriter;
import com.darkcollective.relix.lang.ast.ConnectionDeclaration;
import com.darkcollective.relix.lang.ast.source.ConnectionTableSourceConfig;
import com.darkcollective.relix.symbol.ArrayType;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ColumnStatistics;
import com.darkcollective.relix.symbol.RelationStatistics;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.StructType;
import com.darkcollective.relix.symbol.Type;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Table metadata captured once and replayed later, so a session composes and
 * <em>costs</em> a query with no database in reach.
 *
 * <p>A relation composes, renders and optimises offline already. Metadata, however,
 * genuinely does come <em>from</em> the database, so "works offline" and "works well" pull
 * apart unless it can be supplied some other way. There are three ways over one seam —
 * live introspection, a schema declared in the script, and this: introspect once,
 * serialize, replay.
 *
 * <p>It is a {@link CatalogProvider}, so replaying it is a matter of handing it to the
 * analyser where a live provider would go. Statistics ride the same seam as schemas, so a
 * snapshot carries both and a snapshot-backed session reaches the same cost-based
 * decisions the live one would: candidate keys for merge joins, distinct counts for
 * selectivity, row counts for the build side of a hash join. The practical shape of that
 * is tuning or validating a production query from a machine with no access to production.
 *
 * {@snippet lang = "java":
 * // Against the live database, once:
 * CatalogSnapshot snapshot = CatalogSnapshot.capture(liveCatalog, model, Clock.systemUTC());
 * Files.writeString(path, snapshot.toJson());
 *
 * // Anywhere, afterwards:
 * CatalogProvider offline = CatalogSnapshot.parse(Files.readString(path));
 * }
 *
 * <h2>It is a log of observations, not a merged picture</h2>
 *
 * <p>Every {@link Entry} records where it came from and when. That is not decoration: a
 * snapshot is a cache, every cache goes stale, and a plan optimised from statistics that
 * no longer describe the database is harder to notice than a missing plan, because it is
 * still a correct answer arrived at badly.
 *
 * <p>So entries are never merged into one another. A table may carry several — an
 * introspected one and, later, one recorded from a run that measured the real thing — and
 * a lookup picks per field: {@link Origin#OBSERVED} ahead of {@link Origin#INTROSPECTED},
 * and within one origin the most recent. A measured row count therefore beats a
 * {@code DatabaseMetaData} estimate without discarding the schema that came with the
 * estimate, and nothing has to invent an origin for an entry assembled from two.
 *
 * <h2>What it is not</h2>
 *
 * <p>Statistics are advisory everywhere in the engine, and a snapshot changes nothing
 * about that: a stale row count costs a worse plan, never a wrong answer. A stale
 * <em>schema</em> is different — a column that has since been dropped resolves here and
 * fails at execution — which is what the capture time is for.
 */
public final class CatalogSnapshot implements CatalogProvider {

    /** The format version written into every document, so a reader can refuse a future one. */
    private static final long FORMAT_VERSION = 1;

    /** Where one entry's knowledge came from. */
    public enum Origin {

        /** Read from the database's own catalog — what {@code DatabaseMetaData} says. */
        INTROSPECTED,

        /**
         * Measured by a run over the real data. Preferred over an introspected entry
         * field by field, because a count of what was actually delivered beats an
         * estimate of what is there.
         */
        OBSERVED;

        String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Origin of(String token) {
            return valueOf(token.toUpperCase(Locale.ROOT));
        }
    }

    /**
     * One observation of one table.
     *
     * <p>Both halves are optional and independently so: introspection may yield a schema
     * and no statistics (a table it could describe but not count), and a run yields
     * statistics and no schema.
     *
     * @param connection the declared connection's name
     * @param table      the table name within it, as the script spells it
     * @param schema     the heading, when this observation carries one
     * @param statistics the statistics, when this observation carries them
     * @param origin     where the observation came from
     * @param captured   when it was made
     */
    public record Entry(String connection, String table, Optional<Schema> schema,
                        Optional<RelationStatistics> statistics, Origin origin, Instant captured) {

        public Entry {
            Objects.requireNonNull(connection, "connection");
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(statistics, "statistics");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(captured, "captured");
        }

        /** The lookup key: connection and table, case-folded as every name is. */
        String key() {
            return connection.toLowerCase(Locale.ROOT) + "." + table.toLowerCase(Locale.ROOT);
        }

        /** Whether this observation says anything at all — an empty one is not recorded. */
        boolean isEmpty() {
            return schema.isEmpty() && statistics.isEmpty();
        }
    }

    /** Highest precedence first: observed before introspected, recent before old. */
    private static final Comparator<Entry> PRECEDENCE =
            Comparator.comparing((Entry e) -> e.origin() == Origin.OBSERVED ? 0 : 1)
                    .thenComparing(Entry::captured, Comparator.reverseOrder());

    private final List<Entry> entries;
    private final Map<String, List<Entry>> byKey;
    private final Instant captured;

    private CatalogSnapshot(List<Entry> entries, Instant captured) {
        this.entries = List.copyOf(entries);
        this.captured = Objects.requireNonNull(captured, "captured");
        Map<String, List<Entry>> index = new LinkedHashMap<>();
        for (Entry entry : this.entries) {
            index.computeIfAbsent(entry.key(), unused -> new ArrayList<>()).add(entry);
        }
        index.values().forEach(list -> list.sort(PRECEDENCE));
        this.byKey = index;
    }

    /**
     * A snapshot over observations the caller already holds.
     *
     * @param entries  the observations, in any order; must not be null
     * @param captured when the snapshot as a whole was assembled
     * @return the snapshot
     */
    public static CatalogSnapshot of(Collection<Entry> entries, Instant captured) {
        return new CatalogSnapshot(new ArrayList<>(Objects.requireNonNull(entries, "entries")),
                captured);
    }

    /** A snapshot that knows nothing — the shape {@link CatalogProvider#NONE} already has. */
    public static CatalogSnapshot empty() {
        return new CatalogSnapshot(List.of(), Instant.EPOCH);
    }

    /**
     * Introspects every connection-backed table {@code model} names, through
     * {@code live}, and records what comes back.
     *
     * <p>The model is what says which tables matter: a source bound to a connection
     * declares one, and a dotted reference the analyser has already resolved has become
     * one. So capturing against a live database yields exactly the metadata the same
     * script will ask for again offline — no more, and nothing guessed at.
     *
     * <p>A table the live provider says nothing about is not recorded. An entry carrying
     * neither a schema nor statistics is indistinguishable, on replay, from a table the
     * snapshot never heard of.
     *
     * @param live  the provider to introspect through; must not be null
     * @param model the analysed model naming the tables; must not be null
     * @param clock the clock the capture time is read from; must not be null
     * @return the snapshot
     */
    public static CatalogSnapshot capture(CatalogProvider live, SemanticModel model, Clock clock) {
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(model, "model");
        Instant now = Objects.requireNonNull(clock, "clock").instant();

        List<Entry> captured = new ArrayList<>();
        for (var source : model.sources().values()) {
            if (!(source.config() instanceof ConnectionTableSourceConfig table)) {
                continue;
            }
            ConnectionDeclaration connection =
                    model.connections().get(table.connection().toLowerCase(Locale.ROOT));
            if (connection == null) {
                continue;   // an undeclared connection is already a diagnostic
            }
            Entry entry = new Entry(connection.name(), table.table(),
                    live.tableSchema(connection, table.table()),
                    live.tableStatistics(connection, table.table()),
                    Origin.INTROSPECTED, now);
            if (!entry.isEmpty()) {
                captured.add(entry);
            }
        }
        return new CatalogSnapshot(captured, now);
    }

    /**
     * This snapshot with {@code entry} added.
     *
     * <p>Added rather than merged: an observation is a fact about a moment, and a table
     * that has been both introspected and measured carries both.
     *
     * @param entry the observation; must not be null
     * @return a new snapshot
     */
    public CatalogSnapshot with(Entry entry) {
        List<Entry> combined = new ArrayList<>(entries);
        combined.add(Objects.requireNonNull(entry, "entry"));
        return new CatalogSnapshot(combined, captured);
    }

    /** {@return every observation, in the order it was recorded} */
    public List<Entry> entries() {
        return entries;
    }

    /** {@return when this snapshot was assembled} */
    public Instant captured() {
        return captured;
    }

    // -------------------------------------------------------------------------
    // Replay
    // -------------------------------------------------------------------------

    @Override
    public Optional<Schema> tableSchema(ConnectionDeclaration connection, String table) {
        return best(connection, table, Entry::schema);
    }

    @Override
    public Optional<RelationStatistics> tableStatistics(
            ConnectionDeclaration connection, String table) {
        return best(connection, table, Entry::statistics);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A snapshot knows exactly the tables it recorded for the connection, so it always
     * enumerates them, in the order they were first recorded.
     */
    @Override
    public Optional<List<String>> tables(ConnectionDeclaration connection) {
        Objects.requireNonNull(connection, "connection");
        return Optional.of(entries.stream()
                .filter(e -> e.connection().equalsIgnoreCase(connection.name()))
                .map(Entry::table)
                .distinct()
                .toList());
    }

    /** The highest-precedence entry that carries the asked-for half, if any does. */
    private <T> Optional<T> best(ConnectionDeclaration connection, String table,
                                 java.util.function.Function<Entry, Optional<T>> half) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(table, "table");
        String key = connection.name().toLowerCase(Locale.ROOT)
                + "." + table.toLowerCase(Locale.ROOT);
        return byKey.getOrDefault(key, List.of()).stream()
                .map(half)
                .flatMap(Optional::stream)
                .findFirst();
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    /**
     * This snapshot as JSON.
     *
     * <p>Round-trips through {@link #parse(String)}. A type is written structurally
     * rather than as its compact IR code, because a code needs a parser and a parser is a
     * second grammar to keep in step with the first.
     *
     * @return the document
     */
    public String toJson() {
        JsonWriter out = new JsonWriter().beginObject()
                .name("version").value(FORMAT_VERSION)
                .name("captured").value(captured.toString())
                .name("tables").beginArray();
        for (Entry entry : entries) {
            out.beginObject()
                    .name("connection").value(entry.connection())
                    .name("table").value(entry.table())
                    .name("origin").value(entry.origin().token())
                    .name("captured").value(entry.captured().toString());
            entry.schema().ifPresent(schema -> writeSchema(out, schema));
            entry.statistics().ifPresent(stats -> writeStatistics(out, stats));
            out.endObject();
        }
        return out.endArray().endObject().toJson();
    }

    /**
     * Reads a snapshot written by {@link #toJson()}.
     *
     * @param json the document; must not be null
     * @return the snapshot
     * @throws IllegalArgumentException if the text is not a snapshot this version reads
     */
    public static CatalogSnapshot parse(String json) {
        Object parsed = JsonReader.parse(Objects.requireNonNull(json, "json"));
        if (!(parsed instanceof Map<?, ?> document)) {
            throw new IllegalArgumentException("not a catalog snapshot: expected an object");
        }
        long version = Long.parseLong(string(document, "version"));
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("catalog snapshot version " + version
                    + " was written by a later release; this one reads version "
                    + FORMAT_VERSION);
        }
        List<Entry> entries = new ArrayList<>();
        for (Object row : list(document, "tables")) {
            Map<?, ?> table = (Map<?, ?>) row;
            entries.add(new Entry(
                    string(table, "connection"),
                    string(table, "table"),
                    Optional.ofNullable(table.get("schema")).map(CatalogSnapshot::readSchema),
                    Optional.ofNullable(table.get("statistics"))
                            .map(CatalogSnapshot::readStatistics),
                    Origin.of(string(table, "origin")),
                    Instant.parse(string(table, "captured"))));
        }
        return new CatalogSnapshot(entries, Instant.parse(string(document, "captured")));
    }

    private static void writeSchema(JsonWriter out, Schema schema) {
        out.name("schema").beginArray();
        for (ColumnDefinition column : schema.columns()) {
            out.beginObject().name("name").value(column.name()).name("type");
            writeType(out, column.type());
            out.endObject();
        }
        out.endArray();
    }

    /**
     * A type as a one-key object naming its kind. Self-describing, so a reader needs no
     * grammar, and diffable, which is half of why a snapshot is worth keeping in a repo.
     */
    private static void writeType(JsonWriter out, Type type) {
        out.beginObject();
        switch (type) {
            case ScalarType scalar -> out.name("scalar").value(scalar.name());
            case ArrayType array -> {
                out.name("array");
                writeType(out, array.element());
            }
            case StructType struct -> {
                out.name("struct").beginArray();
                for (StructType.Field field : struct.fields()) {
                    out.beginObject().name("name").value(field.name()).name("type");
                    writeType(out, field.type());
                    out.endObject();
                }
                out.endArray();
            }
        }
        out.endObject();
    }

    private static Schema readSchema(Object value) {
        List<ColumnDefinition> columns = new ArrayList<>();
        for (Object element : (List<?>) value) {
            Map<?, ?> column = (Map<?, ?>) element;
            columns.add(new ColumnDefinition(string(column, "name"), readType(column.get("type"))));
        }
        return new Schema(columns);
    }

    private static Type readType(Object value) {
        Map<?, ?> type = (Map<?, ?>) value;
        if (type.containsKey("scalar")) {
            return ScalarType.valueOf(string(type, "scalar"));
        }
        if (type.containsKey("array")) {
            return new ArrayType(readType(type.get("array")));
        }
        if (type.containsKey("struct")) {
            List<StructType.Field> fields = new ArrayList<>();
            for (Object element : (List<?>) type.get("struct")) {
                Map<?, ?> field = (Map<?, ?>) element;
                fields.add(new StructType.Field(
                        string(field, "name"), readType(field.get("type"))));
            }
            return new StructType(fields);
        }
        throw new IllegalArgumentException("unknown type encoding: " + type.keySet());
    }

    private static void writeStatistics(JsonWriter out, RelationStatistics stats) {
        out.name("statistics").beginObject();
        stats.rowCount().ifPresent(rows -> out.name("rowCount").value(rows));
        if (!stats.keys().isEmpty()) {
            out.name("keys").beginArray();
            for (List<String> key : stats.keys()) {
                out.beginArray();
                key.forEach(out::value);
                out.endArray();
            }
            out.endArray();
        }
        if (!stats.columnStatistics().isEmpty()) {
            out.name("columns").beginObject();
            stats.columnStatistics().forEach((column, column_stats) -> {
                out.name(column).beginObject();
                column_stats.distinctCount()
                        .ifPresent(count -> out.name("distinctCount").value(count));
                column_stats.nullCount().ifPresent(count -> out.name("nullCount").value(count));
                out.endObject();
            });
            out.endObject();
        }
        out.endObject();
    }

    private static RelationStatistics readStatistics(Object value) {
        Map<?, ?> stats = (Map<?, ?>) value;
        OptionalLong rowCount = stats.containsKey("rowCount")
                ? OptionalLong.of(Long.parseLong(string(stats, "rowCount")))
                : OptionalLong.empty();

        List<List<String>> keys = new ArrayList<>();
        if (stats.get("keys") instanceof List<?> declared) {
            for (Object key : declared) {
                keys.add(((List<?>) key).stream().map(String.class::cast).toList());
            }
        }

        Map<String, ColumnStatistics> columns = new LinkedHashMap<>();
        if (stats.get("columns") instanceof Map<?, ?> declared) {
            declared.forEach((column, entry) -> {
                Map<?, ?> counts = (Map<?, ?>) entry;
                columns.put((String) column, new ColumnStatistics(
                        optionalLong(counts, "distinctCount"), optionalLong(counts, "nullCount")));
            });
        }
        return new RelationStatistics(rowCount, columns, keys);
    }

    private static OptionalLong optionalLong(Map<?, ?> object, String key) {
        return object.containsKey(key)
                ? OptionalLong.of(Long.parseLong(string(object, key)))
                : OptionalLong.empty();
    }

    private static String string(Map<?, ?> object, String key) {
        Object value = object.get(key);
        if (value == null) {
            throw new IllegalArgumentException("catalog snapshot is missing '" + key + "'");
        }
        return value.toString();
    }

    private static List<?> list(Map<?, ?> object, String key) {
        return object.get(key) instanceof List<?> values ? values : List.of();
    }

    @Override
    public String toString() {
        return "CatalogSnapshot[" + entries.size() + " entries, captured " + captured + "]";
    }
}
