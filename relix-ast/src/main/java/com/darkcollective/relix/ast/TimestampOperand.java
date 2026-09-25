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

import com.darkcollective.relix.ast.internal.TemporalLiterals;
import com.darkcollective.relix.ast.visitor.OperandVisitor;

import java.time.Instant;
import java.util.Objects;

/**
 * A typed timestamp literal operand, written
 * {@code TIMESTAMP '2026-06-15T13:40:00Z'}.  The ISO-8601 payload is
 * parsed at parse time and carried as a UTC-normalised {@link Instant}: a payload
 * with an offset is converted to UTC, a payload without one is interpreted as UTC
 * (see {@link TemporalLiterals#parseTimestamp(String)}).
 *
 * @param value    the absolute instant; must not be null
 * @param location the source location of this operand; never null
 */
public record TimestampOperand(Instant value, SourceLocation location) implements Operand {
    public TimestampOperand {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public TimestampOperand(Instant value) {
        this(value, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(OperandVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
