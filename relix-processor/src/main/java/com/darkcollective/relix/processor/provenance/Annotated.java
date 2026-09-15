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
package com.darkcollective.relix.processor.provenance;

import com.darkcollective.relix.processor.Row;

import java.util.Objects;

/**
 * A tuple paired with its provenance annotation — the side-channel carrier of the
 * K-relation model.
 *
 * <p>The annotation lives <em>alongside</em> the {@link Row}, never as an ordinary
 * data column, so the relational algebra stays closed: operators see a {@code Row}
 * exactly as before, and the {@code K} rides next to it only while a provenance
 * mode is active. An annotation of {@link com.darkcollective.relix.provenance.Semiring#zero()
 * zero} denotes an absent tuple; the canonical form of an
 * {@link AnnotatedRelation} therefore never contains a zero-annotated row.
 *
 * @param <K>        the semiring annotation type
 * @param row        the annotated tuple; never {@code null}
 * @param annotation the tuple's semiring annotation; never {@code null}
 */
public record Annotated<K>(Row row, K annotation) {

    /** Validates that neither component is {@code null}. */
    public Annotated {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(annotation, "annotation");
    }
}
