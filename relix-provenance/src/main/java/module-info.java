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
/**
 * The provenance-semiring algebra for relix (K-relations).
 *
 * <p>This module defines a single pluggable extension point —
 * {@link com.darkcollective.relix.provenance.Semiring} — and the cheap,
 * fixed-size built-in semirings that instantiate it: existence
 * ({@link com.darkcollective.relix.provenance.BooleanSemiring}), multiplicity /
 * path-count ({@link com.darkcollective.relix.provenance.CountingSemiring}),
 * cheapest-derivation / shortest-path
 * ({@link com.darkcollective.relix.provenance.TropicalSemiring}), and
 * trust / access-control
 * ({@link com.darkcollective.relix.provenance.SecurityLattice}).
 *
 * <p>Provenance (Green, Karvounarakis &amp; Tannen, PODS 2007) annotates each
 * tuple with a semiring element and threads {@code ⊕}/{@code ⊗} through every
 * positive-algebra operator, so one mechanism yields set semantics, bag
 * multiplicity, why-provenance lineage, trust levels, and semiring-weighted
 * closure by swapping the semiring. This module is the algebra only; the
 * annotated-relation model and the operator threading live in relix-processor.
 *
 * <p>The module deliberately has no relix dependencies, so it can sit at the
 * bottom of the module graph and be required by the processor without
 * introducing a cycle, while the plain evaluation path stays free of any
 * provenance code.
 */
module com.darkcollective.relix.provenance {
    exports com.darkcollective.relix.provenance;

    // Declared here so ServiceLoader binds against this module: a semiring is resolved
    // by the name a --provenance request carries, and a consumer that ran its own scan
    // could disagree with this one about what that name means.
    uses com.darkcollective.relix.provenance.SemiringLibrary;
}
