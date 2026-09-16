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

import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.Qualifiers;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.Schema;

import java.util.Optional;
import java.util.Set;

/**
 * Optimization pass that converts Cartesian products with selections into
 * theta joins, and pushes one-sided selections into join inputs
 * ({@code JOIN-001} and {@code JOIN-002}).
 *
 * <ul>
 *   <li><b>JOIN-001</b> — Converts {@code σ(p)(A × B)} into a theta join
 *       {@code A ⨝_p B} when the predicate {@code p} references columns
 *       from <em>both</em> inputs.  The selection node is absorbed into the
 *       join condition, allowing the executor to filter during the join rather
 *       than materialising the full cross product first.</li>
 *   <li><b>JOIN-002</b> — Pushes a selection into the appropriate input of a
 *       join when its predicate references only one side.  Applies to
 *       {@link ProductNode} (the selection is pushed into that product input,
 *       and the product is preserved) and {@link ThetaJoinNode} (the
 *       selection is pushed into the matching join input).  This rule fires
 *       after JOIN-001 has been attempted, so it handles the one-sided
 *       residuals that cannot be converted to join conditions.</li>
 * </ul>
 *
 * <h2>Traversal strategy</h2>
 * <p>The traversal is <em>top-down</em> for selection nodes: the rules are
 * attempted before recursing into the input.  If a rule fires, the pass
 * recurses into the rewritten tree.  If no rule fires at the top, the pass
 * recurses into the input; if that changes the input, the rules are retried
 * on the updated selection.  This two-step retry allows the common pattern
 * {@code σ(join_cond)(σ(filter)(A × B))} to be fully optimised in a single
 * pass: JOIN-002 pushes the inner filter into the left product input, and
 * JOIN-001 then converts the outer selection to a theta join.
 *
 * <p>Schema annotations on product and join inputs are used to determine
 * which side of a binary operator each predicate attribute belongs to.  To
 * handle the case where a prior transformation wrapped an input in a
 * {@link SelectionNode} (eliminating its direct annotation), the helper
 * {@link #schemaOf(RelNode, SchemaAnnotations)} looks through intervening
 * selection nodes to find the nearest annotated ancestor; this is safe
 * because selections never change the column schema of their input.
 *
 * <p>This pass should run <em>after</em> {@link SelectionPushdownPass} (which
 * handles selections over existing joins) and <em>before</em> the cleanup
 * passes.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a
 * static method.
 */
final class JoinRulesPass {

    private JoinRulesPass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Applies join rules (JOIN-001 and JOIN-002) to the entire tree rooted at
     * {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations used to classify predicate attributes
     *                  as belonging to the left or right side of a binary
     *                  operator; rules are skipped when required annotations are
     *                  absent
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewriteNode(node, queryName, schemas, ctx);
    }

    // =========================================================================
    // RelNode traversal (top-down for σ, bottom-up for everything else)
    // =========================================================================

    private static RelNode rewriteNode(RelNode node, String queryName,
                                        SchemaAnnotations schemas,
                                        OptimizationContext ctx) {
        return switch (node) {

            // ── σ: try join rules first (top-down), then recurse into input ──
            case SelectionNode s -> {
                // 1. Try rules on the current selection before recursing
                RelNode pushed = tryJoinRules(s, queryName, schemas, ctx);
                if (pushed != null) {
                    // Rule fired — recurse into the rewritten structure
                    yield rewriteNode(pushed, queryName, schemas, ctx);
                }
                // 2. No rule fired — recurse into the input first
                RelNode newInput = rewriteNode(s.input(), queryName, schemas, ctx);
                if (newInput == s.input()) {
                    yield s; // nothing changed
                }
                // 3. Input changed — retry rules on the updated selection
                SelectionNode updated =
                        new SelectionNode(s.predicate(), newInput, s.location());
                RelNode pushed2 = tryJoinRules(updated, queryName, schemas, ctx);
                if (pushed2 != null) {
                    yield rewriteNode(pushed2, queryName, schemas, ctx);
                }
                yield updated;
            }

            // ── Bottom-up traversal for all other nodes ──────────────────────
            default -> node.mapChildren(child -> rewriteNode(child, queryName, schemas, ctx));
        };
    }

    // =========================================================================
    // Join rule dispatcher
    // =========================================================================

    /**
     * Attempts to apply JOIN-001 or JOIN-002 to {@code s}.
     * Returns the rewritten node (not yet recursed) if a rule fires, or
     * {@code null} if no rule applies.
     */
    private static RelNode tryJoinRules(SelectionNode s, String queryName,
                                         SchemaAnnotations schemas,
                                         OptimizationContext ctx) {
        Predicate      pred  = s.predicate();
        Set<String>    attrs = PredicateAttributeCollector.collectNames(pred);

        return switch (s.input()) {

            // ── σ(p)(A × B) → either JOIN-001 (theta join) or JOIN-002 (push) ─
            case ProductNode prod -> {
                Optional<Schema> ls = schemaOf(prod.left(),  schemas);
                Optional<Schema> rs = schemaOf(prod.right(), schemas);
                if (ls.isEmpty() || rs.isEmpty()) yield null;

                JoinSide side = determineJoinSide(attrs, prod.left(), ls.get(), prod.right(), rs.get());
                yield switch (side) {
                    case LEFT -> {
                        // Predicate only references left input — push into it
                        ctx.record(OptimizationCode.JOIN_002, queryName,
                                "selection pushed into left input of cartesian product",
                                s.location());
                        yield new ProductNode(
                                new SelectionNode(pred, prod.left(),  s.location()),
                                prod.right(),
                                prod.location());
                    }
                    case RIGHT -> {
                        // Predicate only references right input — push into it
                        ctx.record(OptimizationCode.JOIN_002, queryName,
                                "selection pushed into right input of cartesian product",
                                s.location());
                        yield new ProductNode(
                                prod.left(),
                                new SelectionNode(pred, prod.right(), s.location()),
                                prod.location());
                    }
                    case NEITHER -> {
                        // Determine whether predicate spans both sides (JOIN-001)
                        // vs references unknown columns (leave alone)
                        boolean anyLeft  =
                                attrs.stream().anyMatch(a -> hasColumn(ls.get(), a));
                        boolean anyRight =
                                attrs.stream().anyMatch(a -> hasColumn(rs.get(), a));
                        if (anyLeft && anyRight) {
                            // Spans both sides — convert to theta join
                            ctx.record(OptimizationCode.JOIN_001, queryName,
                                    "cartesian product with selection converted to theta join",
                                    s.location());
                            yield new ThetaJoinNode(
                                    prod.left(), prod.right(), pred, prod.location());
                        }
                        // Unknown columns (not in either schema) — leave alone
                        yield null;
                    }
                };
            }

            // ── σ(p)(A ⨝_q B) — push one-sided selection into join input ────
            case ThetaJoinNode j -> {
                Optional<Schema> ls = schemaOf(j.left(),  schemas);
                Optional<Schema> rs = schemaOf(j.right(), schemas);
                if (ls.isEmpty() || rs.isEmpty()) yield null;

                JoinSide side = determineJoinSide(attrs, j.left(), ls.get(), j.right(), rs.get());
                yield switch (side) {
                    case LEFT -> {
                        ctx.record(OptimizationCode.JOIN_002, queryName,
                                "selection pushed into left input of theta join",
                                s.location());
                        yield new ThetaJoinNode(
                                new SelectionNode(pred, j.left(), s.location()),
                                j.right(), j.condition(), j.location());
                    }
                    case RIGHT -> {
                        ctx.record(OptimizationCode.JOIN_002, queryName,
                                "selection pushed into right input of theta join",
                                s.location());
                        yield new ThetaJoinNode(
                                j.left(),
                                new SelectionNode(pred, j.right(), s.location()),
                                j.condition(), j.location());
                    }
                    case NEITHER -> null;
                };
            }

            // ── No applicable join rule ───────────────────────────────────────
            default -> null;
        };
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Returns the schema for {@code node}, looking through any wrapping
     * {@link SelectionNode} layers when the node itself has no direct annotation.
     *
     * <p>Selection operators do not change the column schema of their input
     * (they only filter rows), so it is safe to use the input's schema as a
     * proxy for the selection's schema when annotations are absent.
     */
    private static Optional<Schema> schemaOf(RelNode node, SchemaAnnotations schemas) {
        Optional<Schema> direct = schemas.get(node);
        if (direct.isPresent()) return direct;
        if (node instanceof SelectionNode s) return schemaOf(s.input(), schemas);
        return Optional.empty();
    }

    /**
     * Determines which side of a binary operator the predicate with
     * {@code predAttrs} can be pushed into: {@link JoinSide#LEFT} when every
     * referenced attribute belongs to the left input, {@link JoinSide#RIGHT} when
     * every one belongs to the right, {@link JoinSide#NEITHER} otherwise. An empty
     * attribute set is vacuously all-left.
     *
     * <p>Ownership is {@link JoinSides#sideOf}'s answer, the one
     * {@link SelectionPushdownPass} uses. This pass used to decide by stripped column
     * name alone, which a schema-on-read input answers for every name — so a σ over a
     * view of an open join was pushed into the open input under a qualifier only the
     * view defines, and matched nothing (#971).
     */
    private static JoinSide determineJoinSide(Set<String> predAttrs,
                                               RelNode left, Schema leftSchema,
                                               RelNode right, Schema rightSchema) {
        Set<String> leftQualifiers  = Qualifiers.inScope(left);
        Set<String> rightQualifiers = Qualifiers.inScope(right);
        boolean allLeft  = true;
        boolean allRight = true;
        for (String attr : predAttrs) {
            JoinSides.Side side = JoinSides.sideOf(
                    attr, leftSchema, rightSchema, leftQualifiers, rightQualifiers);
            allLeft  &= side == JoinSides.Side.LEFT;
            allRight &= side == JoinSides.Side.RIGHT;
        }
        if (allLeft)  return JoinSide.LEFT;
        if (allRight) return JoinSide.RIGHT;
        return JoinSide.NEITHER;
    }

    /** Returns {@code true} when {@code schema} has a column matching the
     *  unqualified column part of {@code attrName} (case-insensitive). */
    private static boolean hasColumn(Schema schema, String attrName) {
        return schema.column(PredicateAttributeCollector.columnPart(attrName)).isPresent();
    }

    private enum JoinSide { LEFT, RIGHT, NEITHER }
}
