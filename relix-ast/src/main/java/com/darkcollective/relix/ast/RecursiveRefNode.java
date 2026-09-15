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

/**
 * An occurrence of a {@code FIX}-bound recursive relation name inside the body of
 * its enclosing {@link FixpointNode}.
 *
 * <p>This is a <strong>dedicated leaf</strong> — not an overloaded
 * {@link RelationNode} — for the recursive reference. The parser resolves a bound
 * name to a {@code RecursiveRefNode} at parse time (it tracks a stack of in-scope
 * binder names), so the recursive name never enters the symbol table and symbol
 * resolution stays unambiguous: a {@code RecursiveRefNode} resolves to "the relation
 * accumulated so far by the enclosing {@link FixpointNode}", whose schema is the
 * fixpoint's {@code base} schema (supplied by inference in the semantics slice). It
 * carries no children; like a base relation it materialises a stream.
 *
 * <p>By construction a {@code RecursiveRefNode} only ever appears within a
 * {@code FixpointNode}'s {@code step}; one outside any {@code FIX} scope is
 * structurally impossible to produce from source.
 *
 * @param name     the referenced bound recursive relation name; must not be blank
 * @param location the source location of this node; never null
 */
public record RecursiveRefNode(String name, SourceLocation location) implements RelNode {

    public RecursiveRefNode {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Recursive reference name must not be blank");
        }
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests: {@link SourceLocation#UNKNOWN}. */
    public RecursiveRefNode(String name) {
        this(name, SourceLocation.UNKNOWN);
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
