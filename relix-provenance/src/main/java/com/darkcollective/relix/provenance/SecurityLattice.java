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
 * The security / access-control lattice semiring over {@link SecurityLevel}
 * {@code (min, max)} — propagates a confidentiality (clearance) level through a
 * query so each output tuple carries the level at which it may be released.
 *
 * <p>{@link #plus ⊕} is {@code min} (a tuple derivable two ways is released at the
 * <em>most accessible</em> of them), {@link #times ⊗} is {@code max} (a join is
 * only as releasable as its <em>most restrictive</em> input). The identities follow
 * from the total order {@code PUBLIC < CONFIDENTIAL < SECRET < TOP_SECRET}:
 * {@link #zero() 0} is {@link SecurityLevel#TOP_SECRET} (the {@code min} identity
 * and absent/unreachable element) and {@link #one() 1} is
 * {@link SecurityLevel#PUBLIC} (the {@code max} identity). Annihilation holds
 * because {@code max(TOP_SECRET, x) == TOP_SECRET}, and {@code max} distributes
 * over {@code min}.
 */
public enum SecurityLattice implements Semiring<SecurityLevel> {

    /** The singleton instance. */
    INSTANCE;

    @Override
    public SecurityLevel zero() {
        return SecurityLevel.TOP_SECRET;
    }

    @Override
    public SecurityLevel one() {
        return SecurityLevel.PUBLIC;
    }

    @Override
    public SecurityLevel plus(SecurityLevel a, SecurityLevel b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }

    @Override
    public SecurityLevel times(SecurityLevel a, SecurityLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
