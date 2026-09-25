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
package com.darkcollective.relix.plan;

import com.darkcollective.relix.plan.internal.PhysicalPlanPrinter;
import com.darkcollective.relix.symbol.SchemaAssert;
import com.darkcollective.relix.symbol.SymbolAssertions;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;

import java.util.List;
import java.util.function.Consumer;

/**
 * Assertions on a {@link PhysicalNode} plan, whose failures print the plan.
 *
 * <p>This is the assert the issue that introduced these was written about.  A plan
 * shape checked by hand reads
 *
 * {@snippet lang = "java":
 * assertThat(plan).isInstanceOf(PhysicalNode.Select.class);
 * PhysicalNode.Select select = (PhysicalNode.Select) plan;
 * assertThat(select.input()).isInstanceOf(PhysicalNode.Scan.class);
 * assertThat(((PhysicalNode.Scan) select.input()).source().declaredName()).isEqualTo("Users");
 * }
 *
 * <p>and, when the planner changes, prints {@code expected Select but was Project} —
 * naming the node the reader already knew about and none of the plan that was
 * actually produced.  The same claim here
 *
 * {@snippet lang = "java":
 * assertThat(plan).isNode(PhysicalNode.Select.class).input().isScanOf("Users");
 * }
 *
 * <p>fails with the whole plan, rendered by {@link PhysicalPlanPrinter} — the same
 * renderer {@code --explain} shows a user, so a failure and a bug report describe
 * the plan identically.
 *
 * <p>Obtain one from {@link PlanAssertions#assertThat(PhysicalNode)}.
 *
 * @see PlanAssertions
 */
public final class PlanAssert extends AbstractAssert<PlanAssert, PhysicalNode> {

    PlanAssert(PhysicalNode actual) {
        super(actual, PlanAssert.class);
        if (actual != null) {
            as("plan:%n%s", describe(actual));
        }
    }

    /**
     * Asserts the node is of the given kind.
     *
     * @param kind the expected {@link PhysicalNode} record type
     * @return this assert, for chaining
     */
    public PlanAssert isNode(Class<? extends PhysicalNode> kind) {
        isNotNull();
        if (!kind.isInstance(actual)) {
            failWithMessage("expected a %s but was a %s",
                    kind.getSimpleName(), actual.getClass().getSimpleName());
        }
        return this;
    }

    /**
     * Asserts the node is of the given kind and hands the typed node to
     * {@code requirements} — the seam for the per-kind accessors this assert does
     * not enumerate (a join's keys, a pushed scan's native query, a sample's seed).
     *
     * <p>An assertion failing inside {@code requirements} still prints the plan,
     * because the description travels with it.
     *
     * @param kind         the expected {@link PhysicalNode} record type
     * @param requirements the assertions to run against the narrowed node
     * @param <T>          the narrowed node type
     * @return this assert, for chaining
     */
    public <T extends PhysicalNode> PlanAssert isNodeSatisfying(Class<T> kind, Consumer<T> requirements) {
        isNode(kind);
        requirements.accept(kind.cast(actual));
        return this;
    }

    /**
     * Asserts the node is of the given kind and <em>returns</em> it, narrowed.
     *
     * <p>The published replacement for the hand-rolled cast helper — {@code asJoin},
     * {@code asDistinct}, {@code asAggregate}, {@code asPushedScan}, four of them in a
     * single test class — that a suite grows once cast-and-navigate gets tedious.  Each
     * of those was written for brevity and none of them improved the failure output;
     * this one costs the same at the call site and prints the plan.
     *
     * <p>Prefer {@link #isNode(Class)} and {@link #child(int)} where the claim is the
     * shape.  Use this where the claim is a record component the shape assertions
     * cannot reach, and several of them are asserted in turn.
     *
     * @param kind the expected {@link PhysicalNode} record type
     * @param <T>  the narrowed node type
     * @return the node, as {@code kind}
     */
    public <T extends PhysicalNode> T asNode(Class<T> kind) {
        isNode(kind);
        return kind.cast(actual);
    }

    /**
     * Asserts the node is a {@link PhysicalNode.Scan} of the named base relation.
     *
     * @param relationName the relation the scan must read, matched case-insensitively
     * @return this assert, for chaining
     */
    public PlanAssert isScanOf(String relationName) {
        isNode(PhysicalNode.Scan.class);
        String declared = ((PhysicalNode.Scan) actual).source().declaredName();
        if (!declared.equalsIgnoreCase(relationName)) {
            failWithMessage("expected a Scan of %s but it reads %s", relationName, declared);
        }
        return this;
    }

    /**
     * Asserts the node is a {@link PhysicalNode.PushedScan} against {@code connection}
     * whose native query is exactly {@code nativeQuery}.
     *
     * <p>Stated as one claim because the two halves are one fact: a query text is
     * meaningless without the connection it was rendered for, and a pushdown test
     * that checks only the text cannot tell a right query on the wrong connection
     * from a right one.
     *
     * @param connection  the connection name the scan must run on
     * @param nativeQuery the exact backend-native query text
     * @return this assert, for chaining
     */
    public PlanAssert isPushedTo(String connection, String nativeQuery) {
        isNode(PhysicalNode.PushedScan.class);
        PhysicalNode.PushedScan scan = (PhysicalNode.PushedScan) actual;
        if (!scan.connection().equals(connection)) {
            failWithMessage("expected a PushedScan on connection %s but it runs on %s",
                    connection, scan.connection());
        }
        if (!scan.nativeQuery().equals(nativeQuery)) {
            failWithMessage("expected the pushed query%n    %s%nbut it is%n    %s",
                    nativeQuery, scan.nativeQuery());
        }
        return this;
    }

    /**
     * Asserts the node has exactly {@code count} direct children.
     *
     * @param count the expected child count
     * @return this assert, for chaining
     */
    public PlanAssert hasChildCount(int count) {
        isNotNull();
        List<PhysicalNode> children = actual.children();
        if (children.size() != count) {
            failWithMessage("expected %s to have %d child plan(s) but it has %d",
                    actual.getClass().getSimpleName(), count, children.size());
        }
        return this;
    }

    /**
     * Descends to the child at {@code index}, returning an assert on it.
     *
     * @param index the zero-based child position
     * @return an assert on that child plan
     */
    public PlanAssert child(int index) {
        isNotNull();
        List<PhysicalNode> children = actual.children();
        if (index >= children.size()) {
            failWithMessage("expected %s to have a child at index %d but it has %d",
                    actual.getClass().getSimpleName(), index, children.size());
        }
        return new PlanAssert(children.get(index)).as("child %d of %s, in the plan:%n%s",
                index, actual.getClass().getSimpleName(), describe(actual));
    }

    /**
     * Descends to the single input of a unary operator, failing if the node has any
     * other arity.
     *
     * @return an assert on the input plan
     */
    public PlanAssert input() {
        hasChildCount(1);
        return child(0);
    }

    /**
     * Descends to the left input of a binary operator.
     *
     * @return an assert on the left input plan
     */
    public PlanAssert left() {
        hasChildCount(2);
        return child(0);
    }

    /**
     * Descends to the right input of a binary operator.
     *
     * @return an assert on the right input plan
     */
    public PlanAssert right() {
        hasChildCount(2);
        return child(1);
    }

    /**
     * Begins an assertion on this node's baked-in output heading, keeping the plan as
     * the failure's context.
     *
     * @return an assert on {@link PhysicalNode#schema()}
     */
    public SchemaAssert schema() {
        isNotNull();
        return SymbolAssertions.assertThat(actual.schema()).as(descriptionText());
    }

    /**
     * Asserts no node anywhere in the plan is of {@code kind} — the claim a pushdown
     * test makes when it says an operator was folded into the source rather than
     * left in the engine.
     *
     * <p>A {@link PhysicalNode.Spool}'s sub-plan is visited once, matching how the
     * plan is executed and how {@link PhysicalPlanPrinter} draws it.
     *
     * @param kind the plan node type that must not appear
     * @return this assert, for chaining
     */
    public PlanAssert containsNoNode(Class<? extends PhysicalNode> kind) {
        isNotNull();
        int found = countOf(actual, kind, new java.util.HashSet<>());
        if (found != 0) {
            failWithMessage("expected no %s anywhere in the plan, but found %d",
                    kind.getSimpleName(), found);
        }
        return this;
    }

    /**
     * Asserts the plan contains exactly {@code count} nodes of {@code kind}.
     *
     * @param kind  the plan node type to count
     * @param count the expected number of occurrences
     * @return this assert, for chaining
     */
    public PlanAssert containsNodes(Class<? extends PhysicalNode> kind, int count) {
        isNotNull();
        int found = countOf(actual, kind, new java.util.HashSet<>());
        if (found != count) {
            failWithMessage("expected %d %s node(s) in the plan but found %d",
                    count, kind.getSimpleName(), found);
        }
        return this;
    }

    /**
     * Asserts the plan renders to exactly {@code expected} through
     * {@link PhysicalPlanPrinter} — a whole-shape claim in one assertion, for the
     * tests whose subject is the plan itself rather than one decision in it.
     *
     * @param expected the expected rendering, trailing newline included
     * @return this assert, for chaining
     */
    public PlanAssert explainsAs(String expected) {
        isNotNull();
        Assertions.assertThat(PhysicalPlanPrinter.explain(actual)).isEqualTo(expected);
        return this;
    }

    /** Counts nodes of {@code kind}, visiting a shared sub-plan once. */
    private static int countOf(PhysicalNode node, Class<? extends PhysicalNode> kind,
                               java.util.Set<Integer> seenSpools) {
        if (node instanceof PhysicalNode.Spool spool && !seenSpools.add(spool.id())) {
            return 0;
        }
        int here = kind.isInstance(node) ? 1 : 0;
        for (PhysicalNode child : node.children()) {
            here += countOf(child, kind, seenSpools);
        }
        return here;
    }

    /** Indents a rendered plan so it reads as a block inside the failure message. */
    private static String indent(String rendered) {
        return rendered.lines().map(line -> "    " + line)
                .reduce((a, b) -> a + System.lineSeparator() + b).orElse("    <empty>");
    }

    /**
     * Renders {@code a plan} for the failure message, and never throws.
     *
     * <p>A description is not the claim under test.  Some of the subjects a test builds
     * are deliberately malformed — a pass is asked what it does with a null component,
     * and {@code PrettyPrinter} throws on one — so a renderer that propagated that would
     * turn a <em>passing</em> assertion into a red test whose stack trace points at the
     * assert rather than at the code.  Rendering is best-effort for exactly that reason.
     */
    static String describe(PhysicalNode node) {
        try {
            return indent(PhysicalPlanPrinter.explain(node));
        } catch (RuntimeException e) {
            return "    <unrenderable " + node.getClass().getSimpleName() + ": " + e + ">";
        }
    }
}
