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
import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.internal.ArrayRow;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.value.ArrayValue;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.StructValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.value.internal.JsonValues;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Rows read from a stream of text — CSV, TSV, a JSON array or JSON lines — by the same
 * rules the file sources read them with, and a heading inferred from the first records
 * when none is declared.
 *
 * <p>The delimited formats share the CSV source's row building and cell coercion, so an
 * empty field is a NULL and a row that ends early is refused here exactly as it is for a
 * {@code csv("…")} source. JSON values keep the JSON's own types.
 *
 * <p>Reading is lazy for every format but a JSON array, which is one document and is
 * parsed whole; JSON lines is the streaming form. An instance reads its stream once:
 * {@link #open} samples the first records to settle the heading, and {@link #rows} then
 * yields every record, the sampled ones included.
 */
public final class StreamInput {

    /** The formats a stream can be read in. */
    public enum Format {
        /** RFC 4180 comma-separated values with a header row. */
        CSV,
        /** Tab-separated values with a header row; <code>\t</code>, <code>\n</code>, <code>\r</code> and <code>\\</code> escape. */
        TSV,
        /** One JSON array of objects. */
        JSON,
        /** One JSON object per line. */
        NDJSON
    }

    private final Format format;
    private final String where;
    private final Schema schema;
    private final BufferedReader reader;
    /** Delimited formats: the header's fields. */
    private final List<String> header;
    /** Records already read while sampling, as raw lines or (JSON) parsed objects. */
    private final List<Object> sampled;
    /** The line number of the first sampled record, for messages. */
    private final int firstLine;

    private StreamInput(Format format, String where, Schema schema, BufferedReader reader,
                        List<String> header, List<Object> sampled, int firstLine) {
        this.format = format;
        this.where = where;
        this.schema = schema;
        this.reader = reader;
        this.header = header;
        this.sampled = sampled;
        this.firstLine = firstLine;
    }

    /**
     * Starts reading {@code in}, settling the heading.
     *
     * @param format   how the stream is written
     * @param in       the stream; this instance closes it when its rows are closed
     * @param declared the heading to read it as, or null to infer one
     * @param sample   how many records to infer a heading from; at least 1
     * @param name     what to call the input in a message
     * @return the input, its heading settled and its stream positioned at the first record
     * @throws EvaluationException if the stream has no header, or is not what its format says
     */
    public static StreamInput open(Format format, InputStream in, Schema declared, int sample, String name) {
        return open(format, in, declared, sample,
                "input '" + name + "' (" + format.name().toLowerCase(java.util.Locale.ROOT) + ")",
                true, List.of());
    }

    /**
     * Starts reading {@code in}, settling the heading, with the delimited layout spelt out.
     *
     * <p>{@code names} names a delimited stream's columns by position, their types still
     * inferred when no heading is declared: for a stream with no header row, or one whose
     * header's names are not wanted, which is then skipped. A delimited stream with no
     * header row needs names or a declared heading, since nothing else could name its
     * columns. The JSON formats ignore both.
     *
     * @param format    how the stream is written
     * @param in        the stream; this instance closes it when its rows are closed
     * @param declared  the heading to read it as, or null to infer one
     * @param sample    how many records to infer a heading from; at least 1
     * @param where     what to call the input in a message, e.g. {@code CSV file 'a.csv'}
     * @param headerRow whether a delimited stream's first line is a header row
     * @param names     a delimited stream's column names, in order; empty to take them
     *                  from the header row, or from {@code declared}
     * @return the input, its heading settled and its stream positioned at the first record
     * @throws EvaluationException if the stream has nothing to name its columns with, or
     *         is not what its format says
     */
    public static StreamInput open(Format format, InputStream in, Schema declared, int sample,
                                   String where, boolean headerRow, List<String> names) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(names, "names");
        if (sample < 1) {
            throw new IllegalArgumentException("sample must be at least 1, was: " + sample);
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            return switch (format) {
                case CSV, TSV -> delimited(format, where, reader, declared, sample, headerRow, names);
                case NDJSON -> lines(where, reader, declared, sample);
                case JSON -> array(where, reader, declared, sample);
            };
        } catch (IOException e) {
            closeQuietly(reader);
            throw new UncheckedIOException("cannot read " + where, e);
        } catch (RuntimeException e) {
            closeQuietly(reader);
            throw e;
        }
    }

    /** {@return the heading every row carries} */
    public Schema schema() {
        return schema;
    }

    /**
     * Every record of the stream as a row, the sampled ones first; closing the stream
     * closes the input.
     */
    public Stream<Row> rows() {
        Iterator<Row> rows = switch (format) {
            case CSV, TSV -> delimitedRows();
            case NDJSON -> lineRows();
            case JSON -> sampled.stream().map(this::jsonRow).iterator();
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(rows,
                Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(() -> closeQuietly(reader));
    }

    // -------------------------------------------------------------------------
    // Delimited: CSV and TSV
    // -------------------------------------------------------------------------

    private static StreamInput delimited(Format format, String where, BufferedReader reader,
                                         Schema declared, int sample, boolean headerRow,
                                         List<String> names) throws IOException {
        String first = reader.readLine();
        if (first != null && first.startsWith("\uFEFF")) {
            first = first.substring(1);
        }
        List<String> header;
        List<Object> lines = new ArrayList<>();
        if (headerRow) {
            if (first == null && names.isEmpty()) {
                throw new EvaluationException(where + " is empty: it needs a header row naming its columns");
            }
            // A header that names gives is skipped: what the columns are called is decided.
            header = names.isEmpty() ? split(format, first) : names;
        } else {
            if (!names.isEmpty()) {
                header = names;
            } else if (declared != null) {
                header = declared.columns().stream().map(ColumnDefinition::name).toList();
            } else {
                throw new EvaluationException(where + " has no header row, so nothing names its"
                        + " columns; name them, or declare its schema");
            }
            if (first != null) {
                lines.add(first);
            }
        }
        if (declared == null) {
            String line;
            while (lines.size() < sample && (line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        Schema schema = declared != null ? declared : inferDelimited(format, header, lines);
        return new StreamInput(format, where, schema, reader, header, lines, headerRow ? 2 : 1);
    }

    private static Schema inferDelimited(Format format, List<String> header, List<Object> lines) {
        List<List<String>> columns = new ArrayList<>();
        header.forEach(h -> columns.add(new ArrayList<>()));
        for (Object line : lines) {
            if (((String) line).isBlank()) {
                continue;
            }
            List<String> cells = split(format, (String) line);
            for (int i = 0; i < header.size() && i < cells.size(); i++) {
                if (!cells.get(i).isEmpty()) {
                    columns.get(i).add(cells.get(i));
                }
            }
        }
        List<ColumnDefinition> definitions = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            definitions.add(new ColumnDefinition(header.get(i).strip(), inferText(columns.get(i))));
        }
        return new Schema(definitions);
    }

    private Iterator<Row> delimitedRows() {
        int[] mapping = CsvDataSourceConnector.buildHeaderMapping(header, schema, where);
        Iterator<String> lines = concat(sampled.stream().map(String.class::cast).iterator(), readerLines());
        return new Iterator<>() {
            private int lineNumber = firstLine - 1;
            private Row next;

            @Override
            public boolean hasNext() {
                while (next == null && lines.hasNext()) {
                    String line = lines.next();
                    lineNumber++;
                    if (!line.isBlank()) {
                        next = CsvDataSourceConnector.buildRow(split(format, line), mapping, schema,
                                where, lineNumber);
                    }
                }
                return next != null;
            }

            @Override
            public Row next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Row row = next;
                next = null;
                return row;
            }
        };
    }

    /** A line split into fields: RFC 4180 for CSV, the IANA rule with escapes for TSV. */
    static List<String> split(Format format, String line) {
        if (format == Format.CSV) {
            return CsvDataSourceConnector.parseCsvLine(line);
        }
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (c == '\\' && i + 1 < line.length()) {
                char e = line.charAt(++i);
                field.append(switch (e) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case '\\' -> '\\';
                    default -> {
                        field.append('\\');
                        yield e;
                    }
                });
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    /**
     * The narrowest type every sampled text value reads as: NUMBER, then BOOLEAN
     * ({@code true}/{@code false}), then DATE, then TIMESTAMP, else STRING. A column with
     * no value in the sample is a STRING.
     */
    static ScalarType inferText(List<String> values) {
        if (values.isEmpty()) {
            return ScalarType.STRING;
        }
        if (all(values, StreamInput::isNumber)) return ScalarType.NUMBER;
        if (all(values, v -> v.strip().equalsIgnoreCase("true") || v.strip().equalsIgnoreCase("false"))) {
            return ScalarType.BOOLEAN;
        }
        if (all(values, v -> parses(v, TemporalLiterals::parseDate))) return ScalarType.DATE;
        if (all(values, v -> parses(v, TemporalLiterals::parseTimestamp))) return ScalarType.TIMESTAMP;
        return ScalarType.STRING;
    }

    // -------------------------------------------------------------------------
    // JSON: one array, or one object per line
    // -------------------------------------------------------------------------

    private static StreamInput lines(String where, BufferedReader reader, Schema declared, int sample)
            throws IOException {
        List<Object> objects = new ArrayList<>();
        int lineNumber = 0;
        int firstLine = 0;
        if (declared == null) {
            String line;
            while (objects.size() < sample && (line = reader.readLine()) != null) {
                lineNumber++;
                if (!line.isBlank()) {
                    if (firstLine == 0) {
                        firstLine = lineNumber;
                    }
                    objects.add(object(parse(line, where + " line " + lineNumber), where + " line " + lineNumber));
                }
            }
        }
        Schema schema = declared != null ? declared : inferJson(objects);
        // The sampled objects are kept already parsed; the line count picks up after them.
        return new StreamInput(Format.NDJSON, where, schema, reader, List.of(), objects, lineNumber + 1);
    }

    private Iterator<Row> lineRows() {
        Iterator<Row> fromSample = sampled.stream().map(this::jsonRow).iterator();
        Iterator<String> rest = readerLines();
        Iterator<Row> fromRest = new Iterator<>() {
            private int lineNumber = firstLine - 1;
            private Row next;

            @Override
            public boolean hasNext() {
                while (next == null && rest.hasNext()) {
                    String line = rest.next();
                    lineNumber++;
                    if (!line.isBlank()) {
                        String at = where + " line " + lineNumber;
                        next = jsonRow(object(parse(line, at), at));
                    }
                }
                return next != null;
            }

            @Override
            public Row next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Row row = next;
                next = null;
                return row;
            }
        };
        return concat(fromSample, fromRest);
    }

    private static StreamInput array(String where, BufferedReader reader, Schema declared, int sample)
            throws IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            text.append(buffer, 0, read);
        }
        Value document = parse(text.toString(), where);
        if (!(document instanceof ArrayValue array)) {
            throw new EvaluationException(where + " must be a JSON array of objects; use ndjson for one "
                    + "object per line");
        }
        List<Object> objects = new ArrayList<>();
        for (Value element : array.elements()) {
            objects.add(object(element, where + " element " + (objects.size() + 1)));
        }
        Schema schema = declared != null ? declared
                : inferJson(objects.subList(0, Math.min(sample, objects.size())));
        return new StreamInput(Format.JSON, where, schema, reader, List.of(), objects, 1);
    }

    /**
     * Every key the sample uses, in the order first seen, each typed by its values: one
     * JSON type throughout gives that type (a string that reads as a date or timestamp
     * throughout gives that); anything mixed, nested or never non-null is ANY.
     */
    private static Schema inferJson(List<Object> objects) {
        Map<String, List<Value>> columns = new LinkedHashMap<>();
        for (Object object : objects) {
            ((StructValue) object).fields().forEach((key, value) -> {
                List<Value> values = columns.computeIfAbsent(key, k -> new ArrayList<>());
                if (!(value instanceof NullValue)) {
                    values.add(value);
                }
            });
        }
        List<ColumnDefinition> definitions = new ArrayList<>();
        columns.forEach((key, values) -> definitions.add(new ColumnDefinition(key, inferJsonType(values))));
        return new Schema(definitions);
    }

    private static ScalarType inferJsonType(List<Value> values) {
        if (values.isEmpty()) return ScalarType.ANY;
        if (all(values, v -> v instanceof NumberValue)) return ScalarType.NUMBER;
        if (all(values, v -> v instanceof BooleanValue)) return ScalarType.BOOLEAN;
        if (all(values, v -> v instanceof StringValue)) {
            List<String> text = values.stream().map(v -> ((StringValue) v).value()).toList();
            ScalarType type = inferText(text);
            // A string of digits is still a JSON string: only the temporal readings are
            // taken from its text.
            return type == ScalarType.DATE || type == ScalarType.TIMESTAMP ? type : ScalarType.STRING;
        }
        return ScalarType.ANY;
    }

    private Row jsonRow(Object object) {
        StructValue fields = (StructValue) object;
        List<Value> values = new ArrayList<>(schema.width());
        for (ColumnDefinition column : schema.columns()) {
            Value value = fields.fields().getOrDefault(column.name(), NullValue.INSTANCE);
            ScalarType type = column.type() instanceof ScalarType s ? s : ScalarType.ANY;
            values.add(jsonValue(value, type, column.name()));
        }
        return ArrayRow.of(schema, values);
    }

    /** A JSON value as a column's type: its own type where they agree, read from text for a temporal. */
    private Value jsonValue(Value value, ScalarType type, String column) {
        if (value instanceof NullValue || type == ScalarType.ANY) {
            return value;
        }
        boolean fits = switch (type) {
            case NUMBER -> value instanceof NumberValue;
            case BOOLEAN -> value instanceof BooleanValue;
            case STRING -> value instanceof StringValue;
            case DATE, TIME, TIMESTAMP, DURATION -> value instanceof StringValue;
            default -> false;
        };
        if (fits && value instanceof StringValue text && type != ScalarType.STRING) {
            return CsvDataSourceConnector.coerce(text.value(), type, column, where, 0);
        }
        if (fits) {
            return value;
        }
        throw new EvaluationException(where + ": column '" + column + "' is " + type.name()
                + " but a record holds " + value.asDisplayString() + "; declare the column's type,"
                + " or sample more records");
    }

    // -------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------

    private static Value parse(String json, String at) {
        try {
            return JsonValues.parse(json);
        } catch (JsonValues.JsonParseException e) {
            throw new EvaluationException("malformed JSON in " + at + ": " + e.getMessage());
        }
    }

    private static StructValue object(Value value, String at) {
        if (value instanceof StructValue struct) {
            return struct;
        }
        throw new EvaluationException(at + " is not a JSON object");
    }

    private Iterator<String> readerLines() {
        return new Iterator<>() {
            private String next;
            private boolean done;

            @Override
            public boolean hasNext() {
                if (next == null && !done) {
                    try {
                        next = reader.readLine();
                    } catch (IOException e) {
                        throw new UncheckedIOException("cannot read " + where, e);
                    }
                    done = next == null;
                }
                return next != null;
            }

            @Override
            public String next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                String line = next;
                next = null;
                return line;
            }
        };
    }

    private static <T> Iterator<T> concat(Iterator<T> first, Iterator<T> second) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return first.hasNext() || second.hasNext();
            }

            @Override
            public T next() {
                return first.hasNext() ? first.next() : second.next();
            }
        };
    }

    private static boolean isNumber(String value) {
        try {
            new BigDecimal(value.strip());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean parses(String value, Function<String, ?> parser) {
        try {
            parser.apply(value.strip());
            return true;
        } catch (DateTimeException | IllegalArgumentException e) {
            return false;
        }
    }

    private static <T> boolean all(List<T> values, java.util.function.Predicate<T> test) {
        for (T value : values) {
            if (!test.test(value)) {
                return false;
            }
        }
        return true;
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) {
            // the input is finished with either way
        }
    }
}
