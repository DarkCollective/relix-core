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

import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.function.FunctionCatalog;
import com.darkcollective.relix.symbol.table.SymbolTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Public facade for running schema inference over an arbitrary
 * {@link RelNode} tree outside the full five-phase analysis pipeline.
 *
 * <p>This is primarily intended for consumers — such as the query optimizer and
 * execution engine — that hold a tree which is <em>not</em> the one originally
 * analysed (for example, a tree rewritten by optimization passes) and therefore
 * has no entries in the original {@link SemanticModel#nodeSchemas()} map.  Because
 * {@link SchemaAnnotations} is keyed by object identity, a rewritten tree's nodes
 * are unknown to the original annotation map and must be re-annotated before they
 * can be executed.
 *
 * <h2>Merging semantics</h2>
 * <p>{@link #annotate} returns a fresh
 * {@code SchemaAnnotations} seeded from the supplied base map and then augmented
 * with annotations for {@code root} and all of its descendants.  Existing entries
 * are preserved: this matters when {@code root} contains
 * {@link com.darkcollective.relix.ast.RelationNode} leaves that reference named views ({@code
 * QueryRelationSymbol}s) whose own
 * bodies were annotated during the original analysis but are not re-walked here.
 *
 * <h2>Thread safety</h2>
 * <p>Stateless; safe to call concurrently.  Each call builds its own annotation
 * map and visitor.
 */
public final class SchemaInference {

    private SchemaInference() {
    }

    /**
     * Returns a {@link SchemaAnnotations} containing every entry from {@code base}
     * plus a freshly-inferred schema for {@code root} and each of its descendant
     * nodes.
     *
     * <p>The base map is not mutated.  Inference errors are not surfaced: callers
     * are expected to supply a tree derived from an already-valid model (e.g. an
     * optimizer rewrite, which is semantics-preserving), so any node that cannot
     * be resolved simply remains unannotated.
     *
     * @param symbolTable the symbol table used to resolve relation and function
     *                    references; must not be {@code null}
     * @param root        the root of the tree to annotate; must not be {@code null}
     * @param base        annotations to seed the result with (typically the
     *                    original {@link SemanticModel#nodeSchemas()}); must not be
     *                    {@code null}
     * @param functions   the catalogue function calls in the tree are typed against —
     *                    pass the one the tree was analysed with
     *                    ({@link SemanticModel#functions()}), so a re-annotated tree
     *                    types its calls exactly as the original did; must not be
     *                    {@code null}
     * @return a new annotation map covering {@code base} and {@code root}'s subtree
     */
    public static SchemaAnnotations annotate(SymbolTable symbolTable,
                                             RelNode root,
                                             SchemaAnnotations base,
                                             FunctionCatalog functions) {
        Objects.requireNonNull(symbolTable, "symbolTable");
        Objects.requireNonNull(root,        "root");
        Objects.requireNonNull(base,        "base");
        Objects.requireNonNull(functions,   "functions");

        SchemaAnnotations result = new SchemaAnnotations(base.asMap());
        List<SemanticError> ignoredErrors = new ArrayList<>();
        var visitor = new SchemaInferenceVisitor(
                symbolTable, result, ignoredErrors,
                SemanticAnalyzer.STDIN_PATH, functions);
        root.accept(visitor);
        return result;
    }
}
