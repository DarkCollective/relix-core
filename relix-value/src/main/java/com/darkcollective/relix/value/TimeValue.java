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

import java.time.LocalTime;
import java.util.Objects;

/**
 * A runtime wall-clock time-of-day value with no time zone, backed by
 * {@link LocalTime} (e.g. {@code 13:40:00}).
 *
 * @param value the time of day; must not be {@code null}
 */
public record TimeValue(LocalTime value) implements Value {

    public TimeValue {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public boolean isNull() {
        return false;
    }

    @Override
    public ScalarType type() {
        return ScalarType.TIME;
    }

    /**
     * Returns the canonical ISO-8601 time string, e.g. {@code "13:40"} or
     * {@code "13:40:00.5"} (trailing zero fields are elided by
     * {@link LocalTime#toString()}).
     *
     * @return ISO-8601 local-time string
     */
    @Override
    public String asDisplayString() {
        return value.toString();
    }
}
