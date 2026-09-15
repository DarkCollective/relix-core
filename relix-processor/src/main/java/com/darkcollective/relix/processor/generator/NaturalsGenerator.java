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
package com.darkcollective.relix.processor.generator;

import com.darkcollective.relix.processor.ArrayRow;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code Naturals} — the canonical <strong>infinite</strong> leaf: the
 * natural numbers ℕ₀ {@code 0, 1, 2, 3, …} in a single column {@code n:NUMBER}.
 * Takes no arguments.
 *
 * <p>The rows are produced by an arbitrary-precision {@link BigInteger} counter, so
 * the stream never overflows — it is genuinely unbounded. {@link #unbounded()} is
 * therefore {@code true}: a blocking operator over {@code Naturals} (e.g.
 * {@code γ count (Naturals)}) is a plan-time error, while a streaming consumer with a
 * bound — {@code λ 10 (Naturals)} — pulls only as many rows as it needs (the limit
 * operator consumes the stream lazily). The series is strictly increasing, so it is
 * {@link #duplicateFree()}.
 */
public final class NaturalsGenerator implements Generator {

    private static final Schema SCHEMA =
            new Schema(List.of(new ColumnDefinition("n", ScalarType.NUMBER)));

    @Override
    public String name() {
        return "Naturals";
    }

    @Override
    public Schema schema(Map<String, String> args) {
        return SCHEMA;   // constant heading; the generator takes no arguments
    }

    @Override
    public Stream<Row> rows(Map<String, String> args, Schema schema) {
        return Stream.iterate(BigInteger.ZERO, n -> n.add(BigInteger.ONE))
                .map(n -> (Row) ArrayRow.of(schema, List.of(new NumberValue(new BigDecimal(n)))));
    }

    @Override
    public boolean unbounded() {
        return true;   // ℕ₀ has no last row
    }

    @Override
    public boolean duplicateFree() {
        return true;   // a strictly-increasing series has no duplicates
    }

    @Override
    public java.util.Optional<String> ascendingColumn() {
        return java.util.Optional.of("n");   // ℕ₀ ascends in n — an upper bound on n is a valid stop
    }
}
