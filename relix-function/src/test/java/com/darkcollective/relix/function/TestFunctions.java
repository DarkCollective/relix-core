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
package com.darkcollective.relix.function;

import com.darkcollective.relix.symbol.FunctionProperty;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.value.StringValue;
import com.darkcollective.relix.value.Value;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Small definitions the SPI tests build catalogues out of.
 *
 * <p>Deliberately written the way a third-party library would write them — a record
 * implementing the interface, nothing more — since a fixture that needs privileged
 * access would be evidence against the seam it is testing.
 */
final class TestFunctions {

    private TestFunctions() {}

    /** A strict scalar function returning a fixed marker string, for identity checks. */
    record Marker(String fnName, String marker) implements StrictScalarFunction {
        @Override
        public FunctionSignature signature() {
            return FunctionSignature.of(fnName, ScalarType.STRING, "test",
                    Set.of(FunctionProperty.PURE, FunctionProperty.DETERMINISTIC),
                    new ParameterDefinition("s", ScalarType.STRING));
        }

        @Override
        public Value invoke(FunctionContext context, List<Value> arguments) {
            return new StringValue(marker);
        }
    }

    /** A lazy scalar function yielding whichever argument it is told to read. */
    record PickOne(String fnName, int index) implements LazyScalarFunction {
        @Override
        public FunctionSignature signature() {
            return new FunctionSignature(fnName, List.of(), Arity.atLeast(1),
                    ScalarType.ANY, Set.of(), "test", java.util.Optional.empty());
        }

        @Override
        public Value invoke(FunctionContext context, List<Argument> arguments) {
            return arguments.get(index).value();
        }
    }

    /** An aggregate counting the rows it is fed. */
    record Counter(String aggName) implements AggregateFunction {
        @Override
        public AggregateSignature signature() {
            return AggregateSignature.of(aggName, ScalarType.NUMBER,
                    Set.of(AggregateProperty.ORDER_INSENSITIVE),
                    new ParameterDefinition("x", ScalarType.ANY));
        }

        @Override
        public Accumulator accumulator(FunctionContext context) {
            return new Accumulator() {
                private long seen;

                @Override
                public void accumulate(List<Value> arguments) {
                    seen++;
                }

                @Override
                public Value finish() {
                    return new NumberValue(BigDecimal.valueOf(seen));
                }
            };
        }
    }

    /** A library offering exactly what it is handed, at the priority it is given. */
    record Library(String name, int priority,
                   List<ScalarFunction> scalarFunctions,
                   List<AggregateFunction> aggregateFunctions) implements FunctionLibrary {

        static Library of(String name, ScalarFunction... functions) {
            return new Library(name, 0, List.of(functions), List.of());
        }

        static Library at(String name, int priority, ScalarFunction... functions) {
            return new Library(name, priority, List.of(functions), List.of());
        }

        static Library aggregates(String name, AggregateFunction... functions) {
            return new Library(name, 0, List.of(), List.of(functions));
        }
    }

    /**
     * A library written the smallest way the SPI allows — one method, everything else
     * left to its default. It exists to hold those defaults to their word.
     */
    record MinimalLibrary(String name) implements FunctionLibrary {}
}
