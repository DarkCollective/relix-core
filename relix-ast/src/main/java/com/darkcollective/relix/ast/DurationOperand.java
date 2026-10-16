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

import com.darkcollective.relix.ast.visitor.OperandVisitor;

import java.time.Duration;
import java.util.Objects;

/**
 * A typed duration literal operand, written {@code DURATION 'PT30M'}, or with
 * calendar months {@code DURATION 'P1M'} or {@code INTERVAL '1 month'}. The payload is
 * parsed at parse time and carried as a count of calendar {@linkplain #months() months}
 * and an exact, time-based {@link Duration}, a day being 24 hours.
 *
 * @param months   the calendar months; zero for an exact duration
 * @param value    the exact part; must not be null
 * @param location the source location of this operand; never null
 */
public record DurationOperand(long months, Duration value, SourceLocation location) implements Operand {
    public DurationOperand {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public DurationOperand(Duration value) {
        this(0, value, SourceLocation.UNKNOWN);
    }

    /**
     * Whether this is an exact span, with no calendar months.
     *
     * @return {@code true} when {@link #months()} is zero
     */
    public boolean isExact() {
        return months == 0;
    }

    /**
     * The literal's ISO-8601 text, e.g. {@code PT30M} or {@code P1Y2M}.
     *
     * @return the text
     */
    public String iso() {
        return com.darkcollective.relix.ast.internal.TemporalLiterals.formatSpan(months, value);
    }

    @Override
    public <R> R accept(OperandVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
