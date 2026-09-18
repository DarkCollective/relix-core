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
package com.darkcollective.relix.provenance;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.SortedMap;

/**
 * A base tuple as a {@link Semiring} can read it — the engine's row reduced to the two
 * things an annotation can be built from: <em>which</em> tuple this is, and <em>what it
 * weighs</em>.
 *
 * <p>It exists so that {@link Semiring#base} can be answered by a semiring that has never
 * heard of a row, a schema or a value. That is what keeps this module free of engine
 * dependencies, and it is what makes an installed semiring able to do everything a
 * built-in one can: the four shapes of base annotation that ship — a constant, a numeric
 * weight, a weight carrying a route token, and a freshly minted lineage variable — are
 * each derivable from the four accessors below, so none of them needs the engine to
 * recognise the semiring by name.
 *
 * <h2>Cost</h2>
 * {@link #columns()} materialises a map per call and most semirings never ask for one, so
 * an implementation is expected to build it on demand rather than up front. The other
 * three are cheap.
 */
public interface BaseTuple {

    /**
     * {@return a label for the leaf this tuple came from} Typically a relation name.
     */
    String source();

    /**
     * {@return the 1-based occurrence index of this tuple within its leaf} Distinct
     * occurrences of an identical row have distinct ordinals, which is what lets an
     * annotation address the occurrence rather than the value.
     */
    long ordinal();

    /**
     * {@return this tuple's per-edge weight, or empty when it has none}
     *
     * <p>Empty covers every way a weight can fail to be a number: no weight column was
     * requested, the row does not carry it, or its value is null or non-numeric. A
     * semiring that reads a weight therefore states its own no-weight answer rather than
     * relying on the engine to have picked one.
     */
    Optional<BigDecimal> weight();

    /**
     * {@return this tuple's column values, as a sorted {@code name → display-string} map}
     * A SQL-null column is present with a {@code null} value, which is why this is not a
     * {@code Map.of}. Materialised per call — ask only if the annotation needs it.
     */
    SortedMap<String, String> columns();
}
