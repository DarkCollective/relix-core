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
package com.darkcollective.relix.function.builtin;

import com.darkcollective.relix.function.Accumulator;
import com.darkcollective.relix.function.AggregateFunction;
import com.darkcollective.relix.function.AggregateProperty;
import com.darkcollective.relix.function.AggregateSignature;
import com.darkcollective.relix.function.Arity;
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.function.PushdownSpelling;
import com.darkcollective.relix.symbol.ParameterDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Type;
import com.darkcollective.relix.value.BooleanValue;
import com.darkcollective.relix.value.NullValue;
import com.darkcollective.relix.value.Value;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.darkcollective.relix.function.builtin.Arguments.reject;

/**
 * The boolean aggregates: {@code BOOL_AND}, whose standard name {@code EVERY} the parser
 * reads as the same aggregate, and {@code BOOL_OR}.
 *
 * <p>Each takes a {@code BOOLEAN} and returns one, as PostgreSQL's do. A NULL is skipped,
 * and a group with no value, empty or all NULL, is NULL. Neither cares how many times it
 * saw a value or in what order, and each merges, so it can be computed in parts.
 *
 * <p>Only PostgreSQL is offered a spelling, its own {@code bool_and} and {@code bool_or}.
 * MySQL's {@code MIN} and {@code MAX} over a {@code TINYINT(1)} answer {@code 0} or
 * {@code 1}, which is not the same value without a cast, so every other dialect leaves
 * the aggregate to the engine.
 */
final class BooleanAggregates {

    private BooleanAggregates() {
    }

    static List<AggregateFunction> all() {
        return List.of(definition("BOOL_AND", true), definition("BOOL_OR", false));
    }

    private static AggregateFunction definition(String name, boolean conjunction) {
        AggregateSignature signature = new AggregateSignature(name,
                List.of(new ParameterDefinition("value", ScalarType.BOOLEAN)), Arity.exactly(1),
                ScalarType.BOOLEAN,
                Set.of(AggregateProperty.DUPLICATE_INSENSITIVE, AggregateProperty.ORDER_INSENSITIVE),
                true, AggregateSignature.AGGREGATE, Optional.empty());
        return new Definition(signature, Spellings.sqlOn(name, 1, Spellings.POSTGRES),
                context -> new Fold(name, conjunction));
    }

    private record Definition(AggregateSignature signature, PushdownSpelling pushdown,
                              java.util.function.Function<FunctionContext, Accumulator> reduction)
            implements AggregateFunction {

        @Override
        public Accumulator accumulator(FunctionContext context) {
            return reduction.apply(context);
        }

        @Override
        public Type returnTypeFor(List<Type> argumentTypes) {
            return ScalarType.BOOLEAN;
        }
    }

    /**
     * AND or OR over the values seen. NULL until a value arrives; a conjunction stays true
     * until a false, a disjunction false until a true.
     */
    private static final class Fold implements Accumulator {

        private final String name;
        private final boolean conjunction;
        private Boolean result;

        Fold(String name, boolean conjunction) {
            this.name = name;
            this.conjunction = conjunction;
        }

        @Override
        public void accumulate(List<Value> arguments) {
            Value value = arguments.get(0);
            if (!(value instanceof BooleanValue b)) {
                throw reject(name + ": expected BOOLEAN, got " + value.type());
            }
            combine(b.value());
        }

        @Override
        public void merge(Accumulator other) {
            Boolean theirs = ((Fold) other).result;
            if (theirs != null) {
                combine(theirs);
            }
        }

        private void combine(boolean value) {
            result = result == null ? value : conjunction ? result && value : result || value;
        }

        @Override
        public Value finish() {
            return result == null ? NullValue.INSTANCE : BooleanValue.of(result);
        }
    }
}
