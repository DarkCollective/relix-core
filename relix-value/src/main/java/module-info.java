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
/**
 * The sealed {@code Value} hierarchy — the scalar values a query produces and consumes.
 *
 * <p>A {@link com.darkcollective.relix.value.Value} is one field of one row: a string,
 * an arbitrary-precision number, a boolean, SQL {@code NULL}, one of the four temporal
 * kinds, or a nested struct/array. Every subtype is immutable and reports the
 * {@link com.darkcollective.relix.symbol.ScalarType} it maps onto, so a value can be
 * matched against the schema that describes it.
 *
 * <p>The module holds nothing but those values and the two utilities that operate on
 * them alone — {@link com.darkcollective.relix.value.internal.ValuePath} (dotted/indexed access
 * into a nested value) and {@link com.darkcollective.relix.value.internal.JsonValues} (JSON text
 * to and from a {@code Value} tree). It deliberately knows nothing about rows,
 * operators, or execution: its one dependency is the type system it reports against, so
 * it can sit near the bottom of the module graph and be required by both the metadata
 * layer and the engine without introducing a cycle.
 */
module com.darkcollective.relix.value {
    requires transitive com.darkcollective.relix.symbol;  // ScalarType — in Value's API

    exports com.darkcollective.relix.value;
    exports com.darkcollective.relix.value.internal to com.darkcollective.relix.connectors.std, com.darkcollective.relix.function, com.darkcollective.relix.function.builtin, com.darkcollective.relix.processor;
}
