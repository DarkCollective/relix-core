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
package com.darkcollective.relix.processor;

import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * Shared test infrastructure for the relix-processor test suite.
 *
 * <p>Provides factory helpers for commonly constructed test objects so that
 * individual tests stay concise and focused on the behaviour under test.
 */
public abstract class ProcessorTestSupport {

    // -------------------------------------------------------------------------
    // Value factories
    // -------------------------------------------------------------------------

    protected static StringValue str(String value) {
        return new StringValue(value);
    }

    protected static NumberValue num(String value) {
        return NumberValue.of(value);
    }

    protected static NumberValue num(int value) {
        return new NumberValue(BigDecimal.valueOf(value));
    }

    protected static BooleanValue bool(boolean value) {
        return BooleanValue.of(value);
    }

    protected static NullValue nullVal() {
        return NullValue.INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Schema factories
    // -------------------------------------------------------------------------

    protected static Schema schema(String... columnNames) {
        List<ColumnDefinition> cols = List.of(columnNames).stream()
                .map(n -> new ColumnDefinition(n, ScalarType.ANY))
                .toList();
        return new Schema(cols);
    }

    protected static Schema schema(ColumnDefinition... columns) {
        return new Schema(List.of(columns));
    }

    protected static ColumnDefinition col(String name, ScalarType type) {
        return new ColumnDefinition(name, type);
    }

    // -------------------------------------------------------------------------
    // Row factories
    // -------------------------------------------------------------------------

    protected static Row row(Schema schema, Value... values) {
        return ArrayRow.of(schema, values);
    }
}
