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
package com.darkcollective.relix.connectors.std.internal;

import com.darkcollective.relix.ast.internal.TemporalLiterals;
import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.connector.ConnectorConfig;
import com.darkcollective.relix.processor.connector.RelixConnector;
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.TimestampValue;
import com.darkcollective.relix.value.Value;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads a web server's access log as a relation: {@code connection X from log { … }}.
 *
 * <pre>{@code
 * connection logs from log { path: "/var/log/nginx", format: "combined" };
 * source Access from logs { table: "access.log",
 *     schema: { host: STRING, at: TIMESTAMP, method: STRING, path: STRING,
 *               status: NUMBER, bytes: NUMBER, referer: STRING, agent: STRING } };
 * }</pre>
 *
 * <h2>The format decides the fields, and their types</h2>
 * {@code format} is {@code common}, {@code combined}, or the server's own format string,
 * pasted from its configuration: Apache's {@code LogFormat} or nginx's
 * {@code log_format}; it defaults to {@code common}, and {@code clf} is another name for
 * the {@code log} type. What a log is worth to a query is the typing: the request time
 * is a {@code TIMESTAMP}, the status
 * and byte count are numbers, the request line is split into {@code method},
 * {@code path} and {@code protocol}, and a {@code -} is NULL wherever it appears.
 *
 * <h2>The declared schema decides the heading</h2>
 * Every column is filled by name, and a column the format does not produce is NULL,
 * the rule the GEDCOM connector follows. A dotted reference, with nothing declared,
 * gets every field the format produces.
 *
 * <h2>A directory holds several logs</h2>
 * When the connection's file is a directory, {@code table} names a file in it, so
 * {@code access.log} and a rotated {@code access.log.2.gz} are two tables of one
 * connection. When it is a file, {@code table} names that file. A gzip file is read
 * transparently.
 *
 * <h2>Reading</h2>
 * A log is streamed a line at a time rather than read whole, so the relation holds an
 * open file until the query closes it. A line the format does not match is an error
 * naming its line number, unless the connection says {@code onError: "skip"}: then it
 * is left out, and the number skipped is logged when the file is closed. Blank lines
 * are not records and are passed over.
 */
public final class LogConnector implements RelixConnector, FileBacked {

    private static final System.Logger LOG = System.getLogger(LogConnector.class.getName());

    /** Apache's {@code %t} and nginx's {@code $time_local}. */
    private static final DateTimeFormatter CLF_TIME =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    /** Public no-arg constructor required for {@link java.util.ServiceLoader} discovery. */
    public LogConnector() {
    }

    @Override
    public Set<String> handles() {
        return Set.of("log", "clf");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every field the format produces, at its natural type. Described from the format
     * alone, so an unreadable file does not stop analysis.
     */
    @Override
    public Optional<Schema> tableSchema(ConnectorConfig config, String table) {
        LogFormat format;
        try {
            format = format(config);
        } catch (EvaluationException e) {
            return Optional.empty();
        }
        List<ColumnDefinition> columns = new ArrayList<>();
        format.columns().forEach((name, type) -> columns.add(new ColumnDefinition(name, type)));
        return Optional.of(new Schema(columns));
    }

    @Override
    public Stream<Row> open(ConnectorConfig config, String table, Schema schema) {
        LogFormat format = format(config);
        Path file = file(FileResolver.localPath(config), table);
        boolean skip = switch (config.getOrDefault("onError", "refuse").toLowerCase(Locale.ROOT)) {
            case "skip" -> true;
            case "refuse" -> false;
            default -> throw new EvaluationException("log connection: onError is \"refuse\" or \"skip\", not \""
                    + config.require("onError") + "\"");
        };
        String name = file.getFileName().toString();
        BufferedReader reader = FileResolver.openText(file);
        long[] counts = new long[2];   // lines read, lines skipped
        return reader.lines()
                .map(line -> {
                    long number = ++counts[0];
                    if (line.isBlank()) {
                        return null;
                    }
                    List<String> values = format.split(line);
                    if (values == null) {
                        if (skip) {
                            counts[1]++;
                            return null;
                        }
                        throw new EvaluationException("line " + number + " of " + name
                                + " does not match the log format: " + snippet(line));
                    }
                    return row(format, values, schema, name, number);
                })
                .filter(java.util.Objects::nonNull)
                .onClose(() -> {
                    if (counts[1] > 0) {
                        LOG.log(System.Logger.Level.WARNING, "skipped {0} of {1} lines of {2} "
                                + "that did not match the log format", counts[1], counts[0], name);
                    }
                    try {
                        reader.close();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    private static LogFormat format(ConnectorConfig config) {
        String spec = config.getOrDefault("format", "");
        return LogFormat.parse(spec.isBlank() ? "common" : spec);
    }

    /** {@return the log {@code table} names within {@code location}} */
    static Path file(Path location, String table) {
        if (Files.isDirectory(location)) {
            if (table == null || table.isBlank() || table.contains("/") || table.contains("\\")
                    || table.equals("..") || table.equals(".")) {
                throw new EvaluationException("log connection on directory " + location
                        + ": 'table' names a file in it, not '" + table + "'");
            }
            return location.resolve(table);
        }
        String name = location.getFileName().toString();
        if (table != null && !table.isBlank() && !table.equals(name)) {
            throw new EvaluationException("log connection reads the file " + name
                    + ", so its only table is '" + name + "', not '" + table + "'");
        }
        return location;
    }

    private static Row row(LogFormat format, List<String> captured, Schema schema,
                           String file, long line) {
        Map<String, String> text = new HashMap<>();
        Map<String, Value> typed = new HashMap<>();
        List<LogFormat.Field> fields = format.fields();
        for (int i = 0; i < fields.size(); i++) {
            LogFormat.Field field = fields.get(i);
            String raw = unescape(captured.get(i));
            if (field.kind() == LogFormat.Kind.REQUEST) {
                request(raw, text, typed);
            } else {
                text.put(field.column(), raw);
                typed.put(field.column(), natural(field.kind(), raw, file, line));
            }
        }
        List<Value> values = new ArrayList<>(schema.columns().size());
        for (ColumnDefinition column : schema.columns()) {
            String name = column.name().toLowerCase(Locale.ROOT);
            values.add(declared(typed.get(name), text.get(name), column, file, line));
        }
        return ArrayRow.of(schema, values);
    }

    /** {@code GET /index.html HTTP/1.1} into its three parts; a line that will not split keeps its path. */
    private static void request(String raw, Map<String, String> text, Map<String, Value> typed) {
        String line = absent(raw) ? null : raw;
        String[] parts = line == null ? new String[0] : line.split(" ");
        String method = parts.length >= 2 ? parts[0] : null;
        String path = parts.length >= 2 ? parts[1] : line;
        String protocol = parts.length == 3 ? parts[2] : null;
        for (var e : Map.of("method", Optional.ofNullable(method), "path", Optional.ofNullable(path),
                "protocol", Optional.ofNullable(protocol)).entrySet()) {
            text.put(e.getKey(), e.getValue().orElse(null));
            typed.put(e.getKey(), e.getValue().<Value>map(StringValue::new).orElse(NullValue.INSTANCE));
        }
    }

    /** {@return {@code raw} as the type its directive gives it} {@code -} is NULL. */
    private static Value natural(LogFormat.Kind kind, String raw, String file, long line) {
        if (absent(raw)) {
            return NullValue.INSTANCE;
        }
        try {
            return switch (kind) {
                case TEXT, REQUEST -> new StringValue(raw);
                case NUMBER -> new NumberValue(new BigDecimal(raw));
                case CLF_TIME, LOCAL_TIME -> new TimestampValue(OffsetDateTime.parse(raw, CLF_TIME).toInstant());
                case ISO_TIME -> new TimestampValue(OffsetDateTime.parse(raw).toInstant());
            };
        } catch (NumberFormatException | DateTimeException e) {
            throw new EvaluationException("line " + line + " of " + file + ": cannot read '" + raw
                    + "' as " + kind.type.display());
        }
    }

    /** {@return the field as the declared column's type} A column the format lacks is NULL. */
    private static Value declared(Value natural, String raw, ColumnDefinition column,
                                  String file, long line) {
        if (natural == null || natural instanceof NullValue) {
            return NullValue.INSTANCE;
        }
        if (!(column.type() instanceof ScalarType type)) {
            throw new EvaluationException("line " + line + " of " + file + ": a log field is a single "
                    + "value, so column '" + column.name() + "' cannot be " + column.type().display());
        }
        if (type == ScalarType.ANY || natural.type() == type) {
            return natural;
        }
        try {
            return switch (type) {
                case STRING -> new StringValue(raw);
                case NUMBER -> new NumberValue(new BigDecimal(raw.strip()));
                case TIMESTAMP -> new TimestampValue(TemporalLiterals.parseTimestamp(raw.strip()));
                case DATE, TIME, DURATION, BOOLEAN, ANY -> throw new DateTimeException("unsupported");
            };
        } catch (NumberFormatException | DateTimeException e) {
            throw new EvaluationException("line " + line + " of " + file + ": cannot read '" + raw
                    + "' as " + type.display() + " for column '" + column.name() + "'");
        }
    }

    /** A captured field that holds nothing: empty, or the log's {@code -}. */
    private static boolean absent(String raw) {
        return raw.isEmpty() || raw.equals("-");
    }

    /** A quoted field's {@code \"} and {@code \\}. */
    private static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                c = raw.charAt(++i);
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String snippet(String line) {
        return line.length() <= 120 ? line : line.substring(0, 120) + "…";
    }
}
