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
package com.darkcollective.relix.connectors.std;

import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.plan.Dialect;
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.QueryExecutor;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.semantic.CatalogProvider;
import com.darkcollective.relix.semantic.SemanticFixtures;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.semantic.SemanticResult;
import org.junit.jupiter.api.Assumptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs one query both ways — folded into a {@code SELECT} and evaluated in-engine —
 * over the same rows, and requires the same answer.
 *
 * <h2>Why this exists</h2>
 * Every pushable operator has two implementations, the executor and each backend's
 * renderer, and a unit test of either one checks it against itself. #640 lived in
 * that gap: {@code SqlExpressions} rendered {@code (NOT p)}, SQL evaluates that
 * three-valued, the engine was two-valued, and the same script returned different
 * rows depending on where the data happened to live. Both sides were tested. The
 * test that runs both was the one nobody had written.
 *
 * <p>A database is also the only <em>independent</em> oracle the project has. The
 * doc guards catch the engine disagreeing with its own manual; they cannot catch
 * the manual and the engine being wrong together. SQL is a specification somebody
 * else wrote, and a pushed query is the engine's claim about what a query means.
 *
 * <h2>How the two runs differ</h2>
 * Only in the plan. {@code Planner} wires a SQL renderer <em>only</em> when it is
 * given the JDBC connections, so the same model with an empty connections map plans
 * a plain {@code Scan}; the connector is the same object in both runs and serves the
 * rows either way. Nothing else changes — same data, same schema, same connector.
 *
 * <h2>How a case proves it was pushed</h2>
 * By planning it and requiring the <em>root</em> of the physical plan to be a
 * {@code PushedScan}. Watching for the planner's {@code PUSHDOWN} event — which is
 * what this suite did before the corpus grew a case the renderer declines — answers a
 * weaker question than it appears to: the planner emits one for every sub-tree it
 * folds, and a bare connection table is always one of them. So an expression whose
 * <em>top</em> operator has no spelling still fires the event, still passes, and
 * compares an in-engine evaluation against an in-engine evaluation. The root node is
 * the only thing that says the whole expression crossed the boundary.
 *
 * <p>An outcome is rows <em>or</em> a failure, and the two runs must agree about which.
 * That is not fussiness: a function relix rejects for an argument outside its domain —
 * a negative square root — would come back from a database as a NULL, so a folded plan
 * that answered where the unfolded one raised is precisely the kind of disagreement this
 * suite exists to catch, and comparing only rows cannot see it.
 *
 * <p>The fold assertion runs in both directions. A dialect a case is not expected to fold
 * must decline it, so a fold that quietly starts working — or quietly stops — fails
 * rather than passing unnoticed, which is how a stale capability answer like
 * {@link Dialect#supportsWindowFunctions} would otherwise stay stale.
 *
 * <p>Planning here re-plans rather than reusing the executed plan, and that is exact
 * rather than approximate: {@code QueryExecutor.executeStreaming} runs the resolved
 * root tree with no optimizer pass, which is the same tree {@code explain} plans.
 *
 * <h2>Why it is a class rather than a base class</h2>
 * Two suites run the same corpus against two databases. Composed as a helper, the
 * corpus is data one harness executes; inherited from an abstract test, the corpus
 * would be method bodies and each backend's differences would have to be expressed by
 * overriding them.
 *
 * @see PushdownCorpus
 */
final class PushdownAgreement {

    /**
     * What a dotted {@code db.orders} reference is resolved through: the live database.
     *
     * <p>Schemas only. A declared source's statistics would otherwise be read too, which
     * the corpus was written without, and a cost decision moving under it is not what a
     * dotted-reference case is here to test.
     */
    private static final CatalogProvider CATALOG = new JdbcCatalogProvider()::tableSchema;

    private final String preamble;
    private final Dialect dialect;
    private final boolean exactStrings;

    /**
     * @param preamble the connection and source declarations every expression is
     *                 appended to — see {@link PushdownFixture#preamble}
     * @param dialect  the dialect the preamble's connection resolves to, which is what
     *                 decides whether a given case is expected to fold
     */
    PushdownAgreement(String preamble, Dialect dialect) {
        this(preamble, dialect, dialect != Dialect.MYSQL);
    }

    /**
     * @param preamble     the connection and source declarations
     * @param dialect      the dialect the preamble's connection resolves to
     * @param exactStrings whether that connection compares strings as the engine does —
     *                     a dialect's default, unless it declares {@code collation:}
     */
    PushdownAgreement(String preamble, Dialect dialect, boolean exactStrings) {
        this.preamble = preamble;
        this.dialect = dialect;
        this.exactStrings = exactStrings;
    }

    /** Whether this connection compares strings exactly — see the constructor. */
    boolean exactStrings() {
        return exactStrings;
    }

    /** The dialect this harness is running against. */
    Dialect dialect() {
        return dialect;
    }

    /**
     * Asserts that {@code expression} means the same thing folded into SQL as it does
     * evaluated in-engine, and that it folded exactly when {@code expectedToFold} says.
     *
     * <p>Rows are compared without regard to order unless the expression sorts them —
     * an unordered relation has no order to disagree about, and a database is free to
     * return one in any order it likes.
     *
     * @param expression     the relix expression, without the surrounding {@code query { … }}
     * @param expectedToFold whether this dialect is expected to render the whole expression
     */
    void assertAgrees(String expression, boolean expectedToFold) {
        assertAgrees(expression, expectedToFold, null);
    }

    /**
     * The same, for a case this dialect is accepted to answer differently.
     *
     * <p>When {@code knownDivergence} is non-null the fold is still asserted — the
     * renderer keeps being exercised — and the rows are not compared, the case reporting
     * as skipped with that reason.
     *
     * @param expression      the relix expression
     * @param expectedToFold  whether this dialect renders the whole of it
     * @param knownDivergence why this dialect's answer is not compared, or null to compare
     */
    void assertAgrees(String expression, boolean expectedToFold, String knownDivergence) {
        String script = preamble + "query { " + expression + " };";
        SemanticResult analysis = SemanticFixtures.analyze(script, CATALOG);
        assertThat(analysis.errors()).as("analysis of: %s", expression).isEmpty();
        SemanticModel model = analysis.model().orElseThrow();
        assertThat(model.connections().values().stream().map(Dialect::of))
                .as("the connection this suite declares resolves to the dialect it claims "
                        + "to be testing — otherwise every dialect-specific expectation "
                        + "below is asserted against the wrong renderer")
                .containsOnly(dialect);
        assertThat(foldedWholly(model))
                .as("on %s the planner was expected to %s this expression as a whole.%n"
                        + "The plan it produced was:%n%s",
                        dialect, expectedToFold ? "fold" : "decline", plan(model))
                .isEqualTo(expectedToFold);

        SemanticModel unpushed = withoutConnections(model);
        assertThat(foldedWholly(unpushed))
                .as("the in-engine run must fold nothing, or the two runs are the "
                        + "same plan: %s", expression)
                .isFalse();

        if (knownDivergence != null) {
            Assumptions.abort(knownDivergence);
        }

        Run pushed;
        Run inEngine;
        try (DataSourceConnector connector = new JdbcDataSourceConnector(model)) {
            pushed = run(model, connector);
            inEngine = run(unpushed, connector);
        }

        // Raising is an answer too, and for some expressions it is the whole claim: a
        // function relix rejects for an argument outside its domain must not come back
        // from a database as a NULL instead. So the two runs are required to agree about
        // *whether* they failed, and about what they said when they did.
        assertThat(pushed.failure())
                .as("one plan raised and the other did not, on %s: %s%n  pushed:    %s%n"
                        + "  in-engine: %s",
                        dialect, expression, pushed.describe(), inEngine.describe())
                .isEqualTo(inEngine.failure());
        if (pushed.failure() != null) {
            return;
        }

        if (ordered(expression)) {
            assertThat(pushed.rows())
                    .as("pushed vs in-engine on %s (order significant): %s", dialect, expression)
                    .containsExactlyElementsOf(inEngine.rows());
        } else {
            assertThat(pushed.rows())
                    .as("pushed vs in-engine on %s: %s", dialect, expression)
                    .containsExactlyInAnyOrderElementsOf(inEngine.rows());
        }
    }

    /**
     * Asserts agreement for an expression <em>nothing knew in advance</em> would fold —
     * a generated one — and reports whether it did.
     *
     * <p>The corpus states the fold direction for every case and asserts it both ways,
     * which is the right shape for a case somebody chose. A generated expression has no
     * such expectation, and the danger is the opposite one: an expression that folds
     * <b>nothing</b> is the same plan twice, so comparing it passes while testing no
     * renderer at all. The criterion is therefore the same one the corpus uses to mean
     * "this folded" — the <em>root</em> of the physical plan is a pushed scan — and a draw
     * that does not meet it is reported as not folded rather than asserted on.
     *
     * <p>It is the caller's job to do something with that: a search whose draws never fold
     * is testing nothing, and only the caller can see the rate.
     *
     * @param expression the relix expression, without the surrounding {@code query { … }}
     * @return whether the expression folded wholly, and so was actually compared
     */
    boolean assertAgreesWhenFolded(String expression) {
        String script = preamble + "query { " + expression + " };";
        SemanticResult analysis = SemanticFixtures.analyze(script, CATALOG);
        if (!analysis.errors().isEmpty() || analysis.model().isEmpty()) {
            return false;   // not a query; the generator's business, not the renderer's
        }
        SemanticModel model = analysis.model().orElseThrow();
        if (!foldedWholly(model)) {
            return false;
        }
        SemanticModel unpushed = withoutConnections(model);

        Run pushed;
        Run inEngine;
        try (DataSourceConnector connector = new JdbcDataSourceConnector(model)) {
            pushed = run(model, connector);
            inEngine = run(unpushed, connector);
        }

        assertThat(pushed.failure())
                .as("one plan raised and the other did not, on %s: %s%n  pushed:    %s%n"
                        + "  in-engine: %s",
                        dialect, expression, pushed.describe(), inEngine.describe())
                .isEqualTo(inEngine.failure());
        if (pushed.failure() != null) {
            return true;
        }
        if (ordered(expression)) {
            assertThat(pushed.rows())
                    .as("pushed vs in-engine on %s (order significant): %s", dialect, expression)
                    .containsExactlyElementsOf(inEngine.rows());
        } else {
            assertThat(pushed.rows())
                    .as("pushed vs in-engine on %s: %s", dialect, expression)
                    .containsExactlyInAnyOrderElementsOf(inEngine.rows());
        }
        return true;
    }

    /**
     * One run's outcome: the rows it produced, or the message it failed with.
     *
     * @param rows    the rendered rows, empty when the run failed
     * @param failure the failure message, or null when the run produced rows
     */
    private record Run(List<String> rows, String failure) {

        String describe() {
            return failure != null ? "raised " + failure : rows.size() + " rows";
        }
    }

    /**
     * Whether the planner folded the query's <em>entire</em> tree into one backend
     * query — that is, whether the plan's root is a pushed scan.
     */
    private static boolean foldedWholly(SemanticModel model) {
        return plan(model).stripLeading().startsWith("PushedScan");
    }

    /** The query's physical plan, rendered as {@code --explain} renders it. */
    private static String plan(SemanticModel model) {
        StringBuilder rendered = new StringBuilder();
        new QueryExecutor().explain(model, (label, planText) -> rendered.append(planText));
        return rendered.toString();
    }

    /*
     * Why both runs sit inside a try-with-resources.
     *
     * `new JdbcDataSourceConnector(model)` creates a connector that OWNS a ConnectionPool
     * — its javadoc says "closed by close()" — and the pool keeps up to DEFAULT_MAX_IDLE
     * (8) idle connections per config. One connector per case, never closed, is one pool
     * per case left holding open connections, and Postgres allows 100 clients by default.
     *
     * The suite passed for as long as it was only ever run on machines where the leak
     * stayed under that bound. On a hosted runner it did not: forty cases failed at once
     * with `FATAL: sorry, too many clients already`, and the forty were the ones that
     * happened to run after the limit was reached rather than the ones at fault.
     *
     * The connector is needed for the two runs and nothing else — every assertion below
     * reads the Run values — so this is where it ends.
     */

    /** True when the expression establishes an order the comparison must respect. */
    private static boolean ordered(String expression) {
        return expression.contains("τ") || expression.contains("λ") || expression.contains("TOP");
    }

    /**
     * Executes every root query in the model, returning the rows as rendered text — or
     * the message it failed with, since a failure is an outcome the two plans have to
     * agree about as much as a row is.
     */
    private static Run run(SemanticModel model, DataSourceConnector connector) {
        List<String> rows = new ArrayList<>();
        try {
            new QueryExecutor().executeStreaming(model, connector,
                    (label, schema, stream) -> stream.forEach(row -> rows.add(render(row))),
                    ExecutionContext.UNLIMITED_FIXPOINT_ROUNDS, QueryEventListener.NONE);
        } catch (RuntimeException failed) {
            return new Run(List.of(), failed.getMessage());
        }
        return new Run(rows, null);
    }

    /** The same model with no connections, which is what makes the planner fold nothing. */
    private static SemanticModel withoutConnections(SemanticModel model) {
        return new SemanticModel(model.namespace(), model.symbolTable(), model.sources(),
                Map.of(), model.statistics(), model.nodeSchemas(), model.schemaGraph(),
                model.rootQueries(), model.functions());
    }

    private static String render(Row row) {
        return IntStream.range(0, row.width())
                .mapToObj(i -> row.get(i).isNull() ? "NULL" : row.get(i).asDisplayString())
                .reduce((a, b) -> a + "|" + b).orElse("");
    }
}
