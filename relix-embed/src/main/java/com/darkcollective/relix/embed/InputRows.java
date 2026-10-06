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

import com.darkcollective.relix.connectors.std.internal.StreamInput;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.generator.Generator;
import com.darkcollective.relix.symbol.Schema;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The generator behind {@link Relix#input}: an {@link Input}'s rows, read by the stream
 * reader the file sources share, under the heading settled when it was declared.
 *
 * <p>A generator because that is what a code-backed leaf relation already is — the
 * analyser reads its heading from the registry and the connector its rows, so the two
 * cannot disagree — and because a generator is what declares a relation endless.
 */
final class InputRows implements Generator, AutoCloseable {

    private final String name;
    private final Input input;
    private final Schema heading;
    /** A single-pass input's reader, settled at declaration; null once handed over or for a re-readable one. */
    private StreamInput pending;
    /** A bounded single-pass input's rows, kept as they are read so a later scan can replay them. */
    private Replay replay;

    private InputRows(String name, Input input, Schema heading, StreamInput pending) {
        this.name = name;
        this.input = input;
        this.heading = heading;
        this.pending = pending;
    }

    /**
     * Settles the heading: declared, or inferred from the first records. A re-readable
     * input is opened once for that and closed; a single-pass one keeps what it read.
     */
    static InputRows declare(String name, Input input) {
        Schema declared = input.schema().orElse(null);
        if (input.isReReadable()) {
            if (declared != null) {
                return new InputRows(name, input, declared, null);
            }
            StreamInput sampled = open(name, input, input.reopen().get(), null);
            sampled.rows().close();
            return new InputRows(name, input, sampled.schema(), null);
        }
        StreamInput reader = open(name, input, input.once(), declared);
        return new InputRows(name, input, reader.schema(), reader);
    }

    private static StreamInput open(String name, Input input, InputStream stream, Schema declared) {
        if (stream == null) {
            throw new RelixException("the supplier for input '" + name + "' produced no stream");
        }
        StreamInput.Format format = StreamInput.Format.valueOf(input.format().name());
        return StreamInput.open(format, stream, declared, input.sample(),
                "input '" + name + "' (" + format.name().toLowerCase(java.util.Locale.ROOT) + ")",
                input.names().isEmpty(), input.names());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Schema schema(Map<String, String> args) {
        return heading;
    }

    @Override
    public boolean unbounded() {
        return input.isUnbounded();
    }

    @Override
    public synchronized Stream<Row> rows(Map<String, String> args, Schema schema) {
        if (input.isReReadable()) {
            return open(name, input, input.reopen().get(), heading).rows();
        }
        if (input.isUnbounded()) {
            // Nothing is kept of an endless stream, so there is nothing a second scan
            // could be given: refusing it says so, where an empty result would not.
            if (pending == null) {
                throw new RelixException("input '" + name + "' never ends and is read as it "
                        + "arrives, so it can be read by one scan only; it has been read already");
            }
            StreamInput reader = pending;
            pending = null;
            return reader.rows();
        }
        if (replay == null) {
            replay = new Replay(pending.rows());
            pending = null;
        }
        return replay.scan();
    }

    /** Closes a single-pass input's stream if nothing has finished reading it. */
    @Override
    public synchronized void close() {
        if (pending != null) {
            pending.rows().close();
            pending = null;
        }
        if (replay != null) {
            replay.source.close();
        }
    }

    /**
     * One stream, read by as many scans as ask: each scan replays what has been read and
     * then reads on, keeping what it reads for the next. Scans may interleave, as two
     * sides of a self-join do.
     */
    private static final class Replay {
        private final Stream<Row> source;
        private final Iterator<Row> pulled;
        private final List<Row> kept = new ArrayList<>();

        Replay(Stream<Row> source) {
            this.source = source;
            this.pulled = source.iterator();
        }

        Stream<Row> scan() {
            Iterator<Row> rows = new Iterator<>() {
                private int position;

                @Override
                public boolean hasNext() {
                    synchronized (Replay.this) {
                        if (position < kept.size()) {
                            return true;
                        }
                        if (pulled.hasNext()) {
                            kept.add(pulled.next());
                            return true;
                        }
                        source.close();
                        return false;
                    }
                }

                @Override
                public Row next() {
                    synchronized (Replay.this) {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        return kept.get(position++);
                    }
                }
            };
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(rows,
                    Spliterator.ORDERED | Spliterator.NONNULL), false);
        }
    }
}
