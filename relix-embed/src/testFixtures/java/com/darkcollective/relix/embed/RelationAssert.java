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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.ast.AstAssertions;
import com.darkcollective.relix.ast.RelNodeAssert;
import com.darkcollective.relix.events.QueryEvent;
import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.optimizer.TransformationRecord;
import com.darkcollective.relix.plan.PlanAssert;
import com.darkcollective.relix.plan.PlanAssertions;
import com.darkcollective.relix.processor.ProcessorAssertions;
import com.darkcollective.relix.processor.QueryResultAssert;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.SchemaAssert;
import com.darkcollective.relix.symbol.SymbolAssertions;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.AbstractLongAssert;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.ListAssert;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Assertions on a {@link Relation} whose failures print the query.
 *
 * <p>The shape being replaced is a terminal called only to feed a generic assertion:
 *
 * {@snippet lang = "java":
 * assertThat(relix.relation("σ status = 'OPEN' (Orders)").toList()).hasSize(3);
 * }
 *
 * <p>Which reports {@code expected size 3 but was 2} over a list of rows, and says
 * nothing about the expression that produced them — the defect issue #727 was filed
 * about, one layer up. A relation knows its own {@link Relation#render() text},
 * {@link Relation#schema() heading} and {@link Relation#plan() plan}, so it is the one
 * subject at this layer that can say why it disagreed.
 *
 * <h2>Three kinds of method, and when to reach for which</h2>
 *
 * <ul>
 *   <li><b>Navigation</b> — {@link #node()}, {@link #schema()}, {@link #plan()},
 *       {@link #rows()}. Nothing is re-implemented: each hands off to the published
 *       assert for what it navigated to, and what is new is that the hand-off starts
 *       from a relation, so every failure along it still prints the expression.</li>
 *   <li><b>Claims that execute</b> — {@link #isEmpty()}, {@link #isNotEmpty()},
 *       {@link #hasRowCount(int)}, {@link #hasSameRowsAs(Relation)}. The rows are
 *       drained <em>once</em> per assert and reused, so a chain costs one run.</li>
 *   <li><b>Hand-offs to AssertJ</b> — {@link #tuples()}, {@link #renders()},
 *       {@link #explains()}, {@link #count()}. For the claims a generic assert already
 *       states well; the relation travels along as the description, which is the whole
 *       of what they add.</li>
 * </ul>
 *
 * <h2>An execution that fails is reported as this relation failing</h2>
 *
 * <p>Draining can end three ways that are not "the wrong rows": an empty result, a
 * {@code BoundednessException} from the terminal's refusal to collect an unbounded
 * relation, and a {@link RelixException} from a closed session. At a raw call site
 * those read as three unrelated stack traces; here the first is an ordinary
 * assertion failure and the other two fail naming the query, with the original
 * exception kept as the cause.
 *
 * <p>Asserting that a relation is <em>refused</em> stays {@code assertThatThrownBy} on
 * the terminal itself — that claim is about the exception, and this assert is for the
 * claims about the answer.
 *
 * <p>Obtain one from {@link EmbedAssertions#assertThat(Relation)}.
 *
 * @see EmbedAssertions
 */
public final class RelationAssert extends AbstractAssert<RelationAssert, Relation> {

    /** Rows rendered into a failure message before it is truncated. */
    private static final int MAX_RENDERED_ROWS = 20;

    /**
     * The rows, once drained.
     *
     * <p>Memoized so that {@code hasRowCount(2).rows().hasRow(…)} runs the query once.
     * Per assert instance rather than per relation: a test that means to run the same
     * relation twice — a generator asked whether it is called once per scan — writes
     * two assertions and gets two runs, which is what it is asking about.
     */
    private List<Tuple> drained;

    RelationAssert(Relation actual) {
        super(actual, RelationAssert.class);
        if (actual != null) {
            as("relation:%n%s", describe(actual));
        }
    }

    // ── Navigation ──────────────────────────────────────────────────────────────

    /**
     * Begins an assertion on the expression tree, keeping the relation as the
     * failure's context.
     *
     * @return an assert on {@link Relation#node()}
     */
    public RelNodeAssert node() {
        isNotNull();
        return AstAssertions.assertThat(actual.node()).as(descriptionText());
    }

    /**
     * Begins an assertion on the heading this relation produces.
     *
     * @return an assert on {@link Relation#schema()}
     */
    public SchemaAssert schema() {
        isNotNull();
        return SymbolAssertions.assertThat(headingOrFail()).as(descriptionText());
    }

    /**
     * Begins an assertion on the physical plan — join algorithms, build sides, what
     * was folded into a native query.
     *
     * <p>Staged exactly as {@link Relation#plan()} is: this plans the relation as
     * written, and {@code assertThat(r.optimized()).plan()} plans the rewritten one.
     * Nothing is executed.
     *
     * @return an assert on the planned {@code PhysicalNode}
     */
    public PlanAssert plan() {
        isNotNull();
        return PlanAssertions.assertThat(actual.plan().plan()).as(descriptionText());
    }

    /**
     * Executes the relation and begins an assertion on the table it produced.
     *
     * <p>The rows are read under this relation's own heading rather than the first
     * row's, so a column claim over an empty result still has something to check.
     *
     * @return an assert on the rows, as {@code QueryResultAssert} reports them
     */
    public QueryResultAssert rows() {
        List<Tuple> rows = drain();
        return ProcessorAssertions.assertThatRows(headingFor(rows), rows).as(descriptionText());
    }

    // ── Claims that execute ─────────────────────────────────────────────────────

    /**
     * Asserts the relation produces no rows.
     *
     * @return this assert, for chaining
     */
    public RelationAssert isEmpty() {
        List<Tuple> rows = drain();
        if (!rows.isEmpty()) {
            failWithMessage("expected no rows but there are %d:%n%s", rows.size(), table(rows));
        }
        return this;
    }

    /**
     * Asserts the relation produces at least one row.
     *
     * @return this assert, for chaining
     */
    public RelationAssert isNotEmpty() {
        if (drain().isEmpty()) {
            failWithMessage("expected at least one row but the query returned none");
        }
        return this;
    }

    /**
     * Asserts the relation produces exactly {@code count} rows.
     *
     * @param count the expected number of rows
     * @return this assert, for chaining
     */
    public RelationAssert hasRowCount(int count) {
        List<Tuple> rows = drain();
        if (rows.size() != count) {
            failWithMessage("expected %d row(s) but there are %d:%n%s",
                    count, rows.size(), table(rows));
        }
        return this;
    }

    /**
     * Asserts this relation and {@code other} produce the same rows, in the same order.
     *
     * <p>The claim two queries that ought to agree make about each other — a rewrite
     * against what it rewrote, a view against its inlined body. It is not a wrapped
     * {@code isEqualTo} because there are two subjects: a failure prints both
     * expressions and both tables, which is the only form in which "the same rows in a
     * different order" is readable at all.
     *
     * @param other the relation that must produce the same rows; must not be null
     * @return this assert, for chaining
     */
    public RelationAssert hasSameRowsAs(Relation other) {
        Objects.requireNonNull(other, "other");
        List<Tuple> mine = drain();
        List<Tuple> theirs = new RelationAssert(other).drain();
        if (!display(mine).equals(display(theirs))) {
            failWithMessage("expected the same rows as%n%s%nthis relation returned%n%s%n"
                            + "and that one returned%n%s",
                    describe(other), table(mine), table(theirs));
        }
        return this;
    }

    // ── The rewriter's own record ───────────────────────────────────────────────

    /**
     * Asserts the rule fired in the rewrite that produced this relation.
     *
     * <p>The {@link TransformationRecord} counterpart of
     * {@code OptimizationContextAssert.fired} — and it fails the same way, by naming
     * the rules that <em>did</em> fire, which in a facade test is the diagnosis rather
     * than a hint towards one.
     *
     * <p>Only a relation from {@link Relation#optimized()} has rewrites: one nobody
     * asked to rewrite has had nothing done to it, so this fails on the written form
     * rather than reporting a rule that could not have fired.
     *
     * @param code the rule that must have fired
     * @return this assert, for chaining
     */
    public RelationAssert rewrote(OptimizationCode code) {
        isNotNull();
        if (countOf(code) == 0) {
            failWithMessage("expected %s to fire, but %s", code.code(), trail());
        }
        return this;
    }

    /**
     * Asserts the rule fired exactly {@code times} times.
     *
     * @param code  the rule to count
     * @param times the expected number of firings
     * @return this assert, for chaining
     */
    public RelationAssert rewrote(OptimizationCode code, int times) {
        isNotNull();
        long count = countOf(code);
        if (count != times) {
            failWithMessage("expected %s to fire %d time(s) but it fired %d; %s",
                    code.code(), times, count, trail());
        }
        return this;
    }

    /**
     * Asserts the rule did not fire — the claim a guard test makes when it says a
     * rewrite was correctly declined.
     *
     * @param code the rule that must not have fired
     * @return this assert, for chaining
     */
    public RelationAssert didNotRewrite(OptimizationCode code) {
        isNotNull();
        long count = countOf(code);
        if (count != 0) {
            failWithMessage("expected %s not to fire, but it fired %d time(s); %s",
                    code.code(), count, trail());
        }
        return this;
    }

    /**
     * Asserts the rewriter changed nothing.
     *
     * @return this assert, for chaining
     */
    public RelationAssert rewroteNothing() {
        isNotNull();
        if (!actual.rewrites().isEmpty()) {
            failWithMessage("expected no transformation, but %s", trail());
        }
        return this;
    }

    // ── Hand-offs to AssertJ ────────────────────────────────────────────────────

    /**
     * Executes the relation and hands the rows to AssertJ — for the claims a list
     * assert already states well over typed values
     * ({@code singleElement().satisfies(…)}, {@code extracting(…)}).
     *
     * <p>{@link #rows()} is the other route and reads the cells as the CLI displays
     * them; reach for this one where the claim is about a typed accessor
     * ({@code longValue}, {@code instant}) or about a {@link Tuple} as a whole.
     *
     * @return an assert on the rows, in the order the query produced them
     */
    public ListAssert<Tuple> tuples() {
        return Assertions.assertThat(drain()).as(descriptionText());
    }

    /**
     * Hands the {@code OPTIMIZE}-stage event feed to AssertJ.
     *
     * <p>The other half of what {@link Relation#optimized()} produced, beside
     * {@link #rewrote(OptimizationCode)}: an event says a rule fired, a record says
     * what it fired on. Assert through the records where the claim names a rule — the
     * failure lists the trail — and through the feed where the claim is about the feed.
     *
     * @return an assert on {@link Relation#events()}
     */
    public ListAssert<QueryEvent> events() {
        isNotNull();
        return Assertions.assertThat(actual.events()).as(descriptionText());
    }

    /**
     * Hands the relation's text to AssertJ.
     *
     * <p>Nothing is executed. The rendered text is largely its own subject, so what
     * this buys over {@code assertThat(r.render())} is the label and the heading
     * alongside it — and that the guard forbidding a bare terminal has somewhere to
     * point.
     *
     * @return an assert on {@link Relation#render()}
     */
    public AbstractStringAssert<?> renders() {
        isNotNull();
        return Assertions.assertThat(actual.render()).as(descriptionText());
    }

    /**
     * Hands the printed physical plan to AssertJ.
     *
     * <p>Nothing is executed: planning asks the catalog and the cost model, not the
     * data. Use {@link #plan()} where the claim is about the plan's <em>shape</em> —
     * that assert navigates it and prints it whole.
     *
     * @return an assert on {@link Relation#explain()}
     */
    public AbstractStringAssert<?> explains() {
        isNotNull();
        return Assertions.assertThat(actual.explain()).as(descriptionText());
    }

    /**
     * Hands {@link Relation#count()} to AssertJ.
     *
     * <p>Deliberately not {@link #hasRowCount(int)}: that one drains the rows, and this
     * is {@code γ COUNT(*)} over the relation — a different plan, which pushes down, and
     * the one a test about counting means to exercise.
     *
     * @return an assert on the counted rows
     */
    public AbstractLongAssert<?> count() {
        isNotNull();
        return Assertions.assertThat(execute(actual::count)).as(descriptionText());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Every row, run once per assert instance. */
    private List<Tuple> drain() {
        isNotNull();
        if (drained == null) {
            drained = execute(actual::toList);
        }
        return drained;
    }

    /**
     * Runs {@code terminal}, reporting a failure to execute as this relation failing.
     *
     * <p>The cause is kept rather than swallowed: the assertion says which query could
     * not run, and the stack trace beneath it still says why.
     */
    private <T> T execute(Supplier<T> terminal) {
        try {
            return terminal.get();
        } catch (RuntimeException e) {
            AssertionError error = failure("expected to execute this relation, but it threw "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            error.initCause(e);
            throw error;
        }
    }

    /** This relation's heading, reported as an assertion failure when it has none. */
    private Schema headingOrFail() {
        try {
            return actual.schema();
        } catch (RuntimeException e) {
            AssertionError error = failure("expected this relation to have a heading, but "
                    + e.getMessage());
            error.initCause(e);
            throw error;
        }
    }

    /** The heading the drained rows are read under — declared where there is one. */
    private Schema headingFor(List<Tuple> rows) {
        try {
            return actual.schema();
        } catch (RuntimeException e) {
            return rows.isEmpty() ? Schema.open() : rows.getFirst().schema();
        }
    }

    private long countOf(OptimizationCode code) {
        return actual.rewrites().stream().filter(r -> r.code() == code).count();
    }

    /** Renders the rewrite trail as the failure message's subject. */
    private String trail() {
        List<TransformationRecord> records = actual.rewrites();
        if (records.isEmpty()) {
            return "no transformation was recorded — only a relation from optimized() "
                    + "carries any";
        }
        return "these transformations:" + System.lineSeparator()
                + records.stream()
                        .map(r -> "    " + r.code().code() + "  " + r.relationName()
                                + "  " + r.detail())
                        .reduce((a, b) -> a + System.lineSeparator() + b).orElse("");
    }

    private static List<List<String>> display(List<Tuple> rows) {
        return rows.stream().map(RelationAssert::displayRow).toList();
    }

    private static List<String> displayRow(Tuple row) {
        List<String> cells = new ArrayList<>();
        for (String name : row.columnNames()) {
            cells.add(row.get(name).asDisplayString());
        }
        return cells;
    }

    /** The drained rows, rendered for a failure message. */
    private static String table(List<Tuple> rows) {
        if (rows.isEmpty()) {
            return "    <no rows>";
        }
        StringBuilder out = new StringBuilder();
        for (List<String> row : display(rows).stream().limit(MAX_RENDERED_ROWS).toList()) {
            out.append("    ").append(String.join("  ", row)).append(System.lineSeparator());
        }
        if (rows.size() > MAX_RENDERED_ROWS) {
            out.append("    … ").append(rows.size() - MAX_RENDERED_ROWS).append(" more row(s)");
        }
        return out.toString();
    }

    /**
     * Renders {@code a relation} for the failure message, and never throws.
     *
     * <p>A description is not the claim under test, so this degrades rather than
     * propagating — the rule every renderer on this surface follows. The heading is
     * omitted where the analyser inferred none, which is a relation over a name it
     * could not resolve: the expression is the subject there and a missing heading is
     * not news.
     */
    static String describe(Relation relation) {
        try {
            StringBuilder out = new StringBuilder();
            relation.label().ifPresent(label ->
                    out.append("    ").append(label).append(" :=").append(System.lineSeparator()));
            out.append("    ").append(relation.render()).append(System.lineSeparator());
            try {
                out.append("    heading: ").append(heading(relation.schema()))
                        .append(System.lineSeparator());
            } catch (RuntimeException e) {
                // No inferred schema. Said by its absence rather than by a line saying so.
            }
            return out.toString();
        } catch (RuntimeException e) {
            return "    <unrenderable relation: " + e + ">";
        }
    }

    /** The compact {@code name:type} rendering the IR report uses for a heading. */
    private static String heading(Schema schema) {
        if (schema.isOpen()) {
            return "<open>";
        }
        return schema.columns().stream()
                .map(c -> c.name() + ":" + c.type().code())
                .reduce((a, b) -> a + "  " + b)
                .orElse("<empty>");
    }
}
