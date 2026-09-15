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

import java.time.Duration;
import java.util.Objects;

/**
 * A runtime duration value — an exact elapsed span of seconds and nanoseconds,
 * backed by {@link Duration} (e.g. {@code PT30M} for thirty minutes).
 *
 * <p>This is a time-based span only: irregular calendar units (months, years)
 * are not representable.
 *
 * @param value the elapsed span; must not be {@code null}
 */
public record DurationValue(Duration value) implements Value {

    public DurationValue {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public boolean isNull() {
        return false;
    }

    @Override
    public ScalarType type() {
        return ScalarType.DURATION;
    }

    /**
     * Returns the canonical ISO-8601 duration string, e.g. {@code "PT30M"}.
     *
     * @return ISO-8601 duration string
     */
    @Override
    public String asDisplayString() {
        return value.toString();
    }
}
