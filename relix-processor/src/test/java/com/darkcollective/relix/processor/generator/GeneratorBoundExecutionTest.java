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
import com.darkcollective.relix.ast.NumberOperand;
import com.darkcollective.relix.ast.Operand;
import com.darkcollective.relix.ast.RelNode;
import com.darkcollective.relix.ast.RelationNode;
import com.darkcollective.relix.lang.ast.ExpressionQueryTarget;
import com.darkcollective.relix.lang.ast.SourceDeclaration;
import com.darkcollective.relix.processor.internal.QueryExecutor;
import com.darkcollective.relix.processor.internal.QueryResult;
import com.darkcollective.relix.processor.generator.GeneratorDataSourceConnector;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.semantic.SemanticModel;
import com.darkcollective.relix.symbol.ColumnDefinition;
import com.darkcollective.relix.symbol.ScalarType;
import com.darkcollective.relix.symbol.Schema;
import com.darkcollective.relix.symbol.relation.SourceRelationSymbol;
import com.darkcollective.relix.symbol.table.internal.InMemorySymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.darkcollective.relix.lang.ast.ScriptBuilders.*;
import static com.darkcollective.relix.ast.AstBuilders.*;
import static com.darkcollective.relix.processor.ProcessorAssertions.assertThat;

/**
 * End-to-end proof that a pushed generator bound (GEN-001, #331) makes an otherwise
 * non-terminating scan over the unbounded {@code Naturals} generator finite. Each test
 * carries a {@link Timeout}: without the {@code takeWhile} stop these queries would never
 * return, so completion is itself the assertion.
 *
 * <p>relix-processor does not depend on the optimizer, so the bounded {@link RelationNode}
 * is built directly (as {@code SelectionIntoGeneratorPass} would) and run through
 * {@link QueryExecutor#executeOptimized}, which re-annotates the rewritten tree.
 */
final class GeneratorBoundExecutionTest {

    private static final Schema N_SCHEMA =
            new Schema(List.of(new ColumnDefinition("n", ScalarType.NUMBER)));

    /** A model whose relation {@code Nat} is the unbounded {@code Naturals} generator. */
    private static SemanticModel naturalsModel() {
        var table = new InMemorySymbolTable();
        table.register(SourceRelationSymbol.of("Nat", N_SCHEMA));
        Map<String, SourceDeclaration> sources = Map.of(
                "nat", source(true, "Nat",
                        generatorSource("Naturals", Map.of())));
        return new SemanticModel("default", table, sources, Map.of(),
                SchemaAnnotations.empty(),
                List.of(query(rel("Nat"))));
    }

    private static RelationNode boundedNat(ComparisonOperator op, String limit) {
        return rel("Nat").withProduceBound(produceBound("n", op, num(limit)));
    }

    private static List<String> run(SemanticModel model, RelNode tree) {
        var connector = new GeneratorDataSourceConnector(model, new GeneratorRegistry());
        List<QueryResult> results = new QueryExecutor().executeOptimized(model, List.of(tree), connector);
        return results.getFirst().rows().stream().map(r -> r.get("n").asDisplayString()).toList();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("σ n < 5 over a bounded Naturals terminates and yields 0..4")
    void selectionLessTerminates() {
        SemanticModel model = naturalsModel();
        RelNode tree = select(
                cmp(attr("n"), ComparisonOperator.LESS, num("5")),
                boundedNat(ComparisonOperator.LESS, "5"));
        assertThat(run(model, tree)).containsExactly("0", "1", "2", "3", "4");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("σ n <= 5 (inclusive) terminates and yields 0..5")
    void selectionLessEqualTerminates() {
        SemanticModel model = naturalsModel();
        RelNode tree = select(
                cmp(attr("n"), ComparisonOperator.LESS_EQUAL, num("5")),
                boundedNat(ComparisonOperator.LESS_EQUAL, "5"));
        assertThat(run(model, tree)).containsExactly("0", "1", "2", "3", "4", "5");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("σ n = 5 produces up to 5 then the residual σ keeps only 5")
    void selectionEqualTerminates() {
        SemanticModel model = naturalsModel();
        RelNode tree = select(
                cmp(attr("n"), ComparisonOperator.EQUAL, num("5")),
                boundedNat(ComparisonOperator.EQUAL, "5"));
        assertThat(run(model, tree)).containsExactly("5");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("a blocking γ count over a bounded Naturals terminates and counts the finite extent")
    void blockingAggregateOverBoundedGeneratorTerminates() {
        SemanticModel model = naturalsModel();
        // γ count(n) (Nat ⟨produce while n < 5⟩) → 5 rows (0..4)
        RelNode tree = groupBy(
                List.of(),
                List.of(AggregateFunction.aliased(AggregateOperator.COUNT, "n", "c")),
                boundedNat(ComparisonOperator.LESS, "5"));
        var connector = new GeneratorDataSourceConnector(model, new GeneratorRegistry());
        List<QueryResult> results = new QueryExecutor().executeOptimized(model, List.of(tree), connector);
        assertThat(results.getFirst().rows()).hasSize(1);
        assertThat(results.getFirst().rows().getFirst()).hasValue("c", "5");
    }
}
