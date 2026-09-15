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
 * Runtime scalar value types for query execution.
 *
 * <p>The sealed {@link com.darkcollective.relix.value.Value} interface
 * represents a single scalar value produced or consumed during query evaluation.
 * Its permitted subtypes map onto the {@link com.darkcollective.relix.symbol.ScalarType}
 * lattice:
 *
 * <ul>
 *   <li>{@link com.darkcollective.relix.value.StringValue} — textual data</li>
 *   <li>{@link com.darkcollective.relix.value.NumberValue} — arbitrary-precision
 *       decimal numbers ({@link java.math.BigDecimal})</li>
 *   <li>{@link com.darkcollective.relix.value.BooleanValue} — boolean flags;
 *       prefer the {@code TRUE}/{@code FALSE} constants</li>
 *   <li>{@link com.darkcollective.relix.value.NullValue} — the absent value
 *       (SQL {@code NULL}); use the singleton {@code INSTANCE}</li>
 *   <li>{@link com.darkcollective.relix.value.DateValue},
 *       {@link com.darkcollective.relix.value.TimeValue},
 *       {@link com.darkcollective.relix.value.TimestampValue},
 *       {@link com.darkcollective.relix.value.DurationValue} — the four temporal kinds,
 *       each holding the corresponding {@link java.time} value</li>
 *   <li>{@link com.darkcollective.relix.value.StructValue},
 *       {@link com.darkcollective.relix.value.ArrayValue} — nested values, which report
 *       {@link com.darkcollective.relix.symbol.ScalarType#ANY}</li>
 * </ul>
 *
 * <p>Every subtype is immutable.  Use exhaustive pattern matching on the sealed
 * hierarchy rather than {@code instanceof} chains.
 *
 * <p>Two utilities operate on values alone and so live beside them:
 * {@link com.darkcollective.relix.value.ValuePath} reads a dotted/indexed path into a
 * nested value, and {@link com.darkcollective.relix.value.JsonValues} converts between
 * JSON text and a {@code Value} tree.
 */
package com.darkcollective.relix.value;
