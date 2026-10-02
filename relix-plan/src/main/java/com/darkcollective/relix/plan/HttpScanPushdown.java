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
package com.darkcollective.relix.plan;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a {@link PhysicalNode.Scan} over an HTTP source has folded into its request — a
 * query {@code λ} bound to the source's declared {@code paginate} limit, and {@code σ}
 * equalities bound to the source's {@code IN} columns (a query parameter or a path
 * segment).
 *
 * <p>Both are <b>pure optimizations</b>: the engine {@code λ} and {@code σ} that produced
 * them stay in the plan above the scan, so a server that ignores a page-size parameter,
 * clamps it, or does not filter still yields the right answer — the request fetches fewer
 * rows, and the engine operators are the backstop that guarantees the result. Nothing here
 * can change what a query returns; it can only change how much is fetched over the wire.
 *
 * @param limit      the row count folded from an adjacent {@code λ}, bound to the declared
 *                   {@code paginate} limit parameter; empty when none was pushed
 * @param equalities IN-column name to the literal value folded from an {@code σ} equality
 *                   on it; empty when none was pushed. The connector maps each column to
 *                   its declared binding (a query parameter or path segment).
 */
public record HttpScanPushdown(Optional<Long> limit, Map<String, String> equalities) {

    public HttpScanPushdown {
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(equalities, "equalities");
        equalities = Map.copyOf(equalities);
    }

    /** {@return this pushdown with {@code count} as the folded limit} */
    public HttpScanPushdown withLimit(long count) {
        return new HttpScanPushdown(Optional.of(count), equalities);
    }

    /** {@return this pushdown with {@code more} equalities added to any already folded} */
    public HttpScanPushdown withEqualities(Map<String, String> more) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(equalities);
        merged.putAll(more);
        return new HttpScanPushdown(limit, merged);
    }

    /** The empty pushdown — nothing folded. */
    public static HttpScanPushdown none() {
        return new HttpScanPushdown(Optional.empty(), Map.of());
    }
}
