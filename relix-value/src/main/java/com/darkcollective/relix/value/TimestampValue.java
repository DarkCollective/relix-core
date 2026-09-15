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
package com.darkcollective.relix.value;

import com.darkcollective.relix.symbol.ScalarType;

import java.time.Instant;
import java.util.Objects;

/**
 * A runtime timestamp value — an absolute point on the timeline, UTC-normalised,
 * backed by {@link Instant} (e.g. {@code 2026-06-15T13:40:00Z}).
 *
 * @param value the instant; must not be {@code null}
 */
public record TimestampValue(Instant value) implements Value {

    public TimestampValue {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public boolean isNull() {
        return false;
    }

    @Override
    public ScalarType type() {
        return ScalarType.TIMESTAMP;
    }

    /**
     * Returns the canonical ISO-8601 instant string with a trailing {@code Z},
     * e.g. {@code "2026-06-15T13:40:00Z"}.
     *
     * @return ISO-8601 UTC-instant string
     */
    @Override
    public String asDisplayString() {
        return value.toString();
    }
}
