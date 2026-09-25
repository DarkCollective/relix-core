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
package com.darkcollective.relix.symbol.internal;

import com.darkcollective.relix.symbol.Schema;
import java.util.Objects;

/**
 * Which of a join's two inputs a dotted reference reaches into.
 *
 * <p>A dotted name is ambiguous by construction: {@code location.city} is either a
 * qualified reference to a relation's column or a path into a nested one, and only the
 * headings can say which. Resolving it by its <em>tail</em> binds it to whichever side
 * happens to carry a column called {@code city}, which is how a predicate over the left
 * input came to be evaluated against the right — as a selection pushed into the wrong
 * relation, and at run time as a comparison of the right's value with itself, so the
 * join matched every pair and quietly became a cross product.
 *
 * <p><b>An open schema owns nothing here.</b> Schema-on-read resolves every name, so its
 * answer to "do you carry this path" is vacuously yes and carries no evidence. That is
 * the part worth having in one place: it has been gotten wrong independently in the
 * optimizer and in the executor, and in both the mistake was the same one — guarding the
 * <em>whole</em> comparison on both sides being closed, rather than declining the open
 * side's answer. Those are not the same thing, because what follows a declined comparison
 * is a fall back to the bare tail, which an open schema also answers.
 *
 * <p>Note what this deliberately does not change: asking <em>one</em> open schema to
 * resolve a path is right, and inference depends on it. An open source really can carry
 * {@code location.city}, and it types as {@code ANY}. What is worthless is that same
 * answer used as evidence <em>against</em> another side.
 *
 * @since 0.1
 */
public final class NestedPaths {

    private NestedPaths() {
    }

    /** Which side of a join a nested path belongs to. */
    public enum Owner {
        /** Only the left input resolves the path. */
        LEFT,
        /** Only the right input resolves the path. */
        RIGHT,
        /** Both resolve it, so the reference does not say which was meant. */
        BOTH,
        /** Neither resolves it as a path; it is not one, or both sides are open. */
        NEITHER
    }

    /**
     * {@return which input resolves {@code reference} as a path into a nested column}
     *
     * @param reference the dotted reference, as written
     * @param left      the left input's heading; never {@code null}
     * @param right     the right input's heading; never {@code null}
     */
    public static Owner ownerOf(String reference, Schema left, Schema right) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");

        boolean onLeft = resolves(left, reference);
        boolean onRight = resolves(right, reference);
        if (onLeft && onRight) {
            return Owner.BOTH;
        }
        if (onLeft) {
            return Owner.LEFT;
        }
        return onRight ? Owner.RIGHT : Owner.NEITHER;
    }

    /** A closed heading that resolves the path. An open one is not asked; see the class note. */
    private static boolean resolves(Schema schema, String reference) {
        return !schema.isOpen() && schema.resolvePath(reference).isPresent();
    }
}
