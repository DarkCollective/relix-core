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
package com.darkcollective.relix.ast;

/**
 * Describes the output materialisation strategy of a relational algebra node.
 *
 * <p>Most operators produce a lazy {@code Stream<Row>} pipeline with no
 * intermediate collection ({@link #STREAM}).  A subset must accumulate all
 * output rows before the first result can be emitted; these are annotated
 * with one of the three materialising modes.
 *
 * <ul>
 *   <li>{@link #STREAM} — lazy pipeline; no intermediate collection required.
 *       This is the default for selection, projection, rename, limit, distinct,
 *       and all join variants.</li>
 *   <li>{@link #BAG} — all rows are collected into an ordered bag before
 *       output.  Used by: aggregation (γ), union-all (⊎), division (÷), and
 *       full outer join (⟗).</li>
 *   <li>{@link #SET} — all rows are collected and deduplicated before output.
 *       Used by: union (∪), intersection (∩), and difference (−).</li>
 *   <li>{@link #SORTED} — all rows are collected then sorted before output.
 *       Used by: sort (τ).</li>
 * </ul>
 *
 * <p>The mode is surfaced in the IR report as a bracketed tag appended to the
 * node label — {@code [bag]}, {@code [set]}, or {@code [sort]} — so that
 * potentially expensive materialisation steps are immediately visible when
 * inspecting an expression tree.  {@link #STREAM} nodes carry no tag.
 *
 * @see RelNode#materializationMode()
 */
public enum MaterializationMode {

    /**
     * Lazy pipeline — no intermediate collection.  The node's output is a
     * Java {@code Stream} that pulls rows on demand.
     */
    STREAM,

    /**
     * Materialised bag — all output rows are collected into an ordered list
     * before the first row is returned.  Allows duplicates.
     */
    BAG,

    /**
     * Materialised set — all output rows are collected and deduplicated
     * before the first row is returned.
     */
    SET,

    /**
     * Materialised sorted bag — all output rows are collected and then sorted
     * before the first row is returned.
     */
    SORTED
}
