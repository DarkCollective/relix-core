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
package com.darkcollective.relix.processor;

import com.darkcollective.relix.processor.provenance.AnnotatedRelation;

/**
 * Callback that receives one query's result as an annotated
 * {@link AnnotatedRelation K-relation}.
 *
 * <p>Used by {@link QueryExecutor#executeProvenance} to hand each {@code query}
 * statement's annotated output to the caller. Unlike the streaming
 * {@link RowStreamConsumer} path, a K-relation is canonical and fully materialised,
 * so the relation may be retained and rendered after {@code accept} returns. The
 * relation carries its own {@link AnnotatedRelation#schema() schema} and
 * {@link AnnotatedRelation#semiring() semiring}.
 *
 * @param <K> the semiring annotation type
 */
@FunctionalInterface
public interface ProvenanceConsumer<K> {

    /**
     * Consumes one annotated query result.
     *
     * @param label    the query's display label (relation name or {@code "<expression N>"})
     * @param relation the query's result as a canonical K-relation
     */
    void accept(String label, AnnotatedRelation<K> relation);
}
