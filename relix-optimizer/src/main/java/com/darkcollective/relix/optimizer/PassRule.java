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
package com.darkcollective.relix.optimizer;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;

import java.util.List;
import java.util.Objects;

/**
 * The registry entry for one optimizer pass — an {@link OptimizationRule} whose
 * {@link #apply} delegates to that pass.
 *
 * <p>Every pass is a package-private final class with a {@code static apply}, and
 * their signatures are deliberately not uniform — {@code SortEliminationPass}, for
 * one, takes no {@link SchemaAnnotations}.  Adapting them here, rather than reshaping
 * twenty classes to a common signature, keeps each pass's own entry point honest about
 * what it actually needs; a rule wanting more than the four standard arguments can
 * capture it in the closure {@link OptimizationPipeline} registers.
 *
 * @param name  short stable identifier, e.g. {@code "selection-pushdown"}
 * @param codes every code the pass can emit, primary first
 * @param pass  the pass entry point to delegate to
 */
record PassRule(String name, List<OptimizationCode> codes, Pass pass)
        implements OptimizationRule {

    /**
     * The call shape a pass is adapted to — identical to
     * {@link OptimizationRule#apply}, so a pass with that exact signature registers
     * as a method reference and any other as a lambda.
     */
    @FunctionalInterface
    interface Pass {

        /**
         * Applies the pass to the whole tree rooted at {@code node}.
         *
         * @param node      root of the tree to transform; never null
         * @param queryName display name used in transformation records; never blank
         * @param schemas   schema annotations from semantic analysis; never null
         * @param ctx       transformation record accumulator; never null
         * @return the transformed tree, or {@code node} itself when nothing fired
         */
        RelNode apply(RelNode node, String queryName, SchemaAnnotations schemas,
                      OptimizationContext ctx);
    }

    /** Validates and defensively copies the code list. */
    PassRule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(codes, "codes");
        Objects.requireNonNull(pass, "pass");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("a rule must declare at least one code");
        }
        codes = List.copyOf(codes);
    }

    /**
     * Creates a registry entry.
     *
     * @param name  short stable identifier; must not be blank
     * @param pass  the pass entry point; must not be null
     * @param codes every code the pass can emit, primary first; must not be empty
     * @return the rule; never null
     */
    static PassRule of(String name, Pass pass, OptimizationCode... codes) {
        return new PassRule(name, List.of(codes), pass);
    }

    /** {@inheritDoc} */
    @Override
    public OptimizationCode code() {
        return codes.get(0);
    }

    /** {@inheritDoc} */
    @Override
    public RelNode apply(RelNode node, String queryName, SchemaAnnotations schemas,
                         OptimizationContext ctx) {
        return pass.apply(node, queryName, schemas, ctx);
    }
}
