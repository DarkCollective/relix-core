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
 * The absent (SQL {@code NULL}) value, compatible with all scalar types.
 *
 * <p>Use the singleton {@link #INSTANCE} rather than constructing a new instance.
 */
public enum NullValue implements Value {

    /** The sole null value. */
    INSTANCE;

    @Override
    public boolean isNull() {
        return true;
    }

    @Override
    public ScalarType type() {
        return ScalarType.ANY;
    }

    @Override
    public String asDisplayString() {
        return "NULL";
    }
}
