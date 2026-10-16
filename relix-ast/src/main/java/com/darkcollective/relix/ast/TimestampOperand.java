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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * A typed timestamp literal operand, written
 * {@code TIMESTAMP '2026-06-15T13:40:00Z'}.  The ISO-8601 payload is
 * parsed at parse time and carried as a UTC-normalised {@link Instant}: a payload
 * with an offset is converted to UTC, a payload without one is interpreted as UTC
 * (see {@link TemporalLiterals#parseTimestamp(String)}).
 *
 * <p>With an {@linkplain #offset() offset} it is a {@code TIMESTAMPTZ} literal instead,
 * written {@code TIMESTAMPTZ '2026-03-01T09:00:00+02:00'} or
 * {@code TIMESTAMP WITH TIME ZONE '…'}: the same instant, and the offset it was written
 * at, which it keeps.
 *
 * @param value    the absolute instant; must not be null
 * @param offset   for a {@code TIMESTAMPTZ} literal, the offset it was written at;
 *                 empty for a {@code TIMESTAMP}
 * @param location the source location of this operand; never null
 */
public record TimestampOperand(Instant value, Optional<ZoneOffset> offset, SourceLocation location)
        implements Operand {
    public TimestampOperand {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(offset, "offset");
        Objects.requireNonNull(location, "location");
    }

    /** A {@code TIMESTAMP} literal: an instant, with no offset of its own. */
    public TimestampOperand(Instant value, SourceLocation location) {
        this(value, Optional.empty(), location);
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public TimestampOperand(Instant value) {
        this(value, Optional.empty(), SourceLocation.UNKNOWN);
    }

    /**
     * A {@code TIMESTAMPTZ} literal: the moment and the offset it was written at.
     *
     * @param value    the moment, with its offset
     * @param location the source location of this operand
     * @return the operand
     */
    public static TimestampOperand zoned(OffsetDateTime value, SourceLocation location) {
        return new TimestampOperand(value.toInstant(), Optional.of(value.getOffset()), location);
    }

    /**
     * Whether this is a {@code TIMESTAMPTZ} literal, one that keeps an offset.
     *
     * @return {@code true} when it has an {@link #offset()}
     */
    public boolean isZoned() {
        return offset.isPresent();
    }

    /**
     * This literal's moment at its offset, or at UTC for a {@code TIMESTAMP}.
     *
     * @return the moment with an offset
     */
    public OffsetDateTime atOffset() {
        return value.atOffset(offset.orElse(ZoneOffset.UTC));
    }

    @Override
    public <R> R accept(OperandVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
