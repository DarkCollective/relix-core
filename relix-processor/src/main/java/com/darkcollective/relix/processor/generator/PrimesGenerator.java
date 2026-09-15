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
 * {@code Primes} — the conceptual driver of the generator story: the prime
 * numbers {@code 2, 3, 5, 7, 11, …} in a single column {@code n:NUMBER}, in ascending
 * order. Takes no arguments.
 *
 * <p>Primes are enumerated lazily via {@link BigInteger#nextProbablePrime()} starting
 * at {@code 2}, so the stream is genuinely <strong>infinite</strong> and never
 * overflows. {@link #unbounded()} is {@code true}: {@code γ count (Primes)} is a
 * plan-time error, while {@code λ 10 (Primes)} streams the first ten primes. The
 * series is strictly increasing, so it is {@link #duplicateFree()}.
 *
 * <p>There is no existence probe: {@code σ n = 17 (Primes)} enumerates up to 17
 * rather than testing 17 for primality directly.
 */
public final class PrimesGenerator implements Generator {

    private static final Schema SCHEMA =
            new Schema(List.of(new ColumnDefinition("n", ScalarType.NUMBER)));

    @Override
    public String name() {
        return "Primes";
    }

    @Override
    public Schema schema(Map<String, String> args) {
        return SCHEMA;   // constant heading; the generator takes no arguments
    }

    @Override
    public Stream<Row> rows(Map<String, String> args, Schema schema) {
        return Stream.iterate(BigInteger.TWO, BigInteger::nextProbablePrime)
                .map(p -> (Row) ArrayRow.of(schema, List.of(new NumberValue(new BigDecimal(p)))));
    }

    @Override
    public boolean unbounded() {
        return true;   // there is no largest prime
    }

    @Override
    public boolean duplicateFree() {
        return true;   // a strictly-increasing series has no duplicates
    }

    @Override
    public java.util.Optional<String> ascendingColumn() {
        return java.util.Optional.of("n");   // primes ascend in n — an upper bound on n is a valid stop
    }
}
