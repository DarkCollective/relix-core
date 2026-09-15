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
import com.darkcollective.relix.processor.eval.EvaluationException;
import com.darkcollective.relix.value.NumberValue;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * {@code Range(lo, hi[, step])} — a finite arithmetic series, the {@code generate_series}
 * workhorse. Emits one row per value {@code lo, lo+step, … ≤ hi} in a
 * single column {@code n:NUMBER}. {@code step} defaults to {@code 1}; {@code lo > hi}
 * yields no rows.
 *
 * <p>Arguments are integers given as strings: {@code lo} and {@code hi} are required,
 * {@code step} is an optional positive integer.
 */
public final class RangeGenerator implements Generator {

    private static final Schema SCHEMA =
            new Schema(List.of(new ColumnDefinition("n", ScalarType.NUMBER)));

    @Override
    public String name() {
        return "Range";
    }

    @Override
    public Schema schema(Map<String, String> args) {
        return SCHEMA;   // constant heading, independent of the bounds
    }

    @Override
    public Stream<Row> rows(Map<String, String> args, Schema schema) {
        long lo = requireLong(args, "lo");
        long hi = requireLong(args, "hi");
        long step = args.containsKey("step") ? requireLong(args, "step") : 1L;
        if (step <= 0) {
            throw new EvaluationException("Range 'step' must be a positive integer, got " + step);
        }
        if (lo > hi) {
            return Stream.empty();
        }
        long count = (hi - lo) / step + 1;
        return LongStream.range(0, count)
                .mapToObj(i -> (Row) ArrayRow.of(schema,
                        List.of(new NumberValue(BigDecimal.valueOf(lo + i * step)))));
    }

    @Override
    public OptionalLong cardinality(Map<String, String> args) {
        try {
            long lo = requireLong(args, "lo");
            long hi = requireLong(args, "hi");
            long step = args.containsKey("step") ? requireLong(args, "step") : 1L;
            if (step <= 0) {
                return OptionalLong.empty();   // invalid; surfaced at open time
            }
            return OptionalLong.of(lo > hi ? 0L : (hi - lo) / step + 1L);
        } catch (EvaluationException invalidArgs) {
            return OptionalLong.empty();   // cost is advisory — never throw here
        }
    }

    @Override
    public boolean duplicateFree() {
        return true;   // a strictly-increasing series has no duplicates
    }

    private static long requireLong(Map<String, String> args, String key) {
        String raw = args.get(key);
        if (raw == null) {
            throw new EvaluationException("Range requires a '" + key + "' argument");
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new EvaluationException("Range '" + key + "' must be an integer, got '" + raw + "'");
        }
    }
}
