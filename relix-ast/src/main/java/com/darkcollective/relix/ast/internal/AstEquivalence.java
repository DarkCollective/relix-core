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
package com.darkcollective.relix.ast.internal;

import com.darkcollective.relix.ast.AndPredicate;
import com.darkcollective.relix.ast.ArrayConstruction;
import com.darkcollective.relix.ast.AstBuilders;
import com.darkcollective.relix.ast.AttributeOperand;
import com.darkcollective.relix.ast.BinaryArithmeticExpression;
import com.darkcollective.relix.ast.BooleanOperand;
import com.darkcollective.relix.ast.ComparisonPredicate;
import com.darkcollective.relix.ast.ConditionOperand;
import com.darkcollective.relix.ast.DateOperand;
import com.darkcollective.relix.ast.DurationOperand;
import com.darkcollective.relix.ast.ElementOfPredicate;
import com.darkcollective.relix.ast.FunctionCall;
import com.darkcollective.relix.ast.NotPredicate;
import com.darkcollective.relix.ast.NullPredicate;
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.OrPredicate;
import com.darkcollective.relix.ast.PatternPredicate;
import com.darkcollective.relix.ast.Predicate;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.SetLiteralOperand;
import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.ast.StructConstruction;
import com.darkcollective.relix.ast.TimeOperand;
import com.darkcollective.relix.ast.TimestampOperand;
import com.darkcollective.relix.ast.UnaryOperand;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Structural equality over the AST — "are these two fragments the same
 * expression?", asked without regard to <em>where</em> each was written.
 *
 * <h2>Why record equality is not the answer</h2>
 * <p>Every {@link RelNode}, {@link Predicate} and {@link Operand} is a Java
 * {@code record} carrying a {@link SourceLocation} as a <strong>component</strong>,
 * and nothing in this module overrides {@code equals}. The compiler-generated
 * component-wise equality therefore includes the source position: {@code σ x > 5}
 * written on line 3 and {@code σ x > 5} written on line 7 are <em>unequal</em>, with
 * different hash codes. Every rule that wants to know whether two fragments say the
 * same thing — duplicate-conjunct removal ({@code p ∧ p → p}), bound comparison for
 * contradiction detection, the set-op idempotence laws, any common-subexpression
 * elimination — needs this class instead.
 *
 * <h2>⚠ Testing this</h2>
 * <p><strong>Every test-convenience constructor in this module uses
 * {@link SourceLocation#UNKNOWN}.</strong> An implementation keyed on record equality
 * passes its unit tests and then finds nothing on real parsed queries, because only
 * parsed fragments carry distinct locations. Fixtures for anything that depends on
 * this class must be <em>built through the parser</em>; hand-built {@code AstBuilders}
 * fragments are not evidence.
 *
 * <h2>What is normalised</h2>
 * <ul>
 *   <li><b>Source locations</b> — ignored everywhere, recursively, including inside
 *       the predicates and operands a node carries.</li>
 *   <li><b>Numeric literals</b> — {@link NumberOperand} stores a {@code String}, so
 *       {@code 5}, {@code 5.0} and {@code 05} are three distinct values under record
 *       equality. They are compared as {@link BigDecimal}s and are equivalent here.
 *       A literal that does not parse as a decimal falls back to string comparison.</li>
 *   <li><b>Identifier case</b> — attribute, function and relation names are compared
 *       case-insensitively, matching {@link com.darkcollective.relix.ast.internal.Predicates#isColumn}
 *       and the symbol layer.</li>
 * </ul>
 *
 * <h2>This is not exact structural equality</h2>
 * <p>The normalisation above is the point of this class, and it is also what makes it
 * the <em>wrong</em> comparison for a test asserting that a fragment parsed or was built
 * exactly as written: under the rules above {@code 5.0} matches {@code 5} and
 * {@code users} matches {@code Users}, so a test asserting either distinction would
 * silently stop asserting anything. Comparing printed forms is the weaker claim in the
 * other direction too — two structurally different trees that print alike compare equal
 * here. For the exact, position-blind comparison, strip the locations and use record
 * equality: {@code AstLocations.stripLocations}, published from this module's test
 * fixtures, with {@code RelNodeAssert.isStructurallyEqualTo} as its assertion form.
 *
 * <h2>What is deliberately <em>not</em> normalised</h2>
 * <ul>
 *   <li><b>An attribute's qualifier.</b> {@code Users.id} and {@code id} are
 *       <strong>not</strong> equivalent. {@link AttributeNames#stripQualifier} exists
 *       and is tempting, but "equal ignoring qualifier" is wrong exactly where this
 *       class gets used: in {@code A ⨝ B}, {@code A.x} and {@code B.x} name different
 *       columns, and merging them would make a rule that reasons about them vacuous —
 *       the same conclusion {@code EQ-001} reached independently. A caller that
 *       genuinely wants the unqualified reading should strip before comparing, which
 *       makes the choice visible at the call site.</li>
 *   <li><b>Commutativity and associativity.</b> {@code a ∧ b} is not equivalent to
 *       {@code b ∧ a}, and {@code x + 1} is not equivalent to {@code 1 + x}. This is
 *       <em>structural</em> equality, not semantic equality; a caller that wants the
 *       commutative reading should canonicalise its operands first (the predicate
 *       simplifier's {@code PRED-003} already normalises a comparison's operand order
 *       for precisely this reason).</li>
 *   <li><b>Arithmetic identities.</b> {@code x + 0} is not equivalent to {@code x};
 *       that is the expression simplifier's job, and running it first is what makes
 *       this comparison see the simplified form.</li>
 * </ul>
 *
 * <h2>Reference identity is a different question</h2>
 * <p>{@link RelNode#mapChildren} contracts to return {@code this} when nothing
 * changed, so {@code !=} is the optimizer's established "did this rewrite fire"
 * signal. That is <em>identity</em>. This class answers <em>semantic sameness</em>,
 * and the two must not be conflated: two fragments can be equivalent here and still
 * be different objects, which is the entire point.
 *
 * <p>This class is stateless; every method is static.
 */
public final class AstEquivalence {

    private AstEquivalence() {
    }

    // =========================================================================
    // Operand
    // =========================================================================

    /**
     * Returns whether two operand expressions are structurally equivalent, ignoring
     * source locations and normalising numeric literals and identifier case.
     *
     * @param a the first operand; must not be null
     * @param b the second operand; must not be null
     * @return {@code true} if the two denote the same expression
     */
    public static boolean equivalent(Operand a, Operand b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (a == b) {
            return true;
        }
        return switch (a) {
            case AttributeOperand x -> b instanceof AttributeOperand y
                    && x.name().equalsIgnoreCase(y.name());
            case NumberOperand x -> b instanceof NumberOperand y && sameNumber(x.value(), y.value());
            case StringOperand x -> b instanceof StringOperand y && x.value().equals(y.value());
            case BooleanOperand x -> b instanceof BooleanOperand y && x.value() == y.value();
            case DateOperand x -> b instanceof DateOperand y && x.value().equals(y.value());
            case TimeOperand x -> b instanceof TimeOperand y && x.value().equals(y.value());
            case TimestampOperand x -> b instanceof TimestampOperand y && x.value().equals(y.value());
            case DurationOperand x -> b instanceof DurationOperand y && x.value().equals(y.value());
            case BinaryArithmeticExpression x -> b instanceof BinaryArithmeticExpression y
                    && x.operator() == y.operator()
                    && equivalent(x.left(), y.left())
                    && equivalent(x.right(), y.right());
            case UnaryOperand x -> b instanceof UnaryOperand y
                    && equivalent(x.operand(), y.operand());
            case FunctionCall x -> b instanceof FunctionCall y
                    && x.functionName().equalsIgnoreCase(y.functionName())
                    && allEquivalent(x.arguments(), y.arguments());
            case ConditionOperand x -> b instanceof ConditionOperand y
                    && equivalent(x.predicate(), y.predicate());
            case SetLiteralOperand x -> b instanceof SetLiteralOperand y
                    && allEquivalent(x.elements(), y.elements());
            case ArrayConstruction x -> b instanceof ArrayConstruction y
                    && allEquivalent(x.elements(), y.elements());
            case StructConstruction x -> b instanceof StructConstruction y
                    && sameFields(x.fields(), y.fields());
        };
    }

    // =========================================================================
    // Predicate
    // =========================================================================

    /**
     * Returns whether two predicates are structurally equivalent, ignoring source
     * locations and normalising the operands they compare.
     *
     * <p>This is the comparison duplicate-conjunct removal ({@code p ∧ p → p}) asks:
     * {@link Predicates#conjuncts} splits, this decides which of the results say the
     * same thing, and {@link Predicates#conjoin} rebuilds.
     *
     * @param a the first predicate; must not be null
     * @param b the second predicate; must not be null
     * @return {@code true} if the two denote the same condition
     */
    public static boolean equivalent(Predicate a, Predicate b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (a == b) {
            return true;
        }
        return switch (a) {
            case ComparisonPredicate x -> b instanceof ComparisonPredicate y
                    && x.operator() == y.operator()
                    && equivalent(x.left(), y.left())
                    && equivalent(x.right(), y.right());
            case AndPredicate x -> b instanceof AndPredicate y
                    && equivalent(x.left(), y.left())
                    && equivalent(x.right(), y.right());
            case OrPredicate x -> b instanceof OrPredicate y
                    && equivalent(x.left(), y.left())
                    && equivalent(x.right(), y.right());
            case NotPredicate x -> b instanceof NotPredicate y
                    && equivalent(x.predicate(), y.predicate());
            case NullPredicate x -> b instanceof NullPredicate y
                    && x.isNull() == y.isNull()
                    && equivalent(x.operand(), y.operand());
            case ElementOfPredicate x -> b instanceof ElementOfPredicate y
                    && x.isNegated() == y.isNegated()
                    && equivalent(x.element(), y.element())
                    && equivalent(x.setExpression(), y.setExpression());
            case PatternPredicate x -> b instanceof PatternPredicate y
                    && x.negated() == y.negated()
                    && equivalent(x.operand(), y.operand())
                    && equivalent(x.pattern(), y.pattern());
        };
    }

    // =========================================================================
    // RelNode
    // =========================================================================

    /**
     * Returns whether two expression trees are structurally equivalent, ignoring
     * source locations.
     *
     * <h4>How, and what that costs</h4>
     * <p>This is decided by comparing {@link RelNode#prettyPrint()} forms rather than
     * by a 48-arm structural walk, for two reasons that are properties of the printer
     * rather than conveniences:
     * <ul>
     *   <li>It is <strong>location-free</strong> — no arm prints a
     *       {@link SourceLocation} — so the position a fragment was written at cannot
     *       leak into the answer.</li>
     *   <li>It <strong>round-trips through the parser</strong>. Two trees that print
     *       identically therefore parse back to the same tree, which is what rules out
     *       a <em>false positive</em> — the only dangerous direction for a rewrite that
     *       deletes one of two "identical" sub-trees.</li>
     * </ul>
     * <p>It is also exhaustive by construction: {@link
     * com.darkcollective.relix.ast.visitor.RelNodeVisitor}
     * has no default arm, so a new node type cannot be added without teaching the
     * printer about it, and this comparison follows for free.
     *
     * <p><strong>Known limitation.</strong> The printer spells literals verbatim, so
     * {@code σ x > 5 (R)} and {@code σ x > 5.0 (R)} are <em>not</em> equivalent here,
     * even though {@link #equivalent(Operand, Operand)} says their bounds are. That is
     * a false <em>negative</em> — a rule declines to fire — which is the safe
     * direction, and it is where the line is drawn deliberately: normalising literals
     * inside a node needs an operand-rewriting arm for all 48 {@code RelNode} types,
     * which is the cost this class exists to avoid, and no shipped rule consumes
     * node-level equivalence yet. Fix it when the first one does.
     *
     * @param a the first tree; must not be null
     * @param b the second tree; must not be null
     * @return {@code true} if the two trees denote the same expression
     */
    public static boolean equivalent(RelNode a, RelNode b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return a == b || digest(a).equals(digest(b));
    }

    /**
     * Returns a location-free canonical string for {@code node}, equal for exactly
     * the trees {@link #equivalent(RelNode, RelNode)} accepts — so it can be used as
     * a map or set key where pairwise comparison would be quadratic.
     *
     * @param node the tree to digest; must not be null
     * @return the canonical form; never null
     */
    public static String digest(RelNode node) {
        Objects.requireNonNull(node, "node");
        return node.prettyPrint();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Compares two numeric literals numerically.
     *
     * <p>Deliberately <strong>exact</strong>, via
     * {@link BigDecimal#compareTo} — which ignores scale, so {@code 5} and
     * {@code 5.0} compare equal — rather than rounded through
     * {@code MathContext.DECIMAL64}, the convention {@code OperandSimplifier} uses for
     * {@code EXPR-001}. That convention is right for <em>folding</em> arithmetic,
     * where a result has to land somewhere; applying it to an equality test would make
     * two genuinely distinct 20-digit literals compare equal, which is a false
     * positive and the one direction this class must never take.
     *
     * <p>A literal that is not a parseable decimal falls back to string equality:
     * {@link NumberOperand} does not validate its content, so the comparison must not
     * throw on whatever it holds.
     */
    private static boolean sameNumber(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
        } catch (NumberFormatException notADecimal) {
            return false;
        }
    }

    private static boolean allEquivalent(List<Operand> a, List<Operand> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!equivalent(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Struct fields are positional here — {@code {a: 1, b: 2}} is not {@code {b: 2, a: 1}}. */
    private static boolean sameFields(List<StructConstruction.Field> a,
                                      List<StructConstruction.Field> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).name().equalsIgnoreCase(b.get(i).name())
                    || !equivalent(a.get(i).value(), b.get(i).value())) {
                return false;
            }
        }
        return true;
    }
}
