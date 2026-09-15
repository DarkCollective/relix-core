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
package com.darkcollective.relix.provenance;

/**
 * The boolean semiring {@code (𝔹, ∨, ∧, false, true)} — plain <em>set semantics</em>
 * (a tuple is present or absent). This is relix's default annotation: a query with
 * no provenance request behaves exactly as a boolean K-relation.
 *
 * <p>{@link #plus ⊕} is logical OR (a tuple exists if any derivation produces it),
 * {@link #times ⊗} is logical AND (a join's tuple exists only if both inputs do),
 * {@link #zero() 0} is {@code false} (absent), and {@link #one() 1} is {@code true}.
 */
public enum BooleanSemiring implements Semiring<Boolean> {

    /** The singleton instance. */
    INSTANCE;

    @Override
    public Boolean zero() {
        return Boolean.FALSE;
    }

    @Override
    public Boolean one() {
        return Boolean.TRUE;
    }

    @Override
    public Boolean plus(Boolean a, Boolean b) {
        return a || b;
    }

    @Override
    public Boolean times(Boolean a, Boolean b) {
        return a && b;
    }
}
