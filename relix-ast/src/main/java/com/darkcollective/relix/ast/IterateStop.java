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

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * When an {@link IterateNode} stops.
 *
 * <p>The three permits are three different claims about the result, not three
 * spellings of one:
 *
 * <ul>
 *   <li>{@link Rounds} — {@code ROUNDS n}: apply the step exactly {@code n} times.
 *       Repetition, with no claim that anything settled; it always finishes.</li>
 *   <li>{@link Stable} — {@code UNTIL STABLE MAX n ROUNDS}: stop when a round's
 *       output equals its input as a set.</li>
 *   <li>{@link Converged} — {@code UNTIL c, … WITHIN ε PER k, … MAX n ROUNDS}: stop
 *       when no row's value in any of the named columns moved by more than
 *       {@code ε} since the previous round, rows being paired across rounds by the
 *       key columns.</li>
 * </ul>
 *
 * <p>The two {@code UNTIL} forms carry a mandatory round cap: unlike {@code FIX},
 * a replace-each-round iteration can run for ever even over a finite set of
 * values, so a convergence test without a cap is refused by the grammar.
 */
public sealed interface IterateStop
        permits IterateStop.Rounds, IterateStop.Stable, IterateStop.Converged {

    /**
     * The round cap the iteration may not exceed, or — for {@link Rounds} — the
     * exact number of rounds it runs.
     *
     * @return a non-negative round count
     */
    int rounds();

    /**
     * The clause as a report shows it — {@code ROUNDS 10},
     * {@code UNTIL STABLE MAX 50 ROUNDS}, {@code UNTIL rank WITHIN 0.0001 PER node MAX 100 ROUNDS}.
     * Names are written as they were given, without the quoting a script needs for a
     * reserved word; the pretty-printer is what produces re-parseable text.
     *
     * @return the stop clause for display
     */
    default String clause() {
        return switch (this) {
            case Rounds r -> "ROUNDS " + r.rounds();
            case Stable s -> "UNTIL STABLE MAX " + s.rounds() + " ROUNDS";
            case Converged c -> "UNTIL " + String.join(", ", c.columns())
                    + " WITHIN " + c.tolerance().toPlainString()
                    + " PER " + String.join(", ", c.keys()) + " MAX " + c.rounds() + " ROUNDS";
        };
    }

    /**
     * {@code ROUNDS n} — apply the step exactly {@code n} times; {@code ROUNDS 0}
     * is the base unchanged.
     *
     * @param rounds the number of rounds; must be ≥ 0
     */
    record Rounds(int rounds) implements IterateStop {
        public Rounds {
            requireNonNegative(rounds);
        }
    }

    /**
     * {@code UNTIL STABLE MAX n ROUNDS} — stop at the first round whose output
     * equals its input.
     *
     * @param rounds the round cap; must be ≥ 1
     */
    record Stable(int rounds) implements IterateStop {
        public Stable {
            requirePositive(rounds);
        }
    }

    /**
     * {@code UNTIL c, … WITHIN ε PER k, … MAX n ROUNDS} — stop when, for every key,
     * every named column changed by at most {@code tolerance} since the previous
     * round, and no key appeared or disappeared.
     *
     * @param columns   the numeric columns tested for convergence; non-empty
     * @param tolerance the largest change still counted as converged; must be ≥ 0
     * @param keys      the columns pairing a row with its counterpart in the
     *                  previous round; non-empty
     * @param rounds    the round cap; must be ≥ 1
     */
    record Converged(List<String> columns, BigDecimal tolerance, List<String> keys, int rounds)
            implements IterateStop {
        public Converged {
            columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
            keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
            Objects.requireNonNull(tolerance, "tolerance");
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("UNTIL needs at least one column");
            }
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("UNTIL … PER needs at least one key column");
            }
            if (tolerance.signum() < 0) {
                throw new IllegalArgumentException("WITHIN tolerance must not be negative, got " + tolerance);
            }
            requirePositive(rounds);
        }
    }

    private static void requireNonNegative(int rounds) {
        if (rounds < 0) {
            throw new IllegalArgumentException("ROUNDS must not be negative, got " + rounds);
        }
    }

    private static void requirePositive(int rounds) {
        if (rounds < 1) {
            throw new IllegalArgumentException("MAX … ROUNDS must be at least 1, got " + rounds);
        }
    }
}
