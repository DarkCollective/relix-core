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
 * Represents an anti-join operation (▷).
 * The anti-join keeps tuples from the left relation that do NOT match the join condition with any
 * tuple in the right relation.
 * The result includes only columns from the left relation.
 *
 * @param left      the left input relation; must not be null
 * @param right     the right input relation; must not be null
 * @param condition the join predicate; must not be null
 * @param location  the source location of this node; never null
 */
public record AntiJoinNode(RelNode left, RelNode right, Predicate condition, SourceLocation location) implements ConditionalJoinNode {
    public AntiJoinNode {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(location, "location");
    }

    /** Convenience constructor for tests; uses {@link SourceLocation#UNKNOWN}. */
    public AntiJoinNode(RelNode left, RelNode right, Predicate condition) {
        this(left, right, condition, SourceLocation.UNKNOWN);
    }

    @Override
    public AntiJoinNode rebuild(RelNode left, RelNode right, Predicate condition) {
        return new AntiJoinNode(left, right, condition, location());
    }

    @Override
    public <R> R accept(RelNodeVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
