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

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.DistinctNode;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.FixpointNode;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.NaturalJoinNode;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.Predicates;
import com.darkcollective.relix.ast.ProductNode;
import com.darkcollective.relix.ast.ProjectedAttribute;
import com.darkcollective.relix.ast.ProjectionNode;
import com.darkcollective.relix.ast.RecursiveRefNode;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RenameNode;
import com.darkcollective.relix.ast.SelectionNode;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.ThetaJoinNode;
import com.darkcollective.relix.ast.UnaryOperand;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Selection-into-{@code FIX} pushdown — general magic-sets / sideways-information-passing
 * over the {@code FIX} least-fixpoint binder ({@code FIX-001}, ADR-0021; the
 * umbrella case of which {@link SelectionIntoClosurePass}/{@link SelectionIntoTracePass}
 * are fixed-shape instances).
 *
 * <p>{@code FIX name (base, step)} computes the <em>whole</em> least fixpoint of a
 * monotone, linear recursive {@code step}. A selection above it that constrains a
 * <strong>frozen</strong> column — one the step carries unchanged from the recursive
 * reference to its output — can be propagated <em>into</em> the recursion so the
 * fixpoint is seeded and restricted by the bound instead of computed in full:
 * <pre>{@code
 *   σ p (FIX name (base, step))
 *       ≡  FIX name (σ p (base),  step[ name ↦ σ p (name) ])
 * }</pre>
 * This is a pure logical rewrite over existing nodes — no new operator field, no
 * executor change.
 *
 * <h2>Soundness — frozen columns only</h2>
 * <p>The distributive law is valid <em>only</em> when {@code p} references frozen
 * columns exclusively. A frozen column's value, on every derived row, equals the value
 * of the single recursive-reference tuple the row was anchored to (linearity ⇒ exactly
 * one anchor); restricting the recursion to {@code p}-satisfying rows therefore never
 * loses a {@code p}-satisfying answer, because any answer's anchor tuple already
 * satisfies {@code p}. The proof (ADR-0021 Decision 2) leans on the two invariants the
 * {@code FIX} validator already enforces — <em>linearity</em> (one ref) and
 * <em>monotonicity</em> (no {@code −}/{@code ÷}/outer-join/{@code γ} in the step) — so
 * no new validation is needed.
 *
 * <h2>Frozen-column analysis (conservative)</h2>
 * <p>{@link #frozen} structurally walks the step: the recursive ref is frozen on every
 * column; {@code σ}/{@code δ} preserve frozenness; {@code π} freezes a bare-column
 * passthrough of a frozen input column; {@code ρ} remaps the column then recurses; an
 * inner join/product freezes a column drawn from the ref-containing side. Anything else
 * is reported not-frozen — a {@code false} only forfeits the optimization, never
 * mis-reports.
 *
 * <h2>What is pushable</h2>
 * <p>A top-level conjunct of the selection chain directly above the {@code FIX} is
 * pushed iff every attribute it references is an <strong>unqualified, frozen</strong>
 * column, it references at least one attribute, and it contains no {@link FunctionCall}
 * (a conservative determinism guard). Everything else stays as a residual {@code σ}
 * above the rewritten {@code FIX} — correctness by construction: an unrecognised
 * conjunct is never dropped, and a {@code FIX} with no pushable conjunct is left
 * untouched (a no-op).
 *
 * <h2>Observability</h2>
 * <p>Each firing records {@link OptimizationCode#FIX_001} (a
 * {@link com.darkcollective.relix.events.QueryEvent.Stage#OPTIMIZE} event via
 * {@link OptimizationContext}), naming the propagated predicate. No event is emitted for
 * an unconstrained {@code FIX} or one whose selection touches only non-frozen columns.
 *
 * <p>This class is package-private and stateless; call
 * {@link #apply(RelNode, String, SchemaAnnotations, OptimizationContext)} as a static
 * method.
 */
final class SelectionIntoFixpointPass {

    private SelectionIntoFixpointPass() {}

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Applies FIX-001 to the entire tree rooted at {@code node}.
     *
     * @param node      root of the tree to transform; must not be null
     * @param queryName display name used in transformation records
     * @param schemas   schema annotations; used to resolve join-side membership
     * @param ctx       transformation record accumulator
     * @return the transformed tree, or {@code node} unchanged when no rule fires
     */
    static RelNode apply(RelNode node, String queryName,
                         SchemaAnnotations schemas, OptimizationContext ctx) {
        return rewriteNode(node, queryName, schemas, ctx);
    }

    // =========================================================================
    // RelNode traversal (a whole selection chain above a FIX is consumed at once)
    // =========================================================================

    private static RelNode rewriteNode(RelNode node, String queryName,
                                       SchemaAnnotations schemas, OptimizationContext ctx) {
        if (node instanceof SelectionNode) {
            RelNode pushed = tryPushChain(node, queryName, schemas, ctx);
            if (pushed != null) {
                return pushed;
            }
            // Not a (pushable) FIX chain — fall through to the structural recursion,
            // which descends into the selection's input like every other node.
        }
        return node.mapChildren(child -> rewriteNode(child, queryName, schemas, ctx));
    }

    // =========================================================================
    // Rewrite
    // =========================================================================

    /**
     * Attempts the magic-sets rewrite for a selection chain bottoming out at a
     * {@code FIX}. The "transparent prefix" between the outermost selection and the
     * fixpoint may interleave selections with <em>relation-only</em> renames (e.g. the
     * {@code ρ ViewName} a view-inline leaves behind): a relation-only rename preserves
     * every column name, so a frozen-column predicate commutes across it freely.
     *
     * <p>Every frozen-column conjunct found anywhere in the prefix is folded into the
     * fixpoint (seeding the base + restricting the recursive ref); the prefix is rebuilt
     * in its original nesting order with those conjuncts removed and renames preserved.
     *
     * @return the rewritten tree, or {@code null} if {@code start} is not a selection
     *         chain over a {@code FIX} with at least one pushable conjunct (in which case
     *         the caller falls back to ordinary structural recursion)
     */
    private static RelNode tryPushChain(RelNode start, String queryName,
                                        SchemaAnnotations schemas, OptimizationContext ctx) {
        List<RelNode> prefix = new ArrayList<>();   // outer → inner: SelectionNode | relation-only RenameNode
        RelNode cur = start;
        while (true) {
            if (cur instanceof SelectionNode s) {
                prefix.add(s);
                cur = s.input();
            } else if (isRelationOnlyRename(cur)) {
                prefix.add(cur);
                cur = ((RenameNode) cur).input();
            } else {
                break;
            }
        }
        if (!(cur instanceof FixpointNode fix)) {
            return null;
        }

        // Collect every pushable conjunct across the selection wrappers in the prefix.
        List<Predicate> pushable = new ArrayList<>();
        for (RelNode w : prefix) {
            if (w instanceof SelectionNode sn) {
                for (Predicate p : flatten(sn.predicate())) {
                    if (isPushable(p, fix, schemas)) {
                        pushable.add(p);
                    }
                }
            }
        }
        if (pushable.isEmpty()) {
            return null;
        }

        // Recurse into the FIX children first, so nested FIX/CLOSURE chains inside the
        // base or step also get a chance to fire.
        RelNode newBaseInner = rewriteNode(fix.base(), queryName, schemas, ctx);
        RelNode newStepInner = rewriteNode(fix.step(), queryName, schemas, ctx);

        Predicate pushedPred = Predicates.conjoin(pushable);
        RelNode newBase = new SelectionNode(pushedPred, newBaseInner, fix.base().location());
        RelNode newStep = injectOnRef(newStepInner, fix.name(), pushedPred);
        RelNode result = new FixpointNode(fix.name(), newBase, newStep, fix.location());

        ctx.record(OptimizationCode.FIX_001, queryName, describe(fix, pushedPred), fix.location());

        // Rebuild the prefix inner → outer: drop pushed conjuncts, keep renames in place.
        for (int i = prefix.size() - 1; i >= 0; i--) {
            RelNode w = prefix.get(i);
            if (w instanceof SelectionNode sn) {
                List<Predicate> residual = new ArrayList<>();
                for (Predicate p : flatten(sn.predicate())) {
                    if (!isPushable(p, fix, schemas)) {
                        residual.add(p);
                    }
                }
                if (!residual.isEmpty()) {
                    result = new SelectionNode(Predicates.conjoin(residual), result, sn.location());
                }
            } else {
                RenameNode rn = (RenameNode) w;
                result = rn.withInput(result);
            }
        }
        return result;
    }

    /** A rename that changes only the relation name (no column renaming) preserves names. */
    private static boolean isRelationOnlyRename(RelNode node) {
        return node instanceof RenameNode rn && rn.attributes().isEmpty();
    }

    /**
     * Replaces every {@link RecursiveRefNode} bound to {@code name} inside {@code node}
     * with {@code σ pushed (ref)} — the "magic" restriction re-applied each round. A
     * nested {@code FIX} that rebinds the same {@code name} shadows the binder within its
     * own step, so injection there descends only into that inner {@code FIX}'s base
     * (where the outer {@code name} is still in scope).
     */
    private static RelNode injectOnRef(RelNode node, String name, Predicate pushed) {
        if (node instanceof RecursiveRefNode r && r.name().equals(name)) {
            return new SelectionNode(pushed, r, r.location());
        }
        if (node instanceof FixpointNode f && f.name().equals(name)) {
            RelNode nb = injectOnRef(f.base(), name, pushed);
            return nb == f.base() ? f : new FixpointNode(f.name(), nb, f.step(), f.location());
        }
        return node.mapChildren(child -> injectOnRef(child, name, pushed));
    }

    // =========================================================================
    // Pushability
    // =========================================================================

    /**
     * A conjunct is pushable iff it references ≥1 attribute, every referenced attribute
     * is unqualified and a frozen column of the step, and it contains no function call.
     */
    private static boolean isPushable(Predicate p, FixpointNode fix, SchemaAnnotations schemas) {
        if (containsFunctionCall(p)) {
            return false;
        }
        Set<String> names = PredicateAttributeCollector.collectNames(p);
        if (names.isEmpty()) {
            return false;
        }
        for (String name : names) {
            // Conservative: a qualified reference would re-bind to a different relation
            // when re-applied to the base/ref, so only unqualified columns are pushed.
            if (!name.equals(PredicateAttributeCollector.columnPart(name))) {
                return false;
            }
            if (!frozen(fix.step(), name, fix.name(), schemas)) {
                return false;
            }
        }
        return true;
    }

    // =========================================================================
    // Frozen-column analysis
    // =========================================================================

    /**
     * Returns true if output column {@code col} of {@code node} is provably <em>frozen</em>
     * — carried unchanged from the recursive reference {@code refName} to {@code node}'s
     * output. Conservative: an unrecognised form returns {@code false}.
     */
    private static boolean frozen(RelNode node, String col, String refName,
                                  SchemaAnnotations schemas) {
        return switch (node) {
            case RecursiveRefNode r -> r.name().equals(refName);
            case SelectionNode s    -> frozen(s.input(), col, refName, schemas);
            case DistinctNode d     -> frozen(d.input(), col, refName, schemas);
            case ProjectionNode p   -> frozenThroughProjection(p, col, refName, schemas);
            case RenameNode rn      -> frozenThroughRename(rn, col, refName, schemas);
            case NaturalJoinNode j  -> frozenThroughJoin(j.left(), j.right(), col, refName, schemas);
            case ThetaJoinNode j    -> frozenThroughJoin(j.left(), j.right(), col, refName, schemas);
            case ProductNode j      -> frozenThroughJoin(j.left(), j.right(), col, refName, schemas);
            default                 -> false;
        };
    }

    /** Frozen through a projection: only a bare-column passthrough of a frozen column. */
    private static boolean frozenThroughProjection(ProjectionNode p, String col,
                                                   String refName, SchemaAnnotations schemas) {
        for (ProjectedAttribute pa : p.attributes()) {
            if (pa.expression() instanceof AttributeOperand a) {
                String out = pa.alias().orElse(a.unqualifiedName());
                if (out.equalsIgnoreCase(col)) {
                    return frozen(p.input(), a.unqualifiedName(), refName, schemas);
                }
            }
            // A computed/aliased expression that produces `col` is not a passthrough.
        }
        return false;
    }

    /** Frozen through a rename: relation-only rename preserves names; column rename remaps. */
    private static boolean frozenThroughRename(RenameNode rn, String col, String refName,
                                               SchemaAnnotations schemas) {
        if (rn.attributes().isEmpty()) {
            return frozen(rn.input(), col, refName, schemas);
        }
        int idx = indexOfIgnoreCase(rn.attributes(), col);
        if (idx < 0) {
            return false;
        }
        Schema inputSchema = schemas.get(rn.input()).orElse(null);
        if (inputSchema == null || inputSchema.isOpen() || idx >= inputSchema.columns().size()) {
            return false;
        }
        return frozen(rn.input(), inputSchema.columns().get(idx).name(), refName, schemas);
    }

    /**
     * Frozen through an inner join / product: {@code col} must come from the side that
     * contains the recursive reference (linear recursion ⇒ exactly one side), and be
     * frozen on that side.
     */
    private static boolean frozenThroughJoin(RelNode left, RelNode right, String col,
                                             String refName, SchemaAnnotations schemas) {
        boolean refLeft = containsRef(left, refName);
        boolean refRight = containsRef(right, refName);
        if (refLeft == refRight) {
            return false;   // defensive: linear recursion places the ref on exactly one side
        }
        RelNode refSide = refLeft ? left : right;
        Schema refSchema = schemas.get(refSide).orElse(null);
        if (refSchema == null || !schemaHasColumn(refSchema, col)) {
            return false;
        }
        return frozen(refSide, col, refName, schemas);
    }

    /** True if the subtree rooted at {@code node} contains a recursive ref to {@code name}. */
    private static boolean containsRef(RelNode node, String name) {
        if (node instanceof RecursiveRefNode r) {
            return r.name().equals(name);
        }
        if (node instanceof FixpointNode f && f.name().equals(name)) {
            // The inner binder shadows `name` in its step; the outer ref is only visible
            // in the inner base.
            return containsRef(f.base(), name);
        }
        return node.children().stream().anyMatch(c -> containsRef(c, name));
    }

    // =========================================================================
    // Predicate helpers
    // =========================================================================

    /** Flattens a (possibly nested) conjunction into its top-level conjuncts. */
    private static List<Predicate> flatten(Predicate p) {
        List<Predicate> out = new ArrayList<>();
        flattenInto(p, out);
        return out;
    }

    private static void flattenInto(Predicate p, List<Predicate> out) {
        if (p instanceof AndPredicate a) {
            flattenInto(a.left(), out);
            flattenInto(a.right(), out);
        } else {
            out.add(p);
        }
    }


    /** True if a {@link FunctionCall} appears anywhere in the predicate's operands. */
    private static boolean containsFunctionCall(Predicate p) {
        return switch (p) {
            case ComparisonPredicate c -> operandHasCall(c.left()) || operandHasCall(c.right());
            case AndPredicate a        -> containsFunctionCall(a.left()) || containsFunctionCall(a.right());
            case OrPredicate o         -> containsFunctionCall(o.left()) || containsFunctionCall(o.right());
            case NotPredicate n        -> containsFunctionCall(n.predicate());
            case NullPredicate n       -> operandHasCall(n.operand());
            case ElementOfPredicate e  -> operandHasCall(e.element()) || operandHasCall(e.setExpression());
            case PatternPredicate pp   -> operandHasCall(pp.operand()) || operandHasCall(pp.pattern());
        };
    }

    private static boolean operandHasCall(Operand op) {
        return switch (op) {
            case FunctionCall ignored          -> true;
            case BinaryArithmeticExpression b  -> operandHasCall(b.left()) || operandHasCall(b.right());
            case UnaryOperand u                -> operandHasCall(u.operand());
            case SetLiteralOperand s           -> s.elements().stream().anyMatch(SelectionIntoFixpointPass::operandHasCall);
            case StructConstruction struct     -> struct.fields().stream().anyMatch(f -> operandHasCall(f.value()));
            case ArrayConstruction array       -> array.elements().stream().anyMatch(SelectionIntoFixpointPass::operandHasCall);
            default                            -> false;
        };
    }

    private static int indexOfIgnoreCase(List<String> names, String target) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(target)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean schemaHasColumn(Schema schema, String col) {
        if (schema.isOpen()) {
            return false;   // open schema: no fixed columns to anchor the analysis on
        }
        return schema.columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(col));
    }

    // =========================================================================
    // Event description
    // =========================================================================

    private static String describe(FixpointNode fix, Predicate pushed) {
        return "σ folded into FIX " + fix.name()
                + " — recursion seeded and restricted by frozen-column predicate "
                + pushed.accept(new com.darkcollective.relix.ast.visitor.PredicatePrettyPrinter())
                + " (full fixpoint avoided)";
    }
}
