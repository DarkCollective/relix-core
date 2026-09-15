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
 * The pluggable provenance-semiring algebra (K-relations).
 *
 * <p>A <em>K-relation</em> annotates every tuple with an element of a commutative
 * semiring {@code (K, ⊕, ⊗, 0, 1)}; each positive-algebra operator is then defined
 * by the semiring operations — union/projection combine alternative derivations
 * with {@code ⊕}, join/product combine joint requirements with {@code ⊗},
 * selection multiplies by {@code 1} (keep) or {@code 0} (drop), and {@code 0}
 * means "absent". Choosing the semiring chooses the feature: set semantics, bag
 * multiplicity, why-provenance lineage, security/trust levels, and
 * shortest-path / weighted closure all fall out of one mechanism.
 *
 * <p>This package provides the {@link com.darkcollective.relix.provenance.Semiring}
 * extension point and the cheap fixed-size built-ins
 * ({@link com.darkcollective.relix.provenance.BooleanSemiring},
 * {@link com.darkcollective.relix.provenance.CountingSemiring},
 * {@link com.darkcollective.relix.provenance.TropicalSemiring},
 * {@link com.darkcollective.relix.provenance.SecurityLattice}). It is the algebra
 * only — threading the annotations through the relix operators is
 * relix-processor's job.
 *
 * @see <a href="https://doi.org/10.1145/1265530.1265535">T. J. Green, G.
 *      Karvounarakis &amp; V. Tannen, <em>Provenance semirings</em>, PODS 2007</a>
 */
package com.darkcollective.relix.provenance;
