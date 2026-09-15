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
package com.darkcollective.relix.symbol.graph;

/**
 * How a {@link Relationship} entered the schema graph.
 *
 * <p>Origin is load-bearing: a declared edge is ground truth and its violations
 * are hard analysis errors, while learned and inferred edges are provisional —
 * they must stay visibly distinguishable and correctable, and a stale one is
 * dropped with a warning rather than failing analysis.
 */
public enum EdgeOrigin {

    /**
     * Declared in a {@code .relix} source file — a {@code relate} statement or a
     * {@code references:} block. Ground truth; validated as a hard error.
     */
    DECLARED,

    /**
     * Learned during a session — supplied through conversational acquisition
     * ("how do orders relate to customers?") or captured from the joins a user
     * actually wrote. Session-scoped until persistence decides
     * otherwise.
     */
    LEARNED,

    /**
     * Inferred by examining data — value containment against candidate keys
     *. A suggestion, never silently authoritative.
     */
    INFERRED
}
