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
package com.darkcollective.relix.embed.equivalence;

import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.optimizer.OptimizationCode;
import com.darkcollective.relix.optimizer.OptimizationResult;
import com.darkcollective.relix.optimizer.QueryOptimizer;
import com.darkcollective.relix.processor.DataSourceConnector;
import com.darkcollective.relix.processor.ExecutionContext;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.processor.exec.RelNodeExecutor;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SchemaInference;
import com.darkcollective.relix.semantic.SemanticModel;

import com.darkcollective.relix.cost.DistinctnessSource;
import com.darkcollective.relix.events.QueryEventListener;
import com.darkcollective.relix.lang.ScriptParser;
import com.darkcollective.relix.processor.generator.GeneratorDataSourceConnector;
import com.darkcollective.relix.processor.generator.GeneratorMonotonicitySource;
import com.darkcollective.relix.processor.generator.GeneratorRegistry;
import com.darkcollective.relix.semantic.CatalogProvider;
import com.darkcollective.relix.semantic.GeneratorCatalog;
import com.darkcollective.relix.semantic.InMemoryScriptLoader;
import com.darkcollective.relix.semantic.SemanticAnalyzer;
import com.darkcollective.relix.semantic.SemanticResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.darkcollective.relix.semantic.SemanticFixtures.model;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared harness for execution-level <em>equivalence</em> tests of optimizer rewrites:
 * a query must produce <strong>identical results</strong> whether or not a given rule
 * fires. The per-pass unit tests prove the <em>shape</em> of each rewrite; these prove
 * the rewrite is answer-preserving when actually executed.
 *
 * <p>Every assertion also checks that the rule under test really fired, so a test can
 * never pass vacuously by the rewrite silently never happening.
 *
 * <p>Used by {@link SipEquivalenceTest} (ADR-0020 / ADR-0021 magic-sets family) and
 * {@link RewriteEquivalenceTest} (the textbook σ/λ arms).
 */
final class OptimizerEquivalence {

    private static final RelNodeExecutor EXECUTOR = new RelNodeExecutor();

    private OptimizerEquivalence() {}

    /** Optimizes the model's first root query and returns its result. */
    static OptimizationResult optimizeFirst(SemanticModel m) {
        List<OptimizationResult> results = new QueryOptimizer().optimize(m);
        return results.getFirst();
    }

    /**
     * Executes a node against the model's inline-relation context as "col=val,…" rows.
     *
     * <p>Optimized trees are fresh {@link RelNode} instances absent from
     * {@link SemanticModel#nodeSchemas()} (which is identity-keyed), so schemas are
     * re-inferred for the tree exactly as the production
     * {@code QueryExecutor.executeOptimizedStreaming} path does.
     */
    static List<String> run(RelNode node, SemanticModel m) {
        return runRows(node, m, ExecutionContext.UNLIMITED_MATERIALIZED_ROWS).stream()
                .map(row -> String.join(",", row))
                .toList();
    }

    /**
     * As {@link #run(RelNode, SemanticModel)}, but keeping each row's columns apart
     * rather than joining them into one string, and capping what a single blocking
     * operator may buffer.
     *
     * <p>Both differences are {@link RandomQueryEquivalence}'s. A generated query has to
     * ask whether the delivered ordering is <em>total on this data</em> before it may
     * compare rows in order, which means reading the sort key's own columns — a joined
     * string cannot be split back apart, since a value may contain the separator. And a
     * generated tree can nest products the way no hand-written fixture does, so the cap
     * turns a combinatorial blow-up into a refusal naming an operator rather than an
     * {@code OutOfMemoryError}.
     *
     * @param maxMaterializedRows the per-operator buffer cap, or
     *                            {@link ExecutionContext#UNLIMITED_MATERIALIZED_ROWS}
     */
    /**
     * The most rows a run may hand back before the draw is abandoned.
     *
     * <p>{@code maxMaterializedRows} bounds one <em>operator's</em> buffer, which is the
     * question {@code MaterializationBudget} was built to answer, and not this one: a
     * query can stream an unbounded result through operators that each hold nothing and
     * still exhaust the heap in the collector at the end. A generated {@code ×} of a
     * {@code ×} does exactly that, and it took the test JVM down rather than reporting
     * anything. A search must fail a draw, never the run.
     */
    static final int MAX_COLLECTED_ROWS = 200_000;

    /** Raised when a run streams past {@link #MAX_COLLECTED_ROWS}; read as a skipped draw. */
    static final class TooManyRows extends RuntimeException {
        TooManyRows(long rows) {
            super("run produced more than " + rows + " rows");
        }
    }

    static List<List<String>> runRows(RelNode node, SemanticModel m, int maxMaterializedRows) {
        SchemaAnnotations merged = SchemaInference.annotate(m.symbolTable(), node, m.nodeSchemas(), m.functions());
        DataSourceConnector connector = ExecutionContext.inlineOnly(m).connector();
        ExecutionContext ctx = ExecutionContext.of(m, merged, connector)
                .withMaxMaterializedRows(maxMaterializedRows);
        try (Stream<Row> s = EXECUTOR.execute(node, ctx)) {
            List<List<String>> rows = new java.util.ArrayList<>();
            s.forEach(row -> {
                if (rows.size() >= MAX_COLLECTED_ROWS) {
                    throw new TooManyRows(MAX_COLLECTED_ROWS);
                }
                rows.add(rowCells(row));
            });
            return List.copyOf(rows);
        }
    }

    private static List<String> rowCells(Row row) {
        List<String> cells = new java.util.ArrayList<>(row.width());
        for (int i = 0; i < row.width(); i++) {
            cells.add(row.get(i).asDisplayString());
        }
        return cells;
    }

    private static String rowStr(Row row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.width(); i++) {
            if (i > 0) sb.append(',');
            sb.append(row.get(i).asDisplayString());
        }
        return sb.toString();
    }

    static boolean fired(OptimizationResult r, OptimizationCode code) {
        return r.applied().stream().anyMatch(t -> t.code() == code);
    }

    /** Asserts the rewrite fired and that it left the answer unchanged (as a multiset). */
    static void assertEquivalent(String src, OptimizationCode code) {
        SemanticModel m = model(src);
        OptimizationResult r = optimizeFirst(m);
        assertThat(fired(r, code))
                .as("rewrite %s should have fired (else the test is vacuous)", code.code())
                .isTrue();
        assertThat(run(r.optimized(), m))
                .as("%s must preserve the answer", code.code())
                .containsExactlyInAnyOrderElementsOf(run(r.original(), m));
    }

    /**
     * As {@link #assertEquivalent(String, OptimizationCode)}, but comparing the rows
     * <em>in order</em> — for a rewrite whose whole point is which rows come back
     * first (top-N), where an order-insensitive comparison would not distinguish
     * "the right rows" from "some rows".
     */
    static void assertEquivalentInOrder(String src, OptimizationCode code) {
        SemanticModel m = model(src);
        OptimizationResult r = optimizeFirst(m);
        assertThat(fired(r, code))
                .as("rewrite %s should have fired (else the test is vacuous)", code.code())
                .isTrue();
        assertThat(run(r.optimized(), m))
                .as("%s must preserve the answer, in order", code.code())
                .containsExactlyElementsOf(run(r.original(), m));
    }

    /**
     * Runs a query whose source is a <strong>generator</strong>, returning the optimized
     * tree's rows and asserting the rule fired.
     *
     * <p>Generators need wiring the plain harness deliberately lacks, in two places: a
     * {@link GeneratorCatalog} at <em>analysis</em> time (without one the generator's
     * schema resolves to the {@code *:ANY} placeholder and planning fails), and a
     * {@link MonotoneGeneratorSource} at <em>optimization</em> time, which is what
     * {@code GEN-001} consults. One {@link GeneratorRegistry} serves as both, plus as the
     * connector that produces the rows — which is the wiring an executing front end arranges.
     *
     * <p>There is no {@code assertEquivalent} counterpart, and cannot be: see
     * {@code OptimizerRuleEquivalenceTest.Generators} for why the unoptimized side of a
     * GEN-001 query does not terminate.
     */
    static List<String> runWithGenerators(String src, OptimizationCode code) {
        GeneratorRegistry registry = new GeneratorRegistry();
        SemanticAnalyzer analyzer = new SemanticAnalyzer(
                new InMemoryScriptLoader(Map.of()), CatalogProvider.NONE, registry);
        SemanticResult analysis = analyzer.analyze(
                ScriptParser.parse(src, SemanticAnalyzer.STDIN_PATH), SemanticAnalyzer.STDIN_PATH);
        assertThat(analysis.isFullyValid())
                .as("the generator script should analyse cleanly: %s", analysis.errors())
                .isTrue();
        SemanticModel m = analysis.model().orElseThrow();

        OptimizationResult r = new QueryOptimizer().optimize(m, QueryEventListener.NONE,
                        DistinctnessSource.NONE,
                        new GeneratorMonotonicitySource(m.sources(), registry))
                .getFirst();
        assertThat(fired(r, code))
                .as("rewrite %s should have fired (else the test is vacuous)", code.code())
                .isTrue();

        SchemaAnnotations merged = SchemaInference.annotate(
                m.symbolTable(), r.optimized(), m.nodeSchemas(), m.functions());
        ExecutionContext ctx = ExecutionContext.of(
                m, merged, new GeneratorDataSourceConnector(m, registry));
        try (Stream<Row> s = EXECUTOR.execute(r.optimized(), ctx)) {
            return s.map(OptimizerEquivalence::rowStr).toList();
        }
    }

    /** Asserts the rewrite did NOT fire but the answer is (trivially) still correct. */
    static void assertNotFiredButEqual(String src, OptimizationCode code) {
        SemanticModel m = model(src);
        OptimizationResult r = optimizeFirst(m);
        assertThat(fired(r, code)).isFalse();
        assertThat(run(r.optimized(), m))
                .containsExactlyInAnyOrderElementsOf(run(r.original(), m));
    }
}
