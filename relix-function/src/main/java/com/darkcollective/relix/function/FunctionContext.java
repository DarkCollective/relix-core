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

import com.darkcollective.relix.value.Value;

import java.time.Clock;
import java.util.Comparator;
import java.util.Objects;

/**
 * The ambient state a function implementation may read while it runs.
 *
 * <p>Everything a function needs beyond its arguments arrives here, so that reading
 * ambient state is an explicit dependency rather than a static call into the
 * platform. The clock is the first member: {@code NOW()} reads it instead of
 * {@link java.time.Instant#now()}, which is what makes a whole run reproducible
 * when the engine pins one.
 *
 * <p>This is an interface rather than a record because it grows. A time zone, a
 * random source or a locale can be added as a {@code default} member without
 * breaking an implementation compiled against an earlier version.
 */
public interface FunctionContext {

    /**
     * The clock the current-time functions read.
     *
     * @return the clock for this evaluation; never {@code null}
     */
    Clock clock();

    /**
     * The engine's total order over values — what {@code smaller} means to a reduction
     * that has to pick one.
     *
     * <p>An aggregate that ranks its input cannot invent an ordering: two libraries that
     * each defined their own would disagree with the engine's sort and with each other,
     * and the disagreement would show up as a {@code MIN} that does not match the first
     * row of a {@code τ}. The engine supplies one, and every implementation reads it
     * from here.
     *
     * <p>The default orders NULL last and compares like with like, which is what an
     * implementation gets when nothing supplies a richer one. The engine's own order
     * additionally reads an ISO-8601 string as the temporal value it spells, so a value
     * that arrived as text from a schema-less source compares against a typed one.
     *
     * @return the comparator to rank values with; never {@code null}
     */
    default Comparator<Value> valueOrder() {
        return ValueOrder.NULLS_LAST;
    }

    /**
     * A context reading the given clock and nothing else.
     *
     * @param clock the clock current-time functions read
     * @return a context over {@code clock}
     */
    static FunctionContext of(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return () -> clock;
    }

    /**
     * A context reading the given clock and ranking values with the given order.
     *
     * @param clock      the clock current-time functions read
     * @param valueOrder the total order over values
     * @return a context over {@code clock} and {@code valueOrder}
     */
    static FunctionContext of(Clock clock, Comparator<Value> valueOrder) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(valueOrder, "valueOrder");
        return new FunctionContext() {
            @Override
            public Clock clock() {
                return clock;
            }

            @Override
            public Comparator<Value> valueOrder() {
                return valueOrder;
            }
        };
    }

    /**
     * A context reading the system UTC clock — the default when nothing has pinned
     * one, and the right choice in a test that does not observe time.
     *
     * @return a context over {@link Clock#systemUTC()}
     */
    static FunctionContext systemDefault() {
        return of(Clock.systemUTC());
    }
}
