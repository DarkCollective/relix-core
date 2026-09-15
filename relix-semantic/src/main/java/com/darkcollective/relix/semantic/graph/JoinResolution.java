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
package com.darkcollective.relix.semantic.graph;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of {@link JoinPathResolver#resolve} — the engine's attempt to
 * assemble the join a request needs from the schema graph rather than trusting
 * the model's guess. One of three shapes:
 *
 * <ul>
 *   <li>{@link Passthrough} — the graph adds nothing decidable (no graph, fewer
 *       than two graph relations, a disconnected or intractable program), so the
 *       model's own program stands. Carries a {@code reason} for tracing.</li>
 *   <li>{@link Resolved} — a unique minimal path; the engine rewrote the join
 *       over graph-derived conditions ({@code program}) and recorded the
 *       multiplicity {@link BoundsFacts} of that path.</li>
 *   <li>{@link Ambiguous} — several equally minimal paths (the two-FK
 *       {@code issues → users} case); the engine cannot choose, so it enumerates
 *       the {@link Alternative}s by relationship name for a "did you mean?".</li>
 * </ul>
 */
public sealed interface JoinResolution
        permits JoinResolution.Passthrough, JoinResolution.Resolved, JoinResolution.Ambiguous {

    /**
     * Multiplicity facts of a chosen path: whether the join fans
     * out (an endpoint admits more than one match, repeating rows) and whether an
     * inner join may drop rows (an endpoint's {@code min} is zero). Recorded for
     * tracing only; nothing surfaces them to the user.
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
