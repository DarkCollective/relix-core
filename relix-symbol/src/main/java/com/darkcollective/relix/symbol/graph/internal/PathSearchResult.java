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
package com.darkcollective.relix.symbol.graph.internal;

import com.darkcollective.relix.symbol.relation.RelationSymbol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a {@link SchemaGraphSearch} over a set of terminal relations, one of three shapes:
 *
 * <ul>
 *   <li><b>Unique</b> ({@link #unique()}) — exactly one minimal path spans the
 *       terminals, and no terminal was unreachable. The common case: mechanical
 *       join assembly, no model judgment (§3 step 4).</li>
 *   <li><b>Ambiguous</b> ({@link #ambiguous()}) — several equally minimal paths
 *       exist (parallel relationships, or a cycle). The engine cannot pick; the
 *       enumerated {@link #paths()} — distinguished by
 *       {@link JoinPath#relationshipNames() relationship name} — are surfaced as
 *       a "did you mean?" (§3(a)).</li>
 *   <li><b>Disconnected</b> ({@link #disconnected()}) — the terminals do not all
 *       lie in one connected component of the (nomination-filtered) graph, so no
 *       single join spans them. {@link #paths()} is empty and
 *       {@link #unreachableTerminals()} names the terminals stranded from the
 *       first.</li>
 * </ul>
 *
 * <p>All enumerated {@link #paths()} share the same minimal size (edge count);
 * choosing among several is a policy question left to the caller.
 *
 * @param paths               the minimal paths, all of equal size; empty when
 *                            disconnected. Deterministically ordered.
 * @param unreachableTerminals terminals not connected to the rest; empty unless
 *                            disconnected
 */
public record PathSearchResult(List<JoinPath> paths, List<RelationSymbol> unreachableTerminals) {

    public PathSearchResult {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(unreachableTerminals, "unreachableTerminals");
        paths = List.copyOf(paths);
        unreachableTerminals = List.copyOf(unreachableTerminals);
    }

    /** Exactly one minimal path, every terminal reachable — assemble it mechanically. */
    public boolean unique() {
        return unreachableTerminals.isEmpty() && paths.size() == 1;
    }

    /** More than one equally minimal path — surface as "did you mean?". */
    public boolean ambiguous() {
        return unreachableTerminals.isEmpty() && paths.size() > 1;
    }

    /** No single join spans the terminals — some are stranded in another component. */
    public boolean disconnected() {
        return !unreachableTerminals.isEmpty();
    }

    /** The single path when {@link #unique()}, else empty. */
    public Optional<JoinPath> single() {
        return unique() ? Optional.of(paths.get(0)) : Optional.empty();
    }
}
