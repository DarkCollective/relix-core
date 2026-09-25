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
package com.darkcollective.relix.plan.internal;

import com.darkcollective.relix.function.AggregateFunction;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.function.FunctionContext;
import com.darkcollective.relix.function.ScalarFunction;

import java.util.Objects;
import java.util.Optional;

/**
 * What a renderer needs to know about functions: which ones exist, and — for the ones
 * that are constant for a run — what this run's ambient state says they are.
 *
 * <p>The two travel together because the second is useless without the first and is
 * wanted in exactly the place the first already reaches: the arm that renders a call.
 * Carrying the context as a further parameter would thread it through every recursive
 * step of two expression renderers to be read in two of them.
 *
 * @param catalog the functions a call resolves in; never null
 * @param context the run's ambient state, holding its pinned clock — null when the
 *                planner was not told it, in which case nothing is substituted and the
 *                renderers behave as they did before substitution existed
 */
public record PushdownFunctions(FunctionCatalog catalog, FunctionContext context) {

    public PushdownFunctions {
        Objects.requireNonNull(catalog, "catalog");
    }

    /** A catalogue with no ambient state: no stable call is substituted. */
    static PushdownFunctions of(FunctionCatalog catalog) {
        return new PushdownFunctions(catalog, null);
    }

    /** This catalogue, with the run's ambient state attached. */
    PushdownFunctions withContext(FunctionContext context) {
        return new PushdownFunctions(catalog, context);
    }

    Optional<ScalarFunction> scalar(String name) {
        return catalog.scalar(name);
    }

    Optional<AggregateFunction> aggregate(String name) {
        return catalog.aggregate(name);
    }
}
