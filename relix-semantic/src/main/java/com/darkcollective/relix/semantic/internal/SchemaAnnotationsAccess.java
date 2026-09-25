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
package com.darkcollective.relix.semantic.internal;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.Schema;

import java.util.Objects;

/**
 * How the analyser records a node's schema in a {@link SchemaAnnotations}.
 *
 * <p>{@code SchemaAnnotations} is published and a caller only reads it, so its writer is
 * not public there. The analyser lives in this package, so the published class hands its
 * writer over when it loads and the analyser writes through here. The jar does not
 * export this package, so the writer stays the engine's.
 */
public final class SchemaAnnotationsAccess {

    /** Records {@code schema} as {@code node}'s schema in {@code annotations}. */
    @FunctionalInterface
    public interface Writer {
        /**
         * @param annotations the annotations to write into
         * @param node        the node
         * @param schema      its schema
         */
        void put(SchemaAnnotations annotations, RelNode node, Schema schema);
    }

    private static Writer writer;

    private SchemaAnnotationsAccess() {
    }

    /**
     * Called once, by {@code SchemaAnnotations} as it loads.
     *
     * @param w the writer
     */
    public static void install(Writer w) {
        if (writer != null) {
            throw new IllegalStateException("the SchemaAnnotations writer is already installed");
        }
        writer = Objects.requireNonNull(w, "w");
    }

    /**
     * Records {@code schema} as {@code node}'s schema.
     *
     * @param annotations the annotations to write into; loading its class installed the writer
     * @param node        the node
     * @param schema      its schema
     */
    public static void put(SchemaAnnotations annotations, RelNode node, Schema schema) {
        writer.put(annotations, node, schema);
    }
}
