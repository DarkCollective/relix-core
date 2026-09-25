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

import java.util.List;
import java.util.Objects;

/**
 * What resolving the joins in an expression over a {@link SchemaGraph} came to: the
 * engine's own assembly of the join from the relationships the graph records, rather
 * than the conditions the expression was written with. One of three shapes:
 *
 * <ul>
 *   <li>{@link Passthrough} — the graph adds nothing decidable (no graph, fewer
 *       than two graph relations, a disconnected or intractable expression), so the
 *       expression stands as written. Carries a {@code reason}.</li>
 *   <li>{@link Resolved} — a unique minimal path; the join was rewritten over the
 *       graph's conditions ({@code program}), with the multiplicity
 *       {@link BoundsFacts} of that path.</li>
 *   <li>{@link Ambiguous} — several equally minimal paths (two foreign keys from
 *       {@code issues} into {@code users}, say), which the engine cannot choose
 *       between, so it lists the {@link Alternative}s by relationship name.</li>
 * </ul>
 *
 * @since 1.0
 */
public sealed interface JoinResolution
        permits JoinResolution.Passthrough, JoinResolution.Resolved, JoinResolution.Ambiguous {

    /**
     * Multiplicity facts of a chosen path: whether the join fans
     * out (an endpoint admits more than one match, repeating rows) and whether an
     * inner join may drop rows (an endpoint's {@code min} is zero).
     *
     * @param fanOut  some endpoint has {@code max > 1} or an unbounded {@code max}
     * @param rowDrop some endpoint has {@code min == 0}
     */
    record BoundsFacts(boolean fanOut, boolean rowDrop) {

        /** Neither effect — a clean 1:1 path. */
        public static final BoundsFacts NONE = new BoundsFacts(false, false);

        /** True if either effect is present (the join changes row multiplicity). */
        public boolean any() {
            return fanOut || rowDrop;
        }
    }

    /** The graph could not improve on the model's program; it stands unchanged. */
    record Passthrough(String reason) implements JoinResolution {
        public Passthrough {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** A unique path was assembled into {@code program} with its {@code bounds}. */
    record Resolved(String program, BoundsFacts bounds) implements JoinResolution {
        public Resolved {
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(bounds, "bounds");
        }
    }

    /** Several equally minimal paths; each {@link Alternative} is a runnable program. */
    record Ambiguous(List<Alternative> alternatives) implements JoinResolution {
        public Ambiguous {
            Objects.requireNonNull(alternatives, "alternatives");
            if (alternatives.size() < 2) {
                throw new IllegalArgumentException(
                        "an ambiguous resolution enumerates at least two alternatives");
            }
            alternatives = List.copyOf(alternatives);
        }
    }

    /**
     * One enumerated join, distinguished by relationship name — the ambiguity is
     * speakable only because edges are named.
     *
     * @param label       the relationship name(s) traversed; the speakable choice
     * @param description a one-line summary of the join condition(s)
     * @param program     the runnable Relix assembling this path
     * @param bounds      the multiplicity facts of this path
     */
    record Alternative(String label, String description, String program, BoundsFacts bounds) {
        public Alternative {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(bounds, "bounds");
        }
    }
}
