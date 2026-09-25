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
package com.darkcollective.relix.processor.generator;

import com.darkcollective.relix.ast.AggregateFunction;
import com.darkcollective.relix.ast.AggregateOperator;
import com.darkcollective.relix.ast.ComparisonOperator;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.StringOperand;
import com.darkcollective.relix.cost.BoundednessChecker;
import com.darkcollective.relix.processor.Row;
import com.darkcollective.relix.semantic.internal.BuiltinProvider;
import com.darkcollective.relix.semantic.CatalogProvider;
import com.darkcollective.relix.semantic.internal.InMemoryScriptLoader;
import com.darkcollective.relix.semantic.internal.SemanticAnalyzer;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.SystemRelationSymbol;
import com.darkcollective.relix.symbol.table.SymbolTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * The ADR-0008 boundedness showcase (#82): over an unbounded generator a bounded,
 * streaming consumer is fine while a blocking operator is a clean plan-time error.
 *
 * <p>Drives the real {@link BoundednessChecker} through a real
 * {@link GeneratorBoundednessSource} backed by the registered {@code Naturals}/
 * {@code Primes} generators — so it exercises exactly the path the planner runs.
 */
final class UnboundedGeneratorShowcaseTest {

    private final GeneratorRegistry registry = new GeneratorRegistry();

    /** A boundedness source in which {@code relation} is the named generator. */
    private GeneratorBoundednessSource sourceFor(String relation, String generator) {
        var decl = source(true, relation, generatorSource(generator, Map.of()));
        return new GeneratorBoundednessSource(Map.of(relation.toLowerCase(), decl), registry);
    }

    @Test
    void blockingAggregateOverPrimesIsAPlanTimeError() {
        // γ count (Primes) — count must buffer the whole (infinite) input.
        RelNode tree = groupBy(
                List.of(),
                List.of(AggregateFunction.simple(AggregateOperator.COUNT, "n")),
                rel("Primes"));

        List<String> violations = BoundednessChecker.check(tree, sourceFor("Primes", "Primes"));
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("γ (GROUP)").contains("add a bound");
    }

    @Test
    void boundedLimitOverPrimesIsSafeAndStreamsTenPrimes() {
        // λ 10 (Primes) — λ bounds the input; the check passes …
        RelNode tree = limit(10L, rel("Primes"));
        assertThat(BoundednessChecker.check(tree, sourceFor("Primes", "Primes"))).isEmpty();

        // … and the generator streams exactly ten rows for it without draining.
        var primes = registry.find("Primes").orElseThrow();
        Schema schema = primes.schema(Map.of());
        try (Stream<Row> rows = primes.rows(Map.of(), schema)) {
            assertThat(rows.limit(10).map(r -> r.get("n").asDisplayString()).toList())
                    .containsExactly("2", "3", "5", "7", "11", "13", "17", "19", "23", "29");
        }
    }

    @Test
    void pushedBoundMakesBlockingAggregateLegal() {
        // GEN-001 (#331): γ count (Naturals ⟨produce while n < 100⟩) — the pushed bound
        // makes the leaf finite, so the blocking γ over it is no longer a plan-time error.
        RelNode bounded = rel("Naturals").withProduceBound(
                new com.darkcollective.relix.ast.ProduceBound(
                        "n", com.darkcollective.relix.ast.ComparisonOperator.LESS,
                        new com.darkcollective.relix.ast.NumberOperand("100")));
        RelNode tree = groupBy(
                List.of(),
                List.of(AggregateFunction.simple(AggregateOperator.COUNT, "n")),
                bounded);

        assertThat(BoundednessChecker.check(tree, sourceFor("Naturals", "Naturals"))).isEmpty();
    }

    @Test
    void blockingSortOverNaturalsIsAPlanTimeError() {
        // τ n (Naturals) — sorting must see the last row, which never comes.
        RelNode tree = sort(
                List.of(asc("n")),
                rel("Naturals"));

        List<String> violations = BoundednessChecker.check(tree, sourceFor("Naturals", "Naturals"));
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("τ (SORT)");
    }

    /**
     * The catalog and the checker read the same generators the same way, driven here by
     * the real registry rather than a stub: {@code relix.relations} says a query over
     * {@code Filtered} is the one the checker refuses to plan, and one over {@code Ten}
     * is the one it allows. Two answers to one question is the failure this rules out —
     * a user reading the catalog to decide what is safe to aggregate is reading the
     * engine's own reason for refusing.
     */
    @Test
    void theCatalogReportsWhatTheCheckerEnforces() {
        // source Naturals from generator { name: "Naturals" };
        // source Ten      from generator { name: "Range", lo: "1", hi: "10" };
        // Filtered := { σ n > 2 (Naturals) };
        // Capped   := { λ 5 (Naturals) };
        var script = script(
                source("Naturals", generatorSource("Naturals", Map.of())),
                source("Ten", generatorSource("Range", Map.of("lo", "1", "hi", "10"))),
                assign("Filtered", select(cmp(attr("n"), ComparisonOperator.GREATER, num("2")), rel("Naturals"))),
                assign("Capped", limit(5L, rel("Naturals"))));
        SymbolTable table = new SemanticAnalyzer(
                        new InMemoryScriptLoader(Map.of()), BuiltinProvider.none(),
                        CatalogProvider.NONE, registry)
                .analyze(script)
                .model().orElseThrow().symbolTable();

        SystemRelationSymbol relations = (SystemRelationSymbol)
                table.lookupRelation("relix", "relations").orElseThrow();
        Map<String, String> reported = relations.rows().stream().collect(Collectors.toMap(
                r -> ((StringOperand) r.get("name")).value(),
                r -> ((StringOperand) r.get("boundedness")).value()));

        assertThat(reported).containsOnly(
                entry("Naturals", "unbounded"),
                entry("Ten", "bounded"),
                entry("Filtered", "unbounded"),
                entry("Capped", "bounded"));

        // …and what the catalog calls unbounded is exactly what the checker refuses.
        assertThat(BoundednessChecker.check(
                sort(List.of(asc("n")), rel("Naturals")),
                sourceFor("Naturals", "Naturals"))).isNotEmpty();
        assertThat(BoundednessChecker.check(
                sort(List.of(asc("n")), limit(5L, rel("Naturals"))),
                sourceFor("Naturals", "Naturals"))).isEmpty();
    }
}
