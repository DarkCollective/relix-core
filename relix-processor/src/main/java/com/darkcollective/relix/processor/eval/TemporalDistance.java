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
package com.darkcollective.relix.processor.eval;

import com.darkcollective.relix.ast.ArithmeticOperator;
import com.darkcollective.relix.value.DurationValue;
import com.darkcollective.relix.value.Value;

import java.time.Duration;
import java.util.Optional;

/**
 * The absolute temporal distance between two values — the metric that
 * complements {@link ValueComparator}'s order, for the operators that bound how
 * far apart two rows may be (the AS-OF join's {@code WITHIN} tolerance).
 *
 * <p>The distance is <em>defined as</em> the language's own subtraction rather
 * than restated as a second table of type pairs, so the two cannot drift: a
 * distance exists exactly where {@code a − b} yields a {@code DURATION}, which is
 * between two TIMESTAMPs, two DATEs (a whole-day span) and two DURATIONs. That
 * inherits the same string coercion the comparator applies, so a text cell
 * measured against a typed temporal value reads as the value it spells.
 *
 * <p>Everything else — two strings, two numbers, a TIMESTAMP against a DURATION,
 * or two TIMEs, whose difference the arithmetic algebra deliberately leaves
 * undefined because a wall-clock span wraps — has no distance, reported as an
 * empty result so the caller can phrase the objection in its own terms.
 */
public final class TemporalDistance {

    private TemporalDistance() {
    }

    /**
     * Returns the absolute distance between two non-NULL values, or empty when no
     * distance is defined for the pair.
     *
     * @param a the first value
     * @param b the second value
     * @return the absolute distance, or empty when the pair has none
     */
    public static Optional<Duration> between(Value a, Value b) {
        if (!TemporalValueArithmetic.involvesTemporal(a, b)) return Optional.empty();
        final Value difference;
        try {
            difference = TemporalValueArithmetic.binary(a, ArithmeticOperator.MINUS, b);
        } catch (EvaluationException noSuchSubtraction) {
            return Optional.empty();
        }
        return difference instanceof DurationValue d
                ? Optional.of(d.value().abs())
                : Optional.empty();
    }
}
