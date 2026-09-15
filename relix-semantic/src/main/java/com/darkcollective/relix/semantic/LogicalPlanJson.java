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
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.json.JsonWriter;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.Objects;
import java.util.Optional;

/**
 * Serializes a logical relational-algebra tree to JSON, annotating every node
 * with its inferred {@link Schema}.
 *
 * <p>This is the machine-readable counterpart to {@link IrReport}'s ASCII
 * expression trees: it walks the same {@link RelNode} structure (reusing
 * {@link IrReport#baseLabel} and {@link IrReport#nodeChildren}) but emits a
 * structured tree suitable for tooling — the playground's AST panel, and any
 * consumer wanting schema-bearing output.
 *
 * <h2>Node shape</h2>
 * Each node is a JSON object:
 * <pre>{@code
 * {
 *   "op": "Selection",            // RelNode type, "Node" suffix stripped
 *   "label": "σ amount > 100",    // human-readable operator form (no [mat] tag)
 *   "schema": { "open": false,    // inferred output schema, or null if unknown
 *               "columns": [ { "name": "amount", "type": "N" }, … ] },
 *   "materialization": "stream",  // stream | bag | set | sort
 *   "children": [ … ]             // child node objects, left-to-right
 * }
 * }</pre>
 *
 * <p>Leaf {@link RelationNode}s additionally carry {@code "relation"} (the
 * relation name) and {@code "kind"} (its symbol kind code, e.g. {@code "SRC"},
 * or {@code "?"} when unresolved).
 *
 * <h2>Schema encoding</h2>
 * <ul>
 *   <li>{@code null} — no schema was inferred for the node (its subtree failed
 *       to resolve), or the inference placeholder is present.</li>
 *   <li>{@code { "open": true, "columns": [] }} — an open (schema-on-read)
 *       relation whose columns resolve dynamically at runtime.</li>
 *   <li>{@code { "open": false, "columns": [ … ] }} — a closed, fully-typed
 *       schema.</li>
 * </ul>
 * Column types use the same one-letter codes as {@link IrReport}:
 * {@code N}umber, {@code S}tring, {@code B}oolean, {@code ?}=any.
 */
public final class LogicalPlanJson {

    private LogicalPlanJson() {}

    /**
     * Serializes the tree rooted at {@code root} to a standalone JSON string.
     *
     * @param root    the root RA node; must not be null
     * @param table   the symbol table, for leaf relation kinds; must not be null
     * @param schemas the inferred schema annotations; must not be null
     * @return the JSON document for the tree
     */
    public static String toJson(RelNode root, SymbolTable table, SchemaAnnotations schemas) {
        JsonWriter writer = new JsonWriter();
        write(writer, root, table, schemas);
        return writer.toJson();
    }

    /**
     * Writes the tree rooted at {@code root} as a single JSON object value into
     * an existing {@link JsonWriter}, so a larger bundle can embed it.
     *
     * @param writer  the writer to emit into; must not be null
     * @param root    the root RA node; must not be null
     * @param table   the symbol table, for leaf relation kinds; must not be null
     * @param schemas the inferred schema annotations; must not be null
     */
    public static void write(JsonWriter writer, RelNode root,
                             SymbolTable table, SchemaAnnotations schemas) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(schemas, "schemas");
        writeNode(writer, root, table, schemas);
    }

    private static void writeNode(JsonWriter w, RelNode node,
                                  SymbolTable table, SchemaAnnotations schemas) {
        w.beginObject();
        w.name("op").value(opName(node));
        w.name("label").value(IrReport.baseLabel(node, table));

        if (node instanceof RelationNode r) {
            w.name("relation").value(r.name());
            w.name("kind").value(
                    table.lookupRelation(r.name()).map(IrReport::kindCode).orElse("?"));
        }

        w.name("schema");
        writeSchema(w, schemas.get(node));

        w.name("materialization").value(materialization(node));

        w.name("children").beginArray();
        for (RelNode child : IrReport.nodeChildren(node)) {
            writeNode(w, child, table, schemas);
        }
        w.endArray();

        w.endObject();
    }

    private static void writeSchema(JsonWriter w, Optional<Schema> maybe) {
        if (maybe.isEmpty() || isUnresolved(maybe.get())) {
            w.nullValue();
            return;
        }
        SchemaJson.write(w, maybe.get());
    }

    /**
     * Detects the schema-inference placeholder (a single {@code *:ANY} column)
     * that marks a node whose schema could not be resolved.
     */
    private static boolean isUnresolved(Schema schema) {
        var cols = schema.columns();
        return cols.size() == 1
                && cols.get(0).name().equals("*")
                && cols.get(0).type() == ScalarType.ANY;
    }

    /**
     * RelNode type name with the {@code "Node"} suffix stripped (e.g. "Selection").
     * Every type in the sealed {@link RelNode} hierarchy is named {@code <Op>Node},
     * so the suffix is always present.
     *
     * <p>Package-visible so the {@code relix.plan} catalog relation
     * ({@link CatalogBuilder}) can reuse the exact same {@code op} naming as this
     * JSON serializer, keeping the two from drifting.
     */
    static String opName(RelNode node) {
        String simple = node.getClass().getSimpleName();
        return simple.substring(0, simple.length() - "Node".length());
    }

    private static String materialization(RelNode node) {
        return switch (node.materializationMode()) {
            case STREAM -> "stream";
            case BAG    -> "bag";
            case SET    -> "set";
            case SORTED -> "sort";
        };
    }
}
