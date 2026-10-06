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

import com.darkcollective.relix.symbol.Schema;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Rows arriving as text — a file, a pipe, a socket, standard input — for
 * {@link Relix#input(String, Input)} to name as a relation.
 *
 * <p>Two kinds, which differ in what a second read of the relation does:
 *
 * <ul>
 *   <li><b>Re-readable</b>, {@link #of(InputFormat, Supplier)}: the supplier is called for
 *       each scan, as {@link Relix#source} calls its own, so a query that reads the
 *       relation twice opens it twice. For a file.</li>
 *   <li><b>Single-pass</b>, {@link #of(InputFormat, InputStream)}: the stream is read
 *       once. Its rows are kept as they are read, so a later scan — a second query, or a
 *       query reading the relation twice — replays them; reading a large stream more
 *       than once therefore holds it in memory. For standard input or a pipe.</li>
 * </ul>
 *
 * <p>With no {@link #schema(Schema) declared heading} the heading is inferred from the
 * first {@link #sample(int) records}: a delimited format's names come from its header row,
 * and JSON's from the keys the sample uses. A record after the sample whose value does
 * not fit the inferred type is an error naming it; declare the heading, or sample more.
 *
 * <p>An input is immutable; each method returns a new one.
 *
 * @since 1.0
 */
public final class Input {

    /** How many records a heading is inferred from unless {@link #sample(int)} says otherwise. */
    public static final int DEFAULT_SAMPLE = 1000;

    private final InputFormat format;
    private final Supplier<InputStream> reopen;
    private final InputStream once;
    private final Schema schema;
    private final int sample;
    private final boolean unbounded;
    private final List<String> names;

    private Input(InputFormat format, Supplier<InputStream> reopen, InputStream once,
                  Schema schema, int sample, boolean unbounded, List<String> names) {
        this.format = format;
        this.reopen = reopen;
        this.once = once;
        this.schema = schema;
        this.sample = sample;
        this.unbounded = unbounded;
        this.names = names;
    }

    /**
     * A re-readable input: {@code open} is called for each scan, and the engine closes
     * what it returns.
     *
     * @param format how the text is written; must not be null
     * @param open   opens the text afresh; must not be null
     * @return the input
     * @since 1.0
     */
    public static Input of(InputFormat format, Supplier<InputStream> open) {
        return new Input(Objects.requireNonNull(format, "format"),
                Objects.requireNonNull(open, "open"), null, null, DEFAULT_SAMPLE, false, List.of());
    }

    /**
     * A single-pass input: {@code stream} is read once, and closed by the session when it
     * is finished with.
     *
     * @param format how the text is written; must not be null
     * @param stream the text; must not be null
     * @return the input
     * @since 1.0
     */
    public static Input of(InputFormat format, InputStream stream) {
        return new Input(Objects.requireNonNull(format, "format"), null,
                Objects.requireNonNull(stream, "stream"), null, DEFAULT_SAMPLE, false, List.of());
    }

    /**
     * This input, read as {@code heading} rather than an inferred one. A delimited format
     * matches its header to the heading's names; JSON reads each name's key.
     *
     * @param heading the heading; must not be null
     * @return the input
     * @since 1.0
     */
    public Input schema(Schema heading) {
        return new Input(format, reopen, once, Objects.requireNonNull(heading, "heading"),
                sample, unbounded, names);
    }

    /**
     * This input, read as text with no header row whose columns are {@code columns}, in
     * order — their types inferred as for any heading, unless one is
     * {@link #schema(Schema) declared}.
     *
     * <p>Without names, a delimited input's first line is its header row, and one that has
     * none cannot be read with an inferred heading: nothing else would name its columns.
     *
     * @param first the first column's name
     * @param more  the rest, in order; each column is named once
     * @return the input
     * @throws IllegalArgumentException for a JSON format, whose columns are its keys, or
     *         for a name given twice
     * @since 1.0
     */
    public Input names(String first, String... more) {
        if (format == InputFormat.JSON || format == InputFormat.NDJSON) {
            throw new IllegalArgumentException("a JSON record names its own columns with its"
                    + " keys; names are for CSV and TSV with no header row");
        }
        List<String> given = new java.util.ArrayList<>();
        given.add(first);
        given.addAll(List.of(more));
        if (given.stream().map(n -> n.toLowerCase(java.util.Locale.ROOT)).distinct().count()
                != given.size()) {
            throw new IllegalArgumentException("names gives a column twice: " + given);
        }
        return new Input(format, reopen, once, schema, sample, unbounded, List.copyOf(given));
    }

    /**
     * {@return the column names a headerless input was given, or empty when its first
     * line is its header row}
     *
     * @since 1.0
     */
    public List<String> names() {
        return names;
    }

    /**
     * This input, its heading inferred from the first {@code records} records.
     *
     * @param records how many; at least 1
     * @return the input
     * @since 1.0
     */
    public Input sample(int records) {
        if (records < 1) {
            throw new IllegalArgumentException("sample must be at least 1, was: " + records);
        }
        return new Input(format, reopen, once, schema, records, unbounded, names);
    }

    /**
     * This input, marked as never ending: a log followed as it is written, a feed.
     *
     * <p>The engine then treats the relation as it treats any endless one: a query
     * streams it, a row at a time, and one that would have to read all of it first — a
     * sort, a grouping, a collecting terminal — is refused before it starts. A
     * single-pass input marked so keeps nothing, and can be read by one scan only. A JSON
     * array is one document and cannot be; read the stream as {@link InputFormat#NDJSON}.
     *
     * @return the input
     * @throws IllegalArgumentException for a JSON array
     * @since 1.0
     */
    public Input unbounded() {
        if (format == InputFormat.JSON) {
            throw new IllegalArgumentException("a JSON array is read whole, so it cannot be "
                    + "unbounded; write one object per line and read it as NDJSON");
        }
        return new Input(format, reopen, once, schema, sample, true, names);
    }

    /**
     * {@return how the text is written}
     *
     * @since 1.0
     */
    public InputFormat format() {
        return format;
    }

    /**
     * {@return the declared heading, if one was}
     *
     * @since 1.0
     */
    public Optional<Schema> schema() {
        return Optional.ofNullable(schema);
    }

    /**
     * {@return how many records a heading is inferred from}
     *
     * @since 1.0
     */
    public int sample() {
        return sample;
    }

    /**
     * {@return whether the input never ends}
     *
     * @since 1.0
     */
    public boolean isUnbounded() {
        return unbounded;
    }

    /**
     * {@return whether each scan opens the text afresh, rather than reading one stream once}
     *
     * @since 1.0
     */
    public boolean isReReadable() {
        return reopen != null;
    }

    Supplier<InputStream> reopen() {
        return reopen;
    }

    InputStream once() {
        return once;
    }
}
