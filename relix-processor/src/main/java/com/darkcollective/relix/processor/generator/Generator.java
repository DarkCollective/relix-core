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
package com.darkcollective.relix.processor.generator;

import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.symbol.Schema;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

/**
 * A code-backed leaf relation — its rows are produced by engine code rather than
 * read from a file or database.
 *
 * <p>A generator <em>owns its schema</em>: {@link #schema(Map)} returns the heading
 * the relation will have (used by semantic analysis), and {@link #rows(Map, Schema)}
 * lazily produces the tuples (used by execution). Both take the generator's raw
 * string arguments (e.g. {@code lo}/{@code hi}/{@code step} for {@code Range}); a
 * generator interprets and validates the args it understands.
 *
 * <p>Layer 0 covers only <strong>finite</strong> generators, which need no
 * boundedness machinery. Boundedness/distinctness declaration is added in a later
 * slice and consumed by the boundedness check.
 */
public interface Generator {

    /**
     * @return the registered generator name (e.g. {@code "Range"}); case-insensitive at lookup
     */
    String name();

    /**
     * The schema (heading) this generator produces for the given arguments.
     * Implementations should keep this total where possible — argument validation
     * that only affects the <em>rows</em> belongs in {@link #rows}.
     *
     * @param args the raw generator arguments
     * @return the output schema
     */
    Schema schema(Map<String, String> args);

    /**
     * Lazily produces the generator's rows.
     *
     * @param args   the raw generator arguments (e.g. {@code lo}/{@code hi}/{@code step})
     * @param schema the schema returned by {@link #schema(Map)} for the same args
     * @return a lazy stream of rows; the caller closes it
     * @throws com.darkcollective.relix.processor.eval.EvaluationException if the
     *         arguments are missing or invalid
     */
    Stream<Row> rows(Map<String, String> args, Schema schema);

    /**
     * The generator's <em>exact</em> cardinality for the given arguments, when
     * known — fed to the cost model as an exact row count (a finite generator like
     * {@code Range} knows it precisely). Returns {@link OptionalLong#empty()} when
     * unknown or unbounded; the default is empty.
     *
     * @param args the raw generator arguments
     * @return the exact row count, or empty when unknown/unbounded
     */
    default OptionalLong cardinality(Map<String, String> args) {
        return OptionalLong.empty();
    }

    /**
     * Whether this generator is provably <em>infinite</em>.
     * Read by {@code GeneratorBoundednessSource}; finite generators (the default)
     * report {@code false}.
     *
     * @return {@code true} if the generator has no last row
     */
    default boolean unbounded() {
        return false;
    }

    /**
     * Whether this generator is inherently <em>duplicate-free</em> (every row
     * distinct). Read by {@code GeneratorDistinctnessSource}, which feeds
     * distinctness-driven {@code δ} elimination ({@code DIST-001}).
     *
     * @return {@code true} if the generator never emits a duplicate row
     */
    default boolean duplicateFree() {
        return false;
    }

    /**
     * The single column on which this generator emits values in strictly <em>ascending</em>
     * order, when it is an <em>unbounded</em> generator — the seam the optimizer's
     * {@code GEN-001} pass consults to fold an upper-bound selection into a production stop.
     * Returning a column declares "an upper bound on this column is a
     * sound termination condition" (a {@code takeWhile}).
     *
     * <p>The default is empty: finite generators terminate on their own, and a non-monotone
     * generator cannot be safely stopped by a value threshold.
     *
     * @return the ascending value column, or empty when not an ascending unbounded generator
     */
    default Optional<String> ascendingColumn() {
        return Optional.empty();
    }
}
