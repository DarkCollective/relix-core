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
package com.darkcollective.relix.lang.ast;

import com.darkcollective.relix.ast.SourceLocation;

import java.util.Objects;
import java.util.Optional;

/**
 * A standalone schema relationship declaration:
 * <pre>
 *   relate "Order Line Items" Orders.order_id -&gt; OrderItems.order_id [1..50];
 *   relate "Manages" / "Reports To" Employees.id -&gt; Employees.manager_id;
 *   relate symmetric "Cross Sells" Products.id -&gt; Products.related_id;
 * </pre>
 *
 * <p>A relate statement declares a <em>named</em> edge of the schema graph
 * between two {@link EndpointSpec endpoints}. The name is required; an optional
 * inverse name (after {@code /}) makes the reverse traversal speakable and marks
 * the two directions as distinct roles. The {@code symmetric} modifier is
 * meaningful only for self-referential edges and is mutually exclusive with an
 * inverse name — both constraints are validated semantically, not here.
 *
 * <p>Relationships are structural metadata, not symbols: they declare nothing
 * nameable and therefore carry no {@code exported} flag.
 *
 * @param name        the relationship name; must not be blank
 * @param inverseName the optional name of the reverse traversal
 * @param symmetric   whether this self-referential relationship is symmetric
 * @param source      the source endpoint
 * @param target      the target endpoint
 * @param location    the source location of the {@code relate} keyword
 */
public record RelateStatement(
        String name,
        Optional<String> inverseName,
        boolean symmetric,
        EndpointSpec source,
        EndpointSpec target,
        SourceLocation location
) implements Statement {

    public RelateStatement {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("relationship name must not be blank");
        }
        Objects.requireNonNull(inverseName, "inverseName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(location, "location");
    }
}
