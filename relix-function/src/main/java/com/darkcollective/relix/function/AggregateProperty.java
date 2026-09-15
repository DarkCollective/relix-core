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

/**
 * A contract an aggregate makes with the optimizer, in the way
 * {@link com.darkcollective.relix.symbol.FunctionProperty} does for scalar functions.
 *
 * <p>Each constant licenses a rewrite that would otherwise be unsound, so declaring one
 * of an aggregate that does not honour it changes results rather than timings.
 */
public enum AggregateProperty {

    /**
     * Feeding the same value twice does not change the result, so a duplicate-removing
     * step below the aggregate can be dropped: {@code MIN} and {@code MAX} do not care
     * how many times they saw a value, while {@code SUM}, {@code AVG}, {@code COUNT} and
     * {@code COLLECT} very much do.
     */
    DUPLICATE_INSENSITIVE,

    /**
     * The result does not depend on the order rows arrive in. {@code SUM} qualifies;
     * {@code COLLECT}, which gathers values into an array in row order, does not, and
     * neither does an {@code ARGMAX} whose ties resolve to the first matching row.
     */
    ORDER_INSENSITIVE
}
