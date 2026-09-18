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

import java.util.List;

/**
 * A {@link SemiringLibrary} registered in {@code META-INF/services} so that discovery is
 * exercised by the mechanism a real provider would use, rather than by handing the catalog
 * a list.
 *
 * <p>Being discovered, its semiring is in {@link Semirings#names()} for every test in this
 * module — including {@link RegisteredSemiringLawsTest}, which therefore holds a
 * third-party semiring to the same laws as a built-in one. That is the point of putting it
 * here rather than constructing it where it is needed.
 */
public final class DiscoverableTestSemiringLibrary implements SemiringLibrary {

    /** Public no-argument constructor: {@link java.util.ServiceLoader} requires one. */
    public DiscoverableTestSemiringLibrary() {
    }

    @Override
    public String name() {
        return "test-kinship";
    }

    @Override
    public List<NamedSemiring> semirings() {
        return List.of(new NamedSemiring("kinship", List.of("consanguinity"),
                KinshipSemiring.INSTANCE,
                "Coefficient of relationship — alternative lines of descent add, "
                        + "successive generations halve."));
    }
}
