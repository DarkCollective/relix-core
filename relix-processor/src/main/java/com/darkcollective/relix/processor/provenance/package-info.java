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
 * The opt-in annotated-relation (K-relation) model that carries provenance
 * annotations through execution.
 *
 * <p>This package bridges the engine's {@link com.darkcollective.relix.processor.Row}
 * runtime tuples to the {@link com.darkcollective.relix.provenance.Semiring} algebra:
 * an {@link com.darkcollective.relix.processor.provenance.Annotated} pairs a row with
 * a semiring element, and an
 * {@link com.darkcollective.relix.processor.provenance.AnnotatedRelation} is the
 * canonical K-relation — distinct tuples mapped to the {@code ⊕}-combination of their
 * derivations, zero-annotated tuples absent.
 *
 * <p>The model is <strong>opt-in</strong>: it is constructed only when a provenance
 * mode is active, so the default {@code Stream<Row>} evaluation path is untouched and
 * pays nothing. The annotation is always a side-channel, never an ordinary data
 * column, keeping the relational algebra closed. This package is the carrier model
 * and its {@code lift}/{@code normalise}/{@code forget} operations;
 * {@link com.darkcollective.relix.processor.provenance.ProvenanceEvaluator} threads
 * the annotations through the positive operators (σ/π/×/⋈/∪).
 *
 * @see <a href="https://doi.org/10.1145/1265530.1265535">T. J. Green, G.
 *      Karvounarakis &amp; V. Tannen, <em>Provenance semirings</em>, PODS 2007</a>
 */
package com.darkcollective.relix.processor.provenance;
