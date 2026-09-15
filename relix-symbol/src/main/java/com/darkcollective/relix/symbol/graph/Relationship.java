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
package com.darkcollective.relix.symbol.graph;

import java.util.Objects;
import java.util.Optional;

/**
 * A named relationship edge of the {@link SchemaGraph}.
 *
 * <p>Edges are named because names are what make ambiguity speakable: with two
 * foreign keys from {@code issues} into {@code users}, "assignee or reporter?"
 * is a question a user can answer where "path A or path B?" is not. An
 * asymmetric edge may also name its reverse traversal ({@code inverseName} —
 * "Reports To" for a "Manages" edge); presence of an inverse name is how the
 * representation records that the two directions are distinct roles.
 *
 * <p>{@code symmetric} applies to self-referential edges only (product
 * cross-sells, friend graphs) and records that the relationship has no
 * direction at the data level — a fact the engine cannot derive and would
 * otherwise guess wrong when traversing. It is mutually exclusive with an
 * inverse name (a symmetric edge has no distinct roles to name).
 *
 * <p>Neither name nor symmetry affects <em>walkability</em>: every edge is
 * traversable in both directions for path-search purposes, because a join is
 * symmetric regardless of which side owns the foreign key.
 *
 * @param name        the relationship name; never blank
 * @param inverseName the optional name of the reverse traversal
 * @param symmetric   whether this self-referential relationship is symmetric
 * @param source      the source endpoint
 * @param target      the target endpoint; its column list has the same length
 *                    as the source's (validated at assembly with a positioned
 *                    diagnostic, not here)
 * @param origin      how this edge entered the graph
 */
public record Relationship(
        String name,
        Optional<String> inverseName,
        boolean symmetric,
        Endpoint source,
        Endpoint target,
        EdgeOrigin origin
) {

    public Relationship {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("relationship name must not be blank");
        }
        Objects.requireNonNull(inverseName, "inverseName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(origin, "origin");
        if (symmetric && inverseName.isPresent()) {
            throw new IllegalArgumentException(
                    "a symmetric relationship cannot carry an inverse name");
        }
    }

    /** Returns {@code true} if this edge connects a relation to itself. */
    public boolean selfReferential() {
        return SchemaGraph.key(source.relation()).equals(SchemaGraph.key(target.relation()));
    }
}
