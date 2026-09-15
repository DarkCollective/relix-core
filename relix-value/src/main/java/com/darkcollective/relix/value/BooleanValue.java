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

/**
 * A runtime boolean value.
 *
 * <p>Prefer the constants {@link #TRUE} and {@link #FALSE} over the constructor
 * to avoid unnecessary allocation.
 *
 * @param value the boolean content
 */
public record BooleanValue(boolean value) implements Value {

    /** The singleton {@code true} value. */
    public static final BooleanValue TRUE = new BooleanValue(true);

    /** The singleton {@code false} value. */
    public static final BooleanValue FALSE = new BooleanValue(false);

    /**
     * Returns {@link #TRUE} or {@link #FALSE} for the given boolean.
     *
     * @param value the boolean
     * @return the corresponding constant
     */
    public static BooleanValue of(boolean value) {
        return value ? TRUE : FALSE;
    }

    @Override
    public boolean isNull() {
        return false;
    }

    @Override
    public ScalarType type() {
        return ScalarType.BOOLEAN;
    }

    @Override
    public String asDisplayString() {
        return value ? "true" : "false";
    }
}
