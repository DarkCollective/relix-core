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
package com.darkcollective.relix.ast;

import com.darkcollective.relix.ast.visitor.RelNodeVisitor;

import java.util.Objects;
import java.util.Optional;

/**
 * A base relation (table) reference — a leaf node in the relational algebra tree.
 *
 * <p>Example: {@code Users} refers to the relation named "Users".
 *
 * <p>{@link #produceBound()} is an optional production stop folded in by the
 * optimizer ({@code GEN-001}) when this leaf is a monotone generator
 * ({@code Naturals}/{@code Primes}) under an upper-bound selection; it is always
 * {@link Optional#empty()} on a parsed tree and for every non-generator relation,
 * and never changes the output schema.
 *
 * @param name         the relation name; must not be blank
 * @param produceBound optional generator production stop; never null, possibly empty
 * @param location     the source location of this node; never null
 */
public record RelationNode(String name, Optional<ProduceBound> produceBound,
                           SourceLocation location) implements RelNode {
    public RelationNode {
        requireNonBlank(name, "name");
        Objects.requireNonNull(produceBound, "produceBound");
        Objects.requireNonNull(location, "location");
    }

    /** Constructor without a production bound. */
    public RelationNode(String name, SourceLocation location) {
        this(name, Optional.empty(), location);
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public RelationNode(String name) {
        this(name, Optional.empty(), SourceLocation.UNKNOWN);
    }

    /**
     * Returns a copy of this leaf carrying the given generator production bound.
     *
     * @param bound the production stop to fold in; must not be null
     * @return a bounded copy, preserving name and location
     */
    public RelationNode withProduceBound(ProduceBound bound) {
        return new RelationNode(name, Optional.of(Objects.requireNonNull(bound, "bound")), location);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
