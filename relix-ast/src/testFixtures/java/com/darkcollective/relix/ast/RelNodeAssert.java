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

import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;

import java.util.List;
import java.util.function.Consumer;

/**
 * Assertions on a logical {@link RelNode} tree, whose failures print the tree.
 *
 * <p>The reason this exists is the failure message, not the call site.  A shape
 * assertion written by hand — instance-check, cast, navigate, repeat — reports
 * {@code expecting actual to be an instance of SelectionNode but was
 * ProjectionNode} and stops there: the one fact the reader already suspected, and
 * none of the expression that was actually produced.  Every assertion here carries
 * {@link RelNode#prettyPrint()} of the subject as its description, so the same
 * failure shows the whole tree, in a form the parser round-trips.
 *
 * <p>Navigation is by {@link #child(int)} rather than by casting, so the position
 * that went wrong is named in the failure:
 *
 * {@snippet lang = "java":
 * assertThat(tree)
 *         .isNode(ProjectionNode.class)
 *         .child(0).isNode(SelectionNode.class)
 *         .child(0).isRelation("Users");
 * }
 *
 * <p>{@link #isEquivalentTo(RelNode)} is the home for {@link AstEquivalence} — the
 * structural, location-free comparison the suite reaches for over a hundred times
 * and always calls directly, because there was nowhere better to put it.  Called
 * here it prints both trees on failure rather than {@code expected true but was
 * false}.
 *
 * <p>{@link #isStructurallyEqualTo(RelNode)} is its exact counterpart — equality
 * component for component, blind only to source position.  The two are not
 * interchangeable, and {@link AstLocations} is where the difference is set out.
 *
 * <p>Obtain one from {@link AstAssertions#assertThat(RelNode)}.
 *
 * @see AstAssertions
 * @see AstLocations
 */
public final class RelNodeAssert extends AbstractAssert<RelNodeAssert, RelNode> {

    RelNodeAssert(RelNode actual) {
        super(actual, RelNodeAssert.class);
        if (actual != null) {
            as("relational expression:%n%s", describe(actual));
        }
    }

    /**
     * Asserts the node is of the given kind, and narrows the assert to it.
     *
     * <p>The replacement for {@code assertThat(n).isInstanceOf(X.class)} followed by a
     * cast: the same claim, but the failure prints the tree the node came from.
     *
     * @param kind the expected concrete node type
     * @return this assert, for chaining
     */
    public RelNodeAssert isNode(Class<? extends RelNode> kind) {
        isNotNull();
        if (!kind.isInstance(actual)) {
            failWithMessage("expected a %s but was a %s",
                    kind.getSimpleName(), actual.getClass().getSimpleName());
        }
        return this;
    }

    /**
     * Asserts the node is of the given kind and hands the typed node to
     * {@code requirements}, for the per-kind accessors this assert deliberately does
     * not enumerate (a join's condition, an aggregation's keys).
     *
     * <p>The description is retained, so an assertion failing inside
     * {@code requirements} still prints the tree.
     *
     * @param kind         the expected concrete node type
     * @param requirements the assertions to run against the narrowed node
     * @param <T>          the narrowed node type
     * @return this assert, for chaining
     */
    public <T extends RelNode> RelNodeAssert isNodeSatisfying(Class<T> kind, Consumer<T> requirements) {
        isNode(kind);
        requirements.accept(kind.cast(actual));
        return this;
    }

    /**
     * Asserts the node is of the given kind and <em>returns</em> it, narrowed.
     *
     * <p>Prefer {@link #isNode(Class)} and {@link #child(int)} where the claim is the
     * shape — they keep the failure on the tree.  Use this where the claim is a record
     * component the shape assertions cannot reach (a join's condition, a window's
     * frame), and several of them are asserted in turn: it is the cast the suite writes
     * anyway, with the kind check that precedes it printing the tree when it fails.
     *
     * @param kind the expected concrete node type
     * @param <T>  the narrowed node type
     * @return the node, as {@code kind}
     */
    public <T extends RelNode> T asNode(Class<T> kind) {
        isNode(kind);
        return kind.cast(actual);
    }

    /**
     * Asserts the node is a {@link RelationNode} naming {@code name}
     * (case-insensitively, as the symbol table resolves it).
     *
     * @param name the expected relation name
     * @return this assert, for chaining
     */
    public RelNodeAssert isRelation(String name) {
        isNode(RelationNode.class);
        String actualName = ((RelationNode) actual).name();
        if (!actualName.equalsIgnoreCase(name)) {
            failWithMessage("expected the relation %s but was %s", name, actualName);
        }
        return this;
    }

    /**
     * Asserts the node has exactly {@code count} direct children.
     *
     * @param count the expected child count
     * @return this assert, for chaining
     */
    public RelNodeAssert hasChildCount(int count) {
        isNotNull();
        List<RelNode> children = actual.children();
        if (children.size() != count) {
            failWithMessage("expected %s to have %d child expression(s) but it has %d",
                    actual.getClass().getSimpleName(), count, children.size());
        }
        return this;
    }

    /**
     * Descends to the child at {@code index}, returning an assert on it.
     *
     * <p>Note that {@link RelNode#children()} reports <em>relational</em> children
     * only: a {@code RelationFunctionCall}'s arguments and a
     * {@code LateralJoinNode}'s arguments are operands and do not appear, and an
     * {@code EmptyRelationNode}'s carried heading is inert by design.
     *
     * @param index the zero-based child position
     * @return an assert on that child
     */
    public RelNodeAssert child(int index) {
        isNotNull();
        List<RelNode> children = actual.children();
        if (index >= children.size()) {
            failWithMessage("expected %s to have a child at index %d but it has %d",
                    actual.getClass().getSimpleName(), index, children.size());
        }
        return new RelNodeAssert(children.get(index)).as("child %d of %s, in:%n%s",
                index, actual.getClass().getSimpleName(), describe(actual));
    }

    /**
     * Descends to the single child of a unary operator, failing if the node has any
     * other arity.
     *
     * @return an assert on the input expression
     */
    public RelNodeAssert input() {
        hasChildCount(1);
        return child(0);
    }

    /**
     * Descends to the left input of a binary operator.
     *
     * @return an assert on the left input
     */
    public RelNodeAssert left() {
        hasChildCount(2);
        return child(0);
    }

    /**
     * Descends to the right input of a binary operator.
     *
     * @return an assert on the right input
     */
    public RelNodeAssert right() {
        hasChildCount(2);
        return child(1);
    }

    /**
     * Asserts this tree is structurally equivalent to {@code expected} under
     * {@link AstEquivalence} — the same expression up to source position.
     *
     * <p>This is where that comparison belongs.  Called directly it yields
     * {@code expected true but was false}; called here the failure prints both
     * trees, which is the only form in which the difference is readable.
     *
     * <p>Equivalence is <em>structural</em>, not semantic: {@code a ∧ b} and
     * {@code b ∧ a} are different trees, and so — see {@link AstEquivalence} — are
     * two different spellings of one literal.
     *
     * @param expected the tree this one should match
     * @return this assert, for chaining
     */
    public RelNodeAssert isEquivalentTo(RelNode expected) {
        isNotNull();
        Assertions.assertThat(expected).as("expected tree").isNotNull();
        if (!AstEquivalence.equivalent(actual, expected)) {
            failWithMessage("expected an equivalent expression, but%nactual:%n%s%nexpected:%n%s",
                    describe(actual), describe(expected));
        }
        return this;
    }

    /**
     * Asserts this tree is <em>exactly</em> {@code expected}, component for component,
     * ignoring only source positions — the claim a parser or builder test makes.
     *
     * <p>Distinct from {@link #isEquivalentTo(RelNode)}, and the distinction matters:
     * that one compares printed forms and normalises numeric literals and identifier
     * case, so {@code 5.0} matches {@code 5} and {@code users} matches {@code Users}.
     * Where the spelling <em>is</em> the claim — a lexer test, a test that a builder
     * put a component where the record declares it — use this one.  See
     * {@link AstLocations} for the full comparison.
     *
     * @param expected the tree this one should equal, up to source position
     * @return this assert, for chaining
     */
    public RelNodeAssert isStructurallyEqualTo(RelNode expected) {
        isNotNull();
        Assertions.assertThat(expected).as("expected tree").isNotNull();
        RelNode strippedActual = AstLocations.stripLocations(actual);
        RelNode strippedExpected = AstLocations.stripLocations(expected);
        if (!strippedActual.equals(strippedExpected)) {
            failWithMessage("expected the same expression, but%nactual:%n%s%nexpected:%n%s",
                    describe(actual), describe(expected));
        }
        return this;
    }

    /**
     * Asserts this tree is <em>not</em> structurally equivalent to {@code other} —
     * the claim a rewrite test makes when it says a pass changed something.
     *
     * @param other the tree this one should differ from
     * @return this assert, for chaining
     */
    public RelNodeAssert isNotEquivalentTo(RelNode other) {
        isNotNull();
        Assertions.assertThat(other).as("other tree").isNotNull();
        if (AstEquivalence.equivalent(actual, other)) {
            failWithMessage("expected a different expression, but both are:%n%s",
                    describe(actual));
        }
        return this;
    }

    /**
     * Asserts this tree pretty-prints to exactly {@code expected}.
     *
     * @param expected the expected rendering, as {@link RelNode#prettyPrint()} produces it
     * @return this assert, for chaining
     */
    public RelNodeAssert prettyPrintsTo(String expected) {
        isNotNull();
        Assertions.assertThat(actual.prettyPrint()).as(descriptionText()).isEqualTo(expected);
        return this;
    }

    /**
     * Asserts the rendered tree contains {@code fragment} — for the claim "this
     * operator survived the rewrite" where the whole rendering is not the point.
     *
     * @param fragment the expected substring of {@link RelNode#prettyPrint()}
     * @return this assert, for chaining
     */
    public RelNodeAssert prettyPrintContains(String fragment) {
        isNotNull();
        Assertions.assertThat(actual.prettyPrint()).as(descriptionText()).contains(fragment);
        return this;
    }

    /**
     * Asserts no node anywhere in this tree is of {@code kind} — the claim an
     * elimination rule makes ("the δ is gone"), which a check on the root alone
     * does not support.
     *
     * @param kind the node type that must not appear
     * @return this assert, for chaining
     */
    public RelNodeAssert containsNoNode(Class<? extends RelNode> kind) {
        isNotNull();
        if (countOf(actual, kind) != 0) {
            failWithMessage("expected no %s anywhere in the expression, but found %d",
                    kind.getSimpleName(), countOf(actual, kind));
        }
        return this;
    }

    /**
     * Asserts this tree contains exactly {@code count} nodes of {@code kind}.
     *
     * @param kind  the node type to count
     * @param count the expected number of occurrences
     * @return this assert, for chaining
     */
    public RelNodeAssert containsNodes(Class<? extends RelNode> kind, int count) {
        isNotNull();
        int found = countOf(actual, kind);
        if (found != count) {
            failWithMessage("expected %d %s node(s) in the expression but found %d",
                    count, kind.getSimpleName(), found);
        }
        return this;
    }

    private static int countOf(RelNode node, Class<? extends RelNode> kind) {
        int here = kind.isInstance(node) ? 1 : 0;
        for (RelNode child : node.children()) {
            here += countOf(child, kind);
        }
        return here;
    }

    /**
     * Indents a rendered tree so it reads as a block inside the failure message
     * rather than running into AssertJ's own text.
     */
    static String indent(String rendered) {
        return rendered.lines().map(line -> "    " + line)
                .reduce((a, b) -> a + System.lineSeparator() + b).orElse("    <empty>");
    }

    /**
     * Renders {@code a tree} for the failure message, and never throws.
     *
     * <p>A description is not the claim under test.  Some of the subjects a test builds
     * are deliberately malformed — a pass is asked what it does with a null component,
     * and {@code PrettyPrinter} throws on one — so a renderer that propagated that would
     * turn a <em>passing</em> assertion into a red test whose stack trace points at the
     * assert rather than at the code.  Rendering is best-effort for exactly that reason.
     */
    static String describe(RelNode node) {
        try {
            return indent(node.prettyPrint());
        } catch (RuntimeException e) {
            return "    <unrenderable " + node.getClass().getSimpleName() + ": " + e + ">";
        }
    }
}
