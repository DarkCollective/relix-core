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
package com.darkcollective.relix.processor.internal;

/**
 * Callback that receives one query's physical plan, rendered as text.
 *
 * <p>Used by {@link QueryExecutor#explain} to hand each {@code query} statement's
 * physical plan to the caller (e.g. the CLI's {@code --explain} output) without
 * the caller needing to depend on the physical-plan types directly.
 */
@FunctionalInterface
public interface PlanConsumer {

    /**
     * Consumes one query's rendered physical plan.
     *
     * @param label    the query's display label (relation name or {@code "<expression N>"})
     * @param planText the multi-line ASCII rendering of the physical plan
     */
    void accept(String label, String planText);
}
