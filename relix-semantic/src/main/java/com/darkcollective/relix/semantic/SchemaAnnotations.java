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
package com.darkcollective.relix.semantic;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.semantic.internal.SchemaAnnotationsAccess;
import com.darkcollective.relix.symbol.Schema;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A mapping from individual {@link RelNode} instances to their inferred
 * {@link Schema}s.
 *
 * <p>Keyed by <em>object identity</em> (using an {@link IdentityHashMap}), not
 * structural equality.  Two relational algebra nodes that are structurally equal
 * but reside at different positions in the tree are tracked independently.
 *
 * <p>The annotation map is populated during schema inference and is then
 * included in the {@link SemanticModel}.  Downstream consumers — execution
 * engines, optimizers, IDEs — can query the schema at any node in any RA
 * expression tree without re-running inference.
 *
 * <h2>Usage</h2>
 * <pre>
 *   SchemaAnnotations annotations = model.nodeSchemas();
 *   Schema schema = annotations.get(someNode)
 *       .orElseThrow(() -> new IllegalStateException("schema not inferred for node"));
 * </pre>
 */
public final class SchemaAnnotations {

    static {
        // The analyser, in semantic.internal, writes annotations; a caller only reads them.
        SchemaAnnotationsAccess.install(SchemaAnnotations::put);
    }

    private final IdentityHashMap<RelNode, Schema> map;

    /** Creates an empty annotation map. */
    public SchemaAnnotations() {
        this.map = new IdentityHashMap<>();
    }

    /**
     * Creates an annotation map pre-populated with the supplied entries.
     *
     * <p>Entries are keyed by <em>object identity</em>.  The caller must
     * supply the exact {@link RelNode} instances that will later be looked
     * up via {@link #get(RelNode)}.  Useful for constructing test fixtures
     * without running the full schema-inference pipeline.
     *
     * @param initialEntries node → schema pairs; must not be null
     */
    public SchemaAnnotations(Map<RelNode, Schema> initialEntries) {
        Objects.requireNonNull(initialEntries, "initialEntries");
        this.map = new IdentityHashMap<>(initialEntries);
    }

    /**
     * Returns a new empty {@code SchemaAnnotations}.
     *
     * @return an empty annotation map
     */
    public static SchemaAnnotations empty() {
        return new SchemaAnnotations();
    }

    /**
     * Records the inferred schema for a node.  Called by the schema inference
     * engine; not part of the consumer-facing read API.
     *
     * @param node   the RA node; must not be null
     * @param schema the inferred schema; must not be null
     */
    void put(RelNode node, Schema schema) {
        map.put(node, schema);
    }

    /**
     * Returns the inferred schema for {@code node}, or {@link Optional#empty()}
     * if this node was not annotated (e.g. analysis of its subtree failed).
     *
     * @param node the RA node to look up
     * @return the schema, or empty
     */
    public Optional<Schema> get(RelNode node) {
        return Optional.ofNullable(map.get(node));
    }

    /**
     * Returns the inferred schema for {@code node}, or throws if the node was
     * never annotated.  Use this from consumers (planner, executor, pushdown)
     * that require every node in a fully-inferred tree to carry a schema; the
     * {@code context} is appended to the error to identify the calling stage.
     *
     * @param node    the RA node to look up; must not be null
     * @param context a short phrase describing the calling stage, e.g.
     *                {@code "during SQL pushdown"}
     * @return the inferred schema
     * @throws IllegalStateException if {@code node} has no annotation
     */
    public Schema require(RelNode node, String context) {
        return get(node).orElseThrow(() -> new IllegalStateException(
                "No schema annotation for " + node.getClass().getSimpleName()
                + " " + context));
    }

    /**
     * Returns {@code true} if a schema has been recorded for {@code node}.
     *
     * @param node the RA node to test
     * @return whether an annotation exists
     */
    public boolean hasSchema(RelNode node) {
        return map.containsKey(node);
    }

    /**
     * Returns the number of annotated nodes.
     *
     * @return annotation count
     */
    public int size() {
        return map.size();
    }

    /**
     * Returns an unmodifiable view of the underlying identity map as a
     * standard {@link Map}.  Useful for iteration in tests or tooling.
     *
     * @return unmodifiable identity map
     */
    public Map<RelNode, Schema> asMap() {
        return Collections.unmodifiableMap(new IdentityHashMap<>(map));
    }
}
